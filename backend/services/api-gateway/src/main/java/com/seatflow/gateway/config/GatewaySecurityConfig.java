package com.seatflow.gateway.config;

import com.seatflow.common.security.converter.JwtRoleConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;

/** Validates bearer JWTs while leaving downstream services responsible for authorization. */
@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

    @Bean
    SecurityWebFilterChain gatewaySecurityWebFilterChain(ServerHttpSecurity http,
                                                          JwtRoleConverter jwtRoleConverter) {
        JwtAuthenticationConverter jwtAuthenticationConverter = new JwtAuthenticationConverter();
        jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(jwtRoleConverter);

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchange -> exchange
                        .pathMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .pathMatchers("/actuator/prometheus").hasAuthority("SCOPE_metrics.read")
                        .pathMatchers("/actuator/metrics", "/actuator/metrics/**").hasRole("ADMIN")
                        .pathMatchers("/actuator/**").authenticated()
                        .anyExchange().permitAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(
                        new ReactiveJwtAuthenticationConverterAdapter(jwtAuthenticationConverter))))
                .build();
    }
}
