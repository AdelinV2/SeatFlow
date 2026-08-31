package com.seatflow.common.security.converter;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JwtRoleConverterTest {

    private final JwtRoleConverter converter = new JwtRoleConverter();

    private Jwt jwtWithRoles(List<String> roles) {
        var builder = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .subject("test-subject");
        if (roles != null) {
            builder = builder.claim("roles", roles);
        }
        return builder.build();
    }

    @Test
    void shouldDefaultToCustomerRoleWhenNoRolesClaim() {
        Collection<GrantedAuthority> authorities = converter.convert(jwtWithRoles(null));

        assertThat(authorities).containsExactly(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    @Test
    void shouldDefaultToCustomerRoleWhenRolesEmpty() {
        Collection<GrantedAuthority> authorities = converter.convert(jwtWithRoles(List.of()));

        assertThat(authorities).containsExactly(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    @Test
    void shouldConvertSingleRoleWithPrefix() {
        Collection<GrantedAuthority> authorities = converter.convert(jwtWithRoles(List.of("ADMIN")));

        assertThat(authorities).containsExactly(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    @Test
    void shouldConvertMultipleRolesAndNormalizePrefix() {
        Collection<GrantedAuthority> authorities = converter.convert(
                jwtWithRoles(List.of("ADMIN", "CUSTOMER", "ROLE_CUSTOMER")));

        assertThat(authorities).containsExactlyInAnyOrder(
                new SimpleGrantedAuthority("ROLE_ADMIN"),
                new SimpleGrantedAuthority("ROLE_CUSTOMER")
        );
    }

    @Test
    void shouldNotDoublePrefixAlreadyPrefixedRoles() {
        Collection<GrantedAuthority> authorities = converter.convert(jwtWithRoles(List.of("ROLE_ADMIN")));

        assertThat(authorities).containsExactly(new SimpleGrantedAuthority("ROLE_ADMIN"));
        assertThat(authorities).doesNotContain(new SimpleGrantedAuthority("ROLE_ROLE_ADMIN"));
    }

    @Test
    void shouldExtractRolesFromSupabaseAppMetadata() {
        Jwt jwt = Jwt.withTokenValue("supabase-token")
                .header("alg", "ES256")
                .subject("1234-5678")
                .claim("app_metadata", Map.of("roles", List.of("ROLE_ADMIN", "STAFF")))
                .build();

        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertThat(authorities).containsExactlyInAnyOrder(
                new SimpleGrantedAuthority("ROLE_ADMIN"),
                new SimpleGrantedAuthority("ROLE_STAFF")
        );
    }

    @Test
    void shouldExtractRolesFromUserMetadataIfAppMetadataMissing() {
        Jwt jwt = Jwt.withTokenValue("supabase-token")
                .header("alg", "ES256")
                .subject("1234-5678")
                .claim("user_metadata", Map.of("roles", List.of("STAFF")))
                .build();

        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertThat(authorities).containsExactly(new SimpleGrantedAuthority("ROLE_STAFF"));
    }

    @Test
    void shouldExtractSingleStringRoleFromAppMetadataOrUserMetadata() {
        Jwt jwt = Jwt.withTokenValue("supabase-token")
                .header("alg", "ES256")
                .subject("1234-5678")
                .claim("app_metadata", Map.of("role", "ADMIN"))
                .build();

        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertThat(authorities).containsExactly(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    @Test
    void shouldExtractRolesFromCommaSeparatedString() {
        Jwt jwt = Jwt.withTokenValue("supabase-token")
                .header("alg", "ES256")
                .subject("1234-5678")
                .claim("roles", "ADMIN, STAFF")
                .build();

        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertThat(authorities).containsExactlyInAnyOrder(
                new SimpleGrantedAuthority("ROLE_ADMIN"),
                new SimpleGrantedAuthority("ROLE_STAFF")
        );
    }

    @Test
    void shouldPreserveOauthScopesAlongsideRoles() {
        Jwt jwt = Jwt.withTokenValue("scoped-token")
                .header("alg", "RS256")
                .subject("monitoring")
                .claim("roles", List.of("ADMIN"))
                .claim("scope", "openid metrics.read")
                .build();

        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertThat(authorities).containsExactlyInAnyOrder(
                new SimpleGrantedAuthority("ROLE_ADMIN"),
                new SimpleGrantedAuthority("SCOPE_openid"),
                new SimpleGrantedAuthority("SCOPE_metrics.read")
        );
    }

    @Test
    void shouldNotGrantCustomerRoleToDedicatedMetricsIdentity() {
        Jwt jwt = Jwt.withTokenValue("metrics-token")
                .header("alg", "RS256")
                .subject("prometheus")
                .claim("scope", "metrics.read")
                .build();

        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertThat(authorities)
                .containsExactly(new SimpleGrantedAuthority("SCOPE_metrics.read"))
                .doesNotContain(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }
}
