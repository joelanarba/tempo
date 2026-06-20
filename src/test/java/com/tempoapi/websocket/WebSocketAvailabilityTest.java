package com.tempoapi.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tempoapi.AbstractIntegrationTest;
import com.tempoapi.entity.Resource;
import com.tempoapi.entity.ResourceType;
import com.tempoapi.entity.Role;
import com.tempoapi.entity.Space;
import com.tempoapi.entity.User;
import com.tempoapi.modules.bookings.dto.CreateBookingRequest;
import com.tempoapi.repository.ResourceRepository;
import com.tempoapi.repository.SpaceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WebSocketAvailabilityTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;
    @Autowired
    private SpaceRepository spaceRepository;
    @Autowired
    private ResourceRepository resourceRepository;
    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    private WebSocketStompClient stompClient;
    private Space space;
    private Resource resource;
    private User member;
    private String memberToken;

    @BeforeEach
    void setUp() {
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(objectMapper);
        stompClient.setMessageConverter(converter);

        space = spaceRepository.save(new Space(
                "Hub", "Accra", "desc", java.time.LocalTime.of(8, 0), java.time.LocalTime.of(20, 0)));
        resource = resourceRepository.save(new Resource(space, "Desk 1", ResourceType.DESK, null));
        member = persistUser(Role.MEMBER);
        memberToken = "Bearer " + jwtUtil.generateAccessToken(member.getId(), member.getRole());
    }

    @AfterEach
    void tearDown() {
        stompClient.stop();
    }

    private StompSession connect(String bearerToken) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        if (bearerToken != null) {
            connectHeaders.add("Authorization", bearerToken);
        }
        return stompClient.connectAsync(
                        "ws://localhost:" + port + "/ws/websocket",
                        new WebSocketHttpHeaders(),
                        connectHeaders,
                        new StompSessionHandlerAdapter() {
                        })
                .get(5, TimeUnit.SECONDS);
    }

    private BlockingQueue<AvailabilityMessage> subscribe(StompSession session) {
        BlockingQueue<AvailabilityMessage> queue = new LinkedBlockingQueue<>();
        session.subscribe("/topic/spaces/" + space.getId() + "/availability", new StompFrameHandler() {
            @Override
            @NonNull
            public Type getPayloadType(@NonNull StompHeaders headers) {
                return AvailabilityMessage.class;
            }

            @Override
            public void handleFrame(@NonNull StompHeaders headers, Object payload) {
                queue.add((AvailabilityMessage) payload);
            }
        });
        return queue;
    }

    private OffsetDateTime at(int hour) {
        return LocalDate.now().plusDays(1).atTime(hour, 0).atOffset(ZoneOffset.UTC);
    }

    private String createBooking() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.AUTHORIZATION, memberToken);
        HttpEntity<CreateBookingRequest> entity = new HttpEntity<>(
                new CreateBookingRequest(resource.getId(), at(9), at(10)), headers);
        ResponseEntity<String> response = restTemplate.postForEntity("/api/v1/bookings", entity, String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        try {
            JsonNode root = objectMapper.readTree(response.getBody());
            return root.get("data").get("id").asText();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void subscriberReceivesBookedMessageWhenBookingIsCreated() throws Exception {
        StompSession session = connect(memberToken);
        BlockingQueue<AvailabilityMessage> queue = subscribe(session);

        createBooking();

        AvailabilityMessage message = queue.poll(5, TimeUnit.SECONDS);
        assertThat(message).isNotNull();
        assertThat(message.state()).isEqualTo(AvailabilityState.BOOKED);
        assertThat(message.resourceId()).isEqualTo(resource.getId());
        assertThat(message.startTime()).isEqualTo(at(9));
    }

    @Test
    void subscriberReceivesAvailableMessageWhenBookingIsCancelled() throws Exception {
        StompSession session = connect(memberToken);
        BlockingQueue<AvailabilityMessage> queue = subscribe(session);

        String bookingId = createBooking();
        // Drain the BOOKED message first.
        assertThat(queue.poll(5, TimeUnit.SECONDS)).isNotNull();

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, memberToken);
        restTemplate.exchange("/api/v1/bookings/" + bookingId, HttpMethod.DELETE,
                new HttpEntity<>(headers), String.class);

        AvailabilityMessage message = queue.poll(5, TimeUnit.SECONDS);
        assertThat(message).isNotNull();
        assertThat(message.state()).isEqualTo(AvailabilityState.AVAILABLE);
        assertThat(message.resourceId()).isEqualTo(resource.getId());
    }

    @Test
    void connectWithoutTokenIsRejected() {
        assertThatThrownBy(() -> connect(null)).isInstanceOf(Exception.class);
    }

    @Test
    void connectWithInvalidTokenIsRejected() {
        assertThatThrownBy(() -> connect("Bearer not-a-real-token")).isInstanceOf(Exception.class);
    }
}
