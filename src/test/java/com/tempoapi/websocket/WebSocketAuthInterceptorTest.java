package com.tempoapi.websocket;

import com.tempoapi.config.JwtProperties;
import com.tempoapi.entity.Role;
import com.tempoapi.security.TokenBlocklist;
import com.tempoapi.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.*;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.*;
import java.time.Clock;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebSocketAuthInterceptorTest {
    private final TokenBlocklist blocklist = mock(TokenBlocklist.class);
    private final WebSocketSessions sessions = new WebSocketSessions(blocklist, Clock.systemUTC());
    private final MessageChannel channel = mock(MessageChannel.class);
    private JwtUtil jwt;
    private WebSocketAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(UUID.randomUUID().toString() + UUID.randomUUID());
        properties.setAccessExpiryMs(60000);
        properties.setRefreshExpiryMs(120000);
        jwt = new JwtUtil(properties);
        interceptor = new WebSocketAuthInterceptor(jwt, sessions);
    }

    private Message<byte[]> frame(StompCommand command, String destination, String token) {
        StompHeaderAccessor headers = StompHeaderAccessor.create(command);
        headers.setSessionId("session");
        if (destination != null) headers.setDestination(destination);
        if (token != null) headers.setNativeHeader("Authorization", "Bearer " + token);
        headers.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }

    private void connect() {
        interceptor.preSend(frame(StompCommand.CONNECT, null, jwt.generateAccessToken(UUID.randomUUID(), Role.MEMBER)), channel);
    }

    @Test
    void permitsOnlyIntendedAuthenticatedSubscriptions() {
        connect();
        assertThat(interceptor.preSend(frame(StompCommand.SUBSCRIBE,
                "/topic/spaces/" + UUID.randomUUID() + "/availability", null), channel)).isNotNull();
        assertThat(interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/user/queue/waitlist", null), channel)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/topic/spaces/availability", "/queue/waitlist-usersession", "/user/another-user/queue/waitlist", "/topic/other"})
    void rejectsArbitrarySubscriptions(String destination) {
        connect();
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, destination, null), channel))
                .isInstanceOf(MessagingException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/topic/spaces/00000000-0000-0000-0000-000000000000/availability", "/queue/waitlist", "/user/another-user/queue/waitlist", "/app/anything"})
    void rejectsClientSend(String destination) {
        connect();
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SEND, destination, null), channel))
                .isInstanceOf(MessagingException.class);
    }

    @Test
    void rejectsSubscriptionWithoutAuthenticatedConnect() {
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/user/queue/waitlist", null), channel))
                .isInstanceOf(MessagingException.class);
    }

    @Test
    void rejectsRefreshTokenOnConnect() {
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, null,
                jwt.generateRefreshToken(UUID.randomUUID())), channel)).isInstanceOf(MessagingException.class);
    }

    @Test
    void rejectsRevokedConnect() {
        when(blocklist.isBlocklisted(anyString())).thenReturn(true);
        assertThatThrownBy(() -> connect()).isInstanceOf(MessagingException.class);
    }
}
