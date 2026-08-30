package com.seatflow.ticket.config;

import com.seatflow.common.security.SecurityRoles;
import com.seatflow.common.security.converter.JwtRoleConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Wraps the granted-authorities converter (JwtRoleConverter) into a full
     * JwtAuthenticationConverter as required by Spring Security's resource server.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter(JwtRoleConverter jwtRoleConverter) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwtRoleConverter);
        return converter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Public Actuator and Swagger documentation
                .requestMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()

                // Guest ticket delivery (ADR-001) and public PDF download
                .requestMatchers(HttpMethod.GET, "/api/tickets/guest/**").permitAll()

                // Gate scanner verification (ADR-005: operational staff and admins)
                .requestMatchers("/api/scanner/tickets/**").hasAnyAuthority(SecurityRoles.ROLE_STAFF, SecurityRoles.ROLE_ADMIN)
                .requestMatchers("/api/admin/tickets/**").hasAuthority(SecurityRoles.ROLE_ADMIN)

                // Authenticated user tickets
                .requestMatchers(HttpMethod.GET, "/api/tickets/my-tickets").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/tickets/*/pdf").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/tickets/*").authenticated()

                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
            )
            .build();
    }
}
