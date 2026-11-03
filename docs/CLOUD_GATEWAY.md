# 可选云网关（C2，只读）

默认关闭，不改变局域网登录、监控、手动派单和现场安全规则。云服务是同级 fabmatrix-cloud，必须先按其 C1/C2 文档准备独立数据库、HTTPS、MQTT TLS 和组织/农场；本轮不改已安装 Windows 服务。

## 配置

| 环境变量 | 说明 |
|---|---|
| FARM_CLOUD_ENABLED | 默认 false，配置完成后显式 true |
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

凭证默认 30 天，尚无自动续期。重绑定/吊销后旧凭证不可再用；重新配置需管理员签发新票据，在停止代理后归档旧凭证、指定新凭证路径并重启服务，不能只替换票据而继续保留旧凭证文件。不得把完整文件、Token 或 CA 私钥贴到日志/工单。

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

2026-11-01 本机联调：常驻开发 Cloud 已从 V4 升至 V5，升级前数据库备份已在隔离 MySQL 中恢复验证；IDEA 开发 Server 已通过本机私有配置启用 V2。授权 API 当次返回 8 台设备的当前观测、16 个加热通道；已在连接的 Android 手机上查看在线设备的统一观测和温度。该记录只覆盖本机开发环境，不等同生产部署或所有设备/打印阶段验收。

后端 `mvnw test` 验证开关、权限、白名单、受保护文件及错误隔离；云仓库的 `scripts/c1-integration.py --backend-jar …` 运行真实现场 JAR、临时 SQLite、云 MySQL 和真实 EMQX TLS，设备状态使用模拟观测，不连接真实打印机。公网双现场、真实设备、Windows 安装包和实际运维凭证轮换仍须单独验收。
