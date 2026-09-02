# WebSocket 实时状态推送指南

> **历史文档（不用于当前联调）**：本文保留早期客户端示例，部分路径、消息字段和认证说明已经过期。当前契约请以 [`API_HANDOFF.md`](./API_HANDOFF.md) 第 7 节和实际 `WebSocketServer` 为准：连接地址为 `/ws/farm-status?token=<JWT>`（兼容 `access_token`），首帧为 `SNAPSHOT`，业务消息类型固定为 `SNAPSHOT`、`PRINTER_STATUS`、`PRINTER_OFFLINE`、`JOB_STATUS`，顶层包含 `type`、`printerId`（快照除外）、`timestamp`、`data`。本文中的“无需 Token”、`unifiedState` 和 `FarmWebSocketServer.java` 均不可用于当前开发。

## 架构概述

```
┌─────────────────┐     HTTP API      ┌─────────────────┐
│   前端页面       │ ◄────────────────► │  REST Controller │
│  (Vue/React)    │                    │                 │
└────────┬────────┘                    └─────────────────┘
         │
         │ WebSocket
         ▼
┌─────────────────┐     每5秒轮询       ┌─────────────────┐
│ FarmWebSocket   │ ◄───────────────── │ PrinterMonitor  │
│   Server        │    broadcast       │     Task        │
│  (/ws/farm-     │                    │  (Scheduled)    │
│    status)      │                    └─────────────────┘
└────────┬────────┘                           │
         │                                    │
         │ 广播打印机状态                       │ 查询 Moonraker API
         ▼                                    ▼
┌─────────────────┐                    ┌─────────────────┐
│   数字孪生看板   │                    │  3D 打印机群     │
│  (大屏展示)      │                    │  (Klipper/      │
└─────────────────┘                    │   Moonraker)    │
                                       └─────────────────┘
```

## 后端实现

### 1. WebSocket 服务端

**端点**: `ws://localhost:8080/ws/farm-status`

**文件位置**: `src/main/java/com/example/farm/controller/FarmWebSocketServer.java`

功能特点：
- 使用原生 Jakarta WebSocket (JSR 356)
- 支持多客户端并发连接
- 使用同步锁解决并发写入冲突
- 自动清理异常连接

### 2. 状态监控任务

**文件位置**: `src/main/java/com/example/farm/task/PrinterMonitorTask.java`

工作机制：
- **调度频率**: 每 5 秒执行一次 (`@Scheduled(fixedRate = 5000)`)
- **数据来源**: 优先从 Redis 缓存获取打印机列表
- **并行查询**: 使用线程池(10线程)并发查询多台打印机状态
- **状态转换**:
  ```
  Moonraker State    →    DB Status
  ─────────────────────────────────
  printing/paused    →    PRINTING
  standby/complete   →    IDLE
  error              →    ERROR
  offline            →    OFFLINE
  ```

### 3. 推送消息格式

```json
{
  "printerId": 1,
  "data": {
    "systemState": "ready",
    "systemMessage": "Printer is ready",
    "state": "printing",
    "filename": "model.gcode",
    "printDuration": 3600.0,
    "totalDuration": 8000.0,
    "filamentUsed": 1250.5,
    "progress": 45.5,
    "toolTemperature": 210.5,
    "toolTarget": 210.0,
    "bedTemperature": 60.0,
    "bedTarget": 60.0
  },
  "timestamp": 1709712000000
}
```

**字段说明：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `systemState` | String | 系统状态：ready/startup/shutdown/error，反映 Klipper 主板底层状态 |
| `systemMessage` | String | 系统状态消息，如 "Printer is ready" |
| `state` | String | 打印任务状态：standby/printing/paused/complete/error/offline |
| `filename` | String | 当前打印文件名，可能为 null |
| `printDuration` | Double | 打印持续时间（秒） |
| `totalDuration` | Double | 总持续时间（秒） |
| `filamentUsed` | Double | 已用耗材长度（毫米） |
| `progress` | Double | 打印进度（0.00 - 100.00） |
| `toolTemperature` | Double | 喷头当前温度（°C） |
| `toolTarget` | Double | 喷头目标温度（°C） |
| `bedTemperature` | Double | 热床当前温度（°C） |
| `bedTarget` | Double | 热床目标温度（°C） |

**状态说明：**
- `systemState` (webhooks.state): 反映主板底层状态
  - `ready`: 主板正常就绪
  - `startup`: 正在启动
  - `shutdown`: 已关闭
  - `error`: 主板报错
- `state` (print_stats.state): 反映打印任务状态
  - `standby`: 待机
  - `printing`: 打印中
  - `paused`: 暂停
  - `complete`: 完成
  - `error`: 错误

## 前端接入方式

### 方式一：原生 JavaScript WebSocket

