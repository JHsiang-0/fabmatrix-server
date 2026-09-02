# 3D 打印农场管理系统 - MAC 地址 Upsert 机制重构指南

> **历史重构指南（不用于当前联调）**：本文用于说明 MAC Upsert 的历史背景和思路，示例可能早于当前 Controller/DTO。当前接口和状态以 [`API_HANDOFF.md`](./API_HANDOFF.md) 与当前源码为准；特别是新录入设备先写 `UNKNOWN`，等待协议探测后再更新实际状态。

## 📋 重构概述

本次重构解决了 **DHCP 动态分配 IP 导致的设备重复录入问题**。通过引入基于 **MAC 地址的 Upsert 机制**，系统现在能够：

1. **识别已知设备**：即使设备 IP 变化，也能通过 MAC 地址识别为同一台设备
2. **自动更新 IP**：当设备获得新 IP 时，自动更新数据库中的记录
3. **防止重复录入**：相同 MAC 地址的设备不会创建多条记录
4. **IP 冲突处理**：当新设备占用已分配 IP 时，自动将旧设备标记为离线

---

## 🏗️ 架构变更

### 新增文件

| 文件路径 | 说明 |
|---------|------|
| `src/main/java/com/example/farm/common/utils/MacAddressUtil.java` | MAC 地址获取与处理工具类 |
| `src/main/java/com/example/farm/entity/dto/PrinterScanResultDTO.java` | 扫描结果 DTO（含 MAC、新旧状态等） |

### 修改文件

| 文件路径 | 变更内容 |
|---------|---------|
| `FarmPrinterService.java` | 新增基于 MAC 的 Upsert 方法、批量操作方法 |
| `FarmPrinterServiceImpl.java` | 实现核心业务逻辑（Upsert、IP 冲突处理） |
| `FarmPrinterController.java` | 更新 API 接口，支持新的扫描和批量添加方式 |
| `FarmPrinterMapper.java` | 新增按 MAC/IP 查询、释放 IP、Upsert 等方法 |
| `FarmPrinterMapper.xml` | 新增 SQL 映射（ON DUPLICATE KEY UPDATE） |

---

## 🔌 API 接口变更

### 1. 扫描设备（重构）

```http
GET /api/v1/printers/scan?subnet=192.168.1
```

**响应示例：**
```json
{
  "code": 200,
  "message": "扫描完成，发现 3 台设备（新设备 2 台，已知设备 1 台）",
  "data": [
    {
      "ipAddress": "192.168.1.100",
      "macAddress": "00:11:22:33:44:55",
      "firmwareType": "Klipper",
      "isNewDevice": true,
      "status": "NEW",
      "suggestedName": "Printer_4455"
    },
    {
      "ipAddress": "192.168.1.101",
      "macAddress": "00:11:22:33:44:66",
      "firmwareType": "Klipper",
      "isNewDevice": false,
      "status": "EXISTING",
      "suggestedName": "Printer_4466"
    }
  ]
}
```

### 2. 单台添加（重构 - Upsert 机制）

```http
POST /api/v1/printers/add
Content-Type: application/json

{
  "ipAddress": "192.168.1.100",
  "name": "Voron-01"
}
```

**业务逻辑：**
- 系统自动获取设备的 MAC 地址
- 如果该 MAC 已存在 → **更新** IP 和状态为 ONLINE
- 如果该 MAC 不存在 → **插入**新记录

### 3. 批量添加（重构 - Upsert 机制）

```http
POST /api/v1/printers/batch-add
Content-Type: application/json

[
  {
    "ipAddress": "192.168.1.100",
    "macAddress": "00:11:22:33:44:55",
    "isNewDevice": true,
    "suggestedName": "Printer_4455"
  },
  {
    "ipAddress": "192.168.1.101",
    "macAddress": "00:11:22:33:44:66",
    "isNewDevice": false,
    "suggestedName": "Printer_4466"
  }
]
```

**响应示例：**
```json
{
  "code": 200,
  "message": "批量处理完成：新增 1 台，更新 1 台，失败 0 台",
  "data": {
    "totalCount": 2,
    "insertedCount": 1,
    "updatedCount": 1,
    "failedCount": 0,
    "message": "批量处理完成：新增 1 台，更新 1 台，失败 0 台"
  }
}
```

---

## 🔄 核心业务流程

### DHCP 场景处理流程

```
场景：打印机原 IP 192.168.1.100，DHCP 重新分配后变为 192.168.1.200

步骤 1: 用户调用 scan 接口
        ↓
步骤 2: 系统扫描到 192.168.1.200，获取其 MAC 地址 (00:11:22:33:44:55)
        ↓
步骤 3: 查询数据库，发现该 MAC 已存在（原记录 IP 为 192.168.1.100）
        ↓
步骤 4: 调用 batch-add 或 add 接口
        ↓
步骤 5: 【Upsert 逻辑】
        ├─ MAC 存在 → 更新原记录的 IP 为 192.168.1.200，状态设为 ONLINE
        └─ 不会创建新记录！
        ↓
步骤 6: 原 IP 192.168.1.100 被释放，可被其他设备使用
```

