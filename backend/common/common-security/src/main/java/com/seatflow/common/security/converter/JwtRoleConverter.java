package com.seatflow.common.security.converter;

import com.seatflow.common.security.SecurityRoles;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.*;
import java.util.stream.Collectors;

public class JwtRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final String ROLES_CLAIM = "roles";
    private static final String ROLE_CLAIM = "role";
    private static final String GROUPS_CLAIM = "groups";
    private static final String APP_METADATA_CLAIM = "app_metadata";
    private static final String USER_METADATA_CLAIM = "user_metadata";

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        List<String> roles = extractRoles(jwt);
        if (roles.isEmpty()) {
            return List.of(new SimpleGrantedAuthority(SecurityRoles.ROLE_CUSTOMER));
        }

        return roles.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(r -> !r.isEmpty())
                .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role.toUpperCase(Locale.ROOT))
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
    }

    private List<String> extractRoles(Jwt jwt) {
        List<String> collected = new ArrayList<>();

        // 1. Root "roles" claim (List or String)
        extractFromObject(jwt.getClaims().get(ROLES_CLAIM), collected);

        // 2. Root "groups" claim (List or String, for Entra/Keycloak)
        extractFromObject(jwt.getClaims().get(GROUPS_CLAIM), collected);

        // 3. Root "role" claim
        Object rootRole = jwt.getClaims().get(ROLE_CLAIM);
        if (rootRole instanceof String roleStr) {
            if ("service_role".equalsIgnoreCase(roleStr) || "admin".equalsIgnoreCase(roleStr)) {
                collected.add(SecurityRoles.ADMIN);
            }
        }

        // 4. Check Supabase "app_metadata"
        Map<String, Object> appMetadata = jwt.getClaimAsMap(APP_METADATA_CLAIM);
        if (appMetadata != null) {
            extractFromObject(appMetadata.get(ROLES_CLAIM), collected);
            extractFromObject(appMetadata.get(ROLE_CLAIM), collected);
        }

        // 5. Check Supabase "user_metadata"
        Map<String, Object> userMetadata = jwt.getClaimAsMap(USER_METADATA_CLAIM);
        if (userMetadata != null) {
            extractFromObject(userMetadata.get(ROLES_CLAIM), collected);
            extractFromObject(userMetadata.get(ROLE_CLAIM), collected);
        }

        return collected;
    }

    private void extractFromObject(Object obj, List<String> target) {
        if (obj == null) return;

        if (obj instanceof Collection<?> col) {
            for (Object item : col) {
                if (item != null) {
                    target.add(item.toString());
                }
            }
        } else if (obj instanceof String str) {
            if (str.contains(",")) {
                for (String part : str.split(",")) {
                    if (!part.isBlank()) target.add(part.trim());
                }
            } else if (!str.isBlank()) {
                target.add(str.trim());
            }
        }
    }
}
