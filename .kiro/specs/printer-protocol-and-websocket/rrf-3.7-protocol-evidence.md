# RRF 3.7 协议证据登记

登记日期：2026-09-02；实机补充：2026-09-03

状态：已完成官方资料核对、可复现 HTTP 协议测试和一台真实 RRF 设备的只读协议探测；控制、上传和完整打印链路仍未做实机验收。

本文只登记已经从 RepRapFirmware 官方资料确认的协议事实。可复现 Mock 只证明调用边界和解析逻辑，不代表真实设备响应、副作用或部署模式已经验收。

## 1. 证据来源

1. [RepRapFirmware HTTP requests](https://github.com/Duet3D/RepRapFirmware/wiki/HTTP-requests)
2. [RepRapFirmware JSON responses](https://github.com/Duet3D/RepRapFirmware/wiki/JSON-responses)
3. [RepRapFirmware Object Model Documentation](https://github.com/Duet3D/RepRapFirmware/wiki/Object-Model-Documentation)
4. [Duet3D G-code dictionary](https://docs.duet3d.com/User_manual/Reference/Gcodes/M586)
5. [RepRapFirmware 3.7 releases](https://github.com/Duet3D/RepRapFirmware/releases)

官方 HTTP 文档说明 RRF 3.x 的 HTTP 接口主要面向 Duet Web Control，但也可以供第三方应用使用；官方发布页还存在 3.7.0 beta 发布记录。因此，Farm 接入前仍必须在用户实际设备上确认具体 3.7 构建版本、运行模式（standalone/SBC）和启用的网络服务。

## 2. 已确认的 HTTP 边界

| 能力 | 方法和路径 | 已确认事实 | Farm 实现状态 |
|---|---|---|---|
| 建立会话 | `GET /rr_connect?password=<password>&sessionKey=yes` | 返回 `err`；成功时可返回 `sessionKey` 和 `sessionTimeout`。后续请求使用 `X-Session-Key`。 | 已实现；Mock 验证通过；`192.168.0.62` 空密码实机探测返回 HTTP 200、`err:0`、`sessionTimeout:8000` |
| 查询状态 | `GET /rr_model?key=state`、`GET /rr_model?key=job` | RRF 3.x 支持；通过 `key` 查询对象模型，响应为 `{ key, flags, result }`。 | 已实现状态、文件名、文件大小/位置和任务字段解析；`192.168.0.62` 已返回 `state.status=off`、空任务文件 |
| 执行 G-code | `GET /rr_gcode?gcode=<encoded-gcode>` | 用于发送 G/M/T-code，返回 `buff`。 | 已实现并由暂停/恢复/取消/急停适配器调用；实机响应待验证 |
| 上传文件 | `POST /rr_upload?name=<path>` | 请求体是原始文件内容；发送 `Content-Length`，结果包含 `err`。 | 已实现原始流上传到 `0:/gcodes`；路径和实机可见性待验证 |
| 文件上传结果 | 上传响应中的 `err` | `0` 表示成功。 | 已集成上传结果校验；未单独依赖 `GET /rr_upload` |

`rr_status` 在官方文档中已经标记为废弃，并且在 3.6 计划移除；不作为 RRF 3.7 的状态接口。

## 3. 控制能力证据

RRF 官方 G-code 文档确认 Farm 所需控制动作可以通过 `rr_gcode` 发送：

| Farm 能力 | RRF G-code | 说明 | 当前结论 |
|---|---|---|---|
| 暂停 | `M25` | 从其他 G-code 来源暂停 SD 卡打印；继续使用 `M24`。 | 可实现，需真实设备验证响应错误 |
| 恢复 | `M24` | 开始或恢复已通过 `M23` 选择的 SD 打印文件。 | 可实现，需确认 Farm 上传后的文件选择流程 |
| 取消 | `M0` 或 `M2` | `M0` 根据当前状态执行 stop/cancel 宏；`M2` 终止当前任务且当前版本行为类似 `M0`。 | 初版优先使用 `M0`，需真实设备确认 cancel.g/stop.g 副作用 |
| 急停 | `M112` | 立即终止运动并关闭电机、加热器，设备需要复位或断电重启。 | 可实现，但必须作为高风险动作单独记录和确认 |
| 上传并开始 | `POST /rr_upload` + `M23` + `M24`，或 `M32` | 官方确认 `M32` 等价于 `M23` 后 `M24`；文件路径必须使用 RRF 路径格式。 | 可实现，需确认目标设备存储路径 |

## 4. 状态字段和映射依据

官方对象模型文档确认以下路径存在于 RRF 对象模型语义中：

- `state.status`：`disconnected`、`starting`、`updating`、`off`、`halted`、`pausing`、`paused`、`resuming`、`cancelling`、`processing`、`simulating`、`busy`、`changingTool`、`idle`。
- `job.file.fileName`、`job.file.size`：当前任务文件信息。
- `job.filePosition`：当前文件字节位置。
- `job.duration`、`job.rawExtrusion`、`job.timesLeft`：任务耗时、挤出量和剩余时间信息。
- `temps`：温度对象；具体工具/热床数量和路径依设备配置而变，不能假定只有一个喷嘴或一个热床。

第一版建议映射如下，最终以真实设备响应测试冻结：

```text
disconnected/off                  -> OFFLINE
starting/updating                 -> PREPARING
halted                            -> ERROR
paused/pausing                    -> PAUSED
processing                        -> PRINTING
resuming/cancelling/busy          -> PREPARING 或 PRINTING，需结合 job 是否存在确认
idle                              -> IDLE
simulating/changingTool           -> PREPARING
未知值                            -> UNKNOWN
```

注意：官方状态文档同时提供单字符 `status`（如 `P` printing、`S` paused），以及对象模型的 `state.status` 字符串。Farm 的 RRF 客户端必须选择一种已验证的来源，不能把两套字段混合后静默得出错误状态。

## 5. 认证和安全约束

1. 除 `rr_connect` 外，官方 HTTP 文档要求请求具有有效会话。
2. 使用 `sessionKey=yes` 时，后续请求携带 `X-Session-Key`；Farm 的 `apiKey` 字段不能直接当作 RRF session key 永久复用。
3. RRF 设备可能允许空密码；Farm 必须按设备实际配置传递空密码，不应在客户端层无条件拒绝空密码。管理员仍应在网络边界和设备侧启用认证，并禁止密码写入日志。
4. `M112` 会使设备进入需要复位或断电重启的状态，Farm 必须保留独立的急停权限、审计日志和前端二次确认。
5. RRF standalone 与 SBC 模式的对象模型字段可能不同，缺失字段必须按 `null/UNKNOWN` 处理。

## 6. 本次实机探测记录

- 目标：`192.168.0.62`，访问方式：HTTP 默认端口 80，未提供设备密码。
- `GET /rr_connect?password=&sessionKey=yes`：HTTP 200，`err=0`，`isEmulated=true`，`sessionTimeout=8000`，`boardType=unknown`，响应未返回 `sessionKey`；省略 `sessionKey` 参数时响应相同。
- 只读 `GET /rr_model?key=state`：HTTP 200，返回 `state.status=off`，`machineMode=FFF`。
- 只读 `GET /rr_model?key=job`：HTTP 200，返回空文件名、文件大小和位置为 0。
- 只读 `GET /rr_model?key=move`、`heat`、`directories`：HTTP 200；设备报告 `0:/gcodes/` 目录。
- 本次没有发送 `rr_gcode`、`rr_upload`、`M0`、`M112` 或启动命令，因此不构成控制/上传/打印完成验收。设备响应中的 `isEmulated=true` 和 `boardType=unknown` 需要用户确认是否为实际控制板、模拟器或兼容网关。
- 后端客户端已兼容该类 `err=0` 且无 `sessionKey` 的设备：后续请求不附加 `X-Session-Key`；具备会话的设备仍按标准流程附加该请求头。该兼容路径不能证明设备具备生产环境应有的认证隔离。

## 7. 尚未确认、暂不实现的内容

- 实际 RRF 3.7 设备的完整 JSON 响应样例和 HTTP 固件构建号。
- 设备密码是否启用、会话过期后的重连策略以及不同设备的并发会话限制。
- `rr_model` 的最小查询 key、频繁字段 flags 和设备实际响应大小。
- 上传目标目录（通常涉及 `0:/gcodes` 或 SBC 映射路径）及上传后文件可见性。
- `M0/M2` 对设备宏 `cancel.g`、`stop.g`、加热器和风扇的实际副作用。
- RRF 设备是否支持本项目需要的全部暂停、恢复、取消和急停操作。
- 工具温度、热床温度、进度百分比在具体设备配置下的精确字段映射。

在上述事项获得更多真实设备响应前，已实现的控制/上传调用只表示符合官方协议边界并通过 Mock；设备特有字段、副作用和路径失败必须归类为统一的 protocol/offline/rejected 错误，不能返回假成功或调用 Moonraker。
