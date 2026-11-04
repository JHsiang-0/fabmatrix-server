# 统一设备模型验收记录

更新时间：2026-11-04。本文记录当前证据及缺口，不等同于现场或发布验收通过。任务状态以 [Kiro 清单](../.kiro/specs/unified-device-model/tasks.md) 为准，升级和回退见 [发布说明](UNIFIED_MODEL_RELEASE.md)。

## 字段与链路

| 范围 | 已验证 | 尚需确认 |
| --- | --- | --- |
| 来源、设备身份和绑定 | SQLite 启动升级、库存增删、网关重启、旧 ID 和绑定隔离有隔离测试；Cloud 设备 ID 保持稳定 | 现场数据库恢复、克隆与实际网关迁移按目标环境验收 |
| Klipper、RRF 采集 | 合成响应覆盖状态、加热器、工具、风扇、轴、传感器、0 与缺失；两种固件真机空闲状态、工具和温度及 RRF 打印状态经只读接口、Cloud 和手机核对 | Klipper 真机打印过程及其他字段逐项核对；未能证明的字段继续为未知 |
| 当前观测和执行 | Server SQLite → V3 → Cloud MySQL → Flutter 测试客户端覆盖多通道、质量、当前执行和文件摘要；RRF 真机打印时 Cloud 和手机显示打印中及进度 | 真机终态及暂停、断电恢复的完整过程仍需核对；不下发物理动作 |
| 事件、告警与遥测 | 事件 outbox、断网补报、重复去重、温度采样、查询及 Flutter 界面有隔离覆盖；告警查询和确认使用合成记录 | 告警自动规则尚未实现；真实设备事件与采样可用性待确认 |
| 客户端时效和权限 | 旧协议兼容、组织/农场权限、离线与过期隐藏有自动测试；Android 真机覆盖网关重绑、设备离线恢复、虚拟设备删除和手机断网重试 | 实际账号权限变化及全部设备操作状态待验收 |

## 兼容与资源边界

- 产品版本 `1.0.0`、模型 `modelVersion=2`、上报协议 V1 / 受限 V2 / 完整 V3 是不同版本。V3 显式开启，默认关闭；网关只读，不下发设备动作。
- 网关最多 200 台设备、单条消息最多 64 KiB；云代理单台本地模型读取上限 128 KiB，事件 outbox 和上报速率有界。损坏模型只隔离对应设备，存储故障仍向上报告。
- 200 台合成库存、16384 字节/秒应用消息预算和 MQTT 双向每次转发 150 毫秒的隔离测试通过；一次采样 Server RSS 约 380 MiB，包含 JVM。Broker 与 Cloud HTTP 中断期间验证了本地设备列表读取和私人文件夹创建，恢复后事件补报。此结果不涵盖随机丢包、长时间运行或全部现场业务。
- 历史 Cloud `66a94ea` 与 Server `2729a15` 在各自恢复的合成数据库上启动并读取数据；不表示任意旧程序可直接使用升级后的库，也不等于目标环境恢复演练通过。

## 构建与待办

