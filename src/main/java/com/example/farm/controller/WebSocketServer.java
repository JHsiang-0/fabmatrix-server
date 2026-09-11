package com.example.farm.controller;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.example.farm.common.utils.JwtUtils;
import com.example.farm.common.utils.LoginProtectUtil;
import com.example.farm.service.FarmStatusSnapshotService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.websocket.*;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@ServerEndpoint("/ws/farm-status") // 前端大屏连接的 WebSocket 地址
public class WebSocketServer {

    private static final int DEFAULT_MAX_CONNECTIONS = 100;
    private static volatile int maxConnections = DEFAULT_MAX_CONNECTIONS;

    /** 稳定会话上下文必须先完整构造，再加入可广播集合。 */
    private static final CopyOnWriteArraySet<SessionContext> sessions = new CopyOnWriteArraySet<>();
    private static final Map<Session, SessionContext> sessionContexts = new ConcurrentHashMap<>();
    
    private static volatile ObjectMapper objectMapper;
    private static volatile FarmStatusSnapshotService snapshotService;
    private static volatile LoginProtectUtil loginProtectUtil;

    /**
     * Jakarta Endpoint 由容器创建，使用 setter 将 Spring 快照服务注册到端点。
     */
    @Autowired
    public void setSnapshotService(FarmStatusSnapshotService service) {
        WebSocketServer.snapshotService = service;
    }

    /** 使用 Spring 全局 ObjectMapper，确保 REST 与 WebSocket 的 Long 序列化一致。 */
    @Autowired
    public void setObjectMapper(ObjectMapper mapper) {
        WebSocketServer.objectMapper = mapper;
    }

    /**
     * 将 HTTP 鉴权链使用的禁用状态检查桥接到 Jakarta Endpoint。
     */
    @Autowired
    public void setLoginProtectUtil(LoginProtectUtil service) {
        WebSocketServer.loginProtectUtil = service;
    }

    @OnOpen
    public void onOpen(Session session) {
        String token = firstText(session.getRequestParameterMap().get("token"));
        if (token == null) {
            token = firstText(session.getRequestParameterMap().get("access_token"));
        }
        if (token == null) {
            closeForPolicy(session, "缺少 WebSocket Token");
            return;
        }

        try {
            DecodedJWT jwt = JwtUtils.verifyToken(token);
            Long userId = jwt.getClaim("userId").asLong();
            String role = jwt.getClaim("role").asString();
            if (userId == null || role == null || role.isBlank()) {
                closeForPolicy(session, "Token 缺少用户身份");
                return;
            }
            LoginProtectUtil protectUtil = loginProtectUtil;
            if (protectUtil == null) {
                closeForPolicy(session, "鉴权服务尚未就绪");
                return;
            }
            try {
                if (protectUtil.isUserDisabled(userId)) {
                    closeForPolicy(session, "用户已被禁用");
                    return;
                }
            } catch (RuntimeException exception) {
                log.warn("WebSocket 用户禁用状态检查失败，拒绝连接: sessionId={}, userId={}",
                        session.getId(), userId, exception);
                closeForPolicy(session, "鉴权服务暂不可用");
                return;
            }
            SessionContext context = new SessionContext(session);
            synchronized (sessions) {
                if (sessions.size() >= maxConnections) {
                    closeForPolicy(session, "WebSocket 连接数已达上限");
                    return;
                }
                session.getUserProperties().put("userId", userId);
                session.getUserProperties().put("role", role);
                if (sessionContexts.putIfAbsent(session, context) != null) return;
                sessions.add(context);
            }
        } catch (JWTVerificationException | IllegalArgumentException e) {
            closeForPolicy(session, "WebSocket Token 无效或已过期");
            return;
        }

        log.info("WebSocket 客户端接入，当前在线连接数: {}", sessions.size());
        SessionContext context = sessionContexts.get(session);
        if (context != null) sendInitialSnapshot(context);
    }

    /**
     * 由 Spring 配置桥接组件设置连接上限；测试和运行时配置必须使用正数。
     */
    public static void configureMaxConnections(int configuredMaxConnections) {
        if (configuredMaxConnections <= 0) {
            throw new IllegalArgumentException("WebSocket 连接上限必须为正数");
        }
        maxConnections = configuredMaxConnections;
    }

    @OnClose
    public void onClose(Session session) {
        removeSession(session);
        log.info("WebSocket 客户端断开，当前在线连接数: {}", sessions.size());
    }

    @OnError
    public void onError(Session session, Throwable error) {
        log.error("WebSocket 发生错误", error);
        removeSession(session);
    }

    /**
     * 向所有在线的大屏广播打印机最新状态 (JSON 格式)
     * 使用同步锁解决并发写入冲突问题
     */
    public static void broadcastPrinterStatus(FarmStatusMessage data) {
        broadcast(data, false);
    }

    public static void broadcastPrinterOffline(FarmStatusMessage data) {
        broadcast(data, false);
    }

    public static void broadcastJobStatus(FarmStatusMessage data) {
        broadcast(data, true);
    }

    public static void broadcastLayoutUpdated(FarmStatusMessage data) {
        broadcast(data, false);
    }

