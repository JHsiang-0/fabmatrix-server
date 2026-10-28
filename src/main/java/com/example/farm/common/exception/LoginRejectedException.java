package com.example.farm.common.exception;

/**
 * 预期的凭据拒绝，不包含账号写入；允许提交同一事务中的失败审计。
 * 数据库异常、密码迁移失败等仍使用普通异常并回滚。
 */
public class LoginRejectedException extends BusinessException {
    public LoginRejectedException(long code, String message) {
        super(code, message);
    }
}
