# 3D 农场管理系统 API 文档

> **历史文档（不用于当前联调）**：本文保留早期接口说明，部分示例已经过期。当前前后端唯一接口契约是 [`API_HANDOFF.md`](./API_HANDOFF.md)，实际行为以当前 Controller、SecurityConfig 和 Swagger/OpenAPI 为准。特别注意：注册仅允许 ADMIN，管理员密钥使用 `X-Admin-Secret` 请求头，推荐任务创建地址为 `POST /api/v1/print-jobs`，WebSocket 必须通过 `?token=<JWT>` 握手。

## 基础信息
- Base URL: `http://localhost:8080`
- API 前缀: `/api/v1`
- 认证方式: `Authorization: Bearer <token>`

## 统一响应格式
```json
{
  "code": 200,
  "message": "操作成功",
  "data": {}
}
```

## 1. 认证与用户（`/api/v1/auth`）

### 1.1 登录
- `POST /api/v1/auth/login`
- 免认证

请求体：
```json
{
  "username": "admin",
  "password": "Admin123"
}
```

响应 `data`：
```json
{
  "token": "eyJ...",
  "expiresIn": 604800,
  "userId": 1,
  "username": "admin",
  "role": "ADMIN",
  "email": "admin@example.com",
  "phone": "13800138000"
}
```

### 1.2 注册
- `POST /api/v1/auth/register`
- 免认证

请求体：
```json
{
  "username": "operator01",
  "password": "Pass1234",
  "confirmPassword": "Pass1234",
  "email": "operator@example.com",
  "phone": "13800138001"
}
```

校验规则：
- `username`: 3-20 位，仅字母/数字/下划线
- `password`: 6-20 位，必须包含大写字母+小写字母+数字
- `confirmPassword`: 必填，且与 `password` 一致
- `phone`: 中国大陆手机号格式

### 1.3 修改密码
- `POST /api/v1/auth/{userId}/change-password`
- 需认证，且 `userId` 必须是当前登录用户

请求体：
```json
{
  "oldPassword": "OldPass123",
  "newPassword": "NewPass123",
  "confirmPassword": "NewPass123"
}
```

### 1.4 获取个人信息
- `GET /api/v1/auth/{userId}/profile`
- 需认证，且 `userId` 必须是当前登录用户

### 1.5 更新个人信息
- `PUT /api/v1/auth/{userId}/profile`
- 需认证，且 `userId` 必须是当前登录用户
- 普通用户接口不允许修改 `role`

请求体：
```json
{
  "email": "newemail@example.com",
  "phone": "13900139000"
}
```

### 1.6 检查用户名是否可用
- `GET /api/v1/auth/check-username?username=operator01`
- 免认证

### 1.7 检查邮箱是否可用
- `GET /api/v1/auth/check-email?email=test@example.com`
- 免认证

---

## 2. 管理员用户接口（`/api/v1/auth/admin`）
> 需要 `ADMIN` 角色

### 2.1 分页查询用户
- `GET /api/v1/auth/admin/users?pageNum=1&pageSize=10`

### 2.2 更新用户
- `PUT /api/v1/auth/admin/users/{userId}`

请求体示例：
```json
{
  "email": "u@example.com",
  "phone": "13800138000",
  "role": "OPERATOR"
}
```

### 2.3 禁用用户
- `POST /api/v1/auth/admin/users/{userId}/disable`
- 不需要传 `adminId`，后端从登录态获取

### 2.4 启用用户
- `POST /api/v1/auth/admin/users/{userId}/enable`
- 不需要传 `adminId`

### 2.5 批量迁移明文密码
- `POST /api/v1/auth/admin/migrate-passwords?adminSecret=...`

### 2.6 查询密码存储状态
- `GET /api/v1/auth/admin/password-status?adminSecret=...`

---

## 3. 打印任务（`/api/v1/print-jobs`）

### 3.1 获取队列任务
- `GET /api/v1/print-jobs/queue`

### 3.2 创建任务
- 推荐：`POST /api/v1/print-jobs/create`
- 兼容：`POST /api/v1/print-jobs`（同义）

