package com.tempoapi.websocket;

import com.tempoapi.exception.ServiceUnavailableException;
import com.tempoapi.security.TokenBlocklist;
import com.tempoapi.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.*;
import org.springframework.messaging.simp.*;
import org.springframework.messaging.support.*;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebSocketSessionsTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2027-01-01T00:00:00Z"));
    private final TokenBlocklist blocklist = mock(TokenBlocklist.class);
    private final WebSocketSessions sessions = new WebSocketSessions(blocklist, clock);

    private Message<byte[]> outbound() {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId("session");
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }

    @Test
    void revokedTokenCannotConnect() {
        when(blocklist.isBlocklisted("jti")).thenReturn(true);
        assertThatThrownBy(() -> sessions.register("session", "jti", clock.instant().plusSeconds(60)))
                .isInstanceOf(MessagingException.class);
    }

    @Test
    void logoutStopsOutboundDeliveryWithoutAnotherClientFrame() {
        sessions.register("session", "jti", clock.instant().plusSeconds(60));
        assertThat(sessions.preSend(outbound(), mock(MessageChannel.class))).isNotNull();
        when(blocklist.isBlocklisted("jti")).thenReturn(true);
        assertThat(sessions.preSend(outbound(), mock(MessageChannel.class))).isNull();
    }

    @Test
    void expiryStopsOutboundDeliveryAtTheBoundary() {
        sessions.register("session", "jti", clock.instant().plusSeconds(60));
        clock.set(clock.instant().plusSeconds(60));
        assertThat(sessions.preSend(outbound(), mock(MessageChannel.class))).isNull();
    }

    @Test
    void redisOutageSuppressesDelivery() {
        sessions.register("session", "jti", clock.instant().plusSeconds(60));
        when(blocklist.isBlocklisted("jti")).thenThrow(new ServiceUnavailableException("offline"));
        assertThat(sessions.preSend(outbound(), mock(MessageChannel.class))).isNull();
    }

    @Test
    void disconnectRemovesAuthentication() {
        sessions.register("session", "jti", clock.instant().plusSeconds(60));
        sessions.remove("session");
        sessions.remove("session");
        assertThatThrownBy(() -> sessions.requireValid("session")).isInstanceOf(MessagingException.class);
    }
}
