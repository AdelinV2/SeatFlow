package com.seatflow.common.security.converter;

import com.seatflow.common.security.SecurityRoles;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class JwtRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final String ROLES_CLAIM = "roles";
    private static final String APP_METADATA_CLAIM = "app_metadata";
    private static final String USER_METADATA_CLAIM = "user_metadata";

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        List<String> roles = extractRoles(jwt);
        if (roles.isEmpty()) {
            return List.of(new SimpleGrantedAuthority(SecurityRoles.ROLE_CUSTOMER));
        }

        return roles.stream()
                .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private List<String> extractRoles(Jwt jwt) {
        // 1. Check root "roles" claim
        List<String> rootRoles = jwt.getClaimAsStringList(ROLES_CLAIM);
        if (rootRoles != null && !rootRoles.isEmpty()) {
            return rootRoles;
        }

        // 2. Check Supabase "app_metadata.roles"
        Map<String, Object> appMetadata = jwt.getClaimAsMap(APP_METADATA_CLAIM);
        if (appMetadata != null && appMetadata.containsKey(ROLES_CLAIM)) {
            Object rolesObj = appMetadata.get(ROLES_CLAIM);
            if (rolesObj instanceof List<?> list) {
                return list.stream().map(Object::toString).toList();
            }
        }

        // 3. Check "user_metadata.roles"
        Map<String, Object> userMetadata = jwt.getClaimAsMap(USER_METADATA_CLAIM);
        if (userMetadata != null && userMetadata.containsKey(ROLES_CLAIM)) {
            Object rolesObj = userMetadata.get(ROLES_CLAIM);
            if (rolesObj instanceof List<?> list) {
                return list.stream().map(Object::toString).toList();
            }
        }

        return Collections.emptyList();
    }
}
