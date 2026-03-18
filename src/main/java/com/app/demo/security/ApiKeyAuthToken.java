package com.app.demo.security;

import java.util.Collection;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import com.app.demo.model.Tenant;

/**
 * Spring Security token representing an authenticated API key request.
 * Wraps the Tenant so that controllers can inject it via @AuthenticationPrincipal.
 * 
 * Future: For OAuth, you would create a separate token (e.g. JwtAuthToken)
 * with claims/scopes as GrantedAuthorities — SecurityConfig then registers
 * both authentication mechanisms without touching any controllers.
 */
public class ApiKeyAuthToken extends AbstractAuthenticationToken {

    private final Tenant tenant;

    public ApiKeyAuthToken(Tenant tenant, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.tenant = tenant;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        // We don't store the raw key after authentication
        return null;
    }

    @Override
    public Object getPrincipal() {
        return tenant;
    }

    public Tenant getTenant() {
        return tenant;
    }
}
