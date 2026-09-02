package com.example.farm.config;

import com.example.farm.controller.WebSocketServer;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.socket.server.standard.ServerEndpointExporter;

@Configuration
@ConditionalOnProperty(
        name = "farm.websocket.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class WebSocketConfig {

    @Value("${farm.websocket.max-connections:100}")
    private int maxConnections;

    @PostConstruct
    public void configureWebSocketLimit() {
        WebSocketServer.configureMaxConnections(maxConnections);
    }

    @Bean
    public ServerEndpointExporter serverEndpointExporter() {
        return new ServerEndpointExporter();
    }
}
