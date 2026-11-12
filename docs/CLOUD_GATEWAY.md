# 可选云网关（C2 上报与 C3 受限远程控制）

默认关闭。云服务是同级 fabmatrix-cloud，接入前按其 C1/C2 文档准备独立数据库、HTTPS、MQTT TLS 和组织/农场。

## 当前开发版本：统一生产控制授权 v1

当前代码以单一 `remoteEnabled` 为权威，不再分别授权暂停与温控。下方“远程暂停”和“独立温控”段落描述此前发布版本，仅用于解释兼容边界。

2026-11-08已按用户授权保留数据升级原Windows Server/Client、本机Cloud V27及手机开发版28。运行JAR/ASAR与最终制品一致，服务健康、网关回执恢复，账号/设备身份/原绑定保留；统一授权与两项旧兼容字段仍关闭，命令0，没有发送真实打印机控制命令。31台设备仍为既有陈旧观测，不能因网关恢复即声称设备在线。详见[四端部署记录](../../fabmatrix-cloud/docs/REMOTE_PRODUCTION_CONTROL_ROLLOUT_20261108.md)。

本地 ADMIN 的 `GET /api/v1/cloud/remote-control` 返回 `remoteEnabled`、`unifiedControlSupported:true`、`authorizationVersion:1`、`revision`、`requiresReauthorization` 和固定八项 `supportedActions`。旧 `enabled`、`temperatureEnabled` 兼容字段均从有效总授权派生，不能独立开启。新界面只提交 `PUT {remoteEnabled,authorizationVersion:1,revision}`；字段类型与授权版本严格校验。旧请求开启返回 409，旧请求关闭会撤销全部授权。

配置仍为受保护的 `cloud-remote-control.json`。旧文件没有授权版本 1 时一律关闭远控，原先开启过任一旧开关则提示重新确认；不会扩大旧授权。同绑定重启保留显式新授权，换绑或解绑撤销授权。目录 v1 只包含下列八项，新增动作必须重新定义授权版本，不能默默扩大范围。

| 动作 | 当前允许状态 | 设备确认 |
|---|---|---|
| PAUSE | 当前打印执行 | 真正 PAUSED；PAUSING 只等待 |
| RESUME | 同一暂停执行 | 同一执行恢复 PRINTING；RESUMING 只等待 |
| CANCEL | 同一 PRINTING / PAUSED 执行 | 固件明确取消，或 RRF 命令链关联的两帧一致 IDLE |
| SET_HEATER_TARGET | IDLE / PRINTING / PAUSED | 单通道目标值已应用；不表示实际温度达到目标 |
| SET_FAN_SPEED | IDLE / PRINTING / PAUSED | 可靠人工料冷风扇设定值，0–100% |
| SET_SPEED_FACTOR | PRINTING / PAUSED | 任务速度倍率设定值，10–200% |
| SET_FLOW_FACTOR | PRINTING / PAUSED | 当前唯一工具/挤出驱动的倍率，10–200% |
| HEATERS_OFF | IDLE 且无执行/农场任务绑定 | 完整受控 TOOL/BED 通道集合全部确认关闭 |

统一授权与设备能力分开。`supportedActions` 表示当前 Server 实现的目录，具体设备仍要通过 Cloud 能力判断和现场新鲜探测；显示了温度、风扇或轴位置不代表可写。Klipper 风扇首版仅原生 `fan`，RRF 风扇要求工具关联且明确未启用恒温控制。RRF 流量必须映射当前工具的唯一驱动并显式发送 D 参数，不能默认驱动 0。多驱动、共享映射和未知通道均拒绝。

Klipper 原生风扇还须确认固件配置 `max_power=1` 且 `off_below=0`；否则 `fan.speed` 已经被固件缩放或阈值处理，不能冒充原始设定值。非默认或未知配置仅保留实际 PWM，`requestedPwmRatio` 为 UNAVAILABLE，不下发风扇控制。私有证据按设备端点、凭据摘要和可观察 Klippy 进程/启动变化隔离，提取布尔证据缓存最多 256 项、有效 60 秒，失败负缓存 15 秒；不缓存或上报原始配置。写前与确认时重新读取配置，配置探测失败只禁用该可选能力，不中断设备状态读取。相同 PID 且漏掉软重载过渡时不能绝对识别配置变化，定时到期与写前强制复核共同兜底，不推断固件未提供的启动身份。

