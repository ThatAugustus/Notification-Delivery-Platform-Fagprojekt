package com.app.demo.config;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class AdminAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthFilter.class);
    private static final String ADMIN_PATH_PREFIX = "/api/v1/admin";
    private static final String HEADER = "X-Admin-Key";

    private final String configuredKey;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AdminAuthFilter(@Value("${admin.api-key:}") String configuredKey) {
        this.configuredKey = configuredKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String path = request.getRequestURI();

        // Only guard admin endpoints
        if (!path.startsWith(ADMIN_PATH_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        if (configuredKey == null || configuredKey.isBlank()) {
            log.error("admin.api-key is not configured — rejecting admin request to {}", path);
            writeUnauthorized(response, "Admin endpoints are not configured on this server");
            return;
        }

        String provided = request.getHeader(HEADER);
        if (provided == null || provided.isBlank()) {
            writeUnauthorized(response, "Missing required header: " + HEADER);
            return;
        }

        if (!constantTimeEquals(provided, configuredKey)) {
            writeUnauthorized(response, "Invalid admin key");
            return;
        }

        chain.doFilter(request, response);
    }

    // === FIX: write 401 response directly instead of throwing ===
    // Filters run outside @RestControllerAdvice scope. Throwing here
    // propagates to the servlet container and becomes a generic 500.
    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = Map.of(
                "status", 401,
                "error", "Unauthorized",
                "message", message,
                "timestamp", Instant.now().toString()
        );
        objectMapper.writeValue(response.getWriter(), body);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}