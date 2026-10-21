package com.tempoapi.filter;

import com.tempoapi.security.TokenBlocklist;
import com.tempoapi.security.RestAuthenticationEntryPoint;
import com.tempoapi.exception.ServiceUnavailableException;
import com.tempoapi.util.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Validates the Bearer access token on each request and, when valid and not
 * blocklisted, populates the security context with the user id and role.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);
    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;
    private final TokenBlocklist tokenBlocklist;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;

    public JwtAuthFilter(JwtUtil jwtUtil, TokenBlocklist tokenBlocklist,
                         RestAuthenticationEntryPoint authenticationEntryPoint) {
        this.jwtUtil = jwtUtil;
        this.tokenBlocklist = tokenBlocklist;
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (header != null && header.startsWith(PREFIX)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = header.substring(PREFIX.length());
            try {
                Claims claims = jwtUtil.parse(token);
                if (jwtUtil.isAccessToken(claims) && !tokenBlocklist.isBlocklisted(jwtUtil.extractJti(claims))) {
                    authenticate(request, claims);
                }
            } catch (ServiceUnavailableException ex) {
                authenticationEntryPoint.unavailable(response);
                return;
            } catch (JwtException | IllegalArgumentException ex) {
                log.debug("Rejected JWT ({})", ex.getClass().getSimpleName());
            }
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, Claims claims) {
        String userId = jwtUtil.extractUserId(claims).toString();
        String role = jwtUtil.extractRole(claims);
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
        var authentication = new UsernamePasswordAuthenticationToken(userId, null, authorities);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