请求体：
```json
{
  "fileId": 5,
  "materialType": "PLA",
  "nozzleSize": 0.4,
  "priority": 5,
  "autoAssign": true
}
```

### 3.3 取消任务
- `DELETE /api/v1/print-jobs/{id}`

### 3.4 手动派发任务
- `POST /api/v1/print-jobs/{jobId}/assign?printerId=1`

---

## 4. 打印文件（`/api/v1/print-files`）

### 4.1 上传并解析 G-code
- `POST /api/v1/print-files/upload`
- `Content-Type: multipart/form-data`
- 参数：`file`

### 4.2 分页查询文件
- `POST /api/v1/print-files/page`

请求体：
```json
{
  "pageNum": 1,
  "pageSize": 10
}
```

### 4.3 删除文件
- `DELETE /api/v1/print-files/{id}`

---

## 5. 打印机（`/api/v1/printers`）

### 5.1 分页查询
- `GET /api/v1/printers/page?pageNum=1&pageSize=10`
- 支持按名称和状态筛选

### 5.2 新增打印机（基于 MAC Upsert）
- `POST /api/v1/printers/add`

**核心逻辑**：基于 MAC 地址的 Upsert 机制
- MAC 已存在 → 更新该设备的 IP 和状态为 ONLINE（设备换了 IP 重新上线）
- MAC 不存在 → 插入新记录（真正的新设备）

请求体：
```json
{
  "name": "Voron-2.4-01",
  "ipAddress": "192.168.1.100",
  "macAddress": "b8:27:eb:12:34:56",
  "firmwareType": "Klipper",
  "apiKey": "optional_api_key",
  "currentMaterial": "ABS",
  "nozzleSize": 0.40,
  "machineNumber": "P001",
  "gridRow": 2,
  "gridCol": 3
}
```

字段说明：
| 字段 | 必填 | 说明 |
|------|------|------|
| `name` | 否 | 打印机名称，不传则自动生成（如 Printer_3456） |
| `ipAddress` | **是** | 局域网 IP 地址 |
| `macAddress` | 否 | MAC 地址，不传则后端自动获取 |
| `firmwareType` | 否 | 固件类型，默认 Klipper |
| `apiKey` | 否 | Moonraker API 密钥 |
| `currentMaterial` | 否 | 当前装载耗材，默认 ABS |
| `nozzleSize` | 否 | 喷嘴直径，默认 0.40 |
| `machineNumber` | 否 | 设备编号/机台号 |
| `gridRow` | 否 | 网格行号（1-4），null 表示待分配区 |
| `gridCol` | 否 | 网格列号（1-12），null 表示待分配区 |

### 5.3 更新打印机
- `PUT /api/v1/printers/update`

请求体：
```json
{
  "id": 1,
  "name": "Voron-2.4-01-Updated",
  "ipAddress": "192.168.1.101",
  "macAddress": "b8:27:eb:12:34:56",
  "firmwareType": "Klipper",
  "apiKey": "new_api_key",
  "currentMaterial": "PETG",
  "nozzleSize": 0.6,
  "machineNumber": "P001",
  "gridRow": 3,
  "gridCol": 5
}
```

字段说明：
| 字段 | 必填 | 说明 |
|------|------|------|
| `id` | **是** | 打印机 ID |
| `name` | 否 | 打印机名称 |
| `ipAddress` | 否 | 局域网 IP 地址 |
| `macAddress` | 否 | MAC 地址 |
| `firmwareType` | 否 | 固件类型 |
| `apiKey` | 否 | Moonraker API 密钥 |
| `currentMaterial` | 否 | 当前装载耗材 |
| `nozzleSize` | 否 | 喷嘴直径 |
| `machineNumber` | 否 | 设备编号/机台号 |
| `gridRow` | 否 | 网格行号（1-4），null 表示移回待分配区 |
| `gridCol` | 否 | 网格列号（1-12），null 表示移回待分配区 |

> **注意**：修改 IP 时会自动处理 IP 冲突（将占用该 IP 的旧设备下线）

### 5.4 删除打印机
- `DELETE /api/v1/printers/delete/{id}`
- **限制**：正在打印中的设备无法删除

