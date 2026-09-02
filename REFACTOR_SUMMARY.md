# 3D 打印农场管理系统 - MAC Upsert 重构总结

> **历史重构记录（不用于当前联调）**：本文记录早期重构过程，文件名、类名、状态和部分接口示例可能已演进。当前接口、字段和状态请以 [`API_HANDOFF.md`](./API_HANDOFF.md) 及当前源码为准；当前物理位置接口为 `PUT /api/v1/printers/positions`，设备新录入状态为 `UNKNOWN`，不是 `ONLINE`。

## 📋 重构背景

解决 DHCP 动态分配 IP 导致的设备重复录入问题。旧系统仅依赖 `ip_address` 作为唯一标识，当设备 IP 变化后，系统会将同一台物理设备识别为全新机器。

## 🎯 核心解决方案

**基于 MAC 地址的 Upsert 机制**：
- **MAC 存在** → 更新该设备的 IP 和状态（设备换了 IP 重新上线）
- **MAC 不存在** → 插入新记录（真正的新设备）

---

## 📁 修改文件清单

### 1. Controller 层
| 文件 | 修改内容 |
|------|----------|
| `FarmPrinterController.java` | ✅ 新增 `POST /api/v1/printers/batch-add` 批量添加接口<br>✅ 新增 `POST /api/v1/printers/batch-update-positions` 位置更新接口<br>✅ 新增 `BatchUpsertResult` 内部类返回批量操作结果 |

### 2. Service 层
| 文件 | 修改内容 |
|------|----------|
| `FarmPrinterService.java` | ✅ 新增 `batchUpsertPrinters()` 方法签名<br>✅ 新增 `releaseIpAddress()` 方法签名<br>✅ 新增 `batchUpdatePositions()` 方法签名 |
| `FarmPrinterServiceImpl.java` | ✅ 实现基于 MAC 的 Upsert 核心逻辑<br>✅ 实现 IP 冲突检测与释放机制<br>✅ 实现批量位置更新功能<br>✅ 保留降级处理兼容旧逻辑 |

### 3. Mapper 层
| 文件 | 修改内容 |
|------|----------|
| `FarmPrinterMapper.java` | ✅ 新增 `selectByMacAddress()`<br>✅ 新增 `selectByIpAddress()`<br>✅ 新增 `releaseIpAddress()`<br>✅ 新增 `upsertByMacAddress()`<br>✅ 新增 `batchUpsert()`<br>✅ 新增 `updateIpAndStatusByMac()`<br>✅ 新增 `countByMacAddresses()`<br>✅ 新增 `updatePrinterPosition()` |
| `FarmPrinterMapper.xml` | ✅ 实现所有 SQL 映射<br>✅ 使用 MySQL `ON DUPLICATE KEY UPDATE` 实现原子性 Upsert |

### 4. Entity & DTO
| 文件 | 修改内容 |
|------|----------|
| `FarmPrinter.java` | ✅ 新增 `machineNumber` 字段<br>✅ 新增 `gridRow` 字段<br>✅ 新增 `gridCol` 字段 |
| `FarmPrinterUpdateDTO.java` | ✅ 新增 `machineNumber` 字段<br>✅ 新增 `currentMaterial` 字段<br>✅ 新增 `nozzleSize` 字段 |
| `PrinterScanResultDTO.java` | ✅ 新增 `isNewDevice` 标记<br>✅ 新增 `suggestedName` 建议名称<br>✅ 新增 `status` 状态标识 |
| `PrinterPositionUpdateDTO.java` | ✅ 新增位置更新 DTO |

### 5. Utility 工具类
| 文件 | 修改内容 |
|------|----------|
| `MacAddressUtil.java` | ✅ 实现 ARP 表解析获取 MAC<br>✅ 实现 Moonraker API 获取 MAC<br>✅ 实现 MAC 地址标准化<br>✅ 实现默认名称生成 |

### 6. 数据库迁移
| 文件 | 说明 |
|------|------|
| `V2026_03_05__add_printer_fields.sql` | ✅ 添加 `machine_number` 字段<br>✅ 添加 `grid_row`/`grid_col` 字段<br>✅ 创建 MAC 唯一索引<br>✅ 创建 IP 普通索引 |

---

## 🔧 核心业务逻辑流程

### 单台设备添加流程
```
用户请求 POST /api/v1/printers/add
    ↓
尝试获取 MAC 地址（ARP 或 Moonraker API）
    ↓
检查 MAC 是否已存在？
    ├── 是 → 更新现有设备 IP + 状态 = ONLINE
    └── 否 → 防 IP 冲突处理 → 插入新设备
```

