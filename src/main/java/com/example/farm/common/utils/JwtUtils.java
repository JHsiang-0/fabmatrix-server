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

    /**
     * 根据用户ID和用户名，生成 Token
     *
     * @param userId   用户ID
     * @param username 用户名
     * @param role     用户角色
     * @return JWT Token 字符串
     */
    public static String generateToken(Long userId, String username, String role) {
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
