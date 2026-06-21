package com.tempoapi.modules.waitlist;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tempoapi.AbstractIntegrationTest;
import com.tempoapi.entity.Resource;
import com.tempoapi.entity.ResourceType;
import com.tempoapi.entity.Role;
import com.tempoapi.entity.Space;
import com.tempoapi.entity.User;
import com.tempoapi.modules.bookings.BookingService;
import com.tempoapi.modules.bookings.dto.BookingResponse;
import com.tempoapi.modules.bookings.dto.CreateBookingRequest;
import com.tempoapi.modules.waitlist.dto.JoinWaitlistRequest;
import com.tempoapi.repository.ResourceRepository;
import com.tempoapi.repository.SpaceRepository;
import com.tempoapi.websocket.WaitlistMessage;
import com.tempoapi.websocket.WaitlistState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that B receives a WaitlistMessage(state=OFFERED) on the private /user/queue/waitlist
 * destination when A cancels a booking and the waitlist promotion fires.
 *
 * <p>Requires a running broker (RANDOM_PORT SpringBootTest) and Testcontainers Postgres + Redis.
 * Runs in Codespaces, not locally (Docker unavailable locally).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WaitlistWebSocketTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private SpaceRepository spaceRepository;
    @Autowired
    private ResourceRepository resourceRepository;
    @Autowired
    private BookingService bookingService;
    @Autowired
    private WaitlistService waitlistService;
    @Autowired
    private ObjectMapper objectMapper;

    private WebSocketStompClient stompClient;

    @BeforeEach
    void setUp() {
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(objectMapper);
        stompClient.setMessageConverter(converter);
    }

    @AfterEach
    void tearDown() {
        stompClient.stop();
    }

    private StompSession connect(String bearerToken) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", bearerToken);
        return stompClient.connectAsync(
                        "ws://localhost:" + port + "/ws/websocket",
                        new WebSocketHttpHeaders(),
                        connectHeaders,
                        new StompSessionHandlerAdapter() {
                        })
                .get(5, TimeUnit.SECONDS);
    }

    private OffsetDateTime at(int hour) {
        return LocalDate.now().plusDays(1).atTime(hour, 0).atOffset(ZoneOffset.UTC);
    }

    @Test
    void waiterReceivesOfferedMessageWhenBookerCancels() throws Exception {
        Space space = spaceRepository.save(
                new Space("Hub", "Accra", "d", LocalTime.of(8, 0), LocalTime.of(20, 0)));
        Resource resource = resourceRepository.save(
                new Resource(space, "Desk 1", ResourceType.DESK, null));

        User a = persistUser(Role.MEMBER);
        User b = persistUser(Role.MEMBER);

        String bToken = "Bearer " + jwtUtil.generateAccessToken(b.getId(), b.getRole());

        // Connect B via STOMP and subscribe to the private waitlist destination.
        StompSession bSession = connect(bToken);
        BlockingQueue<WaitlistMessage> queue = new LinkedBlockingQueue<>();
        bSession.subscribe("/user/queue/waitlist", new StompFrameHandler() {
            @Override
            @NonNull
            public Type getPayloadType(@NonNull StompHeaders headers) {
                return WaitlistMessage.class;
            }

            @Override
            public void handleFrame(@NonNull StompHeaders headers, Object payload) {
                queue.add((WaitlistMessage) payload);
            }
        });

        // A books the slot; B joins the waitlist.
        BookingResponse booking = bookingService.createBooking(
                a.getId(), new CreateBookingRequest(resource.getId(), at(9), at(10)));
        waitlistService.join(b.getId(), new JoinWaitlistRequest(resource.getId(), at(9), at(10)));

        // A cancels — this triggers waitlist promotion, which should push OFFERED to B.
        bookingService.cancelBooking(a.getId(), booking.id());

        WaitlistMessage message = queue.poll(5, TimeUnit.SECONDS);
        assertThat(message).isNotNull();
        assertThat(message.state()).isEqualTo(WaitlistState.OFFERED);
        assertThat(message.resourceId()).isEqualTo(resource.getId());
    }
}
