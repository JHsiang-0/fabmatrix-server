package com.example.farm.common.exception;

import com.example.farm.common.api.Result;
import com.example.farm.common.api.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

import javax.sql.DataSource;
import java.net.ConnectException;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 全局异常处理。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 生产环境通用提示
     */
    private static final String PROD_ERROR_MESSAGE = "服务器正在维护，请稍后再试";

    /**
     * 是否为开发环境
     */
    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

    /**
     * 判断是否为开发环境
     */
    private boolean isDev() {
        return "dev".equalsIgnoreCase(activeProfile);
    }

    /**
     * 根据环境返回错误消息
     * @param devMessage 开发环境消息
     * @return 格式化后的消息
     */
    private String getEnvironmentMessage(String devMessage) {
        if (isDev()) {
            return devMessage;
        }
        return PROD_ERROR_MESSAGE;
    }

    /**
     * 处理业务异常
     */
    @ExceptionHandler(BusinessException.class)
    public Result<Object> handleBusinessException(BusinessException e) {
        log.warn("业务异常提示: {}", e.getMessage());
        return Result.failed(e.getMessage());
    }

    /**
     * 处理参数校验异常
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e, HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        String message = fieldErrors.entrySet()
                .stream()
                .map(entry -> entry.getKey() + ":" + entry.getValue())
                .collect(Collectors.joining("; "));

        log.warn("参数校验失败: method={}, uri={}, errors={}", request.getMethod(), request.getRequestURI(), fieldErrors);
        return Result.failed("参数校验失败: " + message);
    }

    /**
     * 处理请求方法不支持异常
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Object> handleMethodNotSupported(HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        String[] supportedMethods = e.getSupportedMethods();
        String supported = supportedMethods == null ? "N/A" : String.join(",", supportedMethods);
        log.warn("请求方法不支持: method={}, uri={}, supported={}, detail={}",
                e.getMethod(), request.getRequestURI(), supported, e.getMessage());
        return Result.failed("请求方法不支持，当前方法=" + e.getMethod() + "，支持方法=" + supported);
    }

    /**
     * 处理客户端中止连接异常（如用户刷新页面、关闭浏览器等）
     * 这是正常的网络行为，不需要记录为错误
     */
    @ExceptionHandler(ClientAbortException.class)
    public void handleClientAbortException(ClientAbortException e, HttpServletRequest request) {
        // 客户端主动断开连接，属于正常现象，仅记录 debug 日志
        if (log.isDebugEnabled()) {
            log.debug("客户端中止连接: uri={}, message={}", request.getRequestURI(), e.getMessage());
        }
        // 不需要返回任何内容，因为客户端已经断开
    }

    // ==================== 中间件异常处理 ====================

    /**
     * 处理 MySQL 连接异常 - CannotGetJdbcConnectionException
     * 通常由 Druid/HikariCP 连接池获取连接失败时抛出
     */
    @ExceptionHandler(org.springframework.dao.DataAccessResourceFailureException.class)
    public Result<Object> handleDataAccessResourceFailure(
            org.springframework.dao.DataAccessResourceFailureException e,
            HttpServletRequest request) {
        String devMessage = "MySQL连接异常: " + e.getMessage();
        String message = getEnvironmentMessage(devMessage);

        log.error("MySQL连接异常: uri={}, error={}", request.getRequestURI(), e.getMessage(), e);

        return Result.failed(ResultCode.MYSQL_ERROR.getCode(), message);
    }

    /**
     * 处理 MySQL 连接异常 - SQLNonTransientConnectionException
     * MySQL 服务器主动拒绝连接
     */
    @ExceptionHandler(SQLNonTransientConnectionException.class)
    public Result<Object> handleSQLNonTransientConnectionException(
            SQLNonTransientConnectionException e,
            HttpServletRequest request) {
        String devMessage = "MySQL连接被拒绝: " + e.getMessage();
        String message = getEnvironmentMessage(devMessage);

        log.error("MySQL连接被拒绝: uri={}, error={}", request.getRequestURI(), e.getMessage(), e);

        return Result.failed(ResultCode.MYSQL_ERROR.getCode(), message);
    }

    /**
     * 处理数据库查询超时异常
     */
    @ExceptionHandler(QueryTimeoutException.class)
    public Result<Object> handleQueryTimeoutException(QueryTimeoutException e, HttpServletRequest request) {
        String devMessage = "数据库查询超时: " + e.getMessage();
        String message = getEnvironmentMessage(devMessage);

        log.error("数据库查询超时: uri={}, error={}", request.getRequestURI(), e.getMessage(), e);

        return Result.failed(ResultCode.MYSQL_ERROR.getCode(), message);
    }

    /**
     * 处理通用 SQLException（未明确捕获的数据库异常）
     */
    @ExceptionHandler(SQLException.class)
    public Result<Object> handleSQLException(SQLException e, HttpServletRequest request) {
        String devMessage = "数据库异常: " + e.getMessage();
        String message = getEnvironmentMessage(devMessage);

        log.error("数据库异常: uri={}, error={}", request.getRequestURI(), e.getMessage(), e);

        return Result.failed(ResultCode.MYSQL_ERROR.getCode(), message);
    }

    /**
     * 处理 Redis 连接失败异常
     */
    @ExceptionHandler(RedisConnectionFailureException.class)
    public Result<Object> handleRedisConnectionFailureException(
            RedisConnectionFailureException e,
            HttpServletRequest request) {
        String devMessage = "Redis连接失败: " + e.getMessage();
        String message = getEnvironmentMessage(devMessage);

        log.error("Redis连接失败: uri={}, error={}", request.getRequestURI(), e.getMessage(), e);

        return Result.failed(ResultCode.REDIS_ERROR.getCode(), message);
    }

    /**
     * 处理网络连接异常 - java.net.ConnectException
     */
    @ExceptionHandler(ConnectException.class)
    public Result<Object> handleConnectException(ConnectException e, HttpServletRequest request) {
        String devMessage = "网络连接失败: " + e.getMessage();
        String message = getEnvironmentMessage(devMessage);

        log.error("网络连接失败: uri={}, error={}", request.getRequestURI(), e.getMessage(), e);

        return Result.failed(ResultCode.NETWORK_ERROR.getCode(), message);
    }

    /**
     * 处理资源访问异常 - ResourceAccessException
     * 通常用于 RestTemplate/Feign 访问外部服务（如 RustFS）超时或连接失败
     */
    @ExceptionHandler(ResourceAccessException.class)
    public Result<Object> handleResourceAccessException(ResourceAccessException e, HttpServletRequest request) {
        String devMessage = "外部服务访问异常: " + e.getMessage();
        String message = getEnvironmentMessage(devMessage);

        log.error("外部服务访问异常: uri={}, error={}", request.getRequestURI(), e.getMessage(), e);

        // 判断是否为存储服务超时（基于异常消息判断）
        String errorMsg = e.getMessage();
        if (errorMsg != null && (errorMsg.contains("timeout") || errorMsg.contains("Timeout"))) {
            return Result.failed(ResultCode.STORAGE_ERROR.getCode(), message);
        }

        return Result.failed(ResultCode.NETWORK_ERROR.getCode(), message);
    }

    /**
     * 处理未知异常
     */
    @ExceptionHandler(Exception.class)
    public Result<Object> handleException(Exception e, HttpServletRequest request) {
        // 统一处理未被明确捕获的异常，确保生产环境不泄露敏感信息
        log.error("系统内部异常: uri={}, error={}", request.getRequestURI(), e.getMessage(), e);

        String message = getEnvironmentMessage("系统内部异常: " + e.getMessage());
        return Result.failed(ResultCode.FAILED.getCode(), message);
    }
}