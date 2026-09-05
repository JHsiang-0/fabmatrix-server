package com.example.farm.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.example.farm.entity.AuditLog;
import com.example.farm.entity.dto.AuditLogQueryDTO;
import com.example.farm.entity.vo.AuditLogVO;

/** 审计日志写入与管理员查询服务。 */
public interface AuditLogService extends IService<AuditLog> {

    IPage<AuditLogVO> pageAuditLogs(AuditLogQueryDTO query);

    void recordSuccess(String action, String targetType, Object targetId, String targetLabel);

    void recordFailure(String action, String targetType, Object targetId, String targetLabel, String errorCode);

    /** 登录成功时尚未建立 SecurityContext，由服务端已校验的用户身份填充操作者快照。 */
    void recordLoginSuccess(Long actorId, String actorUsername, String actorRole);

    /** 登录失败时身份可能未知；仅保存用户名标签，不保存密码或认证材料。 */
    void recordLoginFailure(String actorUsername, Long targetId, String errorCode);
}
