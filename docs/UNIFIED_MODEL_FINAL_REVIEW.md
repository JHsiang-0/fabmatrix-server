# 统一设备模型最终审查

审查日期为 2026-11-04。本文件对应 Kiro `unified-device-model` 的 T6.5，完成状态以 [任务清单](../.kiro/specs/unified-device-model/tasks.md) 为准。

结论为本规格实现及记录范围内的验收完成，T0–T6 共 35 项完成。最终代码、隔离验证和现场部署已逐项核对；下文明确列出的未实测工况和后续产品功能保留边界。

## 交付与版本边界

交付范围包含完整只读模型的身份、描述、通道观测、执行、事件、告警实例与确认、遥测历史，以及 Server SQLite、Cloud MySQL 和 Flutter 的查询与展示链路。产品版本为 Server `1.0.0`，canonical 模型为 `modelVersion=2`，完整 MQTT 信封为 `schemaVersion=3`。既有 V1、受限 V2 和本地业务接口继续兼容。

| 版本 | 用途与证据 |
| --- | --- |
| Server `395e2b2` | 已安装的 Windows 服务，安装 JAR 与构建校验一致。两种真机打印、取消、换网恢复及手机展示已验证，Klipper 功率与取消补报修复已现场复验 |
| Server `469786d` | 用户已安装并核对 JAR。新增 RRF 功率、两个风扇及三轴采集已通过 Cloud 和 Android 空闲只读复验，文件位置、层数和独立时间字段另有自动回归 |
| Server `de6a41c` | 已安装最终构建，JAR 校验一致。补充真实 RRF 3.7 的二维热床映射，Cloud/Android 显示 heater-0=BED、heater-1=TOOL、两个风扇和三轴，857 项全量测试及安装器契约通过 |
| Cloud `995d71e` | 当前运行的业务代码基线。后续本地 main 的协作和审查文档提交保留相同业务代码，MySQL 迁移为 V16 |
| Flutter `f566cc8` | 已安装的 Android App，现场验证覆盖设备详情、在线/离线、旧值隐藏、执行结果与换网后的原设备关联 |
| Flutter `3e0c536` | 已保留数据安装 Android debug 包，空闲 RRF 的功率、风扇、轴和原取消结果已核对。文件字节位置、层数和独立时间的零值、旧响应、大整数及过期隐藏有单元/组件测试，新增执行字段未重复做真机打印 |

当前候选产物使用固定的 `dist/windows-installer` 和 Flutter `build/app/outputs/flutter-apk`，版本校验以产物中的 `release-info.txt`、`checksums.txt` 及本次交付结果为准。安装升级与回退步骤见 [发布说明](UNIFIED_MODEL_RELEASE.md)。

## 需求逐项审查

| 需求 | 实现与证据 | 验收边界 |
| --- | --- | --- |
| R1 契约和所有权 | canonical 类型、JSON Schema、Cloud DTO/VO 及白名单转换一致。任务、文件和本地用户归 Server，组织/农场与授权归 Cloud。产品、模型、协议版本分离 | 远程控制、视频、文件传输另立规格 |
| R2 稳定身份 | SQLite 来源 UUID、设备 UID 和 Cloud ID 映射持久化。迁移、重绑、克隆拒绝、本地 ID 复用、冲突审核有隔离覆盖，现场换 IP 与重绑保持 Cloud ID | 数据库克隆后的独立安装分叉没有用户流程，不能并行复用原身份 |
| R3 固件采集 | Klipper 按实际对象白名单读取，RRF 分组读取并复核执行身份。通道和测量带来源、质量、采样时间，真实零值不补成未知 | RRF 多请求帧为 PARTIAL。缺失对象维持未知，不能仅凭固件家族声称支持全部能力 |
| R4 执行和历史 | 执行 UUID、当前/上次结果、事务 outbox、提交 ACK、事件历史、告警实例与确认、遥测采样和保留已实现。两种真机取消及 RRF 重启后的未知结果有现场证据 | 自动告警规则未实现。遥测默认关闭。结果确认时刻与实际物理结束时刻可能不同 |
| R5 本地持久化和兼容 | SQLite 新旧库升级、重复运行、模型恢复和执行关系有测试。既有任务、锁、权限、LAN API、WebSocket 与本地文件运行保持原路径 | 单个数据库只允许一个 Server 进程。恢复使用停机快照，不能以重建库代替迁移 |
| R6 上报和云消费 | V3 协商、分片库存、版本/绑定/启动代次、ACK、事件去重和断网补报有真实 EMQX/MySQL 隔离覆盖，现场代理已启用 V3 | V3 默认关闭。完整库存未提交不移除设备，损坏单设备模型被隔离 |
| R7 手机和权限 | 组织/农场范围查询、完整详情、历史与旧接口兼容。覆盖登录切换、失效、旧值隐藏、小屏大字、断网重试及设备移除。Android 现场覆盖两种设备及网关重绑，当前手机包已更新 | 新增执行计数与时间字段有组件和隔离测试，未要求用户重复真实打印 |
| R8 迁移和验收 | 自动测试、SQLite/MySQL 升级和独立恢复、历史 JAR 回退、Windows 打包契约、实际安装与真实手机记录均有证据 | 历史回退只覆盖记录中的版本和快照。合成状态及压力结果不能推广为全部现场工况 |

