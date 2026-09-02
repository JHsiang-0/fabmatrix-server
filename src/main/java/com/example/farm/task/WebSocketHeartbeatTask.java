package com.example.farm.task;

import com.example.farm.controller.WebSocketServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * WebSocket 协议级保活任务。
 *
 * <p>Ping/Pong 属于 WebSocket 控制帧，不改变对外业务消息契约。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "farm.websocket", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WebSocketHeartbeatTask {

    @Scheduled(fixedDelayString = "${farm.websocket.heartbeat-interval-ms:30000}")
    public void sendHeartbeat() {
        WebSocketServer.sendHeartbeat();
        log.debug("WebSocket 心跳检查完成，在线连接数: {}", WebSocketServer.getOnlineCount());
    }
}
