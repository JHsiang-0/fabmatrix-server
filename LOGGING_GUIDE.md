# Farm 项目日志规范

## 1. 日志级别使用规范

| 级别 | 使用场景 | 示例 |
|------|----------|------|
| **ERROR** | 系统错误、异常、需要立即处理的问题 | 数据库连接失败、关键业务异常 |
| **WARN** | 警告、潜在问题、不符合预期的情况 | 参数校验失败、资源不足 |
| **INFO** | 重要业务操作、系统状态变化 | 用户登录、任务创建、状态变更 |
| **DEBUG** | 调试信息、详细执行过程 | 方法入参、执行时间、缓存命中 |

## 2. 日志格式规范

### 2.1 基本格式
```
[模块] 操作描述: key1=value1, key2=value2
```

### 2.2 常用模块标识
- `[STARTUP]` - 系统启动
- `[SHUTDOWN]` - 系统关闭
- `[MONITOR]` - 监控任务
- `[SCHEDULER]` - 调度任务
- `[SECURITY]` - 安全相关
- `[CACHE]` - 缓存操作
- `[DB]` - 数据库操作
- `[EXTERNAL]` - 外部调用
- `[LOCK]` - 分布式锁
- `[DATA]` - 数据变更

## 3. 日志工具类使用

### 3.1 业务操作日志
```java
// 记录业务操作
LogUtil.bizInfo("JOB_CREATE", "fileId", fileId, "userId", userId);

// 记录数据变更
LogUtil.dataChange("STATUS_CHANGE", "FarmPrinter", printerId, "OFFLINE -> IDLE");

// 记录慢操作
LogUtil.slowOperation("fetchPrinterStatus", durationMs, 5000);
```

### 3.2 安全日志
```java
// 登录成功
LogUtil.loginSuccess(username, ip);

// 登录失败
LogUtil.loginFailed(username, ip, "密码错误");

// 权限拒绝
LogUtil.accessDenied(userId, resource, requiredRole);
```

### 3.3 外部调用日志
```java
// 调用成功
LogUtil.externalCallSuccess("Moonraker", "getStatus", durationMs);

// 调用失败
LogUtil.externalCallFailed("Moonraker", "upload", errorMsg, durationMs);
```

## 4. 禁止的日志写法

### ❌ 不要使用的写法
```java
// 1. 使用 emoji
log.info("🎉 手动派单成功！");

// 2. 冗余的日志
log.info("✅ Redis SET success: key={}, ttl={} {}", key, timeout, unit);
log.info("✅ Redis GET hit: key={}", key);

// 3. 无意义的日志
log.debug("进入方法");
log.debug("离开方法");

// 4. 敏感信息未脱敏
log.info("用户登录: username={}, password={}", username, password);
```

### ✅ 推荐的写法
```java
// 1. 使用标准格式
log.info("[JOB] Assign success: jobId={}, printerId={}", jobId, printerId);

// 2. 只记录关键信息
log.debug("[CACHE] Hit: key={}", key);

// 3. 使用 LogUtil 工具类
LogUtil.bizInfo("JOB_ASSIGN", "jobId", jobId, "printerId", printerId);

// 4. 敏感信息脱敏
LogUtil.loginSuccess(username, ip); // 内部自动处理
```

## 5. 日志配置文件

日志配置文件位于 `src/main/resources/logback-spring.xml`，支持以下环境：

- **dev** - 开发环境：DEBUG 级别，输出到控制台
- **test** - 测试环境：DEBUG 级别
- **prod** - 生产环境：INFO 级别，输出到文件

### 日志文件位置
- 应用日志：`./logs/farm.log`
- 错误日志：`./logs/farm-error.log`
- 访问日志：`./logs/farm-access.log`

### 日志保留策略
- 单个文件最大：100MB
- 保留天数：30天
- 总大小限制：10GB

## 6. 性能考虑

1. **DEBUG 日志使用占位符**
   ```java
   // 正确
   log.debug("Processing {}", id);
   
   // 错误（会执行字符串拼接）
   log.debug("Processing " + id);
   ```

2. **避免在循环中记录大量日志**
   ```java
   // 错误
   for (Printer p : printers) {
       log.info("Processing printer: {}", p.getId());
   }
   
   // 正确
   log.info("Processing {} printers", printers.size());
   ```

3. **使用 isDebugEnabled()**
   ```java
   if (log.isDebugEnabled()) {
       log.debug("Complex object: {}", expensiveToString());
   }
   ```

## 7. 监控告警

以下日志需要配置监控告警：

- ERROR 级别日志
- `[SLOW]` 慢操作日志
- `[SECURITY]` 安全相关日志
- 连续多次失败的 `[EXTERNAL]` 外部调用