协议仍使用通知 MQTT + HTTPS 领取/回执，命令锁、持久 inbox、Cloud EXECUTING 栅栏、30 秒新鲜度/发送期限、精确会话与绑定检查均保留。PAUSE 保持命令 schema 1，单通道温控保持 schema 2；其余六项使用 schema 3，携带设备 boot、描述版本、操作状态和执行上下文。倍率/风扇 `expectedPercent` 为比例乘 100 的精确 CAS，回读容差与 CAS 分离。新 Server 向旧 Cloud 回退时发送关闭策略并禁用控制，只读 C2 上报继续。

本地 `PrinterControlService` 的恢复/取消仅面向绑定农场任务，且旧取消接口接受后可能提前解绑；远程不会转发这两个 HTTP 入口。`RemoteProductionControlService` 直接核验本地 ACTIVE 执行：FARM 只更新其真实本地任务，EXTERNAL 不构造 FarmJob。RRF 取消必要时只发一次 M25，等待真正暂停，再在授权/期限仍有效时只发一次 M0；CANCELLING 不能作为完成证据。standalone RRF 不依赖 DWC 才提供的 `lastFileCancelled`，其取消结果使用服务端核对证据 `RECONCILED`，不能伪称设备明确报告。

动作前不一致采样拒绝执行；动作后有限重采只读状态，任何 `INCONSISTENT` 帧均不能用于确认或触发重发。明确换任务、换文件路径或重启时返回未知。全部关热先完整预检 1–16 个通道，存在未知/CHAMBER 或不可靠映射则整体拒绝；开始后逐通道写 0 并回读，已确认通道独立留审计，后续失败为 `UNKNOWN/PARTIAL_APPLIED`，不回滚、不重放，也不假称全关成功。

急停不进入普通排队控制目录；回零、点动、挤出、远程开打分别需要独立的优先级、维护安全、文件与现场确认流程，当前接口不会开放任意 G-code。

## 2026-11-08 温控版本本机升级

用户追加部署授权后，已在原`D:\Program Files\FabMatrix`保留数据覆盖升级至508c97c对应制品，安装器退出0、服务Running/Auto、28081健康UP；安装JAR摘要与交付包一致。31台设备、2个账号、2个文件/目录及设备/安装来源标识、网关凭据和云连接配置均保留，SQLite完整性正常。已启动普通用户托盘，暂停及温控授权升级前后均关闭。

配套Cloud已升级V26、Client已更新并打开登录页、PJE110开发版已升26；新网关epoch40的提交回执推进，温控能力1同步。当前31台设备在本地也为OFFLINE，最新源观测早于本轮升级，未修改IP、监控或30秒过期规则。没有向真实打印机发送任何控制命令，详见[四端温控部署记录](../../fabmatrix-cloud/docs/REMOTE_TEMPERATURE_ROLLOUT_20261108.md)。下文71ffaa0/V25等为此前暂停版本升级历史。

## 2026-11-08 本机安装版升级

用户追加部署授权后，已在 `D:\Program Files\FabMatrix` 保留数据覆盖升级至 `71ffaa0` 对应制品，安装器退出 0，服务 Running/Auto、28081 健康 UP。SQLite 完整性检查通过，新增远程命令 inbox 和执行时长字段；31 台设备、2 个账号及设备稳定标识保留，网关凭据与云连接配置未替换。远程暂停授权升级前后均为关闭，没有向真实打印机发送控制命令。

