package com.omc.payment.infrastructure.config;

import com.omc.common.security.GatewayHeaderAuthFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

// @EnableWebSecurity, @EnableMethodSecurity 는 common GatewaySecurityAutoConfiguration이 처리
@Configuration
public class SecurityConfig {

    @Value("${gateway.secret}")
    private String gatewaySecret;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.ignoringRequestMatchers("/**"))
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // GatewayHeaderAuthFilter를 등록해서 SecurityContext 생성
            .addFilterBefore(new GatewayHeaderAuthFilter(gatewaySecret), UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/internal/v1/**").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/actuator/**").permitAll()
                .anyRequest().authenticated()
            );
        return http.build();
    }
}
