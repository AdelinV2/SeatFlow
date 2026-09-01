package com.seatflow.common.security.context;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class UserContext {

    private UserContext() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    public static Optional<String> getCurrentUserId() {
        return getJwt().map(Jwt::getSubject);
    }

    public static Optional<UUID> getCurrentUserIdAsUuid() {
        return getJwt()
                .map(Jwt::getSubject)
                .flatMap(subject -> {
                    try {
                        return Optional.of(UUID.fromString(subject));
                    } catch (IllegalArgumentException ex) {
                        return Optional.empty();
                    }
                });
    }

    public static Optional<String> getCurrentUserEmail() {
        return getJwt().map(jwt -> {
            String email = jwt.getClaimAsString("email");
            if (email == null) {
                email = jwt.getClaimAsString("preferred_username");
            }
            if (email == null && jwt.getClaims().get("emails") instanceof java.util.Collection<?> emails && !emails.isEmpty()) {
                Object first = emails.iterator().next();
                if (first != null) {
                    email = first.toString();
                }
            }
            return email;
        });
    }

    public static Optional<String> getCurrentUserName() {
        return getJwt().map(jwt -> {
            String name = jwt.getClaimAsString("name");
            if (name != null && !name.isBlank()) {
                return name;
            }
            String fullName = jwt.getClaimAsString("full_name");
            if (fullName != null && !fullName.isBlank()) {
                return fullName;
            }
            Object userMetadataObj = jwt.getClaims().get("user_metadata");
            if (userMetadataObj instanceof java.util.Map<?, ?> meta) {
                Object metaFullName = meta.get("full_name");
                if (metaFullName instanceof String str && !str.isBlank()) {
                    return str;
                }
                Object metaName = meta.get("name");
                if (metaName instanceof String str && !str.isBlank()) {
                    return str;
                }
                Object first = meta.get("first_name");
                Object last = meta.get("last_name");
                if (first != null || last != null) {
                    String combined = ((first != null ? first.toString() : "") + " " + (last != null ? last.toString() : "")).trim();
                    if (!combined.isBlank()) {
                        return combined;
                    }
                }
            }
            return null;
        });
    }

    public static Set<String> getCurrentRoles() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return Collections.emptySet();
        }
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }

    public static boolean hasRole(String role) {
        String roleWithPrefix = role.startsWith("ROLE_") ? role : "ROLE_" + role;
        return getCurrentRoles().contains(roleWithPrefix);
    }

    private static Optional<Jwt> getJwt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return Optional.of(jwt);
        }
        return Optional.empty();
    }
}