### 5.5 局域网扫描（带 MAC 识别）
- `GET /api/v1/printers/scan?subnet=192.168.1`

**功能说明**：
- 并发扫描网段内所有 IP 的 7125 端口（Klipper/Moonraker 默认端口）
- 对响应的设备尝试获取 MAC 地址（ARP 表或 Moonraker API）
- 与数据库对比，标记新旧设备状态

响应 `data`：
```json
[
  {
    "ipAddress": "192.168.1.100",
    "macAddress": "b8:27:eb:12:34:56",
    "firmwareType": "Klipper",
    "isNewDevice": true,
    "status": "NEW",
    "suggestedName": "Printer_3456"
  },
  {
    "ipAddress": "192.168.1.101",
    "macAddress": "b8:27:eb:78:9a:bc",
    "firmwareType": "Klipper",
    "isNewDevice": false,
    "status": "EXISTING",
    "suggestedName": "Printer_9abc"
  }
]
```

状态说明：
| status | 含义 |
|--------|------|
| `NEW` | 新设备（MAC 不在数据库中） |
| `EXISTING` | 已知设备（MAC 已存在） |
| `UNKNOWN_MAC` | 无法获取 MAC 地址，需手动处理 |

### 5.6 批量新增/更新（基于 MAC Upsert）⭐
- `POST /api/v1/printers/batch-add`

**核心功能**：解决 DHCP 动态分配导致的设备重复录入问题

请求体（扫描结果数组）：
```json
[
  {
    "ipAddress": "192.168.1.100",
    "macAddress": "b8:27:eb:12:34:56",
    "firmwareType": "Klipper",
    "isNewDevice": true,
    "suggestedName": "Printer_3456"
  },
  {
    "ipAddress": "192.168.1.101",
    "macAddress": "b8:27:eb:78:9a:bc",
    "firmwareType": "Klipper",
    "isNewDevice": false,
    "suggestedName": "Printer_9abc"
  }
]
```

响应 `data`：
```json
{
  "totalCount": 2,
  "insertedCount": 1,
  "updatedCount": 1,
  "failedCount": 0,
  "message": "批量处理完成：新增 1 台，更新 1 台，失败 0 台"
}
```

处理逻辑：
1. **防 IP 冲突**：若 IP 被其他 MAC 设备占用，先将旧设备 IP 设为 NULL，状态设为 OFFLINE
2. **基于 MAC 的 Upsert**：使用 MySQL `ON DUPLICATE KEY UPDATE` 实现原子性操作
3. **降级处理**：无 MAC 地址的设备仍可通过普通插入添加

### 5.7 批量更新物理位置（数字孪生看板）⭐
- `PUT /api/v1/printers/positions`

**功能**：更新打印机在数字孪生看板上的物理位置坐标

请求体：
```json
[
  {
    "id": 1,
    "gridRow": 2,
    "gridCol": 3
  },
  {
    "id": 2,
    "gridRow": 1,
    "gridCol": 5
  }
]
```

字段说明：
| 字段 | 必填 | 范围 | 说明 |
|------|------|------|------|
| `id` | **是** | - | 打印机 ID |
| `gridRow` | 否 | 1-4 | 网格行号（null 表示移回待分配区） |
| `gridCol` | 否 | 1-12 | 网格列号（null 表示移回待分配区） |

> **业务规则**：传入 null 可将设备移回"待分配区"

### 5.8 获取未分配位置的打印机列表（数字孪生看板）⭐
- `GET /api/v1/printers/unallocated`

**功能**：查询所有尚未分配物理坐标的设备（grid_row IS NULL AND grid_col IS NULL），用于数字孪生看板的空槽位绑定下拉列表

**查询参数**：
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `keyword` | String | 否 | 搜索关键字，支持模糊匹配 `name` 或 `machine_number` |