- Server 全量测试 848 项、Cloud 全量 `verify` 74 项、Flutter 常规检查 78 项通过；跨 Server JAR、Cloud MySQL/EMQX、Flutter 的隔离 C1/C2 此前 44 项通过。测试结果及提交以各仓库和 Kiro 清单为准。
- 2026-11-04 已授权部署：Cloud 从 V5 升级到 V16 前，在独立 MySQL 中恢复快照并试升级，业务记录数量保持；Windows 安装版经 SQLite 停机快照和副本启动验证后升级，持久配置启用 V3，原网关先成功上报空库存；PJE110 保留数据安装 Android debug APK，并验证 Cloud 登录会话、模拟设备离线列表及详情。
- 随后用户提供并授权接入各一台 RRF 和 Klipper 真机：RRF 只读接口返回 RepRapFirmware 3.7.0、空闲及两个加热器；Klipper 返回 ready/standby 及喷嘴和热床温度。两者经 Server V3 上报到 Cloud，PJE110 显示在线、空闲及相符温度。现场修复并复验了 Klipper 当前挤出机映射；两台设备采集质量均为 PARTIAL，未做物理控制。
- 网关中断验收：两台设备空闲时短暂停止 Windows Server，手机列表显示网关离线、状态未知和带采集时间的上次上报状态；服务恢复后两台设备重新显示在线、空闲。该检查未断开打印机或手机与 Cloud 的连接。
- 经用户明确授权重绑现场网关，绑定版本由 2 升至 3；新凭据生效后，Cloud 库存和手机列表恢复，两台真机的 Cloud ID 与重绑前一致。重绑会使旧凭据失效，受保护备份不能作为有效凭据直接回退。
- 用户授权的虚拟设备已添加并删除，手机详情显示设备不在库存，现场两台真机保留；手机断开 Cloud 并恢复后可在机器页重试恢复登录。RRF 经用户关机后，手机显示设备离线并隐藏过期温度；重新开机后恢复在线。
- 用户启动 RRF 真机打印后，只读核对设备、Cloud 和手机：Cloud 显示打印中、文件位置计算的进度，手机显示打印状态、进度及加热数据，模型质量为 PARTIAL。设备提供的切片器剩余时间此前未完整传递，Server `2dfc878` 已修复，847 项全量测试通过。用户开机后重新启动打印，真机 processing、文件位置 18,263/40,691,069 字节、timesLeft.slicer=79,186 秒；Cloud 为 PRINTING、进度 0.04%（FILE_OFFSET），estimatedRemaining 为 SLICER/GOOD/SLICER_ESTIMATE、79,186 秒，手机详情显示相同剩余时间及热床/工具目标 60℃/230℃。该轮处于加热阶段，当前执行获得新的 UUID，上一轮 UNKNOWN/UNCONFIRMED 结果保留。剩余时间修复现场复验通过。
- 用户确认先暂停再关机；设备无法连接后，Server 显示离线，最后协议状态为 paused，Cloud 显示离线并将旧观测标记为过期。随后用户重新开机，RRF 原生状态为 idle、uptime 约 125 秒且无活动任务，Server、Cloud 和手机恢复在线空闲，观测重新有效。原执行 ID 保持，currentExecution=null，lastExecutionResult 为 UNKNOWN/UNCONFIRMED、原因 TERMINAL_UNCONFIRMED，手机显示相同结果。Cloud 历史包含同一执行的开始、暂停和 EXECUTION_UNKNOWN 结束事件，以及设备离线和重连事件。本次验证了暂停后关机再开机的未知结果路径，正常完成或明确取消路径仍待验证。
- 用户重新安装 Server 后，只读核对安装目录中的 JAR 与本地 `2dfc878` 构建的 SHA-256 一致，Windows 服务运行、网关 CONNECTED，Cloud 保持两台设备原 ID。Klipper 持续 ONLINE/IDLE 且观测有效，RRF OFFLINE 且旧观测过期。完整模型仍通过 V3 上报。
- 当前安装包固定为 `dist/windows-installer/FabMatrix-Server-Setup-1.0.0.exe`，构建提交 `a8af4c1`，SHA-256 `6D24E3D16059C93E1C821E90933A4423DD5D0CE4BC9DE920C7FC0C734002478F`，新增 Klipper 加热器功率映射，848 项全量测试及安装器契约检查通过。该功率修复包尚未安装，现场仍运行已核对的 `2dfc878`。旧任务构建目录已由用户删除，后续构建仅使用固定目录。Cloud 已收拢到原始工作区并将功能快进合入本地 main。
- 安装后继续只读核对 Klipper 的 Moonraker 与手机：当前工具 extruder、热床和工具目标温度 0℃、两个风扇比例 0、四轴位置及未归零状态相符；温度按各自采样时刻变化。Moonraker 返回加热器 power=0，而该安装版 canonical 显示功率未知，发现采集与映射遗漏，修复后仍需现场部署复验。手机已切换到两台真机所属测试农场。
- 本次用原始 Cloud 工作区与 `2dfc878` Server JAR 重跑跨三端隔离检查，包含 Flutter、200 台模拟库存、16384 B/s 限速、双向各 150 毫秒 MQTT 延迟、Cloud HTTP/Broker 中断、重连及稳定身份，结果 PASS。此结果对应功率修复之前的构建。
- 功率修复后使用 `a8af4c1` 新 JAR 再跑同一跨三端隔离场景，42 项检查通过，隔离资源清理通过。此轮未包含历史 JAR 回退参数，检查数量与此前 44 项场景不同；真实功率显示仍需安装后核对。
- 用户在设备端取消本轮 RRF 打印并将其切换网段，随后提供新地址。只读接口返回 idle、lastFileCancelled=true、lastFileAborted=false；通过管理员更新接口修改现有设备的 IP，保留名称、MAC、耗材、喷嘴和编号，监控恢复在线空闲。Cloud 原设备 ID 和稳定 UID 保持，当前执行清空，原执行结果为 CANCELLED/DEVICE_EXPLICIT、原因 DEVICE_REPORTED_CANCELLED，并收到 EXECUTION_CANCELLED 结束事件；手机分别刷新事件和执行页后显示相同取消结果，上一轮 UNKNOWN 结果仍保留。电脑同时更换网段后，Klipper 旧地址不可达，仍待恢复连接。
- 用户提供 Klipper 新地址后，只读核对 Moonraker 返回 ready/standby、当前工具 extruder；Server 原记录已使用该新地址且 ONLINE/IDLE，本次没有重复更新。Cloud 和 Android 列表显示在线空闲、有效观测，本地 ID、稳定 UID 及 Cloud ID 均保持原值。两台真机均恢复连接，设备名称仍保留原有名称。
- 待完成：Klipper 真机打印、功率修复安装复验、字段覆盖及最终证据汇总；RRF 自然完成路径尚未实测，自动告警规则尚未实现。真实库修改、服务重启或设备动作须按目标与影响范围确认。
