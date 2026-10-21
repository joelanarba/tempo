package com.tempoapi.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tempoapi.config.JwtProperties;
import com.tempoapi.config.WebSocketConfig;
import com.tempoapi.entity.Role;
import com.tempoapi.security.TokenBlocklist;
import com.tempoapi.support.MutableClock;
import com.tempoapi.util.JwtUtil;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.lang.NonNull;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.ByteArrayMessageConverter;
import org.springframework.messaging.simp.*;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import java.lang.reflect.Type;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = WebSocketSecurityTest.Server.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketSecurityTest {
    @Configuration
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class, RedisAutoConfiguration.class, SecurityAutoConfiguration.class,
            UserDetailsServiceAutoConfiguration.class})
    @Import({WebSocketConfig.class, WebSocketAuthInterceptor.class, WebSocketSessions.class,
            WebSocketAvailabilityPublisher.class, WebSocketWaitlistPublisher.class})
    static class Server {
        @Bean MutableClock clock() { return new MutableClock(Instant.now()); }
        @Bean JwtUtil jwtUtil() {
            JwtProperties properties = new JwtProperties();
            properties.setSecret(UUID.randomUUID().toString() + UUID.randomUUID());
            properties.setAccessExpiryMs(60000);
            return new JwtUtil(properties);
        }
    }

    @LocalServerPort int port;
    @Autowired JwtUtil jwt;
    @Autowired MutableClock clock;
    @Autowired ObjectMapper mapper;
    @Autowired WebSocketAvailabilityPublisher availability;
    @Autowired WebSocketWaitlistPublisher waitlist;
    @Autowired SimpleBrokerMessageHandler broker;
    @Autowired SimpUserRegistry users;
    @MockBean TokenBlocklist blocklist;
    private final Set<String> revoked = ConcurrentHashMap.newKeySet();
    private final List<StompSession> connected = new ArrayList<>();
    private WebSocketStompClient client;

    private static class Handler extends StompSessionHandlerAdapter {
        final BlockingQueue<String> errors = new LinkedBlockingQueue<>();
        @Override public @NonNull Type getPayloadType(@NonNull StompHeaders headers) { return byte[].class; }
        @Override public void handleFrame(@NonNull StompHeaders headers, Object payload) {
            errors.add(headers.getFirst("message") == null ? "STOMP error" : headers.getFirst("message"));
        }
    }

    @BeforeEach
    void setUp() {
        clock.set(Instant.now());
        when(blocklist.isBlocklisted(anyString())).thenAnswer(call -> revoked.contains(call.getArgument(0)));
        client = new WebSocketStompClient(new StandardWebSocketClient());
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(mapper);
        client.setMessageConverter(new CompositeMessageConverter(List.of(new ByteArrayMessageConverter(), converter)));
    }

    @AfterEach
    void stop() {
        try {
            for (StompSession session : connected) {
                try {
                    if (session.isConnected()) session.disconnect();
                } catch (org.springframework.messaging.MessageDeliveryException ex) {
                    boolean closed = ex.getCause() instanceof IllegalStateException
                            && String.valueOf(ex.getCause().getMessage()).contains("session has been closed");
                    if (!(ex.getCause() instanceof ConnectionLostException) && !closed) throw ex;
                }
            }
        } finally {
            client.stop();
        }
    }

    private String token(UUID userId) { return jwt.generateAccessToken(userId, Role.MEMBER); }
    private String topic(UUID spaceId) { return "/topic/spaces/" + spaceId + "/availability"; }
    private StompSession connect(String token, Handler handler) throws Exception {
        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + token);
        StompSession session = client.connectAsync("ws://localhost:" + port + "/ws/websocket",
                new WebSocketHttpHeaders(), headers, handler).get(5, TimeUnit.SECONDS);
        connected.add(session);
        return session;
    }

    private BlockingQueue<JsonNode> subscribe(StompSession session, String destination) {
        BlockingQueue<JsonNode> queue = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override public @NonNull Type getPayloadType(@NonNull StompHeaders headers) { return JsonNode.class; }
            @Override public void handleFrame(@NonNull StompHeaders headers, Object payload) { queue.add((JsonNode) payload); }
        });
        return queue;
    }

    private void registered(String destination, int count) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setDestination(destination);
        var message = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(broker.getSubscriptionRegistry().findSubscriptions(message)).hasSize(count));
    }

    private SlotChange slot(UUID spaceId) {
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1);
        return new SlotChange(spaceId, UUID.randomUUID(), "Desk", start, start.plusHours(1));
    }

    @Test
    void legitimateBroadcastReachesTwoSubscribers() throws Exception {
        UUID spaceId = UUID.randomUUID();
        var first = subscribe(connect(token(UUID.randomUUID()), new Handler()), topic(spaceId));
        var second = subscribe(connect(token(UUID.randomUUID()), new Handler()), topic(spaceId));
        registered(topic(spaceId), 2);
        availability.publishBooked(slot(spaceId));
        assertThat(first.poll(5, TimeUnit.SECONDS).get("state").asText()).isEqualTo("BOOKED");
        assertThat(second.poll(5, TimeUnit.SECONDS).get("state").asText()).isEqualTo("BOOKED");
    }

    @Test
    void forgedSendProducesErrorAndCannotReachSubscriber() throws Exception {
        UUID spaceId = UUID.randomUUID();
        var victim = subscribe(connect(token(UUID.randomUUID()), new Handler()), topic(spaceId));
        registered(topic(spaceId), 1);
        Handler senderHandler = new Handler();
        var sender = connect(token(UUID.randomUUID()), senderHandler);
        sender.send(topic(spaceId), Map.of("state", "FORGED"));
        assertThat(senderHandler.errors.poll(5, TimeUnit.SECONDS)).isNotNull();
        availability.publishBooked(slot(spaceId));
        assertThat(victim.poll(5, TimeUnit.SECONDS).get("state").asText()).isEqualTo("BOOKED");
        assertThat(victim.poll(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void directPhysicalQueueSubscriptionIsRejected() throws Exception {
        Handler handler = new Handler();
        subscribe(connect(token(UUID.randomUUID()), handler), "/queue/waitlist-useranother-session");
        assertThat(handler.errors.poll(5, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    void revokedTokenCannotEstablishSession() throws Exception {
        String token = token(UUID.randomUUID());
        revoked.add(jwt.extractJti(jwt.parse(token)));
        Handler handler = new Handler();
        assertThatThrownBy(() -> connect(token, handler)).isInstanceOf(ExecutionException.class);
        assertThat(handler.errors.poll(5, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    void logoutStopsPassiveSubscriberWhileOtherSubscriberStillReceives() throws Exception {
        UUID spaceId = UUID.randomUUID();
        String oldToken = token(UUID.randomUUID());
        var old = subscribe(connect(oldToken, new Handler()), topic(spaceId));
        var active = subscribe(connect(token(UUID.randomUUID()), new Handler()), topic(spaceId));
        registered(topic(spaceId), 2);
        revoked.add(jwt.extractJti(jwt.parse(oldToken)));
        availability.publishBooked(slot(spaceId));
        assertThat(active.poll(5, TimeUnit.SECONDS)).isNotNull();
        assertThat(old.poll(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void expiredPassiveSessionStopsReceiving() throws Exception {
        UUID spaceId = UUID.randomUUID();
        var queue = subscribe(connect(token(UUID.randomUUID()), new Handler()), topic(spaceId));
        registered(topic(spaceId), 1);
        clock.set(clock.instant().plusSeconds(120));
        availability.publishBooked(slot(spaceId));
        assertThat(queue.poll(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void privateOfferReachesOnlyIntendedUser() throws Exception {
        UUID recipient = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        var intended = subscribe(connect(token(recipient), new Handler()), "/user/queue/waitlist");
        var unrelated = subscribe(connect(token(other), new Handler()), "/user/queue/waitlist");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(users.getUser(recipient.toString())).isNotNull();
            assertThat(users.getUser(other.toString())).isNotNull();
        });
        String recipientSession = users.getUser(recipient.toString()).getSessions().iterator().next().getId();
        String otherSession = users.getUser(other.toString()).getSessions().iterator().next().getId();
        registered("/queue/waitlist-user" + recipientSession, 1);
        registered("/queue/waitlist-user" + otherSession, 1);
        SlotChange slot = slot(UUID.randomUUID());
        UUID entryId = UUID.randomUUID();
        waitlist.publishOffer(recipient, new WaitlistMessage(entryId, slot.resourceId(), slot.resourceName(), slot.spaceId(),
                slot.startTime(), slot.endTime(), clock.instant().plusSeconds(30), WaitlistState.OFFERED));
        assertThat(intended.poll(5, TimeUnit.SECONDS).get("entryId").asText()).isEqualTo(entryId.toString());
        assertThat(unrelated.poll(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void untrustedBrowserOriginIsRejectedAtHandshake() {
        WebSocketHttpHeaders httpHeaders = new WebSocketHttpHeaders();
        httpHeaders.setOrigin("https://untrusted.example");
        StompHeaders headers = new StompHeaders();
        headers.set("Authorization", "Bearer " + token(UUID.randomUUID()));
        assertThatThrownBy(() -> client.connectAsync("ws://localhost:" + port + "/ws/websocket",
                httpHeaders, headers, new Handler()).get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class).hasStackTraceContaining("403");
    }
}
