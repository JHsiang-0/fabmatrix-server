package com.example.farm.common.utils;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT 工具类
 * 用于生成和验证用户身份令牌
 */
@Slf4j
@Component
public class JwtUtils {

    @Value("${jwt.secret-key}")
    private String secretKey;

    @Value("${jwt.expire-time:604800000}")
    private long expireTime;

    // 静态变量和实例变量，用于支持静态方法和实例注入
    private static String STATIC_SECRET_KEY;
    private static long STATIC_EXPIRE_TIME;

    @PostConstruct
    public void init() {
        STATIC_SECRET_KEY = secretKey;
        STATIC_EXPIRE_TIME = expireTime;
        log.info("JWT 工具初始化完成，Token 有效期: {} 毫秒", expireTime);
    }

    /** 兼容旧调用方：没有会话版本 claim 的 Token 按版本 0 签发。 */
    public static String generateToken(Long userId, String username, String role) {
        return generateToken(userId, username, role, 0L);
    }

    /** 根据用户身份和当前会话版本生成 Token。 */
    public static String generateToken(Long userId, String username, String role, Long tokenVersion) {
        return generateToken(userId, username, role, tokenVersion, null);
    }

    public static String generateToken(Long userId, String username, String role, Long tokenVersion, String sessionId) {
        Date expireDate = new Date(System.currentTimeMillis() + STATIC_EXPIRE_TIME);

        // 设置头部信息
        Map<String, Object> header = new HashMap<>();
        header.put("typ", "JWT");
        header.put("alg", "HS256");

        return JWT.create()
                .withHeader(header)
                .withClaim("userId", userId)
                .withClaim("username", username)
                .withClaim("role", role)
                .withClaim("tokenVersion", tokenVersion == null ? 0L : tokenVersion)
                .withClaim("sessionId", sessionId)
                .withExpiresAt(expireDate)
                .sign(Algorithm.HMAC256(STATIC_SECRET_KEY));
    }

    /**
     * 验卡机：校验 Token 是否合法、是否过期
     *
     * @param token JWT Token
     * @return 解码后的 JWT 对象
     */
    public static DecodedJWT verifyToken(String token) {
        return JWT.require(Algorithm.HMAC256(STATIC_SECRET_KEY)).build().verify(token);
    }
}