本机 Cloud 已升级 V25，Client 与 PJE110 App 也已升级，上报恢复。核查时库存和监控开关均为 31 台，其中 1 台有新鲜在线观测，另外 30 台没有新鲜状态；没有为消除陈旧提示修改设备地址、监控开关或 30 秒过期阈值。实际部署、制品摘要、Cloud 常驻修复和验收边界见 [四端部署记录](../../fabmatrix-cloud/docs/REMOTE_PAUSE_ROLLOUT_20261108.md)。本文其他历史阶段“未部署”描述仅指当时的开发验证。

## Client 管理当前 Server 的云端连接

本地管理员可从 Client 的“设置 → 云端连接”或顶部设置按钮操作当前 Server。手机 App 负责创建云端组织、农场、网关及一次性绑定码。Client 调用所选 Server 的本地 API，由 Server 兑换、保存和使用独立网关凭据。

1. 输入 Cloud API 地址、MQTT TLS 地址和一次性绑定码。自建 CA 可粘贴公共证书，公开受信证书使用系统信任。
2. 绑定成功后上报保持关闭，页面展示组织、农场及网关名称和 ID，核对后开启上报。
3. 开启或关闭立即生效，Server 重启后恢复最后保存的设置。关闭保留绑定，云端按心跳超时标离线并保留历史。
4. 重新绑定使用新的一次性绑定码。解绑先持久化关闭上报，再请求 Cloud 撤销当前网关凭据，确认成功后清除当前绑定。云端不可达时保留已关闭的绑定，恢复网络后重试。云端已拒绝当前凭据的 401 可作为失效确认，支持丢失成功响应后的重试。

管理设置写入原 credential-file 同目录的 `cloud-connection.json`，包含秘密凭据，仅允许受保护的配置目录和文件权限。默认安装版为数据目录下 `config/cloud-connection.json`。此文件存在后优先于旧 YAML/环境变量的云连接默认值。原环境变量和凭据文件可在首次使用界面前兼容导入，备份应包含整个 config 目录。

本地管理 API 均要求 ADMIN，输入携带页面读取的 revision，多管理员并发修改返回 409 并要求刷新。绑定、启用、关闭与解绑写入本地审计。响应不返回绑定码、网关凭据、证书内容或本机路径。

| 方法与路径 | 用途 |
|---|---|
| GET /api/v1/cloud/connection | 配置、绑定展示、状态、最近回执与本地设备数量 |
| POST /api/v1/cloud/bind | revision、apiUrl、mqttUrl、bindingToken、caCertificatePem、useSystemTrust |
| PUT /api/v1/cloud/reporting | revision、enabled |
| POST /api/v1/cloud/refresh-binding | revision，获取云端最新绑定名称 |
| POST /api/v1/cloud/unbind | revision，撤销凭据并解除绑定 |

Cloud 须先部署 `/api/v1/gateway/connection` 和 `/api/v1/gateway/disconnect`。旧 Cloud 仍支持原有上报，但名称查询和凭据撤销管理需更新云服务。升级顺序为 Cloud、Server、Client，无新增数据库迁移。单纯开启上报仅启动独立代理，联网失败通过连接状态报告。

## 产品边界

2026-11-05 已确认 Server + Client 作为独立本地生产系统，Cloud + Flutter 手机 App 提供远程业务。完整职责、数据所有者和通用上报端契约见 [ADR 0011](../../fabmatrix-cloud/docs/decisions/0011-local-production-and-cloud-reporting.md)。

本地账号、密码、岗位、文件、任务及安全执行由 Server 管理。云端组织、农场、成员和接入身份由 Cloud 管理。Client 只连接所选 Server 的业务 API 和 WebSocket，远程管理页面由手机 App 提供。

farm.cloud.enabled 默认关闭；已部署站点的显式启用配置独立保留。代理通过独立后台生命周期执行上报，网络或配置故障报告到代理状态。本地服务使用自己的 SQLite、签名密钥和持久化会话，完成离线登录及生产操作。

本次通过 CloudGatewayConfigurationTest 校验默认关闭与显式开启，已有 CloudGatewayTest 验证无效云配置下本地应用生命周期可继续。独立单设备代理属于后续实现，上报模型和网关协议保留兼容。

## 配置

