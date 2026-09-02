package com.example.farm.common.constant;

/**
 * Redis Key 常量定义
 */
public class RedisKeyConstant {

    /**
     * 打印机实时状态缓存
     * farm:printer:status:{printerId}
     * 过期时间：10秒
     */
    public static final String PRINTER_STATUS = "farm:printer:status:";

    /**
     * 打印机状态变更锁（防止并发更新）
     * farm:printer:lock:{printerId}
     * 过期时间：5秒
     */
    public static final String PRINTER_LOCK = "farm:printer:lock:";

    /**
     * 打印任务分布式锁（防止重复派单）
     * farm:job:lock:{jobId}
     * 过期时间：30秒
     */
    public static final String JOB_LOCK = "farm:job:lock:";

    /**
     * 任务队列分布式锁（防止并发调度）
     * farm:scheduler:lock
     * 过期时间：10秒
     */
    public static final String SCHEDULER_LOCK = "farm:scheduler:lock";

    /**
     * 用户登录 Token 黑名单
     * farm:token:blacklist:{token}
     * 过期时间：与 Token 有效期一致
     */
    public static final String TOKEN_BLACKLIST = "farm:token:blacklist:";

    /**
     * 用户登录失败次数（防暴力破解）
     * farm:login:fail:{username}
     * 过期时间：15分钟
     */
    public static final String LOGIN_FAIL_COUNT = "farm:login:fail:";

    /**
     * 用户禁用标记
     * farm:user:disabled:{userId}
     */
    public static final String USER_DISABLED = "farm:user:disabled:";

    /**
     * 打印机历史状态（最近24小时）
     * farm:printer:history:{printerId}
     * Redis List 结构，过期时间：24小时
     */
    public static final String PRINTER_HISTORY = "farm:printer:history:";

    /**
     * 系统统计数据缓存
     * farm:stats:{type}
     * 过期时间：1分钟
     */
    public static final String SYSTEM_STATS = "farm:stats:";

    /**
     * 打印机列表缓存（基础信息）
     * farm:printer:list
     * 过期时间：1小时
     */
    public static final String PRINTER_LIST = "farm:printer:list";

    /**
     * 在线打印机集合（Redis Set）
     * farm:printer:online
     * 存储在线打印机的 ID 集合
     * 过期时间：根据心跳动态更新
     */
    public static final String PRINTER_ONLINE_SET = "farm:printer:online";

    /**
     * 忙碌打印机集合（Redis Set）
     * farm:printer:busy
     * 存储正在打印的打印机 ID 集合
     */
    public static final String PRINTER_BUSY_SET = "farm:printer:busy";

    /**
     * 打印机心跳记录（Sorted Set）
     * farm:printer:heartbeat
     * 成员：printerId，分数：心跳时间戳
     */
    public static final String PRINTER_HEARTBEAT_ZSET = "farm:printer:heartbeat";

    /**
     * 获取完整的 Redis Key
     */
    public static String getKey(String prefix, Object suffix) {
        return prefix + suffix;
    }
}
