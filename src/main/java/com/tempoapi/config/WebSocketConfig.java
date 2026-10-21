package com.tempoapi.config;

import com.tempoapi.websocket.WebSocketAuthInterceptor;
import com.tempoapi.websocket.WebSocketSessions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.Arrays;

/**
 * STOMP-over-WebSocket configuration. Clients connect at {@code /ws} (with SockJS
 * fallback), subscribe under {@code /topic}, and are authenticated on CONNECT by the
 * inbound-channel interceptor.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketAuthInterceptor webSocketAuthInterceptor;
    private final WebSocketSessions sessions;
    private final String[] allowedOrigins;

    public WebSocketConfig(WebSocketAuthInterceptor webSocketAuthInterceptor, WebSocketSessions sessions,
                           @Value("${app.websocket.allowed-origins:http://localhost:8080}") String allowedOrigins) {
        this.webSocketAuthInterceptor = webSocketAuthInterceptor;
        this.sessions = sessions;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(origin -> !origin.isEmpty()).toArray(String[]::new);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOrigins(allowedOrigins)
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setPreservePublishOrder(true);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(webSocketAuthInterceptor);
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(sessions);
    }
}
