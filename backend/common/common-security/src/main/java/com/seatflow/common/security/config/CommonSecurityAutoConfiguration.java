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
