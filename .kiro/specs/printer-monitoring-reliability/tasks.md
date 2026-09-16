# 打印机监控可靠性 Kiro 执行任务

对应规格：`requirements.md`、`design.md`

状态约定：`[ ]` 未开始，`[~]` 进行中，`[x]` 已完成，`[-]` 裁剪。

## T0 基线确认

- [x] T0.1 阅读当前 `PrinterMonitorTask`、`PrinterMonitorProperties`、协议 Adapter、缓存服务、任务状态机和 Controller。
- [x] T0.2 核对当前 MySQL/SQLite 的 `farm_printer` 实际结构、现有增量 SQL 顺序和持久化数据边界。
- [x] T0.3 确认 `farm.monitor.enabled`、`interval`、`concurrency` 和历史 `printer-ids` 的兼容策略。
- [x] T0.4 为现有监控行为建立回归测试基线，确保本任务不覆盖工作区已有的 `SecurityConfig` 修改。

## T1 [新增] 打印机健康与监控字段

**对应需求：REQ-01、REQ-02、REQ-03、REQ-08**

- [x] T1.1 为 `farm_printer` 增加 `monitor_enabled`、通信健康、`last_known_status`、`last_protocol_state` 和设备 uptime 字段。
- [x] T1.2 更新 Entity、VO 和必要的索引，敏感字段不进入对外响应。
- [x] T1.3 新增显式增量 SQL，兼容 MySQL 8 和 Local Edition SQLite/H2 测试路径。
- [x] T1.4 为历史数据选择安全默认值，禁止迁移后自动轮询全部历史设备。

## T2 [新增] 前端监控配置 API

**对应需求：REQ-01、REQ-08、REQ-09**

- [x] T2.1 新增单台监控开关查询/修改接口。
- [x] T2.2 新增批量启用/停用接口，单次最多 100 台。
- [x] T2.3 在 SecurityConfig 和 Service 层同时限制 ADMIN 修改权限。
- [x] T2.4 更新打印机分页/详情 VO，返回监控开关和通信健康字段。
- [ ] T2.5 增加空请求、重复 ID、未知 ID、无 IP 设备和权限测试。
- [x] T2.6 更新 Swagger（由 Controller 注解自动生成）和 `API_HANDOFF.md`。

## T3 [改造] 监控目标选择与配置兼容

**对应需求：REQ-01、REQ-07、REQ-08**

- [x] T3.1 将监控目标从配置白名单切换为数据库中 `monitor_enabled=true` 且地址有效的设备。
- [x] T3.2 保留 `farm.monitor.enabled` 总开关、轮询间隔和并发上限。
- [x] T3.3 对旧 `printer-ids` 配置增加兼容期告警和明确优先级，空配置不得触发全量历史扫描。
- [x] T3.4 新增打印机默认启用监控，但无效地址不能进入轮询。
- [ ] T3.5 覆盖新增、停用、删除和 IP 变化后的下一轮目标刷新测试。

## T4 [改造] 通信状态与失败恢复

**对应需求：REQ-02、REQ-03、REQ-07**

- [x] T4.1 将设备业务 `status` 与 `connectionStatus` 更新路径分离。
- [x] T4.2 实现连续失败/连续成功阈值、错误摘要、最后在线时间和事件限频。
- [x] T4.3 API 失败时保留 `currentJobId`、活动任务状态和最后已知业务状态。
- [~] T4.4 为在线、短暂抖动、持续离线、恢复和服务重启补充测试；现有回归已覆盖在线、离线限频、任务保留和重叠巡检。
- [x] T4.5 增加包含耗时、在线、离线、跳过数量的监控摘要日志，不记录 API Key；指标接入保留为后续运维增强。

## T5 [改造] Klipper/Moonraker 状态与任务匹配

**对应需求：REQ-04、REQ-05**

- [x] T5.1 验证必要的 Moonraker 对象，缺少 `print_stats` 时返回明确协议错误。
- [x] T5.2 保留 `webhooks` 系统状态优先级，正确处理 startup、shutdown、error、ready。
- [x] T5.3 使用 `print_stats.state=complete` 作为明确完成证据。
- [x] T5.4 将 `print_stats.filename` 与 Farm 任务文件标识进行匹配。
- [ ] T5.5 覆盖 Klipper 打印中、暂停、完成、取消、API 断开、Moonraker 重启和文件不匹配测试。

## T6 [改造] RRF 状态解析与重启恢复

**对应需求：REQ-04、REQ-06**

