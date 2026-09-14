package com.example.farm.controller;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.example.farm.common.utils.JwtUtils;
import com.example.farm.common.utils.LoginProtectUtil;
import com.example.farm.entity.PrintJob;
import com.example.farm.entity.Printer;
import com.example.farm.entity.User;
import com.example.farm.entity.vo.PrinterVO;
import com.example.farm.mapper.UserMapper;
import com.example.farm.service.WebSocketEventPublisher;
import com.example.farm.service.FarmStatusSnapshotService;
import com.example.farm.config.CommonObjectMapperConfig;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class WebSocketSecurityTest {

    private final FarmStatusSnapshotService snapshotService = org.mockito.Mockito.mock(FarmStatusSnapshotService.class);
    private final LoginProtectUtil loginProtectUtil = org.mockito.Mockito.mock(LoginProtectUtil.class);
    private final UserMapper userMapper = org.mockito.Mockito.mock(UserMapper.class);

    @BeforeEach
    void setJwtSecret() {
        ReflectionTestUtils.setField(JwtUtils.class, "STATIC_SECRET_KEY", "websocket-test-secret");
        WebSocketServer.configureMaxConnections(100);
        new WebSocketServer().setSnapshotService(snapshotService);
        new WebSocketServer().setLoginProtectUtil(loginProtectUtil);
        new WebSocketServer().setUserMapper(null);
        new WebSocketServer().setObjectMapper(new CommonObjectMapperConfig().objectMapper());
    }

    @AfterEach
    void closeAllSessions() {
        // 本测试不会将未授权 Session 加入广播集合，确保静态集合不影响其他测试。
        assertThat(WebSocketServer.getOnlineCount()).isZero();
        WebSocketServer.configureMaxConnections(100);
        new WebSocketServer().setSnapshotService(null);
        new WebSocketServer().setLoginProtectUtil(null);
        new WebSocketServer().setUserMapper(null);
        new WebSocketServer().setObjectMapper(null);
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
    void rejectsDisabledUserEvenWhenTokenIsValid() throws Exception {
        when(loginProtectUtil.isUserDisabled(1L)).thenReturn(true);
        Session session = authorizedSession();

        new WebSocketServer().onOpen(session);

        verify(session).close(any());
        assertThat(WebSocketServer.getOnlineCount()).isZero();
    }

    @Test
    void rejectsConnectionWhenDisabledStateCannotBeChecked() throws Exception {
        doThrow(new IllegalStateException("redis unavailable"))
                .when(loginProtectUtil).isUserDisabled(1L);
        Session session = authorizedSession();

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
    void serializesSnapshotWithJavaTimeFields() throws Exception {
        Session session = authorizedSession();
        RemoteEndpoint.Basic remote = org.mockito.Mockito.mock(RemoteEndpoint.Basic.class);
        when(session.getBasicRemote()).thenReturn(remote);
        when(session.isOpen()).thenReturn(true);

        Printer printer = new Printer();
        printer.setId(403L);
        printer.setName("Printer_C0DA");
        printer.setFirmwareType("KLIPPER");
        printer.setStatus("IDLE");
        printer.setCreatedAt(LocalDateTime.of(2026, 9, 3, 0, 0, 0));
        printer.setUpdatedAt(LocalDateTime.of(2026, 9, 3, 0, 0, 0));
        when(snapshotService.buildSnapshot()).thenReturn(Map.of(
                "printers", List.of(PrinterVO.from(printer))));

        new WebSocketServer().onOpen(session);

        verify(remote).sendText(org.mockito.ArgumentMatchers.argThat(message ->
                message.contains("\"type\":\"SNAPSHOT\"")
                        && message.contains("\"createdAt\":\"2026-09-03T00:00:00\"")));
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

    @Test
    void publishesOfflineAlertWithStableReason() throws Exception {
        Session session = authorizedSession();
        RemoteEndpoint.Basic remote = org.mockito.Mockito.mock(RemoteEndpoint.Basic.class);
        when(session.getBasicRemote()).thenReturn(remote);
        when(session.isOpen()).thenReturn(true);
        when(snapshotService.buildSnapshot()).thenReturn(Map.of("printers", List.of()));

        new WebSocketServer().onOpen(session);
        new WebSocketEventPublisher().publishPrinterOffline(403L, "");

        verify(remote).sendText(org.mockito.ArgumentMatchers.argThat(message ->
                        message.contains("\"type\":\"PRINTER_OFFLINE\"")
                        && message.contains("\"printerId\":\"403\"")
                        && message.contains("设备无法连接")));
        new WebSocketServer().onClose(session);
    }

    @Test
    void publishesFailedJobAlertWithErrorReason() throws Exception {
        Session session = authorizedSession();
        RemoteEndpoint.Basic remote = org.mockito.Mockito.mock(RemoteEndpoint.Basic.class);
        when(session.getBasicRemote()).thenReturn(remote);
        when(session.isOpen()).thenReturn(true);
        when(snapshotService.buildSnapshot()).thenReturn(Map.of("printers", List.of()));

        new WebSocketServer().onOpen(session);
        PrintJob job = new PrintJob();
        job.setId(1001L);
        job.setPrinterId(403L);
        job.setUserId(1L);
        job.setStatus("FAILED");
        job.setProgress(java.math.BigDecimal.valueOf(35));
        job.setErrorReason("喷嘴堵塞");
        new WebSocketEventPublisher().publishJobStatus(job);

        verify(remote).sendText(org.mockito.ArgumentMatchers.argThat(message ->
                        message.contains("\"type\":\"JOB_STATUS\"")
                        && message.contains("\"jobId\":\"1001\"")
                        && message.contains("喷嘴堵塞")));
        new WebSocketServer().onClose(session);
    }

    @Test
    void filtersJobEventsByOwnerAndDoesNotCreateSequenceGaps() throws Exception {
        Session owner = authorizedSession(1L, "OPERATOR", "owner-session");
        Session other = authorizedSession(2L, "OPERATOR", "other-session");
        Session admin = authorizedSession(9L, "ADMIN", "admin-session");
        RemoteEndpoint.Basic ownerBasic = basicRemote(owner);
        RemoteEndpoint.Basic otherBasic = basicRemote(other);
        RemoteEndpoint.Basic adminBasic = basicRemote(admin);
        when(snapshotService.buildSnapshot()).thenReturn(Map.of("printers", List.of()));

        WebSocketServer server = new WebSocketServer();
        server.onOpen(owner);
        server.onOpen(other);
        server.onOpen(admin);
        clearInvocations(ownerBasic, otherBasic, adminBasic);

        WebSocketServer.broadcastPrinterStatus(FarmStatusMessage.printerStatus(403L,
                Map.of("status", "IDLE")));
        PrintJob job = new PrintJob();
        job.setId(1002L);
        job.setUserId(1L);
        job.setStatus("PRINTING");
        new WebSocketEventPublisher().publishJobStatus(job);

        ArgumentCaptor<String> ownerMessages = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> otherMessages = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> adminMessages = ArgumentCaptor.forClass(String.class);
        verify(ownerBasic, times(2)).sendText(ownerMessages.capture());
        verify(otherBasic, times(1)).sendText(otherMessages.capture());
        verify(adminBasic, times(2)).sendText(adminMessages.capture());
        assertThat(ownerMessages.getAllValues().get(1)).contains("\"type\":\"JOB_STATUS\"");
        assertThat(adminMessages.getAllValues().get(1)).contains("\"type\":\"JOB_STATUS\"");
        assertThat(ownerMessages.getAllValues().get(1)).contains("\"printerId\":null", "\"currentJobId\":null");
        assertThat(ownerMessages.getAllValues().get(1)).doesNotContain("audienceUserId");
        assertThat(otherMessages.getValue()).doesNotContain("JOB_STATUS", "1002");
        assertThat(ownerMessages.getAllValues().get(0)).contains("\"sequence\":2");
        server.onClose(owner);
        server.onClose(other);
        server.onClose(admin);
    }

    @Test
    void keepsOneSessionContextWhileOnOpenRacesWithBroadcast() throws Exception {
        Session session = authorizedSession(1L, "OPERATOR", "concurrent-open-session");
        RemoteEndpoint.Basic remote = basicRemote(session);
        when(snapshotService.buildSnapshot()).thenReturn(Map.of("printers", List.of()));
        CountDownLatch snapshotStarted = new CountDownLatch(1);
        CountDownLatch releaseSnapshot = new CountDownLatch(1);
        List<String> messages = Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> {
            String message = invocation.getArgument(0);
            messages.add(message);
            if (message.contains("\"type\":\"SNAPSHOT\"")) {
                snapshotStarted.countDown();
                assertThat(releaseSnapshot.await(2, TimeUnit.SECONDS)).isTrue();
            }
            return null;
        }).when(remote).sendText(anyString());

        var executor = Executors.newFixedThreadPool(2);
        try {
            var opening = executor.submit(() -> new WebSocketServer().onOpen(session));
            assertThat(snapshotStarted.await(2, TimeUnit.SECONDS)).isTrue();
            var concurrentBroadcast = executor.submit(() -> WebSocketServer.broadcastPrinterStatus(
                    FarmStatusMessage.printerStatus(403L, Map.of("status", "IDLE"))));
            releaseSnapshot.countDown();
            opening.get(2, TimeUnit.SECONDS);
            concurrentBroadcast.get(2, TimeUnit.SECONDS);

            assertThat(messages).hasSize(2);
            assertThat(messages.get(0)).contains("\"type\":\"SNAPSHOT\"", "\"sequence\":1");
            assertThat(messages.get(1)).contains("\"type\":\"PRINTER_STATUS\"", "\"sequence\":2");
        } finally {
            releaseSnapshot.countDown();
            executor.shutdownNow();
            new WebSocketServer().onClose(session);
        }
    }

    @Test
    void sendsProtocolHeartbeatToAuthorizedSession() throws Exception {
        Session session = authorizedSession();
        RemoteEndpoint.Basic basic = org.mockito.Mockito.mock(RemoteEndpoint.Basic.class);
        RemoteEndpoint.Async async = org.mockito.Mockito.mock(RemoteEndpoint.Async.class);
        when(session.getBasicRemote()).thenReturn(basic);
        when(session.getAsyncRemote()).thenReturn(async);
        when(session.isOpen()).thenReturn(true);
        when(snapshotService.buildSnapshot()).thenReturn(Map.of("printers", List.of()));

        new WebSocketServer().onOpen(session);
        WebSocketServer.sendHeartbeat();

        verify(async).sendPing(org.mockito.ArgumentMatchers.any(java.nio.ByteBuffer.class));
        new WebSocketServer().onClose(session);
    }

    @Test
    void closesSessionWhenTokenVersionChangesDuringHeartbeat() throws Exception {
        User currentUser = new User();
        currentUser.setId(1L);
        currentUser.setUsername("operator");
        currentUser.setRole("OPERATOR");
        currentUser.setEnabled(true);
        currentUser.setTokenVersion(0L);
        when(userMapper.selectById(1L)).thenReturn(currentUser);

        Session session = authorizedSessionWithToken(token(1L, "OPERATOR", 0L, "operator"));
        RemoteEndpoint.Basic basic = org.mockito.Mockito.mock(RemoteEndpoint.Basic.class);
        RemoteEndpoint.Async async = org.mockito.Mockito.mock(RemoteEndpoint.Async.class);
        when(session.getBasicRemote()).thenReturn(basic);
        when(session.getAsyncRemote()).thenReturn(async);
        when(session.isOpen()).thenReturn(true);
        when(snapshotService.buildSnapshot()).thenReturn(Map.of("printers", List.of()));

        WebSocketServer server = new WebSocketServer();
        server.setUserMapper(userMapper);
        server.onOpen(session);
        currentUser.setTokenVersion(1L);

        WebSocketServer.sendHeartbeat();

        verify(session).close(any(jakarta.websocket.CloseReason.class));
        assertThat(WebSocketServer.getOnlineCount()).isZero();
    }

    @Test
    void removesSessionWhenProtocolHeartbeatFails() throws Exception {
        Session session = authorizedSession();
        RemoteEndpoint.Basic basic = org.mockito.Mockito.mock(RemoteEndpoint.Basic.class);
        RemoteEndpoint.Async async = org.mockito.Mockito.mock(RemoteEndpoint.Async.class);
        when(session.getBasicRemote()).thenReturn(basic);
        when(session.getAsyncRemote()).thenReturn(async);
        when(session.isOpen()).thenReturn(true);
        when(snapshotService.buildSnapshot()).thenReturn(Map.of("printers", List.of()));
        doThrow(new IOException("connection closed")).when(async)
                .sendPing(org.mockito.ArgumentMatchers.any(java.nio.ByteBuffer.class));

        new WebSocketServer().onOpen(session);
        WebSocketServer.sendHeartbeat();

        verify(session).close();
        assertThat(WebSocketServer.getOnlineCount()).isZero();
    }

    @Test
    void enforcesConfiguredConnectionLimit() throws Exception {
        WebSocketServer.configureMaxConnections(1);
        Session first = authorizedSession();
        RemoteEndpoint.Basic firstBasic = org.mockito.Mockito.mock(RemoteEndpoint.Basic.class);
        when(first.getBasicRemote()).thenReturn(firstBasic);
        when(first.isOpen()).thenReturn(true);
        when(snapshotService.buildSnapshot()).thenReturn(Map.of("printers", List.of()));

        Session second = authorizedSession();
        new WebSocketServer().onOpen(first);
        new WebSocketServer().onOpen(second);

        verify(second).close(any());
        assertThat(WebSocketServer.getOnlineCount()).isEqualTo(1);
        new WebSocketServer().onClose(first);
    }

    private Session authorizedSession() {
        return authorizedSession(1L, "OPERATOR", "authorized-session");
    }

    private Session authorizedSession(Long userId, String role, String sessionId) {
        return authorizedSessionWithToken(sessionId, token(userId, role));
    }

    private Session authorizedSessionWithToken(String token) {
        return authorizedSessionWithToken("authorized-session", token);
    }

    private Session authorizedSessionWithToken(String sessionId, String token) {
        Session session = org.mockito.Mockito.mock(Session.class);
        when(session.getId()).thenReturn(sessionId);
        when(session.getRequestParameterMap()).thenReturn(Map.of("token", List.of(token)));
        Map<String, Object> properties = new HashMap<>();
        when(session.getUserProperties()).thenReturn(properties);
        return session;
    }

    private String token() {
        return token(1L, "OPERATOR");
    }

    private String token(Long userId, String role) {
        return JWT.create()
                .withClaim("userId", userId)
                .withClaim("role", role)
                .withExpiresAt(new Date(System.currentTimeMillis() + 60_000))
                .sign(Algorithm.HMAC256("websocket-test-secret"));
    }

    private String token(Long userId, String role, Long tokenVersion, String username) {
        return JWT.create()
                .withClaim("userId", userId)
                .withClaim("username", username)
                .withClaim("role", role)
                .withClaim("tokenVersion", tokenVersion)
                .withExpiresAt(new Date(System.currentTimeMillis() + 60_000))
                .sign(Algorithm.HMAC256("websocket-test-secret"));
    }

    private RemoteEndpoint.Basic basicRemote(Session session) {
        RemoteEndpoint.Basic remote = org.mockito.Mockito.mock(RemoteEndpoint.Basic.class);
        when(session.getBasicRemote()).thenReturn(remote);
        when(session.isOpen()).thenReturn(true);
        return remote;
    }
}
