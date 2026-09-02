package com.example.farm.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.websocket.*;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Slf4j
@Component
@ServerEndpoint("/ws/farm-status") // 前端大屏连接的 WebSocket 地址
public class WebSocketServer {

    // 存放所有当前在线的前端大屏客户端
    private static final CopyOnWriteArraySet<Session> sessions = new CopyOnWriteArraySet<>();
    
    // 为每个 Session 创建一个独立的锁对象，用于解决并发写入冲突
    private static final Map<Session, Object> sessionLocks = new ConcurrentHashMap<>();
    
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @OnOpen
    public void onOpen(Session session) {
        sessions.add(session);
        // 为每个新会话创建锁对象
        sessionLocks.put(session, new Object());
        log.info("WebSocket 客户端接入，当前在线连接数: {}", sessions.size());
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
    public static void broadcastPrinterStatus(Object data) {
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
}
