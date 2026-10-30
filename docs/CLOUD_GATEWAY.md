# 可选云网关（C2，只读）

默认关闭，不改变局域网登录、监控、手动派单和现场安全规则。云服务是同级 fabmatrix-cloud，必须先按其 C1/C2 文档准备独立数据库、HTTPS、MQTT TLS 和组织/农场；本轮不改已安装 Windows 服务。

## 配置

| 环境变量 | 说明 |
|---|---|
| FARM_CLOUD_ENABLED | 默认 false，配置完成后显式 true |
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

代理自己的后台线程捕获已有 PrinterService/缓存数据，不直接调用打印机，不订阅命令。每 5 秒采样，变化或 30 秒发完整快照，约 15 秒心跳。最多 200 台、单包 64 KiB；超限报错且不截断上传。只上报设备 ID/名称/固件/状态/监控开关/观测时间/温度/进度，不上传 IP/MAC/API Key、账号、文件或任务内容。

pending 文件只有一个，云端事务提交回执才删除；5 秒重发，60 秒后合并为当前状态。启动用新 bootId 登记云 epoch，丢弃上个进程待发快照；断网保留最新状态，不提供历史事件队列。网络失败 1–30 秒退避且不阻塞局域网线程，配置错误也不阻止本地服务启动。旧 boot 被取代显示 SUPERSEDED，需排查重复运行的网关实例。

## 验证边界

后端 `mvnw test` 验证开关、权限、白名单、受保护文件及错误隔离；云仓库的 `scripts/c1-integration.py --backend-jar …` 运行真实现场 JAR、临时 SQLite、云 MySQL 和真实 EMQX TLS，设备状态使用模拟观测，不连接真实打印机。公网双现场、真实设备、Windows 安装包和实际运维凭证轮换仍须单独验收。