- [x] T6.1 修复 `job.timesLeft.file` 嵌套结构解析。
- [x] T6.2 增加 `lastFileName`、`lastDuration`、取消/中止标志和设备 uptime 的统一快照字段。
- [x] T6.3 明确区分 processing、paused、complete、idle、halted、cancelled 和 aborted。
- [x] T6.4 RRF idle 无完成证据时保留绑定并进入 `RECONCILING`。
- [x] T6.5 覆盖 RRF Standalone 缺失 DSF/DWC 字段时的安全降级。

## T7 [新增] 任务人工核对接口

**对应需求：REQ-04、REQ-08、REQ-09**

- [x] T7.1 新增 `POST /api/v1/print-jobs/{jobId}/reconcile`。
- [x] T7.2 支持 COMPLETED、FAILED、CANCELLED、QUEUED 四种人工结果。
- [x] T7.3 校验任务归属、角色、状态迁移、打印机绑定和操作原因。
- [x] T7.4 只有终态/重新排队持久化成功后才解绑或发布事件。
- [ ] T7.5 增加重复提交、越权、幽灵绑定和并发处理测试。

## T8 [改造] 轮询性能与多实例安全

**对应需求：REQ-07**

- [x] T8.1 为 PRINTING、PAUSED、IDLE、OFFLINE 设计不同轮询频率或退避策略。
- [x] T8.2 保留并发上限，确保设备异常不会耗尽监控线程池。
- [x] T8.3 增加 Redis 设备级分布式锁或确定性分片，避免多实例重复巡检。
- [x] T8.4 控制慢设备、连续离线日志和 WebSocket 事件的频率。
- [x] T8.5 验证监控开启不会带起 `JobSchedulerTask`。

## T9 [测试/文档] 回归与联调证据

**对应需求：REQ-08、REQ-09**

- [x] T9.1 运行 `mvnw.cmd test`：252 个测试通过，0 失败、0 错误；仅有 RustFS/H2 测试环境告警和 JaCoCo/Mockito 运行时提示。
- [~] T9.2 已有 Controller 权限、监控任务、Klipper Adapter、RRF Adapter 和状态机回归；新增监控配置/人工核对接口的专项测试仍可继续补充。
- [~] T9.3 已使用 Mock 验证断网、离线限频、完成证据、重启后 idle/RECONCILING 等关键流程；人工核对的重复提交/并发专项测试仍可继续补充。
- [ ] T9.4 在有授权的真实设备上分别验证 Klipper/Moonraker 和 RRF 的只读状态恢复；不进行未经确认的物理控制。
- [x] T9.5 更新 `API_HANDOFF.md`、运维配置说明和已知限制。
- [x] T9.6 记录数据库备份、迁移、回滚边界；本轮未执行 `docker compose down -v`，未删除数据卷。

## T10 [改造] 平台任务与外部任务隔离

**对应需求：REQ-04、REQ-06、REQ-10**

- [x] T10.1 将设备打印来源分类为 `FARM`、`EXTERNAL`、`UNKNOWN`、`NONE`，缺少文件名不得视为平台任务匹配。
- [x] T10.2 保持外部打印期间的平台 `READY` 任务不变，禁止同步错误进度和终态。
- [x] T10.3 在实时缓存、WebSocket 和状态历史中记录来源、设备文件、平台任务 ID 与判定原因。
- [x] T10.4 前端打印机列表、详情抽屉和状态历史明确区分平台任务与外部任务。
- [x] T10.5 补充文件匹配、外部打印、缺失文件名、历史持久化和前端展示回归测试。
- [x] T10.6 后端 457 项、前端 380 项测试通过，前端 lint、浏览器与 Desktop 构建通过；只修改源码、迁移和文档，未操作运行中的安装版服务。

## T11 [修复] RRF 自然完成与跨重启执行会话

**对应需求：REQ-06、REQ-10、REQ-11**

- [x] T11.1 新增 `farm_printer_execution` 持久化设备执行来源、任务、文件、最大进度和 uptime。
- [x] T11.2 平台启动确认后建立 FARM 会话；仅上传 READY 不建立会话。
- [x] T11.3 监控阶段维护 FARM/EXTERNAL/UNKNOWN 会话，明确文件不匹配时隔离外部打印。
- [x] T11.4 使用已确认执行会话和连续两次 idle 判断 RRF 自然完成，不依赖结束帧中已重置的进度字段。
- [x] T11.5 将任务终态、设备解绑和会话关闭纳入同一事务，并在提交后发布任务事件。
- [x] T11.6 增加 SQLite 自动升级、MySQL 显式迁移和针对执行会话/终态判断的回归测试。
- [ ] T11.7 构建新版 Windows 安装包后，在真实 RRF 3.7beta3d 上验证正常完成、取消、急停、服务中途重启和外部打印。
