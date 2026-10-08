# FabMatrix Server 文档索引

## 当前有效基线

- 产品版本：`1.0.0` 准正式版，候选标识 `v1.0.0-rc.1`。
- 当前交付形态：Windows Local Edition，一个本地服务端、多个前端客户端，默认 HTTP/WebSocket 端口 `28081`。
- 当前发布门禁：前端 `.kiro/specs/farm-v1-release-readiness/` 与服务端发布脚本/验证记录共同决定。
- `/api/v1` 是兼容性接口路径，不是产品版本号。

完整的版本、路线和文档状态说明见：[VERSION_AND_ROADMAP.md](./VERSION_AND_ROADMAP.md)。

## 文档分工

| 文档 | 用途 | 当前地位 |
| --- | --- | --- |
| [`../AGENTS.md`](../AGENTS.md) | 后端协作、配置、安全和验证规则 | 工作规则 |
| [`VERSION_AND_ROADMAP.md`](./VERSION_AND_ROADMAP.md) | 产品版本、历史规格和发布范围的唯一映射 | 当前权威索引 |
| [`../API_HANDOFF.md`](../API_HANDOFF.md) | 前后端 HTTP/WebSocket 契约 | 当前接口依据 |
| [`../PROJECT.md`](../PROJECT.md) | 后端项目目标和执行链路 | 项目说明 |
| [`../OPERATIONS.md`](../OPERATIONS.md) | 运行、部署和运维说明 | 当前运维参考 |
| [`../scripts/windows/README-Windows.md`](../scripts/windows/README-Windows.md) | Windows Local Edition 构建和安装 | 发布参考 |

## Kiro 规格

- `.kiro/specs/farm-v2/`：后端手动优先和 Local Edition 功能基线；目录名是历史开发阶段，不是产品版本。
- `.kiro/specs/farm-v1/`：基础能力和历史验收记录。
- `.kiro/specs/printer-monitoring-reliability/`、`.kiro/specs/printer-protocol-and-websocket/`：设备协议、监控和实时通信规格。
- [`printer-ip-rediscovery`](../.kiro/specs/printer-ip-rediscovery/requirements.md)：1.0.0 维护增强，已登记设备 DHCP 地址恢复、安全身份核对和管理员手动同步；现场/新安装包验收待执行。
- 前端 `.kiro/specs/farm-v1-release-readiness/`：当前 1.0.0 联合发布门禁。
- 前端 `.kiro/specs/farm-v3-batch-dispatch/`：后续后台自动派单规划，1.0.0 不发布。

历史文档不直接删除或重命名，避免破坏任务引用和提交历史；新增任务必须先在版本地图中登记归属。
