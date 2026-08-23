package com.seatflow.common.security.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class UserContextTest {

    private static final String SUBJECT = "user-123";
    private static final String EMAIL = "user@example.com";
    private static final String PREFERRED = "preferred_user";

    private Jwt jwtWith(String subject, String email, String preferredUsername) {
        var builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject);
        if (email != null) {
            builder = builder.claim("email", email);
        }
        if (preferredUsername != null) {
            builder = builder.claim("preferred_username", preferredUsername);
        }
        return builder.build();
    }

    private void setAuthentication(Jwt jwt, Collection<GrantedAuthority> authorities) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, authorities));
    }

    @BeforeEach
    void setUp() {
        Jwt jwt = jwtWith(SUBJECT, EMAIL, PREFERRED);
        Collection<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));
        setAuthentication(jwt, authorities);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldExtractUserIdFromJwtSubject() {
        Optional<String> userId = UserContext.getCurrentUserId();
        assertThat(userId).contains(SUBJECT);
    }

    @Test
    void shouldExtractEmailFromJwtClaim() {
        Optional<String> email = UserContext.getCurrentUserEmail();
        assertThat(email).contains(EMAIL);
    }

    @Test
    void shouldFallbackToPreferredUsernameWhenEmailAbsent() {
        Jwt jwt = jwtWith(SUBJECT, null, PREFERRED);
        setAuthentication(jwt, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));

        Optional<String> email = UserContext.getCurrentUserEmail();
        assertThat(email).contains(PREFERRED);
    }

    @Test
    void shouldReturnEmptyEmailWhenBothClaimsAbsent() {
        Jwt jwt = jwtWith(SUBJECT, null, null);
        setAuthentication(jwt, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));

        Optional<String> email = UserContext.getCurrentUserEmail();
        assertThat(email).isEmpty();
    }

    @Test
    void shouldReturnCurrentRoles() {
        assertThat(UserContext.getCurrentRoles()).contains("ROLE_ADMIN");
    }

    @Test
    void shouldCheckRoleWithAndWithoutPrefix() {
        assertThat(UserContext.hasRole("ADMIN")).isTrue();
        assertThat(UserContext.hasRole("ROLE_ADMIN")).isTrue();
        assertThat(UserContext.hasRole("CUSTOMER")).isFalse();
    }

    @Test
    void shouldReturnEmptyWhenUnauthenticated() {
        SecurityContextHolder.clearContext();

        assertThat(UserContext.getCurrentUserId()).isEmpty();
        assertThat(UserContext.getCurrentUserEmail()).isEmpty();
        assertThat(UserContext.getCurrentRoles()).isEmpty();
        assertThat(UserContext.hasRole("ADMIN")).isFalse();
    }

    @Test
    void shouldReturnEmptyWhenPrincipalIsNotJwt() {
        Authentication auth = new UsernamePasswordAuthenticationToken("someuser", "cred", List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertThat(UserContext.getCurrentUserId()).isEmpty();
        assertThat(UserContext.getCurrentRoles()).contains("ROLE_CUSTOMER");
    }
}
