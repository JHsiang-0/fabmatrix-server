# 可选云网关（C2，只读）

默认关闭。云服务是同级 fabmatrix-cloud，接入前按其 C1/C2 文档准备独立数据库、HTTPS、MQTT TLS 和组织/农场。

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

管理员可调用 `GET /api/v1/cloud/status` 查看 DISABLED/STARTING/SYNCHRONIZING/CONNECTED/RETRYING/SUPERSEDED、最后提交回执和脱敏错误码，其他角色拒绝。连接正常不代表打印机在线，云端仍按独立观察时间标过期。

代理自己的后台线程读取现场已有设备数据，不直接调用打印机，不订阅命令。V1/V2 快照每 5 秒检查变化、至少每 30 秒发送一次，约 15 秒发送心跳。最多 200 台、单包 64 KiB；超限报错且不截断上传。V1/V2 仅上报设备状态白名单，不上传 IP/MAC/API Key、账号或文件内容。

V2 可选上报统一观测的采集时间、质量、加热器、工具和不含文件名的执行摘要，仍使用完整快照与 64 KiB 限额。网关会协商协议版本；旧 Cloud 不支持协商时回退到配置的 V1/V2。

V3 选项启用后，代理才会协商 V3；已确认的设备描述和新鲜观测通过独立消息上报。观测只在监控开启、设备在线且采样不超过 30 秒时发送，携带设备通道、当前执行和上次执行结果。执行文件只带现场模型中的安全文件名摘要，不发送文件内容或本地任务 ID。库存和描述需先经 Cloud 确认，消息按单包 64 KiB 限制。安装版启用前先升级 Cloud、完成数据库迁移并验证兼容性。

跨 Server、Cloud 和客户端的升级顺序、快照范围及回退边界见 [统一模型升级与回退](UNIFIED_MODEL_RELEASE.md)。

云代理读取本地模型时，每台设备的 SQLite JSON 上限为 128 KiB；超限或已存模型无效时，该设备的模型暂不上报并输出限频警告，不影响其他设备。超限不影响本地详情读取；无效的本地模型仍需排查。单包 64 KiB 限制独立生效。

V3 每 5 秒核对设备库存；新增、删除或身份变化时先提交完整库存，确认后再更新状态。库存版本保存在 `cloud-agent/cloud-inventory-revision.txt`，重启后继续递增；状态目录应随现场数据库一起保留。

pending 文件同一时刻只保存一个待确认消息，5 秒重发；快照/观测过期后重新捕获当前状态。启动用新 bootId 登记云 epoch，丢弃上个进程待发快照。V3 事件保存在 SQLite outbox，收到 Cloud 事务提交或去重回执后才移除；未确认事件跨重启保留，失败记录每 30 秒可重新尝试。网络失败 1–30 秒退避且不阻塞局域网线程，配置错误也不阻止本地服务启动。旧 boot 被取代显示 SUPERSEDED，需排查重复运行的网关实例。

## 验证边界

T7 读取迁移：协商到 `schemaVersion=3` 后，兼容快照的状态、进度、主工具/热床温度从持久统一模型派生，不再读取 Moonraker 状态缓存；无有效模型、过期、离线或停止监控时不发送当前测量。完整模型仍通过 `printer-state` 发布，离线/停监健康通过兼容快照及心跳传播。未协商到 V3 的 V1/V2 旧网关路径保留。旧 DTO 仍服务本地生产控制、任务核验及历史记录，不能据此宣称旧类型已全部移除。本轮没有升级安装服务或操作打印机。

2026-11-01 本机联调：常驻开发 Cloud 已从 V4 升至 V5，升级前数据库备份已在隔离 MySQL 中恢复验证；IDEA 开发 Server 已通过本机私有配置启用 V2。授权 API 当次返回 8 台设备的当前观测、16 个加热通道；已在连接的 Android 手机上查看在线设备的统一观测和温度。该记录只覆盖本机开发环境，不等同生产部署或所有设备/打印阶段验收。

后端 `mvnw test` 验证开关、权限、白名单、受保护文件及错误隔离；云仓库的 `scripts/c1-integration.py --backend-jar …` 运行真实现场 JAR、临时 SQLite、云 MySQL 和真实 EMQX TLS，设备状态使用模拟观测，不连接真实打印机。公网双现场、真实设备、Windows 安装包和实际运维凭证轮换仍须单独验收。
