# 已登记打印机 IP 自动恢复设计

产品版本：`1.0.0` 准正式版维护增强；规格：`printer-ip-rediscovery`
更新时间：2026-10-08

## 1. 服务边界

`PrinterRediscoveryController` 公开管理员异步启动与查询接口；`PrinterRediscoveryService` 管理单线程任务、最近快照、自动限频、监控目标和身份匹配。`PrinterRediscoveryTask` 负责启动后/周期触发，`PrinterMonitorTask` 在连续失败达到阈值后调用相同自动入口。

`PrinterNetworkDiscovery` 在指定网段中并发执行只读协议发现，收集 IP/MAC/协议三元组；`PrinterIdentityVerifier` 在访问当前地址前核对身份。启用保护且具有有效 MAC 的设备，监控、`ControlService` 和 `PrintJobService` 的物理操作 endpoint 每次调用 `verifyFresh`，不命中旧成功缓存；身份未知只允许匿名信息与 ARP，失败返回 503 且不下发密钥/动作、不释放绑定。无有效 MAC 的旧记录保持兼容，不获得自动恢复能力。

`PrinterAddressUpdateService` 将身份匹配形成的地址变化列表原子提交。它以设备 ID 顺序持有设备锁，查询并锁定待更新设备、目标地址占用和规范 MAC 匹配记录，检查当前 IP/MAC/协议/监控开关及身份唯一性，先在事务内释放原地址再写入新地址以支持 IP 交换。条件更新任一失败则整批回滚；提交后更新缓存。数据库最终提交前的中间空地址不对其他事务暴露。进程内设备 mutex 不因暂时空闲移除，避免旧引用竞态；Redis 租约释放异常不能覆盖已完成的动作/事务结果，也不能阻止本地锁释放。

## 2. 配置和扫描范围

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `farm.discovery.enabled` | 基础/dev/test false，Local true | 自动恢复及地址身份保护；手动管理员同步独立可用 |
| `farm.discovery.subnets` | `[]` | 最多 8 个私有 IPv4 三段前缀，如 `192.168.0`；不是 CIDR 字符串 |
| `farm.discovery.initial-delay` | `10s` | 后台首次触发延迟 |
| `farm.discovery.interval` | `5m` | 周期与自动触发限频共用；至少 30 秒 |
| `farm.discovery.timeout` | `2m` | 单次网络发现的总体超时 |
| `farm.discovery.concurrency` | `16` | 发现并发数，1–64 |

每个前缀仅检查 `.1` 至 `.254`；配置仅接受 RFC1918 私有 IPv4 范围。无显式范围时，从开启监控、符合白名单、有效 MAC 和受支持协议的现有地址推断。超过 8 个网段时要求管理员缩小范围；缺失旧 IP 且没有配置范围时不能推断地址。网络发现超时、中断或锁租约失效后不应用部分地址结果。

## 3. 任务和接口快照

GET/POST `/api/v1/printers/rediscover` 均返回 `Result<PrinterRediscoveryResultVO>`，字段为 `scanId,status,message,startedAt,finishedAt,subnets,scannedAddressCount,updatedCount,unchangedCount,unresolvedCount,items`。状态为 `IDLE/RUNNING/COMPLETED/FAILED`；IDLE 的 scanId 和时间为 null。进行中 POST 复用同一任务；GET 不触发扫描。任务保存在单实例内存，重启后清空；Redis 存在时增加跨实例互斥与续租。

逐项 `PrinterIpSyncItemVO` 包含字符串 JSON `printerId`、`printerName`、可空 `oldIpAddress/newIpAddress`、`status/message`。状态为 `UPDATED/UNCHANGED/NOT_FOUND/MISSING_MAC/UNSUPPORTED_PROTOCOL/CONFLICT/SKIPPED`；不包含 MAC、API Key 或凭据。COMPLETED 允许存在未解决项，客户端按 `unresolvedCount` 展示待处理。FAILED 中任何原先待更新项改为未提交冲突结果，避免假报成功。

## 4. 监控和客户端一致性

IP 更新只将通信状态置 UNKNOWN，不将设备业务状态直接设置 IDLE 或 ONLINE，不释放任务绑定。监控后续在新地址确认协议状态，正常 REST/WebSocket 链路收敛。前端 ADMIN 抽屉通过 POST 启动、GET 恢复和串行轮询，完成刷新列表；关闭抽屉不取消服务端任务，卸载后停止查询且忽略旧响应。

## 5. 协议兼容边界

Moonraker 匿名 `/server/info` 返回 401/403 时，以匿名 `/access/info` 的 `default_source/available_sources/login_required/trusted` 结构识别协议；该接口允许未授权读取，但协议识别本身不能证明 MAC。局域网中以精确目标 ARP 补足真实 MAC；跨路由且匿名系统信息被拒绝时，身份仍不能确认。[Moonraker 官方授权接口](https://moonraker.readthedocs.io/en/latest/external_api/authorization/)

RRF 3.5-b3 及更早版本或无 sessionKey 网关可能复用相同来源 IP 的既有会话。本功能发现/身份读取只释放获得独立 sessionKey 的会话，无 key 时不调用 disconnect，交设备 idle TTL 回收，避免断开正常操作会话；官方示例 TTL 为 8 秒，实际以设备值为准。此策略仅用于只读发现/身份，不改正常协议操作会话。[RRF 官方 HTTP 请求说明](https://github.com/Duet3D/RepRapFirmware/wiki/HTTP-requests)

受保护跨路由设备即使使用固定 DHCP，若匿名 MAC 与 ARP 均不可用仍会拒绝监控和物理动作。优先使服务端和设备位于同一可 ARP 网络；管理员选择固定 DHCP、关闭 `farm.discovery.enabled` 并手工维护地址时，也同步关闭新增身份保护。

## 6. 验证和发布边界

使用协议 Stub、私有范围校验、Controller/服务权限与真实临时 SQLite 持久化测试验证边界，不接触现有数据库或真实设备。后端 `.\mvnw.cmd test` 全量 714/714，新 9 类 IP 回归共 163 项，包含临时 SQLite 地址交换/并发身份冲突/事务回滚；前端配套提交为 `cb9bf60`，专项 9/9、全量 414/414、浏览器 2/2、发布门禁通过。本轮 Windows 安装器契约测试通过，新安装包构建状态单独补录；现场 DHCP、鉴权/跨路由、真实 MySQL 和安装/升级尚未验证。
