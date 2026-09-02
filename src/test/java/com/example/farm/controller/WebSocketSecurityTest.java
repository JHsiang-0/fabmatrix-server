package com.example.farm.controller;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.example.farm.common.utils.JwtUtils;
import com.example.farm.service.FarmStatusSnapshotService;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Date;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketSecurityTest {

    private final FarmStatusSnapshotService snapshotService = org.mockito.Mockito.mock(FarmStatusSnapshotService.class);

    @BeforeEach
    void setJwtSecret() {
        ReflectionTestUtils.setField(JwtUtils.class, "STATIC_SECRET_KEY", "websocket-test-secret");
        new WebSocketServer().setSnapshotService(snapshotService);
    }

    @AfterEach
    void closeAllSessions() {
        // 本测试不会将未授权 Session 加入广播集合，确保静态集合不影响其他测试。
        assertThat(WebSocketServer.getOnlineCount()).isZero();
        new WebSocketServer().setSnapshotService(null);
    }

    @Test
    void rejectsConnectionWithoutToken() throws Exception {
        Session session = mock(Session.class);
        when(session.getId()).thenReturn("unauthorized-session");
        when(session.getRequestParameterMap()).thenReturn(Map.of());

        new WebSocketServer().onOpen(session);

        verify(session).close(any());
        assertThat(WebSocketServer.getOnlineCount()).isZero();
    }

    @Test
    void acceptsValidTokenAndSendsInitialSnapshot() throws Exception {
        Session session = authorizedSession();
        RemoteEndpoint.Basic remote = org.mockito.Mockito.mock(RemoteEndpoint.Basic.class);
        when(session.getBasicRemote()).thenReturn(remote);
        when(session.isOpen()).thenReturn(true);
        when(snapshotService.buildSnapshot()).thenReturn(Map.of("printers", List.of()));

        new WebSocketServer().onOpen(session);

        verify(remote).sendText(org.mockito.ArgumentMatchers.argThat(message ->
                message.contains("\"type\":\"SNAPSHOT\"")
                        && message.contains("\"printers\":[]")));
        assertThat(WebSocketServer.getOnlineCount()).isEqualTo(1);
        new WebSocketServer().onClose(session);
    }

    @Test
    void removesSessionWhenBroadcastFails() throws Exception {
        Session session = authorizedSession();
        RemoteEndpoint.Basic remote = org.mockito.Mockito.mock(RemoteEndpoint.Basic.class);
        when(session.getBasicRemote()).thenReturn(remote);
        when(session.isOpen()).thenReturn(true);
        when(snapshotService.buildSnapshot()).thenReturn(Map.of("printers", List.of()));

        new WebSocketServer().onOpen(session);
        doThrow(new IOException("connection closed")).when(remote).sendText(anyString());

        WebSocketServer.broadcastPrinterStatus(FarmStatusMessage.printerStatus(403L,
                Map.of("status", "IDLE")));

        verify(session).close();
        assertThat(WebSocketServer.getOnlineCount()).isZero();
    }

    private Session authorizedSession() {
        Session session = org.mockito.Mockito.mock(Session.class);
        when(session.getId()).thenReturn("authorized-session");
        when(session.getRequestParameterMap()).thenReturn(Map.of("token", List.of(token())));
        when(session.getUserProperties()).thenReturn(new HashMap<>());
        return session;
    }

    private String token() {
        return JWT.create()
                .withClaim("userId", 1L)
                .withClaim("role", "OPERATOR")
                .withExpiresAt(new Date(System.currentTimeMillis() + 60_000))
                .sign(Algorithm.HMAC256("websocket-test-secret"));
    }
}