    /**
     * 发送 WebSocket 协议级 Ping。浏览器客户端会自动回复 Pong，
     * 不会产生新的业务消息类型；发送失败的连接立即清理。
     */
    public static void sendHeartbeat() {
        for (SessionContext context : sessions) {
            Session session = context.session;
            if (!session.isOpen()) {
                closeAndRemove(session);
                continue;
            }
            synchronized (context.lock) {
                if (!context.ready) continue;
                try {
                    session.getAsyncRemote().sendPing(ByteBuffer.allocate(0));
                } catch (IOException | RuntimeException exception) {
                    log.warn("WebSocket 心跳发送失败，清理会话: sessionId={}", session.getId(), exception);
                    closeAndRemove(session);
                }
            }
        }
    }

    private static void broadcast(FarmStatusMessage data, boolean jobOnlyForOwner) {
        if (sessions.isEmpty()) return;

        for (SessionContext context : sessions) {
            Session session = context.session;
            if (session.isOpen() && (!jobOnlyForOwner || canReceiveJobStatus(session, data))) {
                synchronized (context.lock) {
                    if (!context.ready || sessionContexts.get(session) != context) continue;
                    try {
                        sendLocked(context, data);
                    } catch (Exception e) {
                        log.error("向客户端发送消息失败: sessionId={}", session.getId(), e);
                        // 发送失败时关闭会话
                        try {
                            session.close();
                        } catch (IOException closeEx) {
                            log.error("关闭异常会话失败: sessionId={}", session.getId(), closeEx);
                        }
                        removeSession(session);
                    }
                }
            }
        }
    }
    
    /**
     * 向指定会话发送消息（单播）
     * @param session 目标会话
     * @param data 消息数据
     */
    public static void sendMessage(Session session, Object data) {
        if (session == null || !session.isOpen()) return;
        SessionContext context = sessionContexts.get(session);
        if (context == null) return;

        synchronized (context.lock) {
            if (!context.ready) return;
            try {
                sendLocked(context, data);
            } catch (Exception e) {
                log.error("单播消息发送失败: sessionId={}", session.getId(), e);
                closeAndRemove(session);
            }
        }
    }
    
    /**
     * 获取当前在线连接数
     */
    public static int getOnlineCount() {
        return sessions.size();
    }

    private static void sendInitialSnapshot(SessionContext context) {
        Session session = context.session;
        FarmStatusSnapshotService service = snapshotService;
        try {
            synchronized (context.lock) {
                if (service == null) {
                    log.warn("WebSocket 初始快照服务尚未就绪: sessionId={}", session.getId());
                } else {
                    sendLocked(context, FarmStatusMessage.snapshot(service.buildSnapshot()));
                }
                if (sessionContexts.get(session) == context && session.isOpen()) context.ready = true;
            }
        } catch (Exception exception) {
            log.error("发送 WebSocket 初始快照失败: sessionId={}", session.getId(), exception);
            closeAndRemove(session);
        }
    }

    private static String firstText(java.util.List<String> values) {
        if (values == null || values.isEmpty() || values.get(0) == null || values.get(0).isBlank()) {
            return null;
        }
        return values.get(0);
    }

    private static void closeForPolicy(Session session, String message) {
        try {
            session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, message));
        } catch (IOException e) {
            log.debug("关闭未通过鉴权的 WebSocket 连接失败: sessionId={}", session.getId(), e);
        }
        log.warn("拒绝未授权 WebSocket 连接: sessionId={}, reason={}", session.getId(), message);
    }

    private static void closeAndRemove(Session session) {
        removeSession(session);
        try {
            if (session.isOpen()) {
                session.close();
            }
        } catch (IOException closeException) {
            log.debug("关闭异常 WebSocket 连接失败: sessionId={}", session.getId(), closeException);
        }
    }

    private static void sendLocked(SessionContext context, Object data) throws Exception {
        ObjectMapper mapper = objectMapper;
        if (mapper == null) {
            throw new IllegalStateException("WebSocket 全局 ObjectMapper 尚未注入");
        }
        Object visibleData = data;
        long nextSequence = 0;
        if (data instanceof FarmStatusMessage message) {
            nextSequence = context.sequence.get() + 1;
            visibleData = message.withSequence(nextSequence);
        }
        String json = mapper.writeValueAsString(visibleData);
        context.session.getBasicRemote().sendText(json);
        if (data instanceof FarmStatusMessage) context.sequence.set(nextSequence);
    }

    private static void removeSession(Session session) {
        SessionContext context = sessionContexts.remove(session);
        if (context != null) sessions.remove(context);
    }

    private static final class SessionContext {
        private final Session session;
        private final Object lock = new Object();
        private final AtomicLong sequence = new AtomicLong();
        private volatile boolean ready;

        private SessionContext(Session session) {
            this.session = session;
        }
    }

    private static boolean canReceiveJobStatus(Session session, FarmStatusMessage message) {
        if (!FarmStatusMessageType.JOB_STATUS.name().equals(message.type())) return true;
        String role = String.valueOf(session.getUserProperties().get("role"));
        if ("ADMIN".equalsIgnoreCase(role)) return true;
        Long audienceUserId = message.audienceUserId();
        Object userId = session.getUserProperties().get("userId");
        return "OPERATOR".equalsIgnoreCase(role) && audienceUserId != null
                && userId != null && java.util.Objects.equals(String.valueOf(audienceUserId), String.valueOf(userId));
    }
}