### IP 冲突处理流程

```
场景：新设备 A(MAC_A) 要使用 IP 192.168.1.100，但该 IP 已被设备 B(MAC_B) 占用

步骤 1: 用户添加设备 A，指定 IP 192.168.1.100
        ↓
步骤 2: 系统检测到该 IP 已被设备 B 占用
        ↓
步骤 3: 【IP 冲突解决】
        ├─ 将设备 B 的 ip_address 设为 NULL
        └─ 将设备 B 的 status 设为 OFFLINE
        ↓
步骤 4: 设备 A 成功使用该 IP 入库
```

---

## 🛠️ MAC 地址获取方式

`MacAddressUtil` 提供两种获取 MAC 地址的方式：

### 方式 1：ARP 表查询（推荐）

```java
// 执行系统 arp -a 命令
String mac = macAddressUtil.getMacFromArpTable("192.168.1.100");
// 返回: "00:11:22:33:44:55"
```

**原理：**
1. 先 ping 目标主机，确保 ARP 表中有记录
2. 执行 `arp -a <ip>` 命令
3. 解析输出提取 MAC 地址

### 方式 2：Moonraker API 查询

```java
// 调用 Moonraker /machine/system_info 接口
String mac = macAddressUtil.getMacFromMoonrakerApi("192.168.1.100");
// 返回: "00:11:22:33:44:55"
```

**适用场景：** ARP 表无法获取时（如跨网段）

---

## 📝 数据库表结构要求

确保 `farm_printer` 表有以下索引：

```sql
-- MAC 地址唯一索引（关键！用于 Upsert 判断）
ALTER TABLE farm_printer ADD UNIQUE INDEX uk_mac_address (mac_address);

-- IP 地址索引（用于快速查询和冲突检测）
ALTER TABLE farm_printer ADD INDEX idx_ip_address (ip_address);
```

---

## 🧪 测试建议

### 测试场景 1：设备更换 IP

```bash
# 1. 首次添加设备
curl -X POST http://localhost:8080/api/v1/printers/add \
  -H "Content-Type: application/json" \
  -d '{"ipAddress": "192.168.1.100", "name": "Test-Printer"}'

# 2. 模拟 DHCP 更换 IP（手动修改设备 IP 为 192.168.1.200）

# 3. 再次添加同一设备（系统应自动更新，不创建新记录）
curl -X POST http://localhost:8080/api/v1/printers/add \
  -H "Content-Type: application/json" \
  -d '{"ipAddress": "192.168.1.200", "name": "Test-Printer"}'

# 验证：数据库中应只有 1 条记录，IP 已更新为 192.168.1.200
```

### 测试场景 2：IP 冲突处理

```bash
# 1. 设备 A 使用 IP 192.168.1.100
curl -X POST http://localhost:8080/api/v1/printers/add \
  -H "Content-Type: application/json" \
  -d '{"ipAddress": "192.168.1.100", "name": "Printer-A"}'

# 2. 设备 B 也使用 IP 192.168.1.100（不同 MAC）
curl -X POST http://localhost:8080/api/v1/printers/add \
  -H "Content-Type: application/json" \
  -d '{"ipAddress": "192.168.1.100", "name": "Printer-B"}'

# 验证：
# - 设备 A 的 ip_address 应为 NULL，status 为 OFFLINE
# - 设备 B 的 ip_address 为 192.168.1.100，status 为 ONLINE
```

---

## ⚠️ 注意事项

1. **MAC 地址必须唯一**：确保数据库中 `mac_address` 字段有唯一约束
2. **权限要求**：ARP 命令可能需要管理员权限，部署时注意配置
3. **网络环境**：跨网段设备可能无法通过 ARP 获取 MAC，依赖 Moonraker API
4. **兼容性**：旧版 API 仍然可用，但建议迁移到新版接口

---

## 📚 相关文档

- [API_DOCUMENT.md](API_DOCUMENT.md) - API 详细文档
- [LOGGING_GUIDE.md](LOGGING_GUIDE.md) - 日志规范

---

## 🎯 后续优化建议

1. **定时任务**：添加定时任务自动扫描并同步设备状态
2. **WOL 支持**：基于 MAC 地址实现网络唤醒功能
3. **设备发现协议**：考虑支持 mDNS/Bonjour 自动发现
4. **缓存优化**：使用 Redis 缓存 MAC→Printer 映射，减少数据库查询