| 环境变量 | 说明 |
|---|---|
| FARM_CLOUD_ENABLED | 无界面管理配置时默认 false，配置完成后可显式 true |
| FARM_CLOUD_OBSERVATION_V2_ENABLED | 默认 false；确认云端已升级并应用 V5 后设为 true，启用 V2 受限观测快照 |
| FARM_CLOUD_OBSERVATION_V3_ENABLED | 默认 false；Cloud 已支持 V3 并完成数据库迁移后设为 true，启用单设备 canonical 观测和执行上报 |
| FARM_CLOUD_MAX_UPLOAD_BYTES_PER_SECOND | 应用消息每秒字节预算，默认 131072，允许 16384–10485760；含重发，不含 MQTT/TLS 网络开销 |
| FARM_CLOUD_API_URL | 云 HTTPS 根地址，无路径/查询；HTTP 仅允许字面 loopback 调试 |
| FARM_CLOUD_MQTT_URL | ssl://域名:8883，强制验证证书和主机名 |
| FARM_CLOUD_CA_CERTIFICATE | 可选 CA PEM 路径，空使用系统信任 |
| FARM_CLOUD_BINDING_TOKEN_FILE | 首次绑定票据文件，只写单个 fmc_b_… 票据；10 分钟失效，不提交 Git |
| FARM_CLOUD_CREDENTIAL_FILE | 默认 FarmData/config/cloud-gateway.json，独立凭证文件 |
| FARM_CLOUD_STATE_DIRECTORY | 默认 FarmData/cloud-agent，单个待确认快照文件 |

FarmData 根优先 FARM_DATA_DIR，未设置使用 user.home/FarmData。云端 OWNER/ADMIN 通过网关创建/重绑定 API 签发票据，现场不使用管理员密码或局域网 JWT 连接云。首次兑换前检查文件目录可写及访问控制能力；凭证原子保存为 POSIX 600 或 NTFS 当前用户/SYSTEM 可访问，保存内容为秘密明文，仍须保护父目录、备份和运行账户。票据消费后失效，可由部署者移除票据文件。

凭证默认 30 天，尚无自动续期。重绑定/吊销后旧凭证不可再用；管理员签发新票据后可从 Client 重新绑定。纯环境变量部署继续使用原凭据文件流程，重新绑定前需停止代理并更新凭据来源，避免继续读取旧凭据。不得把完整文件、Token 或 CA 私钥贴到日志/工单。

## 行为与诊断

2026-11-12修复完整模型的执行类型延续：终态类型复用已持久化模型中同一来源、设备、本地设备ID及执行ID的已知 `PRINT/SIMULATION`，不再仅用“曾看到processing”判断。首次只看到暂停、连续空闲和服务重启均保留可靠类型；旧证据缺失、损坏或冲突时保持UNKNOWN，outcome/evidence不被改成成功。该修复复用已有模型JSON，无数据库结构变更；已丢失的历史类型由Cloud按其已有执行证据兼容合并，不能在现场凭空补造。

管理员可调用 `GET /api/v1/cloud/status` 查看 DISABLED/STARTING/SYNCHRONIZING/CONNECTED/RETRYING/SUPERSEDED、最后提交回执和脱敏错误码，其他角色拒绝。连接正常不代表打印机在线，云端仍按独立观察时间标过期。

C2 上报线程只读取现场已有设备数据。C3 使用独立队列和线程，仅在本地管理员明确授权后支持远程暂停，详见下文。V1/V2 快照每 5 秒检查变化、至少每 30 秒发送一次，约 15 秒发送心跳。最多 200 台、单包 64 KiB；超限报错且不截断上传。V1/V2 仅上报设备状态白名单，不上传 IP/MAC/API Key、账号或文件内容。

V2 可选上报统一观测的采集时间、质量、加热器、工具和不含文件名的执行摘要，仍使用完整快照与 64 KiB 限额。网关会协商协议版本；旧 Cloud 不支持协商时回退到配置的 V1/V2。

