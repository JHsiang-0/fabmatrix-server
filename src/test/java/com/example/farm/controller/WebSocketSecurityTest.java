package com.example.farm.controller;

import jakarta.websocket.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketSecurityTest {

    @AfterEach
    void closeAllSessions() {
        // 本测试不会将未授权 Session 加入广播集合，确保静态集合不影响其他测试。
        assertThat(WebSocketServer.getOnlineCount()).isZero();
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
}
