package com.example.farm.common.exception;

import com.example.farm.common.api.Result;
import com.example.farm.common.api.ResultCode;
import com.example.farm.entity.vo.DashboardLayoutProblemVO;
import com.example.farm.mapper.DashboardLayoutMapper;
import com.example.farm.config.RequestTraceLogFilter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.ConnectException;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @Autowired(required = false)
    private DashboardLayoutMapper dashboardLayoutMapper;

    private static final String INTERNAL_ERROR_MESSAGE = "服务器内部错误，请稍后重试";
    private static final String SERVICE_UNAVAILABLE_MESSAGE = "依赖服务暂时不可用，请稍后重试";

    /**
     * 处理业务异常
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Object>> handleBusinessException(BusinessException e, HttpServletRequest request) {
        log.warn("业务异常提示: {}", e.getMessage());
        return failed(request, resolveHttpStatus(e.getCode()), e.getCode(), safeBusinessMessage(e));
    }

    public ResponseEntity<Result<Object>> handleBusinessException(BusinessException e) {
        return handleBusinessException(e, null);
    }

    @ExceptionHandler(DashboardLayoutProblemException.class)
    public ResponseEntity<Result<Object>> handleDashboardLayoutProblem(DashboardLayoutProblemException e,
                                                                         HttpServletRequest request) {
        log.warn("动态布局保存被拒绝: reason={}, code={}", e.getProblem().getReason(), e.getCode());
        return ResponseEntity.status(resolveHttpStatus(e.getCode()))
                .body(Result.<Object>failed(e.getCode(), safeBusinessMessage(e), e.getProblem())
                        .withTraceId(traceId(request)));
    }

    /**
     * 处理协议适配器异常，避免设备通信失败落入通用 500。
     */
    @ExceptionHandler(com.example.farm.protocol.PrinterProtocolException.class)
    public ResponseEntity<Result<Object>> handlePrinterProtocolException(
            com.example.farm.protocol.PrinterProtocolException e,
            HttpServletRequest request) {
        long code = switch (e.getCategory()) {
            case OFFLINE -> ResultCode.PRINTER_OFFLINE.getCode();
            case TIMEOUT, PROTOCOL_ERROR, UNKNOWN -> ResultCode.NETWORK_ERROR.getCode();
            case UNSUPPORTED -> ResultCode.PRINTER_PROTOCOL_UNSUPPORTED.getCode();
            case REJECTED -> ResultCode.VALIDATE_FAILED.getCode();
        };
        log.warn("打印机协议调用失败: uri={}, operation={}, protocol={}, category={}",
                request.getRequestURI(), e.getOperation(), e.getProtocolType(), e.getCategory());
        return failed(request, resolveHttpStatus(code), code, protocolMessage(e.getCategory()));
    }

    /**
     * 处理参数校验异常
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Object>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e,
            HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        log.warn("参数校验失败: method={}, uri={}, errors={}", request.getMethod(), request.getRequestURI(), fieldErrors);
        return failed(request, HttpStatus.BAD_REQUEST, ResultCode.VALIDATE_FAILED.getCode(), "参数校验失败",
                dashboardProblem(request, "INVALID_REQUEST"));
    }

    /** 处理方法参数校验（例如旧位置接口的集合约束）。 */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Result<Object>> handleHandlerMethodValidation(
            HandlerMethodValidationException e, HttpServletRequest request) {
        log.warn("方法参数校验失败: method={}, uri={}, detail={}",
                request.getMethod(), request.getRequestURI(), e.getMessage());
        return failed(request, HttpStatus.BAD_REQUEST, ResultCode.VALIDATE_FAILED.getCode(), "参数校验失败",
                dashboardProblem(request, "INVALID_REQUEST"));
    }

    /**
     * 处理请求方法不支持异常
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Object>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException e,
            HttpServletRequest request) {
        String[] supportedMethods = e.getSupportedMethods();
        String supported = supportedMethods == null ? "N/A" : String.join(",", supportedMethods);
        log.warn("请求方法不支持: method={}, uri={}, supported={}, detail={}",
                e.getMethod(), request.getRequestURI(), supported, e.getMessage());
        return failed(request, HttpStatus.METHOD_NOT_ALLOWED, HttpStatus.METHOD_NOT_ALLOWED.value(),
                "请求方法不支持");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Object>> handleNoResourceFound(NoResourceFoundException e,
                                                                  HttpServletRequest request) {
        log.warn("请求资源不存在: method={}, uri={}", request.getMethod(), request.getRequestURI());
        return failed(request, HttpStatus.NOT_FOUND, 404, "请求的资源不存在");
    }

    /**
     * 处理请求参数缺失、类型错误和 malformed JSON。
     */
    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MissingRequestHeaderException.class,
            MissingPathVariableException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            MaxUploadSizeExceededException.class
    })
    public ResponseEntity<Result<Object>> handleBadRequest(Exception e, HttpServletRequest request) {
        log.warn("请求参数无效: method={}, uri={}, detail={}",
                request.getMethod(), request.getRequestURI(), e.getMessage());
        return failed(request, HttpStatus.BAD_REQUEST, ResultCode.VALIDATE_FAILED.getCode(), "请求参数无效",
                dashboardProblem(request, "INVALID_REQUEST"));
    }

    /**
     * 处理数据库唯一键等约束冲突，避免前端收到无意义的 500。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Result<Object>> handleDataIntegrityViolation(
            DataIntegrityViolationException e,
            HttpServletRequest request) {
        log.warn("数据约束冲突: method={}, uri={}, detail={}",
                request.getMethod(), request.getRequestURI(), e.getMessage());
        String message = "目标资源发生并发冲突，请刷新后重试";
        if (isDashboardLayoutRequest(request)) {
            Long serverVersion = null;
            if (dashboardLayoutMapper != null) {
                try {
                    var layout = dashboardLayoutMapper.selectById(1L);
                    serverVersion = layout == null ? null : layout.getVersion();
                } catch (RuntimeException lookupException) {
                    log.warn("读取布局冲突版本失败");
                }
            }
            return failed(request, HttpStatus.CONFLICT, HttpStatus.CONFLICT.value(), message,
                    new DashboardLayoutProblemVO("SLOT_CONFLICT", serverVersion, null, null));
        }
        return failed(request, HttpStatus.CONFLICT, HttpStatus.CONFLICT.value(), message);
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
    public ResponseEntity<Result<Object>> handleDataAccessResourceFailure(
            org.springframework.dao.DataAccessResourceFailureException e,
            HttpServletRequest request) {
        log.error("MySQL连接异常: uri={}, error={}", request.getRequestURI(), e.getMessage(), e);

        return failed(request, HttpStatus.SERVICE_UNAVAILABLE, ResultCode.MYSQL_ERROR.getCode(), SERVICE_UNAVAILABLE_MESSAGE);
    }

    /**
     * 处理 MySQL 连接异常 - SQLNonTransientConnectionException
     * MySQL 服务器主动拒绝连接
     */
    @ExceptionHandler(SQLNonTransientConnectionException.class)
    public ResponseEntity<Result<Object>> handleSQLNonTransientConnectionException(
            SQLNonTransientConnectionException e,
            HttpServletRequest request) {
        log.error("MySQL连接被拒绝: uri={}, error={}", request.getRequestURI(), e.getMessage(), e);

        return failed(request, HttpStatus.SERVICE_UNAVAILABLE, ResultCode.MYSQL_ERROR.getCode(), SERVICE_UNAVAILABLE_MESSAGE);
    }

    /**
     * 处理数据库查询超时异常
     */
    @ExceptionHandler(QueryTimeoutException.class)
    public ResponseEntity<Result<Object>> handleQueryTimeoutException(QueryTimeoutException e, HttpServletRequest request) {
        log.error("数据库查询超时: uri={}, error={}", request.getRequestURI(), e.getMessage(), e);

        return failed(request, HttpStatus.SERVICE_UNAVAILABLE, ResultCode.MYSQL_ERROR.getCode(), SERVICE_UNAVAILABLE_MESSAGE);
    }

    /**
     * 处理通用 SQLException（未明确捕获的数据库异常）
     */
    @ExceptionHandler(SQLException.class)
    public ResponseEntity<Result<Object>> handleSQLException(SQLException e, HttpServletRequest request) {
        log.error("数据库异常: uri={}, error={}", request.getRequestURI(), e.getMessage(), e);

        return failed(request, HttpStatus.SERVICE_UNAVAILABLE, ResultCode.MYSQL_ERROR.getCode(), SERVICE_UNAVAILABLE_MESSAGE);
    }

    /** 未被更具体 handler 捕获的 MyBatis/MySQL 访问异常统一归为依赖不可用。 */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Result<Object>> handleDataAccessException(DataAccessException e, HttpServletRequest request) {
        log.error("数据库访问异常: uri={}, error={}", request.getRequestURI(), e.getMessage(), e);
        return failed(request, HttpStatus.SERVICE_UNAVAILABLE, ResultCode.MYSQL_ERROR.getCode(), SERVICE_UNAVAILABLE_MESSAGE);
    }

    /**
     * 处理 Redis 连接失败异常
     */
    @ExceptionHandler(RedisConnectionFailureException.class)
    public ResponseEntity<Result<Object>> handleRedisConnectionFailureException(
            RedisConnectionFailureException e,
            HttpServletRequest request) {
        log.error("Redis连接失败: uri={}, error={}", request.getRequestURI(), e.getMessage(), e);

        return failed(request, HttpStatus.SERVICE_UNAVAILABLE, ResultCode.REDIS_ERROR.getCode(), SERVICE_UNAVAILABLE_MESSAGE);
    }

    /**
     * 处理网络连接异常 - java.net.ConnectException
     */
    @ExceptionHandler(ConnectException.class)
    public ResponseEntity<Result<Object>> handleConnectException(ConnectException e, HttpServletRequest request) {
        log.error("网络连接失败: uri={}, error={}", request.getRequestURI(), e.getMessage(), e);

        return failed(request, HttpStatus.SERVICE_UNAVAILABLE, ResultCode.NETWORK_ERROR.getCode(), SERVICE_UNAVAILABLE_MESSAGE);
    }

    /**
     * 处理资源访问异常 - ResourceAccessException
     * 通常用于 RestTemplate/Feign 访问外部服务（如 RustFS）超时或连接失败
     */
    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<Result<Object>> handleResourceAccessException(ResourceAccessException e, HttpServletRequest request) {
        log.error("外部服务访问异常: uri={}, error={}", request.getRequestURI(), e.getMessage(), e);

        // 判断是否为存储服务超时（基于异常消息判断）
        String errorMsg = e.getMessage();
        if (errorMsg != null && (errorMsg.contains("timeout") || errorMsg.contains("Timeout"))) {
            return failed(request, HttpStatus.SERVICE_UNAVAILABLE, ResultCode.STORAGE_ERROR.getCode(), SERVICE_UNAVAILABLE_MESSAGE);
        }

        return failed(request, HttpStatus.SERVICE_UNAVAILABLE, ResultCode.NETWORK_ERROR.getCode(), SERVICE_UNAVAILABLE_MESSAGE);
    }

    /**
     * 处理 RustFS/S3 对象存储访问失败。
     */
    @ExceptionHandler(StorageException.class)
    public ResponseEntity<Result<Object>> handleStorageException(StorageException e,
                                                                   HttpServletRequest request) {
        log.error("对象存储服务异常: uri={}, error={}", request.getRequestURI(), e.getMessage(), e);
        return failed(request, HttpStatus.SERVICE_UNAVAILABLE, ResultCode.STORAGE_ERROR.getCode(), SERVICE_UNAVAILABLE_MESSAGE);
    }

    /**
     * 处理未知异常
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Object>> handleException(Exception e, HttpServletRequest request) {
        // 统一处理未被明确捕获的异常，确保生产环境不泄露敏感信息
        log.error("系统内部异常: uri={}, error={}", request.getRequestURI(), e.getMessage(), e);

        return failed(request, HttpStatus.INTERNAL_SERVER_ERROR, ResultCode.FAILED.getCode(), INTERNAL_ERROR_MESSAGE);
    }

    /**
     * 将业务错误码映射为 HTTP 状态码。
     * 业务码用于前端识别具体原因，HTTP 状态用于通用客户端和网关处理。
     */
    private HttpStatus resolveHttpStatus(long code) {
        return switch ((int) code) {
            case 400 -> HttpStatus.BAD_REQUEST;
            case 401 -> HttpStatus.UNAUTHORIZED;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            case 410 -> HttpStatus.GONE;
            case 409, 10002 -> HttpStatus.CONFLICT;
            case 10004 -> HttpStatus.NOT_FOUND;
            case 422 -> HttpStatus.UNPROCESSABLE_ENTITY;
            case 503, 10001, 5001, 5002, 5003, 5004 -> HttpStatus.SERVICE_UNAVAILABLE;
            case 10003 -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private ResponseEntity<Result<Object>> failed(HttpServletRequest request, HttpStatus status, long code, String message) {
        return ResponseEntity.status(status).body(Result.failed(code, message).withTraceId(traceId(request)));
    }

    private ResponseEntity<Result<Object>> failed(HttpServletRequest request, HttpStatus status, long code, String message, Object data) {
        return ResponseEntity.status(status).body(Result.failed(code, message, data).withTraceId(traceId(request)));
    }

    private String traceId(HttpServletRequest request) {
        if (request == null) return null;
        Object attribute = request.getAttribute(RequestTraceLogFilter.TRACE_ID_ATTRIBUTE);
        if (attribute instanceof String value && !value.isBlank()) return value;
        String header = request.getHeader(RequestTraceLogFilter.TRACE_ID_HEADER);
        return header == null || header.isBlank() ? null : header;
    }

    private String safeBusinessMessage(BusinessException exception) {
        String message = exception.getMessage();
        if (isUnsafeMessage(message)) return safeCodeMessage(exception.getCode());
        return message == null || message.isBlank() ? safeCodeMessage(exception.getCode()) : message;
    }

    private boolean isUnsafeMessage(String message) {
        if (message == null || message.length() > 256) return true;
        return message.contains("###")
                || message.matches("(?is).*\\b(sql|mybatis|jdbc|stack trace|connection refused)\\b.*")
                || message.matches("(?s).*([A-Za-z]:\\\\|/var/|/tmp/|/home/|org\\.|java\\.).*");
    }

    private String safeCodeMessage(long code) {
        for (ResultCode resultCode : ResultCode.values()) {
            if (resultCode.getCode() == code) return resultCode.getMessage();
        }
        return code >= 500 ? INTERNAL_ERROR_MESSAGE : "请求无法完成，请检查后重试";
    }

    private String protocolMessage(com.example.farm.protocol.FailureCategory category) {
        return switch (category) {
            case OFFLINE -> "打印机当前离线或不可用";
            case UNSUPPORTED -> "打印机协议不支持当前操作";
            case REJECTED -> "打印机拒绝了当前操作";
            case TIMEOUT, PROTOCOL_ERROR, UNKNOWN -> "打印机暂时无法响应，请稍后重试";
        };
    }

    private DashboardLayoutProblemVO dashboardProblem(HttpServletRequest request, String reason) {
        if (isDashboardLayoutRequest(request)) {
            return new DashboardLayoutProblemVO(reason, null, null, null);
        }
        return null;
    }

    private boolean isDashboardLayoutRequest(HttpServletRequest request) {
        return request != null && request.getRequestURI() != null
                && request.getRequestURI().endsWith("/dashboard-layout");
    }
}