V3 选项启用后，代理才会协商 V3；已确认的设备描述和新鲜观测通过独立消息上报。观测只在监控开启、设备在线且采样不超过 30 秒时发送，携带设备通道、当前执行和上次执行结果。执行文件只带现场模型中的安全文件名摘要，不发送文件内容或本地任务 ID。库存和描述需先经 Cloud 确认，消息按单包 64 KiB 限制。安装版启用前先升级 Cloud、完成数据库迁移并验证兼容性。

跨 Server、Cloud 和客户端的升级顺序、快照范围及回退边界见 [统一模型升级与回退](UNIFIED_MODEL_RELEASE.md)。

云代理读取本地模型时，每台设备的 SQLite JSON 上限为 128 KiB；超限或已存模型无效时，该设备的模型暂不上报并输出限频警告，不影响其他设备。超限不影响本地详情读取；无效的本地模型仍需排查。单包 64 KiB 限制独立生效。

V3 每 5 秒核对设备库存；新增、删除或身份变化时先提交完整库存，确认后再更新状态。库存版本保存在 `cloud-agent/cloud-inventory-revision.txt`，重启后继续递增；状态目录应随现场数据库一起保留。

pending 文件同一时刻只保存一个待确认消息，5 秒重发；快照/观测过期后重新捕获当前状态。启动用新 bootId 登记云 epoch，丢弃上个进程待发快照。V3 事件保存在 SQLite outbox，收到 Cloud 事务提交或去重回执后才移除；未确认事件跨重启保留，失败记录每 30 秒可重新尝试。网络失败 1–30 秒退避且不阻塞局域网线程，配置错误也不阻止本地服务启动。旧 boot 被取代显示 SUPERSEDED，需排查重复运行的网关实例。

## C3：管理员授权的远程暂停（2026-11-08）

远程暂停默认关闭，独立于上报开关。Client 通过本地管理员接口 `GET /api/v1/cloud/remote-control` 读取 `{enabled,revision}`，通过 `PUT` 同路径提交这两个字段。沿用标准 `Result.data`，初始 revision 为 1；旧 revision 返回 409。策略受保护、原子写入配置目录的 `cloud-remote-control.json`，精确绑定 gatewayId 与 bindingVersion。同绑定重启保留授权，解绑或更换绑定必须重新授权；损坏或不可读时拒绝远程执行，不影响本地生产。关闭上报、断开 MQTT 或停止 Server 后旧会话和排队通知立即失效。

手机控制使用 Cloud 账号；Server 不模拟本地管理员或接收 Cloud 用户 JWT。MQTT `down/command` 仅接收不超过 4096 字节的非 retained、QoS 1 通知，通知只含命令 ID 和会话身份。独立 worker 使用现有 Cloud TLS 与网关 Bearer 领取命令、同步策略及回传结果。旧 Cloud 不支持 HTTP 控制接口或 Broker 拒绝命令订阅时只禁用 C3，C2 状态上报继续。

执行前先核对网关绑定、boot/epoch、sourceInstanceId、deviceUid、当前 executionUid、授权版本和期限（不超过 30 秒），取得有界设备锁后及协议调用前再次检查。必须启用全局与单台监控，设备在线且执行证据不超过 30 秒，并通过本次 MAC/协议/IP 身份核验；缺少 MAC 不沿用旧路径的跳过行为。文件变化、明显进度/位置/打印时长回退或设备重启会建立新的 EXTERNAL 执行身份。连续性无法确认时拒绝暂停。

`farm_remote_command` 保存命令全文摘要、Cloud 操作者 UUID、会话、状态与投递结果，启动自动补表；`farm_printer_execution.max_print_seconds` 保存执行时长回退证据。命令 ID 重复只返回原结果，内容冲突拒绝执行。先持久化 EXECUTING，再获得 Cloud 的 EXECUTING 回执，最后发送一次暂停。HTTP 接受不等于成功；独立读取到同一执行的 PAUSED 才回报 CONFIRMED。读回超时或发送后通信异常保留 UNKNOWN，不自动重试物理动作。重启时遗留 EXECUTING 转 UNKNOWN、RECEIVED 转拒绝，不重放；旧 boot 的结果不借用新会话发送。

