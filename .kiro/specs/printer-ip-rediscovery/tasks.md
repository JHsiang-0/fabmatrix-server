# 已登记打印机 IP 自动恢复任务

产品版本：`1.0.0` 准正式版维护增强；功能基线：v2 手动优先
更新时间：2026-10-08
前端归属：`farm-v1-release-readiness` R100-15

## T0 基线和边界

- [x] 核对现有 MAC Upsert、扫描、监控和设备协议入口；现有列表刷新不重新发现 IP。
- [x] 冻结只同步已有受监控设备、不导入未知设备、不改变任务绑定、不自动派单、无迁移的范围。

## T1 后端发现与安全身份

- [x] 增加私有 `/24` 范围、最多 8 网段、并发/总体超时/中断和只读协议身份发现；Stub 与超时/中断回归通过。
- [x] 核对当前地址的 MAC/协议，身份未知时不发送已存凭据；鉴权跨路由无法确认时保留人工处理边界。
- [x] 监控及上传/启动/控制动作入口逐次 `verifyFresh`，不复用成功缓存；无法确认时拒绝动作且保留任务绑定，无 MAC 旧记录保持兼容。
- [x] Moonraker 401/403 时匿名 `/access/info` 严格结构识别；RRF 无独立 sessionKey 的发现会话不 disconnect，避免影响已有正常操作会话。
- [x] 缺失 MAC、不支持协议、身份重复、协议不符和未知设备返回明确逐项结果且不写入。

## T2 原子地址同步

- [x] 设备锁、占用核对与当前 IP/MAC/协议/监控开关条件更新；支持事务内 IP 交换。
- [x] 只写 IP、通信 UNKNOWN 和时间，保护配置/业务/任务绑定；整批失败回滚，缓存提交后刷新。

## T3 管理员 API 和自动触发

- [x] ADMIN-only GET/POST 异步任务、进行中复用、最近快照与服务层权限；结果不包含凭据/MAC。
- [x] Local 默认开启，基础/dev/test 默认关闭；监控开启条件、10 秒首次/5 分钟周期与失败触发共用限频。
- [x] 停机清理后台任务和在途发现，Server Edition 使用 Redis 互斥；Windows 生成配置与源码保持一致。

## T4 前端和文档

- [x] 前端独立 ADMIN 同步抽屉、进度/范围/逐项结果、2 秒轮询、完成刷新、防重复和卸载清理，提交 `cb9bf60`。
- [x] 前端专项 9/9、全量 414/414、浏览器 2/2、lint/构建及完整发布门禁通过。
- [x] 同步后端 API、TODO、farm-v2、版本路线、运维与 Windows 配置说明。

## T5 验证与现场验收

- [x] 新增 9 类 IP 功能回归共 163 项通过；另扩展协议、设备锁与异常映射测试。本轮全量包含这些测试，未探测真实设备。
- [x] 后端完整 `.\mvnw.cmd test`：714 项，0 失败/错误/跳过；报告 `target/ip-rediscovery-final-tests.log`。
- [x] Windows 安装器契约：本轮已通过，命令 `powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File scripts/windows/test-installer-contract.ps1 -ClientRoot D:\WorkSpace\Vue\fabmatrix-client`。
- [ ] 新服务端/客户端安装包构建：待补录本轮产物路径、大小与 SHA-256。
- [ ] 真实覆盖安装：NOT RUN，不能继承历史验证结论。
- [ ] 厂内关机/重新分配 IP/开机后自动恢复、手动同步和名称/任务绑定保留：NOT RUN。
- [ ] 旧 IP 被其他设备占用、地址交换、鉴权/跨 VLAN 无 MAC、真实 MySQL 和长时间网络稳定性：NOT RUN。

本轮不执行数据库迁移、不修改生产数据、不操作设备物理动作；只有实际完成的验证才能标记完成。
