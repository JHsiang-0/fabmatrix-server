# Farm 项目执行目标

版本：v1.0

更新时间：2026-09-02

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

对应文件：

- 全局项目目标：`PROJECT.md`
- 项目需求规格：`.kiro/specs/farm-v1/requirements.md`
- 项目技术设计：`.kiro/specs/farm-v1/design.md`
- 可执行任务清单：`.kiro/specs/farm-v1/tasks.md`
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
- 开发环境默认端口 `8080`
- 开发环境默认关闭定时监控任务，避免没有真实打印机时持续报错

## 4. 已完成基线

以下 P0 工作已经在代码中完成，并有本地提交：

- P0.1：统一响应、错误码、认证失败和权限失败处理
- P0.2：统一分页结构和分页参数校验
- P0.3：统一打印任务状态机和历史状态迁移脚本
- P0.4：资源归属、角色权限和 WebSocket 握手鉴权
- P0.5：请求参数、批量操作和文件上传校验
- P0.6：敏感字段保护和生产安全配置

当前最近相关提交包括：

- `54dbe14`：敏感字段和生产配置
- `7ad1ba6`：协议适配需求规格

以上完成状态仍需在后续整体验收中回归验证，不能只依赖提交记录。

## 5. 未完成范围

按优先级推进：

### P0

1. 打印机协议适配基础：统一 Adapter、Klipper 重构、RRF 边界和状态映射。
2. WebSocket 实时状态：消息结构、快照、状态变化、离线事件和自动化测试。

### P1

1. 打印机详情、历史、统计、恢复和取消。
2. 文件目录树、筛选修复、预览和关联任务。
3. 标准任务创建、重试、重新排队、优先级和统一取消流程。
4. 当前用户接口、用户管理测试和登录保护错误统一。
5. 前端基础层、登录权限、打印机看板、文件库、任务队列和联调。

### P2

1. Controller、权限、Service、Mapper、Redis、RustFS、Adapter 和端到端测试。
2. 数据库迁移、Docker 持久化数据升级、健康检查和生产运维。
3. WebSocket 重连、设备告警、文件上传重试和前端体验完善。

## 6. 不可违反的约束

- 以当前 Controller、Service、Mapper、Entity、配置和测试为事实来源；文档不能凭空声明接口已实现。
- HTTP API 继续使用 `/api/v1`，成功响应为 `{code,message,data,timestamp}`。
- 打印机协议类型只使用 `KLIPPER`、`RRF`；未知协议不得回退到 Klipper。
- Controller 不直接调用具体设备协议客户端。
- 不把 API Key、JWT、密码、RustFS key 或数据库密钥写入响应、WebSocket 或日志。
- 没有真实打印机时保持 `farm.tasks.enabled=false`，使用 Mock/Stub 做自动化测试。
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