结果发送与新通知分开处理。临时失败逐条退避；Cloud 永久拒绝结果时保留原状态并记录投递冲突审计（`reported=2`），不会宣称设备成功或阻塞后续命令。`reported=1` 才表示 Cloud 已接受回执。FARM 执行只更新本地 execution 关联的任务；EXTERNAL 不创建或借用 FarmJob。操作与冲突审计通过 commandId 关联独立 Cloud actor，不能将 Cloud UUID 当成本地用户 ID。

RRF 的 `pausing` 表示暂停过渡态，旧 PAUSED 枚举不能作为完成证据。远程命令只在同次新鲜协议状态真正为 `paused` 后确认；已经 `pausing` 时等待最终状态，不再发送 M25。普通 PREPARING、busy、resuming 等状态不能触发盲目暂停，也不能作为完成证据。

本地执行连续性保留完整规范化文件路径，同名不同目录会建立新执行身份，公开上报仍仅含基本文件名。同一完整路径重打而未观察到结束、计数回退或重启时，现有协议不能绝对辨认新执行；执行 UUID 代表已观察到的会话，不能据此宣称固件提供了不可混淆的任务标识。无法确认时拒绝远程动作，后续需结合固件事件或原生任务身份提升证据。

当前支持暂停及下述独立授权的单通道目标温度设置，不提供远程恢复、取消、启动、运动或任意 G-code。本地现有权限和控制接口保持兼容。开发验证使用临时数据库及协议夹具，不对真实打印任务发送控制命令。

## C3 温控：独立授权与单通道目标值

`GET /api/v1/cloud/remote-control` 返回 `{enabled,temperatureEnabled,temperatureSupported:true,revision}`。`enabled` 仅授权暂停；`temperatureEnabled` 默认 false，旧配置升级后保持 false。`PUT` 接受可选的两项 Boolean 和 revision，至少提供一项，省略的项目保持原值。绑定变化时两项都关闭。启停温控写入独立本地审计，授权支持表示当前 Server 软件能力，不表示 Cloud 或现场当前必然满足执行条件。

网关策略额外发送 `temperatureEnabled` 和 `temperatureControlVersion:1`。旧 Cloud 拒绝新字段时，只有温控关闭才退回原策略格式，当前连接不反复试探 400；重连或管理员配置变化后重新探测。温控开启时不能退回并声称成功。C2 上报线程继续独立运行。

`SET_HEATER_TARGET` 领取使用 schemaVersion 2，新增 heaterId、整数 targetC、数值 expectedTargetC、expectedDeviceBootId、expectedDescriptorRevision 和 expectedOperationState。旧 PAUSE 保持 schemaVersion 1 且不增加新字段。温控只允许 IDLE、PRINTING、PAUSED，IDLE 要求 expectedExecutionId 为 null 且现场也没有 ACTIVE 执行，打印或暂停时必须匹配本地执行身份。设备 boot、描述版本、完整本地文件路径、通道映射或有效目标值变化时拒绝陈旧指令。

Klipper 只支持 extruder、extruderN、heater_bed 的固定 `SET_HEATER_TEMPERATURE`；按动作专门查询 configfile.settings 中的 min_temp/max_temp，不在常规轮询中拉取大配置。RRF 从 heat.heaters 的 min/max 及可靠的床/工具映射解析，兼容旧版扁平 bedHeaters 与新版二维 bedHeaterMapping。读取可展示同床多成员，写入仅允许唯一单加热器槽，拒绝多成员、同加热器跨槽或与工具共享；使用固定 M568 或 M140，禁止切工具、共享加热器、多加热器工具、UNKNOWN 和通用 generic 通道。beta 固件缺失床映射时不能沿用旧温度展示的猜测回退。

首版软件上限 TOOL 300°C、BED 120°C，并同时遵守固件实际范围；不覆盖或修改固件保护。未知有效上限时禁止非零目标；0°C 仅关闭所选通道，不使用全机关闭命令。发送前按 BigDecimal 数值精确比较 expectedTargetC，微小现场变化也需要刷新重试。目标已经精确相同才不发送动作；读回则允许同一通道 GOOD effectiveTarget 与请求值相差不超过 0.5°C。

