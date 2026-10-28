package com.example.farm.local;

import com.example.farm.FarmApplication;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;

/** 真 SQLite、单连接池及实际事务代理：失败登录不能争抢第二个数据库连接。 */
@SpringBootTest(classes = FarmApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoginFailurePoolIntegrationTest {
    private static final Path DIRECTORY;
    static {
        try { DIRECTORY = Files.createTempDirectory(Path.of("target"), "login-pool-"); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwords;
    @Autowired private HikariDataSource pool;
    @LocalServerPort private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("FARM_DATA_DIR", () -> DIRECTORY.toString());
        registry.add("logging.file.path", () -> DIRECTORY.resolve("logs").toString());
        registry.add("farm.monitor.enabled", () -> false);
        registry.add("farm.discovery.enabled", () -> false);
        registry.add("farm.websocket.enabled", () -> false);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
        registry.add("spring.datasource.hikari.connection-timeout", () -> 1500);
    }

    @Test
    void wrongPasswordAndMissingUserLeaveAuditWithoutStarvingHealthOrNextLogin() throws Exception {
        jdbc.update("INSERT INTO farm_user(username,password_hash,role) VALUES (?,?,?)",
                "pool-worker", passwords.encode("PoolTest123"), "OPERATOR");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        long started = System.nanoTime();
        HttpResponse<String> rejected = login(client, "pool-worker", "Incorrect123");
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        assertThat(rejected.statusCode()).isEqualTo(401);
        assertThat(elapsedMillis).isLessThan(1500);
        assertThat(login(client, "missing-worker", "Incorrect123").statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM farm_audit_log WHERE action='LOGIN' AND result='FAILURE' AND actor_username IN ('pool-worker','missing-worker')", Integer.class)).isEqualTo(2);
        HttpResponse<String> health = client.send(HttpRequest.newBuilder(URI.create(base() + "/actuator/health"))
                .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("UP");
        assertThat(login(client, "pool-worker", "PoolTest123").statusCode()).isEqualTo(200);
        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    private HttpResponse<String> login(HttpClient client, String username, String password) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base() + "/api/v1/auth/login"))
                .timeout(Duration.ofSeconds(8)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void disabledAndLockedAccountsRemainFastAndAudited() throws Exception {
        jdbc.update("INSERT INTO farm_user(username,password_hash,role,enabled) VALUES (?,?,?,?)",
                "pool-disabled", passwords.encode("PoolTest123"), "OPERATOR", 0);
        jdbc.update("INSERT INTO farm_user(username,password_hash,role) VALUES (?,?,?)",
                "pool-locked", passwords.encode("PoolTest123"), "OPERATOR");
        HttpClient client = HttpClient.newHttpClient();
        assertThat(login(client, "pool-disabled", "PoolTest123").statusCode()).isEqualTo(403);
        for (int attempt = 0; attempt < 6; attempt++) {
            assertThat(login(client, "pool-locked", "Incorrect123").statusCode()).isEqualTo(401);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM farm_audit_log WHERE actor_username='pool-disabled' AND error_code='ACCOUNT_DISABLED'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM farm_audit_log WHERE actor_username='pool-locked' AND error_code='ACCOUNT_LOCKED'", Integer.class)).isEqualTo(2);
    }

    private String base() { return "http://127.0.0.1:" + port; }

    @AfterAll
    void cleanup() throws Exception {
        pool.close();
        Path root = DIRECTORY.toAbsolutePath().normalize();
        if (!root.getParent().equals(Path.of("target").toAbsolutePath().normalize())) {
            throw new IllegalStateException("Unexpected test directory");
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