```javascript
class PrinterWebSocketClient {
  constructor(url = 'ws://localhost:8080/ws/farm-status') {
    this.url = url;
    this.ws = null;
    this.reconnectInterval = 3000; // 重连间隔3秒
    this.listeners = new Map();
  }

  // 建立连接
  connect() {
    this.ws = new WebSocket(this.url);

    this.ws.onopen = () => {
      console.log('✅ WebSocket 连接成功');
      // 连接成功后可以发送认证信息（如果需要）
    };

    this.ws.onmessage = (event) => {
      try {
        const message = JSON.parse(event.data);
        this.handleMessage(message);
      } catch (error) {
        console.error('❌ 消息解析失败:', error);
      }
    };

    this.ws.onclose = () => {
      console.log('⚠️ WebSocket 连接断开，尝试重连...');
      setTimeout(() => this.connect(), this.reconnectInterval);
    };

    this.ws.onerror = (error) => {
      console.error('❌ WebSocket 错误:', error);
    };
  }

  // 处理接收到的消息
  handleMessage(message) {
    const { printerId, data, timestamp } = message;
    
    // 触发所有监听器
    this.listeners.forEach((callback, key) => {
      callback(message);
    });

    // 可以按 printerId 分发到具体组件
    console.log(`🖨️ 打印机 ${printerId} 状态更新:`, data);
  }

  // 订阅状态更新
  subscribe(callback) {
    const id = Date.now().toString();
    this.listeners.set(id, callback);
    return () => this.listeners.delete(id); // 返回取消订阅函数
  }

  // 断开连接
  disconnect() {
    if (this.ws) {
      this.ws.close();
    }
  }
}

// 使用示例
const wsClient = new PrinterWebSocketClient();
wsClient.connect();

// 在 Vue/React 组件中订阅
const unsubscribe = wsClient.subscribe((message) => {
  const { printerId, data } = message;
  // 更新对应打印机的状态
  updatePrinterStatus(printerId, data);
});

// 组件卸载时取消订阅
// unsubscribe();
```

### 方式二：Vue 3 Composition API

```vue
<script setup>
import { ref, onMounted, onUnmounted } from 'vue';

const printerStatuses = ref(new Map());
let ws = null;

onMounted(() => {
  ws = new WebSocket('ws://localhost:8080/ws/farm-status');
  
  ws.onmessage = (event) => {
    const { printerId, data, timestamp } = JSON.parse(event.data);
    
    // 更新指定打印机的状态
    printerStatuses.value.set(printerId, {
      ...data,
      lastUpdate: timestamp
    });
  };

  ws.onclose = () => {
    // 可以在这里实现重连逻辑
  };
});

onUnmounted(() => {
  ws?.close();
});
</script>

<template>
  <div class="printer-grid">
    <div v-for="[id, status] in printerStatuses" :key="id" class="printer-card">
      <h3>打印机 #{{ id }}</h3>
      <p>状态: {{ status.state }}</p>
      <p>进度: {{ status.progress?.toFixed(1) }}%</p>
      <p>喷头: {{ status.toolTemperature }}°C</p>
      <p>热床: {{ status.bedTemperature }}°C</p>
    </div>
  </div>
</template>
```

### 方式三：React Hooks

```jsx
import { useEffect, useState, useCallback } from 'react';

export function usePrinterWebSocket(url = 'ws://localhost:8080/ws/farm-status') {
  const [statuses, setStatuses] = useState(new Map());
  const [isConnected, setIsConnected] = useState(false);

  useEffect(() => {
    const ws = new WebSocket(url);

    ws.onopen = () => setIsConnected(true);
    ws.onclose = () => setIsConnected(false);
    
    ws.onmessage = (event) => {
      const { printerId, data, timestamp } = JSON.parse(event.data);
      
      setStatuses(prev => new Map(prev).set(printerId, {
        ...data,
        lastUpdate: timestamp
      }));
    };

    return () => ws.close();
  }, [url]);

  return { statuses, isConnected };
}

// 使用
function Dashboard() {
  const { statuses, isConnected } = usePrinterWebSocket();

  return (
    <div>
      <div className="connection-status">
        {isConnected ? '🟢 已连接' : '🔴 未连接'}
      </div>
      {Array.from(statuses.entries()).map(([id, status]) => (
        <PrinterCard key={id} id={id} status={status} />
      ))}
    </div>
  );
}
```

## 数字孪生看板集成建议

### 1. 结合物理位置坐标显示

```javascript
// 将 WebSocket 状态与 gridRow/gridCol 结合
const positionedPrinters = computed(() => {
  const result = [];
  
  for (const [id, status] of printerStatuses.value) {
    const printer = allPrinters.value.find(p => p.id === id);
    if (printer?.gridRow && printer?.gridCol) {
      result.push({
        ...printer,
        ...status,
        position: { row: printer.gridRow, col: printer.gridCol }
      });
    }
  }
  
  return result;
});
```

### 2. 状态颜色映射

```javascript
const statusColors = {
  'IDLE': '#52c41a',      // 绿色 - 空闲
  'PRINTING': '#1890ff',  // 蓝色 - 打印中
  'PAUSED': '#faad14',    // 橙色 - 暂停
  'ERROR': '#f5222d',     // 红色 - 错误
  'OFFLINE': '#d9d9d9'    // 灰色 - 离线
};
```

### 3. 心跳检测

```javascript
// 如果超过15秒没有收到某台打印机的消息，标记为可能离线
const isStale = (timestamp) => {
  return Date.now() - timestamp > 15000;
};
```

## 注意事项

1. **无需 Token**: 当前配置 WebSocket 不需要 JWT 认证
2. **自动重连**: 建议前端实现断线重连机制
3. **防抖处理**: 状态更新频繁，UI 更新建议做节流/防抖
4. **内存管理**: 组件卸载时记得关闭 WebSocket 连接

## 相关文件

| 文件 | 说明 |
|------|------|
| `WebSocketConfig.java` | WebSocket 配置类 |
| `FarmWebSocketServer.java` | WebSocket 服务端实现 |
| `PrinterMonitorTask.java` | 定时监控任务 |
| `MoonrakerApiClient.java` | Moonraker API 客户端 |
| `PrinterCacheService.java` | Redis 缓存服务 |