RRF 外部目标 0°C 内部固定映射为床 `M140 P槽位 S-273.1` 或工具 `M568 P工具 S0 A0`；该负值是受控关闭常量，不接受用户负温度。0°C 的确认还要求 RRF 同通道模式 OFF、有效目标为 0；原目标虽为 0 但仍 ACTIVE 时仍需实际关闭，不能直接返回 ALREADY_AT_TARGET。

仍先持久化 EXECUTING 并取得 Cloud 回执再发送一次固定动作，之后只读确认，不自动重发物理命令。CONFIRMED/HEATER_TARGET_CONFIRMED 表示目标值生效，ALREADY_AT_TARGET 表示目标原已相同；两者都不代表实际温度已达到目标。超时、错误通道或不一致采样保留 UNKNOWN。审计记录 REMOTE_SET_HEATER_TARGET、设备、通道和目标数值，不记录协议原文或密钥。

## 验证边界

T7 读取迁移：协商到 `schemaVersion=3` 后，兼容快照的状态、进度、主工具/热床温度从持久统一模型派生，不再读取 Moonraker 状态缓存；无有效模型、过期、离线或停止监控时不发送当前测量。完整模型仍通过 `printer-state` 发布，离线/停监健康通过兼容快照及心跳传播。未协商到 V3 的 V1/V2 旧网关路径保留。旧 DTO 仍服务本地生产控制、任务核验及历史记录，不能据此宣称旧类型已全部移除。本轮没有升级安装服务或操作打印机。

2026-11-01 本机联调：常驻开发 Cloud 已从 V4 升至 V5，升级前数据库备份已在隔离 MySQL 中恢复验证；IDEA 开发 Server 已通过本机私有配置启用 V2。授权 API 当次返回 8 台设备的当前观测、16 个加热通道；已在连接的 Android 手机上查看在线设备的统一观测和温度。该记录只覆盖本机开发环境，不等同生产部署或所有设备/打印阶段验收。

后端 `mvnw test` 验证开关、权限、白名单、受保护文件及错误隔离；云仓库的 `scripts/c1-integration.py --backend-jar …` 运行真实现场 JAR、临时 SQLite、云 MySQL 和真实 EMQX TLS，设备状态使用模拟观测，不连接真实打印机。公网双现场、真实设备、Windows 安装包和实际运维凭证轮换仍须单独验收。
# 手机扫码配对（2026-11-05）

Client「设置 → 云端连接 → 手机扫码配对」生成十分钟二维码；手机「我的 → 扫码连接现场服务器」扫码，选择组织和农场并确认。Server 安全领取凭据后，仍由本地管理员核对归属、开启上报。未配对的 Server 保持独立运行。已有绑定不被扫码自动覆盖。

本地 ADMIN 接口为 `POST /api/v1/cloud/pairing/start`、`/poll`、`/cancel`，分别使用连接配置与 revision、pairingId 与 revision。临时材料保存在凭据目录旁的 `cloud-pairing.json`，完成后清除；不要将此文件提交或复制给手机。关闭弹窗停止轮询，可重新进入并生成以恢复有效会话；取消或过期后可重试。

Cloud 需先升级 V18，再升级 Server、Client 和 App。首次需要部署者填写真实 Cloud API、MQTT TLS 地址及自建 CA；不能使用其他电脑的回环地址。详见 [配对协议](../../fabmatrix-cloud/docs/decisions/0012-qr-gateway-pairing.md)。本次不改变现场打印控制与 30 秒状态过期规则。

## G-code 文件资源（2026-11-07）

新增双入口文件元数据与 PNG 采集。Cloud 升级至 V24 并协商文件能力后，Server 使用低优先级 HTTP 上传具体执行的文件资源，实时观测继续走 MQTT。采集、来源、限额、本地缓存与验证边界见 [G-code 文件信息与缩略图](GCODE_FILE_METADATA.md)。
