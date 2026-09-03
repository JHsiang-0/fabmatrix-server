# Farm 项目执行目标

版本：v2.0

更新时间：2026-09-03

当前执行规格：`.kiro/specs/farm-v2/`

业务基线：v2 只交付手动上传/手动选择打印机/手动启动，以及用户发起并确认的批量分配；后台自动派单不属于 v2，延后到 v3，不能在用户不知情的情况下自动启动任务。`farm-v1` 仅作为已完成基础能力和历史验收记录保留。

## 1. 项目目标

Farm 是一个运行在局域网内的本地 3D 打印农场管理系统：一个服务端管理多台打印机，多个浏览器或客户端连接服务端。

本项目的最终执行目标是：按照 [TODO.md](./TODO.md) 逐项完成第一版农场管理能力，并使真实代码、API 交接文档、测试结果和任务状态保持一致。

当前产品不做 SaaS 化，不引入手机号注册、邮箱验证、公开注册、多租户、联网账号中心或复杂 RBAC。角色只保留 `ADMIN` 和 `OPERATOR`。

## 2. Kiro 执行链路

本项目统一采用以下顺序：

```text
PROJECT.md
    ↓
requirements.md
    ↓
design.md
    ↓
tasks.md
    ↓
实现一个 Task
    ↓
测试 / 验收
    ↓
更新 tasks.md、TODO.md、API_HANDOFF.md
    ↓
本地 Git 提交
    ↓
下一个 Task
```

对应文件（当前 v2）：

- 全局项目目标：`PROJECT.md`
- 项目需求规格：`.kiro/specs/farm-v2/requirements.md`
- 项目技术设计：`.kiro/specs/farm-v2/design.md`
- 可执行任务清单：`.kiro/specs/farm-v2/tasks.md`
- v1 历史规格：`.kiro/specs/farm-v1/`
- 协议适配详细需求：`.kiro/specs/printer-protocol-and-websocket/requirements.md`
- 协议适配详细设计：`.kiro/specs/printer-protocol-and-websocket/design.md`

## 3. 当前技术基线

- Java 25
- Spring Boot 4.0.3
- Spring MVC、Spring Security、WebSocket、Validation、Actuator
- MyBatis-Plus 3.5.15
- MySQL 8.x
- Redis 7.x
- RustFS S3 兼容对象存储
- JWT 认证，角色为 `ADMIN`、`OPERATOR`
- Maven、JaCoCo
- 当前设备协议实现为 Klipper/Moonraker
- 目标设备协议为 Klipper/Moonraker 与 RRF 3.7
- v1 Server Edition 使用 Docker Compose 编排 Farm、MySQL、Redis、RustFS
- v2 Local Edition 已具备 SQLite、本地文件存储和进程内缓存/锁的基础 profile；完整 Windows 发布包和备份恢复仍待验收
- 开发环境默认端口 `8080`
- 开发环境默认关闭定时监控任务，避免没有真实打印机时持续报错

## 4. 当前完成基线

当前源码已经完成 P0.1–P0.6、协议适配基础、WebSocket 后端、打印机/文件/任务/用户相关 P1 后端，以及主要的测试、迁移和运维加固。对应证据以 `TODO.md`、`.kiro/specs/farm-v1/tasks.md`、`API_HANDOFF.md` 和测试报告为准，不能只依赖提交记录。

已完成的后端范围包括：

- 统一响应、错误码、分页、任务状态机和数据库迁移策略；
- `ADMIN`/`OPERATOR` 权限、资源归属和敏感字段保护；
- Klipper/Moonraker 与 RRF 的统一 Adapter、状态映射和协议错误边界；
- WebSocket JWT 握手、快照、四类业务消息、事务提交后发布、Ping 保活和失败清理；
- 打印机详情/历史/统计/控制、文件目录/筛选/预览/下载、任务安全打印流程；
- Controller、Service、Mapper、Redis、RustFS、Adapter 和 WebSocket 自动化测试。

最近的本地提交包括：

- `f573fb6`：登录密码自动迁移检查数据库写入结果；
- `f9ff4ed`：查询参数和任务时间范围防御性校验；
- `85bcd82`：打印机扫描网段参数校验。

## 5. 未完成范围

剩余工作不是未完成的后端 P0/P1 核心实现，主要分为以下外部验收和前端任务：

### 真实环境验收

1. 使用真实 Klipper 和 RRF 3.7 设备验证状态、控制、文件上传和副作用；
2. 在真实设备环境完成“上传文件 → 创建任务 → 派发 → 安全确认 → 启动 → 完成”的端到端测试；
3. 根据真实设备响应补充协议差异和现场运维记录。

### 真实前端工程

1. 配置 API/WS 地址、HTTP 客户端、Bearer Token 和统一错误处理；
2. 完成登录权限、打印机看板、文件库、任务队列和安全打印页面；
3. 完成 WebSocket 自动重连、离线/失败告警和真实接口联调记录。

实际前端工程位于 `/home/codex/workspace/farm-ui`；当前前端已有单任务基础页面、批量上传/预览/确认页面和 WebSocket 序号断档恢复，单任务安全流程完整串联及浏览器端真实端到端仍待验收。

### P2 体验项

WebSocket 重连展示、确认弹窗、上传取消/重试、操作提示、空状态和维护状态等前端体验仍按 `TODO.md` 保留。

## 6. 不可违反的约束

- 以当前 Controller、Service、Mapper、Entity、配置和测试为事实来源；文档不能凭空声明接口已实现。
- HTTP API 继续使用 `/api/v1`，成功响应为 `{code,message,data,timestamp}`。
- 打印机协议类型只使用 `KLIPPER`、`RRF`；未知协议不得回退到 Klipper。
- Controller 不直接调用具体设备协议客户端。
- 不把 API Key、JWT、密码、RustFS key 或数据库密钥写入响应、WebSocket 或日志。
- 没有真实打印机时保持 `farm.monitor.enabled=false` 和 `farm.scheduler.enabled=false`，使用 Mock/Stub 做自动化测试。
- 不使用 `docker compose down -v`，不重建或删除已有 Docker 数据卷。
- 数据库变更必须提供作用范围、备份要求和可回滚 SQL。
- 每个 Task 完成后必须测试、更新任务状态、同步接口文档并创建本地提交。

## 7. 完成定义

只有当以下条件全部满足，才能把本项目目标标记为完成：

- `TODO.md` 中 P0、P1、P2 的必做项全部完成或有明确的产品裁剪记录。
- API_HANDOFF 与实际接口、权限、字段、错误码和 WebSocket 消息一致。
- Klipper 与 RRF 都通过适配器接入；未验证的 RRF 能力不能标记为真实完成。
- ADMIN/OPERATOR/匿名访问的关键权限有自动化测试。
- 文件、任务、设备状态和 WebSocket 状态能够完成端到端同步。
- Docker、数据库迁移、配置、测试账号和启动方式有可复现说明。
- 工作区无未说明的修改，所有阶段提交历史清晰可追溯。
