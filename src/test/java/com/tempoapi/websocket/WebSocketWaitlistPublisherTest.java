package com.tempoapi.websocket;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WebSocketWaitlistPublisherTest {

    @Test
    void offerGoesToTheUsersPrivateQueue() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        WebSocketWaitlistPublisher publisher = new WebSocketWaitlistPublisher(template);
        UUID userId = UUID.randomUUID();
        WaitlistMessage message = new WaitlistMessage(UUID.randomUUID(), UUID.randomUUID(), "Desk 1",
                UUID.randomUUID(), OffsetDateTime.now(), OffsetDateTime.now().plusHours(1),
                Instant.now().plusSeconds(120), WaitlistState.OFFERED);

        publisher.publishOffer(userId, message);

        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> dest = ArgumentCaptor.forClass(String.class);
        verify(template).convertAndSendToUser(user.capture(), dest.capture(), org.mockito.ArgumentMatchers.eq(message));
        assertThat(user.getValue()).isEqualTo(userId.toString());
        assertThat(dest.getValue()).isEqualTo("/queue/waitlist");
    }
}
