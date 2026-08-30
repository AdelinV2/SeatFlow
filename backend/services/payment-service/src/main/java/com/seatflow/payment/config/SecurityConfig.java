package com.seatflow.payment.config;

import com.seatflow.common.security.converter.JwtRoleConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter(JwtRoleConverter jwtRoleConverter) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwtRoleConverter);
        return converter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authorize -> authorize
                // Public payment creation & status retrieval (Hybrid Guest Flow - ADR-001)
                .requestMatchers(HttpMethod.POST, "/api/payments/intent").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/payments/*/tax-preview").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/payments/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/payments/reservation/*").permitAll()
                // Public Stripe Webhook endpoint
                .requestMatchers(HttpMethod.POST, "/api/payments/webhook").permitAll()
                // Documentation & Actuator
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/actuator/health", "/actuator/info", "/actuator/prometheus", "/actuator/metrics").permitAll()
                // All other routes require authentication
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));

        return http.build();
    }
}
