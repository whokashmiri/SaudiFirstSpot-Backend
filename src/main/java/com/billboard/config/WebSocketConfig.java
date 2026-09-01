package com.billboard.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Enables STOMP-over-WebSocket messaging so connected clients can subscribe
 * to {@code /topic/billboard} and receive the live, ranked billboard the
 * moment a payment succeeds.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws-billboard")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Messages published by the server (broadcasts) go out on /topic/**
        registry.enableSimpleBroker("/topic");
        // Messages sent from clients to the server would be prefixed /app/**
        registry.setApplicationDestinationPrefixes("/app");
    }
}
