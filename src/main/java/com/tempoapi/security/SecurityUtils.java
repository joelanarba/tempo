package com.tempoapi.security;

import com.tempoapi.exception.UnauthorizedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/** Helper for reading the authenticated principal placed in the context by {@link JwtAuthFilter}. */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    /** Returns the authenticated user's id, or throws if the request is unauthenticated. */
    public static UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof String principal)) {
            throw new UnauthorizedException("No authenticated user");
        }
        return UUID.fromString(principal);
    }

    /** Returns true if the current request is authenticated as an ADMIN. */
    public static boolean isAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }
}
