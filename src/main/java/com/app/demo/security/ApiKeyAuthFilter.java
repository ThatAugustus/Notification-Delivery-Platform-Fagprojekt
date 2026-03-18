package com.app.demo.security;

import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.app.demo.model.Tenant;
import com.app.demo.service.AuthenticationService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * HTTP filter that intercepts every request and checks for a valid API key.
 * 
 * Runs exactly once per request (OncePerRequestFilter guarantee).
 * If the key is valid → puts the authenticated Tenant into SecurityContext and continues.
 * If the key is missing or invalid → writes 401 Unauthorized and short-circuits the chain.
 * 
 * Future: A JwtAuthFilter for OAuth bearer tokens can sit alongside this filter
 * in SecurityConfig, handling a different set of routes — controllers never change.
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);
    private static final String API_KEY_HEADER = "X-API-Key";

    private final AuthenticationService authenticationService;

    public ApiKeyAuthFilter(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String rawKey = request.getHeader(API_KEY_HEADER);

        try {
            Tenant tenant = authenticationService.authenticate(rawKey);

            // Put authenticated identity into Spring Security's context
            // so controllers can access it via @AuthenticationPrincipal
            ApiKeyAuthToken authToken = new ApiKeyAuthToken(tenant, List.of());
            SecurityContextHolder.getContext().setAuthentication(authToken);

            log.debug("Authenticated request from tenant: {}", tenant.getName());
            filterChain.doFilter(request, response);

        } catch (AccessDeniedException e) {
            log.warn("Rejected unauthenticated request to {}: {}", request.getRequestURI(), e.getMessage());
            SecurityContextHolder.clearContext();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\": \"Unauthorized\", \"message\": \"" + e.getMessage() + "\"}");
        }
    }
}
