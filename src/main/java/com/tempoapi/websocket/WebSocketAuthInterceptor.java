package com.tempoapi.websocket;

import com.tempoapi.util.JwtUtil;
import com.tempoapi.entity.Role;
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
    private final WebSocketSessions sessions;

    public WebSocketAuthInterceptor(JwtUtil jwtUtil, WebSocketSessions sessions) {
        this.jwtUtil = jwtUtil;
        this.sessions = sessions;
    }

    @Override
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            throw new MessagingException("STOMP headers required");
        }
        StompCommand command = accessor.getCommand();
        if (command == StompCommand.CONNECT || command == StompCommand.STOMP) {
            accessor.setUser(authenticate(accessor));
        } else if (command == StompCommand.DISCONNECT) {
            sessions.remove(accessor.getSessionId());
        } else {
            sessions.requireValid(accessor.getSessionId());
            if (command == StompCommand.SUBSCRIBE) {
                if (!allowedSubscription(accessor.getDestination())) {
                    throw new MessagingException("Subscription destination is not allowed");
                }
            } else if (command != null && command != StompCommand.UNSUBSCRIBE) {
                throw new MessagingException("Client messages are not allowed");
            }
        }
        return message;
    }

    private boolean allowedSubscription(String destination) {
        if ("/user/queue/waitlist".equals(destination)) {
            return true;
        }
        String prefix = "/topic/spaces/";
        String suffix = "/availability";
        if (destination == null || destination.length() < prefix.length() + suffix.length()
                || !destination.startsWith(prefix) || !destination.endsWith(suffix)) {
            return false;
        }
        String id = destination.substring(prefix.length(), destination.length() - suffix.length());
        try {
            return id.length() == 36 && UUID.fromString(id).toString().equalsIgnoreCase(id);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private UsernamePasswordAuthenticationToken authenticate(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader(AUTH_HEADER);
        accessor.setNativeHeader(AUTH_HEADER, null);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw new MessagingException("Missing or malformed Authorization header on CONNECT");
        }
        try {
            Claims claims = jwtUtil.parse(header.substring(BEARER_PREFIX.length()));
            if (!jwtUtil.isAccessToken(claims)) {
                throw new MessagingException("A valid access token is required to connect");
            }
            UUID userId = jwtUtil.extractUserId(claims);
            String role = Role.valueOf(jwtUtil.extractRole(claims)).name();
            sessions.register(accessor.getSessionId(), jwtUtil.extractJti(claims), jwtUtil.extractExpiration(claims));
            return new UsernamePasswordAuthenticationToken(
                    userId.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        } catch (JwtException | IllegalArgumentException ex) {
            throw new MessagingException("Invalid access token on CONNECT");
        }
    }
}