**响应 `data`**：
```json
[
  {
    "id": 1,
    "name": "Voron-2.4-01",
    "machineNumber": "P001",
    "ipAddress": "192.168.1.100",
    "macAddress": "b8:27:eb:12:34:56",
    "status": "IDLE"
  },
  {
    "id": 2,
    "name": "Voron-2.4-02",
    "machineNumber": "P002",
    "ipAddress": "192.168.1.101",
    "macAddress": "b8:27:eb:78:9a:bc",
    "status": "OFFLINE"
  }
]
```

返回字段说明：
| 字段 | 说明 |
|------|------|
| `id` | 打印机主键 ID |
| `name` | 打印机名称 |
| `machineNumber` | 设备编号/机台号 |
| `ipAddress` | 局域网 IP 地址 |
| `macAddress` | MAC 地址 |
| `status` | 业务状态：IDLE, PRINTING, OFFLINE, ERROR, MAINTENANCE |

> **使用场景**：前端点击数字孪生看板上的"空槽位"时，调用此接口获取可绑定的设备列表

---

## 5.x 打印机数据结构

### FarmPrinter 实体字段
```json
{
  "id": 1,
  "name": "Printer_3456",
  "ipAddress": "192.168.1.100",
  "macAddress": "b8:27:eb:12:34:56",
  "firmwareType": "Klipper",
  "apiKey": "optional_key",
  "status": "ONLINE",
  "currentMaterial": "PLA",
  "nozzleSize": 0.4,
  "machineNumber": "P001",
  "gridRow": 2,
  "gridCol": 3,
  "createdAt": "2026-03-05T10:30:00",
  "updatedAt": "2026-03-05T14:20:00"
}
```

状态枚举：
| status | 说明 |
|--------|------|
| `ONLINE` | 在线 |
| `OFFLINE` | 离线 |
| `PRINTING` | 打印中 |
| `PAUSED` | 暂停 |
| `ERROR` | 错误 |

---

## 6. 打印控制（`/api/v1/control`）
- `POST /api/v1/control/{id}/emergency-stop`
- `POST /api/v1/control/{id}/pause`

---

## 7. WebSocket 实时状态推送

### 7.1 连接信息
- **URL**: `ws://localhost:8080/ws/farm-status`
- **认证**: 无需 Token（按当前后端配置）

### 7.2 消息格式

WebSocket 推送的消息包含以下字段：

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

### 7.3 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| `printerId` | Long | 打印机 ID |
| `data.systemState` | String | 系统状态：ready/startup/shutdown/error，用于判断底层主板状态 |
| `data.systemMessage` | String | 系统状态消息，如 "Printer is ready" |
| `data.state` | String | 打印任务状态：standby/printing/paused/complete/error/offline |
| `data.unifiedState` | String | **【推荐】统一状态**：融合 systemState 和 state 的最终状态，前端优先使用此字段 |
| `data.filename` | String | 当前打印文件名，可能为 null |
| `data.printDuration` | Double | 打印持续时间（秒） |
| `data.totalDuration` | Double | 总持续时间（秒） |
| `data.filamentUsed` | Double | 已用耗材长度（毫米） |
| `data.progress` | Double | 打印进度（0.00 - 100.00） |
| `data.toolTemperature` | Double | 喷头当前温度（°C） |
| `data.toolTarget` | Double | 喷头目标温度（°C） |
| `data.bedTemperature` | Double | 热床当前温度（°C） |
| `data.bedTarget` | Double | 热床目标温度（°C） |
| `timestamp` | Long | 消息推送时间戳（毫秒） |

### 7.4 统一状态机（Unified State Machine）⭐

**推荐使用 `unifiedState` 字段**，它是将 `systemState`（系统层）和 `state`（业务层）按照严格优先级融合后的最终状态。

#### 状态优先级与定义

**第一层：系统最高优先级拦截（优先判断 systemState）**

| unifiedState | 触发条件 | 定义说明 |
|--------------|----------|----------|
| `FAULT` | systemState = "shutdown" | 🔴 **硬件物理故障**：底层硬件故障或热失控急停，必须人工介入物理干预 |
| `SYS_ERROR` | systemState = "error" | 🟠 **系统软件错误**：Klipper 系统软件级配置错误或通讯错误 |
| `STARTING` | systemState = "startup" | 🟡 **启动中**：主板正在启动中，请稍后 |
| *(放行)* | systemState = "ready" | 系统正常，进入第二层业务状态判断 |