## 当前字段覆盖

下表记录当前候选代码的实际映射。缺失字段使用 null、UNAVAILABLE 或 UNKNOWN；只有确定不适用或不支持时才使用对应质量。数值越界、非有限数及计数小数标记 INVALID。字段单位和 JSON 键以 [canonical 契约](../../fabmatrix-cloud/docs/protocol/schema/printer-observation-v2.schema.json) 为准。

| 字段组 | Klipper / Moonraker | RRF | 质量与验证 |
| --- | --- | --- | --- |
| 来源和设备身份 | 本地安装来源 UUID、设备 UID、本地数字 ID | 同左 | 独立于 IP，Server/Cloud 持久化及现场换网通过 |
| 名称、机台号、材料、喷嘴 | Server 配置 | Server 配置 | 可空，配置值不伪装成实时传感值 |
| 固件家族、接入方式 | KLIPPER / MOONRAKER | RRF / RRF_STANDALONE | 家族由适配器与配置确定。canonical 固件版本、机器模式、仿真资产标记仍可未知，未把现场另外查询的版本写成已上报 |
| 工具与加热器拓扑 | 实际 extruder 对象及 toolhead 当前工具 | tools 与 heater 关联 | 多通道合成覆盖，两种真机当前工具通过 |
| 温度和目标 | temperature / target | current / active / standby / state | ℃，每项有采集时间，OFF 目标 0 与缺失分开 |
| 功率 | heater.power | heat.heaters[].avgPwm | 0..1。Klipper 零值及 RRF 实际响应通过 Cloud/Android 核对 |
| 热床角色 | heater_bed | 优先二维 bedHeaterMapping，兼容旧一维映射及 bedHeaters | 按明确映射确定，不用数组位置猜角色；最终候选兼容真实 [[0]] 结构 |
| 风扇 | 已加载的 fan 对象 speed / rpm | 顶层 fans 的 actualValue / rpm | PWM 0..1，RPM 不从比例推算。RRF 需独立查询该顶层对象 |
| 轴和倍率 | gcode_move 与 toolhead | 顶层 move.axes | 坐标系和归零布尔值独立；RRF 未确认倍率保持未知 |
| 传感器 | 已加载的 filament sensor 对象 | 顶层 sensors.filamentMonitors | 布尔 false、未配置及未知分开。传感器种类受白名单限制 |
| 执行 ID 与来源 | Server 持久执行会话 | 同左 | 外部执行起始物理时刻未知时 startedAt 为空，firstObservedAt 单独保留 |
| 文件名和大小 | basename；virtual_sdcard.file_size | basename；job.file.size | 不上传私有路径，文件 SHA-256 未计算时为空 |
| 文件位置 | virtual_sdcard.file_position | job.filePosition | 字节整数，真实 0 保留，负数、小数和溢出拒绝。Flutter 使用 int 保留精度 |
| 百分比与来源 | display_status.progress，DEVICE_REPORTED | 文件位置/大小，FILE_OFFSET | 保留当前选择策略。真机 Klipper 显示进度为 0 而文件进度约 0.11%，两者口径不同，不从 100% 推断完成 |
| 层数 | print_stats.info.current_layer / total_layer | job.layer / job.file.numLayers | 可用时采集并声明能力，未上报为 UNAVAILABLE，总层数 0 视为未知，不按 Z 推算 |
| 打印与总时长 | print_stats.print_duration / total_duration | 无相同口径时保留未知 | 秒，各字段不互作同义回填 |
| 活动时长与切片总预估 | 无明确来源时未知 | job.duration / job.file.printTime | 运行事实和切片估算分开 |
| 热身与暂停时长 | 没有独立来源时未知 | job.warmUpDuration / pauseDuration | 秒，独立解析，不从其他时长相减猜算 |
| 剩余估算 | 当前没有可靠来源时未知 | timesLeft.file，缺失时 timesLeft.slicer | 保留 FILE / SLICER 方法与来源，confidence 未提供时为空，RRF 切片估算现场通过 |
| 耗材 | print_stats.filament_used | job.rawExtrusion | mm，真实 0 保留，缺失或非法保持质量标记 |
| 当前/上次执行与事件 | 统一会话、设备明确终态 | 统一会话、取消/中止标记 | 明确取消为 DEVICE_EXPLICIT，单纯回到空闲不能当作成功。两种真机已验证 |
| 告警与遥测 | Cloud 实例、确认及受控采样 | 同左 | 告警自动触发规则尚未实现，隔离验收使用合成告警。采样关闭时无伪造历史 |

