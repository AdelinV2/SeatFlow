# TASK-005: Common Security Module (JwtRoleConverter, SecurityRoles, UserContext)

## 1. Task Metadata
- **Target Module:** `backend/common/common-security`
- **Phase:** `Phase 0 - Foundation`
- **Related Specs:** `.ai/architecture/01-common-modules.md`, `.ai/architecture/04-authentication-security.md`, `backend/AGENTS.md`
- **Related ADRs:** N/A
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Implement the `common-security` module providing auto-configured JWT role conversion for Microsoft Entra External ID / OIDC, standard security role constants (`SecurityRoles`), and context helper utilities (`UserContext`) to access the authenticated user's ID, email, and roles across business services.

### Critical Invariants to Enforce:
- [ ] Centralized role conversion: Convert Entra ID `roles` claim into Spring Security `GrantedAuthority` with `ROLE_` prefix.
- [ ] Safe fallback: If no roles are present in the JWT, default to `ROLE_CUSTOMER`.
- [ ] `UserContext` must extract claims safely from `SecurityContextHolder` using `Optional<String>` without throwing unexpected `NullPointerException`s when unauthenticated.
- [ ] Auto-configuration must register `JwtRoleConverter` as a Spring Bean using `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

---

## 3. Exact File Inventory
List of all files to create or modify:

- `[NEW]` `backend/common/common-security/pom.xml`
- `[NEW]` `backend/common/common-security/src/main/java/com/seatflow/common/security/SecurityRoles.java`
- `[NEW]` `backend/common/common-security/src/main/java/com/seatflow/common/security/converter/JwtRoleConverter.java`
- `[NEW]` `backend/common/common-security/src/main/java/com/seatflow/common/security/context/UserContext.java`
- `[NEW]` `backend/common/common-security/src/main/java/com/seatflow/common/security/config/CommonSecurityAutoConfiguration.java`
- `[NEW]` `backend/common/common-security/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- `[NEW]` `backend/common/common-security/src/test/java/com/seatflow/common/security/converter/JwtRoleConverterTest.java`
- `[NEW]` `backend/common/common-security/src/test/java/com/seatflow/common/security/context/UserContextTest.java`

---

## 4. Technical Specifications & Contracts

### 4.1 Maven POM (`backend/common/common-security/pom.xml`)
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.seatflow</groupId>
        <artifactId>common</artifactId>
        <version>1.0.0-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>common-security</artifactId>
    <name>SeatFlow :: Common Security</name>
    <description>OAuth2 JWT role converter, SecurityRoles, and UserContext helper</description>

    <dependencies>
        <dependency>
            <groupId>com.seatflow</groupId>
            <artifactId>common-domain</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

### 4.2 Security Roles Constants (`com.seatflow.common.security.SecurityRoles`)
```java
package com.seatflow.common.security;

public final class SecurityRoles {
    public static final String ROLE_CUSTOMER = "ROLE_CUSTOMER";
    public static final String ROLE_ADMIN = "ROLE_ADMIN";

    public static final String CUSTOMER = "CUSTOMER";
    public static final String ADMIN = "ADMIN";

    private SecurityRoles() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }
}
```

### 4.3 JWT Role Converter (`com.seatflow.common.security.converter.JwtRoleConverter`)
```java
package com.seatflow.common.security.converter;

import com.seatflow.common.security.SecurityRoles;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public class JwtRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final String ROLES_CLAIM = "roles";

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList(ROLES_CLAIM);
        if (roles == null || roles.isEmpty()) {
            return List.of(new SimpleGrantedAuthority(SecurityRoles.ROLE_CUSTOMER));
        }

        return roles.stream()
                .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
    }
}
```

### 4.4 User Context Helper (`com.seatflow.common.security.context.UserContext`)
```java
package com.seatflow.common.security.context;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class UserContext {

    private UserContext() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    public static Optional<String> getCurrentUserId() {
        return getJwt().map(Jwt::getSubject);
    }

    public static Optional<String> getCurrentUserEmail() {
        return getJwt().map(jwt -> {
            String email = jwt.getClaimAsString("email");
            if (email == null) {
                email = jwt.getClaimAsString("preferred_username");
            }
            return email;
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
```

### 4.5 AutoConfiguration Registration
`CommonSecurityAutoConfiguration.java`:
```java
package com.seatflow.common.security.config;

import com.seatflow.common.security.converter.JwtRoleConverter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.Jwt;

@AutoConfiguration
@ConditionalOnClass(Jwt.class)
public class CommonSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JwtRoleConverter jwtRoleConverter() {
        return new JwtRoleConverter();
    }
}
```

`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:
```text
com.seatflow.common.security.config.CommonSecurityAutoConfiguration
```

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. **Step 1:** Create `backend/common/common-security/pom.xml`.
2. **Step 2:** Implement `SecurityRoles` constant class.
3. **Step 3:** Implement `JwtRoleConverter` implementing `Converter<Jwt, Collection<GrantedAuthority>>`.
4. **Step 4:** Implement `UserContext` helper utility.
5. **Step 5:** Create `CommonSecurityAutoConfiguration` and register in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
6. **Step 6:** Write unit tests:
   - `JwtRoleConverterTest`: test empty roles defaulting to `ROLE_CUSTOMER`, single role conversion, multiple roles conversion, prefix normalization.
   - `UserContextTest`: test extracting subject from `JwtAuthenticationToken`, fallback email claim, role checking.
7. **Step 7:** Run tests and verify clean build.

---

## 6. Definition of Done & Verification Command
To verify this task, run:
```bash
mvn clean test -pl common/common-security
```
- [ ] `JwtRoleConverter` correctly transforms JWT claims into `GrantedAuthority` collections.
- [ ] `UserContext` safely parses authentication context without throwing unexpected exceptions.
- [ ] Auto-configuration correctly imports the converter into Spring Boot contexts.
- [ ] Task file is moved to `.ai/tasks/completed/`.
