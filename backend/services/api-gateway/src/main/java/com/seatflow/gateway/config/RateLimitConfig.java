package com.seatflow.gateway.config;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Resolves a stable, verified caller key for Redis-backed Gateway limits. */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    @Bean("rateLimitKeyResolver")
    public KeyResolver rateLimitKeyResolver(RateLimitProperties properties) {
        return exchange -> exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(authentication -> authentication.getToken().getSubject())
                .filter(StringUtils::hasText)
                .map(String::trim)
                .map(subject -> "user:" + subject)
                .switchIfEmpty(Mono.fromSupplier(() -> "ip:" + normalizedRemoteAddress(exchange, properties)));
    }

    /** Convenience factory retained for focused unit tests; Spring uses the injected overload above. */
    public KeyResolver rateLimitKeyResolver() {
        return rateLimitKeyResolver(new RateLimitProperties(20, 40, 1, List.of()));
    }

    @Bean
    public RedisRateLimiter redisRateLimiter(RateLimitProperties properties) {
        return new RedisRateLimiter(
                properties.replenishRate(), properties.burstCapacity(), properties.requestedTokens());
    }

    static String normalizedRemoteAddress(ServerWebExchange exchange) {
        return normalizedRemoteAddress(exchange, new RateLimitProperties(20, 40, 1, List.of()));
    }

    static String normalizedRemoteAddress(ServerWebExchange exchange, RateLimitProperties properties) {
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        if (remoteAddress == null) {
            return "unknown";
        }
        InetAddress address = remoteAddress.getAddress();
        String peer = address != null ? address.getHostAddress() : remoteAddress.getHostString();
        if (!StringUtils.hasText(peer)) {
            return "unknown";
        }
        peer = normalizeIp(peer);

        String forwardedFor = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (StringUtils.hasText(forwardedFor) && isTrustedProxy(peer, properties.trustedProxyCidrs())) {
            String forwardedClient = trustedClientFromForwardedChain(forwardedFor, properties.trustedProxyCidrs());
            if (forwardedClient != null) {
                return forwardedClient;
            }
        }
        return peer == null ? "unknown" : peer;
    }

    private static String trustedClientFromForwardedChain(String forwardedFor, List<String> trustedProxyCidrs) {
        String[] entries = forwardedFor.split(",");
        List<String> addresses = new ArrayList<>(entries.length);
        for (String entry : entries) {
            String candidate = normalizeIp(entry.trim());
            if (candidate == null) {
                return null;
            }
            addresses.add(candidate);
        }
        for (int index = addresses.size() - 1; index >= 0; index--) {
            String candidate = addresses.get(index);
            if (!isTrustedProxy(candidate, trustedProxyCidrs)) {
                return candidate;
            }
        }
        return addresses.isEmpty() ? null : addresses.get(0);
    }

    private static String normalizeIp(String candidate) {
        if (!StringUtils.hasText(candidate)) {
            return null;
        }
        String value = candidate.trim();
        boolean validChars = value.chars().allMatch(character ->
                character >= '0' && character <= '9'
                        || character >= 'a' && character <= 'f'
                        || character >= 'A' && character <= 'F'
                        || character == '.' || character == ':');
        if (!validChars) {
            return null;
        }
        try {
            return InetAddress.getByName(value).getHostAddress();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isTrustedProxy(String address, List<String> trustedProxyCidrs) {
        byte[] addressBytes = parseIp(address);
        if (addressBytes == null) {
            return false;
        }
        for (String cidr : trustedProxyCidrs) {
            if (!StringUtils.hasText(cidr)) {
                continue;
            }
            String[] parts = cidr.trim().split("/", 2);
            byte[] network = parseIp(parts[0]);
            if (network == null || network.length != addressBytes.length) {
                continue;
            }
            int prefix = parts.length == 2 ? parsePrefix(parts[1], network.length * 8) : network.length * 8;
            if (prefix < 0) {
                continue;
            }
            int fullBytes = prefix / 8;
            int remainingBits = prefix % 8;
            boolean matches = true;
            for (int index = 0; index < fullBytes; index++) {
                if (network[index] != addressBytes[index]) {
                    matches = false;
                    break;
                }
            }
            if (matches && remainingBits > 0) {
                int mask = 0xFF << (8 - remainingBits);
                matches = (network[fullBytes] & mask) == (addressBytes[fullBytes] & mask);
            }
            if (matches) {
                return true;
            }
        }
        return false;
    }

    private static int parsePrefix(String value, int max) {
        try {
            int prefix = Integer.parseInt(value);
            return prefix >= 0 && prefix <= max ? prefix : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static byte[] parseIp(String value) {
        String normalized = normalizeIp(value);
        if (normalized == null) {
            return null;
        }
        try {
            return InetAddress.getByName(normalized).getAddress();
        } catch (Exception ignored) {
            return null;
        }
    }
}
