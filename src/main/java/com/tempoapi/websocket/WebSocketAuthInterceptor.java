package com.tempoapi.websocket;

import com.tempoapi.util.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.lang.NonNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Authenticates STOMP connections. On the CONNECT frame it reads the {@code Authorization}
 * native header, validates the JWT, and binds the authenticated user to the STOMP session.
 * A missing or invalid token aborts the connection before it is established.
 */
@Component
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;

    public WebSocketAuthInterceptor(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            accessor.setUser(authenticate(accessor));
        }
        return message;
    }

    private UsernamePasswordAuthenticationToken authenticate(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader(AUTH_HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw new MessagingException("Missing or malformed Authorization header on CONNECT");
        }
        try {
            Claims claims = jwtUtil.parse(header.substring(BEARER_PREFIX.length()));
            if (!jwtUtil.isAccessToken(claims)) {
                throw new MessagingException("A valid access token is required to connect");
            }
            UUID userId = jwtUtil.extractUserId(claims);
            String role = jwtUtil.extractRole(claims);
            return new UsernamePasswordAuthenticationToken(
                    userId.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        } catch (JwtException | IllegalArgumentException ex) {
            throw new MessagingException("Invalid access token on CONNECT");
        }
    }
}
