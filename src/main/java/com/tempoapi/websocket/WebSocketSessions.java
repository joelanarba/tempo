package com.tempoapi.websocket;

import com.tempoapi.exception.ServiceUnavailableException;
import com.tempoapi.security.TokenBlocklist;
import org.springframework.context.event.EventListener;
import org.springframework.lang.NonNull;
import org.springframework.messaging.*;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WebSocketSessions implements ChannelInterceptor {
    private record Credential(String jti, Instant expiresAt) { }

    private final ConcurrentHashMap<String, Credential> sessions = new ConcurrentHashMap<>();
    private final TokenBlocklist blocklist;
    private final Clock clock;

    public WebSocketSessions(TokenBlocklist blocklist, Clock clock) {
        this.blocklist = blocklist;
        this.clock = clock;
    }

    public void register(String sessionId, String jti, Instant expiresAt) {
        if (sessionId == null || jti == null || jti.isBlank() || expiresAt == null) {
            throw new MessagingException("Invalid authenticated session");
        }
        Credential credential = new Credential(jti, expiresAt);
        validate(credential);
        if (sessions.putIfAbsent(sessionId, credential) != null) {
            throw new MessagingException("Session is already connected");
        }
    }

    public void requireValid(String sessionId) {
        Credential credential = sessionId == null ? null : sessions.get(sessionId);
        if (credential == null) {
            throw new MessagingException("Authentication required");
        }
        validate(credential);
    }

    private void validate(Credential credential) {
        if (!clock.instant().isBefore(credential.expiresAt()) || blocklist.isBlocklisted(credential.jti())) {
            throw new MessagingException("Session credential has expired or been revoked");
        }
    }

    public void remove(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    @EventListener
    public void disconnected(SessionDisconnectEvent event) {
        remove(event.getSessionId());
    }

    @Override
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        if (SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) == SimpMessageType.MESSAGE) {
            try {
                // Outbound checks cover passive subscribers that send no more client frames.
                requireValid(SimpMessageHeaderAccessor.getSessionId(message.getHeaders()));
            } catch (MessagingException | ServiceUnavailableException ex) {
                return null;
            }
        }
        return message;
    }
}
