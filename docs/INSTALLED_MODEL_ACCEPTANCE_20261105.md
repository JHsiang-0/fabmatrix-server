# 安装版统一模型只读验收

日期：2026-11-05。首轮本地读取、客户端详情和云端设备身份通过，Cloud 多设备新鲜度未通过；随后已修复并升级 Cloud，持续只读复验通过，详见下方“时效修复复验”。RRF 在线采集等未验证边界保持单独记录。

## 目标与操作边界

- 安装版 Server：`D:/Program Files/FabMatrix`，本地端口 28081，服务运行且健康 UP。已安装 JAR 与本轮安装包 payload 一致，对应代码/构建基线 `4195a27`。
- 安装版 Client：`D:/Program Files/FabMatrix Desktop`，已安装 app.asar 与本轮发布产物一致，当前客户端进程在本轮安装后启动；源码基线 `ddcf2a5`。
- 常驻 Cloud：本机 Docker 28091，源码基线 `f5aac58`，服务、数据库及 MQTT 消费连接正常。
- 只读取业务 API、WebSocket、现有云端数据和客户端页面。测试登录产生普通认证会话/审计，结束了本地测试会话；没有发送打印机控制命令、修改设备/任务、切换监控、关机、重绑或重启服务。

## 已通过

| 检查 | 结果 |
| --- | --- |
| 本地设备列表 | 31 台全部具有 modelVersion 2 摘要；当次为 23 打印中、6 空闲、2 离线 |
| 列表与完整模型 | 逐台读取，设备 ID、新鲜度及同一观测版本的状态/测量/执行摘要未发现不一致 |
| 本地聚合详情 | 打印中、空闲、离线的代表设备均具有正确摘要；离线当前温度、进度、文件和执行摘要为空 |
| WebSocket | 18 秒只读订阅收到 1 次包含 31 台设备/31 份新摘要的初始快照，以及 31 条带摘要的状态消息；未发现身份或过期值泄漏 |
| 已安装 Client 实际页面 | `Printer_8098` 显示真实外部打印任务、进度、温度、时长和耗材并继续刷新；离线 RRF 显示当前数据不可用、温度留空及最后采样时间，控制入口不可用 |
| Server 与 Cloud 库存/身份 | 同一网关的 31 台当前设备集合一致；31 个 sourceInstanceId/deviceUid 均匹配，没有身份冲突或丢设备 |
| Cloud 摘要优先级 | 已返回的新摘要与平铺字段一致；过期时没有恢复旧温度或进度 |

## 首轮未通过：Cloud 多设备观测新鲜度

首轮云 API 查询：31 台设备中 13 台具有新鲜完整观测、2 台明确离线、16 台为 UNKNOWN；与此同时本地 29 台在线。随后限定这 31 台当前设备的只读 SQL 复核，29 台在线中 19 台的完整观测超过 30 秒，最老为 62 秒。数量会随轮询变化，不能将其当作固定的 16/19 台打印机离线。

代码与采样证据指向消息处理节奏：

1. Cloud `TelemetryMqttClient.start` 每 1 秒运行一次取队列/业务提交过程。
2. Server `CloudGatewayAgent` 只有一个待确认消息，收到应用提交 ACK 后才继续准备下一个；V3 本地调度虽为 100 ms，仍会等待 Cloud 的消费周期。
3. 完整观测逐设备发送，并与库存、描述、事件、兼容快照等共享发送过程。完整刷新一轮加上采样/缓存年龄后，会超过当前 30 秒新鲜度窗口。
4. 本轮 Cloud 从完整模型统一派生列表状态并使用 30 秒门限，使该吞吐问题直接表现为云端 UNKNOWN。网关 CONNECTED 和 MQTT 已连接不能证明每台设备的完整模型都新鲜。

首轮建议调整消费唤醒/ACK 节奏及发送公平性，保持消息提交顺序、去重、epoch/绑定隔离和有界队列，再执行至少本次设备数量的持续新鲜度回归；不能只延长过期时间或把旧字段补回当前值。当次仅定位和记录，后续修复如下。

## 时效修复复验

Cloud `2ff820c` 将合法 MQTT 消息改为到达即唤醒原单线程消费者，保留 128 条输入队列、每批 32 条、提交后 ACK、退避和停机边界；1 秒定时器只维护连接。Server、Client 和 30 秒过期判断没有修改。Cloud `62f6d55` 加入 32 台模拟打印设备的持续新鲜度回归，`5981c7d` 登记部署结果。

- 87 项 Maven 检查、2 项 Python 夹具边界检查、38 项隔离链路检查通过。隔离的 32 台设备在 16 KiB/s 限速下连续 65 秒没有 UNKNOWN/stale，最老观测 26,336 ms，每台均持续更新。
- 只重建常驻 Cloud 应用，数据库/Broker 容器、结构及账号/农场/网关数量保持不变；运行 JAR 与验证产物一致。
- 现场库存仍为 31 台，当轮持续在线 27 台。连续 65 秒、64 次云查询没有在线 UNKNOWN/stale，最老观测 25,511 ms，每台持续在线设备的观测版本继续推进；实际离线设备未伪装在线。
- 同步 17 次本地只读检查全部正常，最大读取耗时 46 ms；服务 PID 31960、监听进程 PID 27364 与修复前一致。未重启现场 Server、未更新其安装包、未发送任何打印机控制命令。

因此本次设备数量和网络下的上报时效问题已修复，不需要重装已安装的 Server/Client。持续验证不是对任意弱网或任意设备量的时延保证。证据：Cloud `target/mqtt-wakeup-live-acceptance.json`、Server `target/mqtt-wakeup-local-continuity.json`；实施细节见 Cloud [C2 实现](../../fabmatrix-cloud/docs/C2_IMPLEMENTATION.md)。

## 证据与限制

Git 忽略的本地证据：Server `target/installed-model-acceptance.json`、`target/installed-model-websocket-acceptance.json`；Cloud `target/installed-server-cloud-model-acceptance.json`、`target/installed-cloud-timing-diagnostic.json`、`target/installed-model-identity-acceptance.json`。这些报告不包含密码、JWT 或设备 API Key。

未触发真实设备的暂停、取消、急停、重启或断网；没有验证新的打印启动和取消生命周期。当前 RRF 离线，只验证其离线展示，未重新验收在线 RRF 采集或离线恢复。手机界面不属于本次已安装桌面端验收范围。
