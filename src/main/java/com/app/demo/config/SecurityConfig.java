package com.app.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.app.demo.security.ApiKeyAuthFilter;

/**
 * Central Spring Security configuration.
 *
 * This is the single place where you define:
 *  — Which routes require authentication
 *  — Which filters run and in what order
 *  — Session policy (stateless for APIs)
 *
 * Future extensibility:
 *  — Adding OAuth2/JWT: add a JwtAuthFilter here before ApiKeyAuthFilter,
 *    covering a different set of routes (e.g. /admin/**)
 *  — Adding role-based access: use .hasRole("ADMIN") on specific matchers
 *  — Rate limiting: add a RateLimitFilter before ApiKeyAuthFilter
 *
 * Controllers never need to change when adding new auth mechanisms here.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final ApiKeyAuthFilter apiKeyAuthFilter;

    public SecurityConfig(ApiKeyAuthFilter apiKeyAuthFilter) {
        this.apiKeyAuthFilter = apiKeyAuthFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF — not needed for stateless APIs (no browser sessions/cookies)
            .csrf(csrf -> csrf.disable())

            // Stateless: no sessions, no cookies. Every request must carry its own credentials.
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // Route-level authorization rules
            .authorizeHttpRequests(auth -> auth
                // Health checks and public endpoints — no auth needed
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                // Everything else requires a valid API key
                .anyRequest().authenticated()
            )

            // Register our API key filter — runs before Spring's own auth filters
            .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