**第二层：业务状态判断（当 systemState 为 ready 时）**

| unifiedState | 触发条件 | 定义说明 |
|--------------|----------|----------|
| `STANDBY` | state = "standby" | 🟢 **待机就绪**：打印机已准备就绪，即将开始或正在等待打印任务 |
| `PRINTING` | state = "printing" | 🔵 **打印中**：当前正在执行打印作业 |
| `PAUSED` | state = "paused" | ⏸️ **已暂停**：当前打印作业已暂停 |
| `COMPLETED` | state = "complete" | ✅ **已完成**：最后一项打印任务已成功完成 |
| `PRINT_ERROR` | state = "error" | ❌ **打印错误**：最后一次打印作业出错并退出，如 G-code 解析失败 |
| `CANCELLED` | state = "cancelled" | 🚫 **已取消**：用户主动取消了最后一个打印作业 |
| `UNKNOWN` | 其他未知状态 | ⚪ **未知状态**：系统无法识别的状态 |

#### 状态流转图

```
                    ┌─────────────┐
                    │   STARTING  │ ← systemState = "startup"
                    │   (启动中)   │
                    └──────┬──────┘
                           │ 启动完成
                           ▼
┌─────────┐    ┌─────────────────────────┐
│  FAULT  │    │      READY (系统就绪)    │
│ (故障)   │◄───┤  systemState = "ready"   │
└─────────┘    └───────────┬─────────────┘
    ▲                      │
    │ shutdown             │ 根据 print_stats.state
    │                      ▼
┌───┴────┐    ┌─────────┬─────────┬─────────┬──────────┬───────────┐
│SYS_ERROR│   │ STANDBY │PRINTING │ PAUSED  │COMPLETED │PRINT_ERROR│
│(系统错误)│   │ (待机)   │(打印中)  │ (暂停)   │ (已完成)  │ (打印错误) │
└─────────┘   └─────────┴─────────┴─────────┴──────────┴───────────┘
    ▲                                              │
    │ systemState = "error"                        │ state = "error"
    │                                              │
    └──────────────────────────────────────────────┘
```

#### 前端使用建议

1. **优先使用 `unifiedState`**：不再需要在前端处理 `systemState` 和 `state` 的优先级逻辑
2. **状态显示优先级**：
   - 红色告警：`FAULT` > `SYS_ERROR` > `PRINT_ERROR`
   - 黄色警告：`STARTING` > `PAUSED` > `CANCELLED`
   - 正常状态：`PRINTING` > `STANDBY` > `COMPLETED`
3. **操作建议**：
   - `FAULT` / `SYS_ERROR`：显示紧急处理按钮，建议联系管理员
   - `PRINT_ERROR`：显示重试/取消按钮
   - `COMPLETED` / `CANCELLED`：显示清理/开始新任务按钮

### 7.5 原始状态映射关系（供参考）

**系统状态 (systemState)** 与 **打印状态 (state)** 的原始定义：
- `systemState`: 反映 Klipper 主板底层状态（webhooks.state）
  - `ready`: 主板正常就绪
  - `startup`: 正在启动
  - `shutdown`: 已关闭
  - `error`: 主板报错

- `state`: 反映当前打印任务状态（print_stats.state）
  - `standby`: 待机
  - `printing`: 打印中
  - `paused`: 暂停
  - `complete`: 完成
  - `error`: 错误
  - `cancelled`: 已取消

> **注意**：前端开发请直接使用 `unifiedState`，无需关心原始状态的映射逻辑。

### 7.6 前端接入示例

详见 [WEBSOCKET_GUIDE.md](./WEBSOCKET_GUIDE.md)

---

## 常见错误码说明
- 参数校验失败：`code != 200`，`message` 含字段级错误（例如 `password:密码必须包含大小写字母和数字`）
- 方法不支持：`message` 类似 `请求方法不支持，当前方法=POST，支持方法=DELETE`
- 未登录/Token 失效：401
- 用户被禁用：403