字段来源复核使用 [Klipper Status Reference](https://www.klipper3d.org/Status_Reference.html) 和 [RRF Object Model](https://github.com/Duet3D/RepRapFirmware/wiki/Object-Model-Documentation)。RRF 的 move、fans、sensors 由三个额外只读请求获取；通道不支持时退化为部分帧，最后仍复读 state/job 检查执行是否变化。新增请求的整场 RRF 延迟和长期负载尚无现场数据。

## 发布与现场限制

两种真机已覆盖空闲、打印、明确取消、换 IP 和手机只读展示。RRF 还覆盖暂停后关机、重新开机及未知结果保存。自然完成、连续暂停/恢复、真实故障和模拟等全部场景没有逐项做现场动作，其合成测试不能替代现场结果。

资源验证覆盖 200 台合成库存、16384 B/s 应用消息预算、MQTT 双向各 150 毫秒延迟、Cloud HTTP/Broker 中断以及本地业务继续可用。不包含随机丢包、互联网部署、长时间运行或 200 台真实 RRF 的采集负载。Cloud 与手机本次仍通过本机开发环境和 USB 转发连接。

遥测采样和保留策略须由管理员明确配置。自动告警规则、远程控制、媒体、跨农场自动迁移以及固件硬件身份或证书绑定均需要后续独立工作。本轮终态修复与只读采集不扩大物理控制授权。

## 构建和复现

| 检查 | 结果与范围 |
| --- | --- |
| Server `pwsh -File scripts/check-code.ps1` | 857 项通过，含 SQLite 新旧库、状态生命周期、协议、身份与安全回归 |
| Cloud Schema 自检 | 5 个正例和 24 个语义/结构反例通过 |
| Flutter `pwsh -File tool/check.ps1 -Mode Code` | 文档链接、静态分析及 81 项测试通过，隔离专用测试另行运行 |
| 跨三端 C1/C2 | 最终 `de6a41c` JAR 与新版 Flutter 通过 44 项检查，包含新增文件计数/层数/热身时间查询断言、真实 MySQL/EMQX、200 台合成库存、限速、中断及历史 JAR 回退 |
| Windows 安装器 | 固定输出目录、打包后独立启动/健康、托盘及维护脚本契约通过 |
| Android 构建 | `flutter build apk --debug --no-pub --android-project-arg=kotlin.incremental=false` 成功。沿用项目已有跨磁盘 Kotlin 缓存规避参数 |

最终 Server 包位于 `dist/windows-installer/FabMatrix-Server-Setup-1.0.0.exe`，构建 `de6a41c`，SHA-256 为 `C81767B7F932AE79C9A26A478F27FC1FD865FA6ADE98BA15CA4617C72D173A97`。对应 JAR 为 `D40A4F829EC04B176CA9E8A86682B2A0CBDFB77FCB2221E130F065EFBC083CD7`。

Android `3e0c536` debug APK 位于 Flutter `build/app/outputs/flutter-apk/app-debug.apk`，SHA-256 为 `1004FD3A282CE40631B34E2F4D0C99DF95D99D0A7FDC40F57740A81167ED6C0A`，已安装。

验证过程中多次临时登录占满自动化账号的 20 个会话。只对该自动化账号最早会话做过期处理以恢复登录，随后通过账号会话 API 撤销其余临时会话，最后退出本次查询会话，复核剩余自动化会话为 0。后续查询均使用 finally 退出；手机使用独立账号。

当前现场运行 Server `de6a41c` 和 Flutter `3e0c536`，安装 JAR 校验一致，空闲状态只读复验通过，两台设备稳定 Cloud ID 保持。Windows 升级由用户手动执行，手机保留数据安装由 ADB 完成。最后一轮只核对空闲采集，新增执行计数/时间字段的真机打印仍未重测，其端到端证据来自 44 项隔离检查。