### 批量设备添加流程
```
用户请求 POST /api/v1/printers/batch-add
    ↓
遍历每台扫描到的设备
    ↓
对每台设备执行：
    1. 防 IP 冲突处理（释放被占用的 IP）
    2. 基于 MAC 的 Upsert（INSERT ... ON DUPLICATE KEY UPDATE）
    ↓
返回统计结果：新增 X 台，更新 Y 台，失败 Z 台
```

### 防 IP 冲突处理逻辑
```java
// 伪代码
if (IP 被其他设备占用) {
    将旧设备的 ip_address 设为 NULL
    将旧设备的 status 设为 OFFLINE
}
// 然后新设备可以使用该 IP
```

---

## 🌐 API 接口变更

### 新增接口

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/v1/printers/scan?subnet=192.168.1` | 扫描局域网设备（带 MAC） |
| `POST` | `/api/v1/printers/add` | 单台添加（基于 MAC Upsert） |
| `POST` | `/api/v1/printers/batch-add` | 批量添加（基于 MAC Upsert） |
| `POST` | `/api/v1/printers/batch-update-positions` | 批量更新物理位置 |

### 扫描结果示例
```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "ipAddress": "192.168.1.100",
      "macAddress": "b8:27:eb:12:34:56",
      "firmwareType": "Klipper",
      "isNewDevice": true,
      "status": "NEW",
      "suggestedName": "Printer_3456"
    }
  ]
}
```

### 批量添加请求示例
```json
[
  {
    "ipAddress": "192.168.1.100",
    "macAddress": "b8:27:eb:12:34:56",
    "firmwareType": "Klipper",
    "isNewDevice": true
  }
]
```

### 批量添加响应示例
```json
{
  "code": 200,
  "message": "批量入库成功：新增 2 台，更新 1 台，失败 0 台",
  "data": {
    "totalCount": 3,
    "insertedCount": 2,
    "updatedCount": 1,
    "failedCount": 0
  }
}
```

---

## 🗄️ 数据库表结构变更

### farm_printer 表新增字段
```sql
-- 产线管理用
ALTER TABLE farm_printer ADD COLUMN machine_number VARCHAR(50);

-- 数字孪生看板用
ALTER TABLE farm_printer ADD COLUMN grid_row TINYINT;
ALTER TABLE farm_printer ADD COLUMN grid_col TINYINT;

-- 索引优化
CREATE UNIQUE INDEX uk_mac_address ON farm_printer(mac_address);
CREATE INDEX idx_ip_address ON farm_printer(ip_address);
```

---

## ⚠️ 部署注意事项

### 1. 执行数据库迁移
```bash
# 方式一：手动执行 SQL
mysql -u root -p your_database < src/main/resources/db/migration/V2026_03_05__add_printer_fields.sql

# 方式二：使用 Flyway/Liquibase（如果项目已集成）
```

### 2. 确保网络权限
- 应用服务器需要执行 `arp -a` 命令的权限
- 应用服务器需要访问打印机 7125 端口的权限

### 3. 验证 MAC 地址获取
```bash
# Linux/Mac
arp -a 192.168.1.100

# Windows
arp -a | findstr 192.168.1.100
```

---

## 📊 预期效果

| 场景 | 旧系统行为 | 新系统行为 |
|------|-----------|-----------|
| 设备重启获得新 IP | 显示为新设备，旧设备离线 | 识别为同一设备，更新 IP |
| DHCP 租期到期换 IP | 重复录入多台"相同"设备 | 自动关联，保持单条记录 |
| 批量导入设备 | 可能因 IP 冲突导致失败 | 自动处理冲突，平滑入库 |

---

## 🔄 向后兼容性

- 旧版 `batchAddPrinters(List<String>)` 方法标记为 `@Deprecated`
- 无 MAC 地址的设备仍可通过降级逻辑添加
- 所有 API 保持 RESTful 规范，前端无需大幅改动

---

## 📝 后续优化建议

1. **定期同步任务**：定时扫描更新所有设备状态
2. **MAC 缓存机制**：减少频繁调用 ARP/Moonraker API
3. **冲突告警**：当检测到 IP 频繁变动时发送通知
4. **数据清洗脚本**：清理历史重复数据

---

**重构完成时间**: 2026-03-05  
**作者**: Cline (AI Assistant)  
**版本**: v1.0
