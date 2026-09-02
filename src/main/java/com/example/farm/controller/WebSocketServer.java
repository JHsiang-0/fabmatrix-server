package com.example.farm.controller;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.example.farm.common.utils.JwtUtils;
import com.example.farm.service.FarmStatusSnapshotService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.websocket.*;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Slf4j
@Component
@ServerEndpoint("/ws/farm-status") // 前端大屏连接的 WebSocket 地址
public class WebSocketServer {

    private static final int MAX_CONNECTIONS = 100;

    // 存放所有当前在线的前端大屏客户端
    private static final CopyOnWriteArraySet<Session> sessions = new CopyOnWriteArraySet<>();
    
    // 为每个 Session 创建一个独立的锁对象，用于解决并发写入冲突
    private static final Map<Session, Object> sessionLocks = new ConcurrentHashMap<>();
    
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static volatile FarmStatusSnapshotService snapshotService;

    /**
     * Jakarta Endpoint 由容器创建，使用 setter 将 Spring 快照服务注册到端点。
     */
    @Autowired
    public void setSnapshotService(FarmStatusSnapshotService service) {
        WebSocketServer.snapshotService = service;
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
            if (sessions.size() >= MAX_CONNECTIONS) {
                closeForPolicy(session, "WebSocket 连接数已达上限");
                return;
            }
            session.getUserProperties().put("userId", userId);
            session.getUserProperties().put("role", role);
        } catch (JWTVerificationException | IllegalArgumentException e) {
            closeForPolicy(session, "WebSocket Token 无效或已过期");
            return;
        }

        sessions.add(session);
        // 为每个新会话创建锁对象
        sessionLocks.put(session, new Object());
        log.info("WebSocket 客户端接入，当前在线连接数: {}", sessions.size());
        sendInitialSnapshot(session);
    }

    @OnClose
    public void onClose(Session session) {
        sessions.remove(session);
        sessionLocks.remove(session);
        log.info("WebSocket 客户端断开，当前在线连接数: {}", sessions.size());
    }

    @OnError
    public void onError(Session session, Throwable error) {
        log.error("WebSocket 发生错误", error);
        sessions.remove(session);
        sessionLocks.remove(session);
    }

    /**
     * 向所有在线的大屏广播打印机最新状态 (JSON 格式)
     * 使用同步锁解决并发写入冲突问题
     */
    public static void broadcastPrinterStatus(FarmStatusMessage data) {
        broadcast(data);
    }

    public static void broadcastPrinterOffline(FarmStatusMessage data) {
        broadcast(data);
    }

    public static void broadcastJobStatus(FarmStatusMessage data) {
        broadcast(data);
    }

    private static void broadcast(FarmStatusMessage data) {
        if (sessions.isEmpty()) return;

        String jsonMessage;
        try {
            jsonMessage = objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            log.error("WebSocket 消息序列化失败", e);
            return;
        }

        for (Session session : sessions) {
            if (session.isOpen()) {
                // 获取该 session 的锁对象
                Object lock = sessionLocks.get(session);
                if (lock == null) {
                    lock = new Object();
                    sessionLocks.putIfAbsent(session, lock);
                    lock = sessionLocks.get(session);
                }
                
                // 同步发送，避免并发冲突
                synchronized (lock) {
                    try {
                        session.getBasicRemote().sendText(jsonMessage);
                    } catch (IOException e) {
                        log.error("向客户端发送消息失败: sessionId={}", session.getId(), e);
                        // 发送失败时关闭会话
                        try {
                            session.close();
                        } catch (IOException closeEx) {
                            log.error("关闭异常会话失败: sessionId={}", session.getId(), closeEx);
                        }
                        sessions.remove(session);
                        sessionLocks.remove(session);
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
        
        Object lock = sessionLocks.get(session);
        if (lock == null) {
            lock = new Object();
            sessionLocks.putIfAbsent(session, lock);
            lock = sessionLocks.get(session);
        }
        
        synchronized (lock) {
            try {
                String jsonMessage = objectMapper.writeValueAsString(data);
                session.getBasicRemote().sendText(jsonMessage);
            } catch (Exception e) {
                log.error("单播消息发送失败", e);
            }
        }
    }
    
    /**
     * 获取当前在线连接数
     */
    public static int getOnlineCount() {
        return sessions.size();
    }

    private static void sendInitialSnapshot(Session session) {
        FarmStatusSnapshotService service = snapshotService;
        if (service == null) {
            log.warn("WebSocket 初始快照服务尚未就绪: sessionId={}", session.getId());
            return;
        }
        try {
            sendMessage(session, FarmStatusMessage.snapshot(service.buildSnapshot()));
        } catch (RuntimeException exception) {
            log.error("发送 WebSocket 初始快照失败: sessionId={}", session.getId(), exception);
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
}
