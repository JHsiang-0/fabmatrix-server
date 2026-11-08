package com.example.farm.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** Durable at-most-one device attempt. An interrupted attempt is never replayed. */
@Repository
@Profile("local")
public class CloudRemoteCommandInbox {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    private boolean initialized;
    public record Entry(CloudControlWire.Command command, String status, String code, boolean reported) { }
    public CloudRemoteCommandInbox(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public static void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS farm_remote_command (
                    command_id VARCHAR(36) PRIMARY KEY, payload_sha256 VARCHAR(64) NOT NULL,
                    gateway_id VARCHAR(36) NOT NULL, binding_version BIGINT NOT NULL,
                    boot_id VARCHAR(36) NOT NULL, epoch BIGINT NOT NULL,
                    actor_user_id VARCHAR(36) NOT NULL, actor_username VARCHAR(100) NOT NULL,
                    command_json TEXT NOT NULL, status VARCHAR(16) NOT NULL,
                    result_code VARCHAR(64) NOT NULL, reported INTEGER NOT NULL DEFAULT 0,
                    created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_remote_command_receipts ON farm_remote_command(reported, gateway_id, binding_version, boot_id, epoch)");
    }

    public synchronized void initialize() {
        if (initialized) return;
        // Startup follows schema migration; a not-yet-ready database fails closed and retries later.
        jdbc.update("UPDATE farm_remote_command SET status='UNKNOWN', result_code='RESULT_UNKNOWN', reported=0, updated_at=? WHERE status='EXECUTING'",
                System.currentTimeMillis());
        jdbc.update("UPDATE farm_remote_command SET status='REJECTED', result_code='STALE_SESSION', reported=0, updated_at=? WHERE status='RECEIVED'",
                System.currentTimeMillis());
        initialized = true;
    }

    public synchronized Entry accept(CloudControlWire.Command command) {
        initialize();
        try {
            String payload = json.writeValueAsString(command);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
            List<String> previous = jdbc.query("SELECT payload_sha256 FROM farm_remote_command WHERE command_id=?",
                    (rs, row) -> rs.getString(1), command.commandId());
            if (!previous.isEmpty()) {
                if (!previous.getFirst().equals(hash)) throw new IllegalArgumentException("Command identity conflict");
                return find(command.commandId());
            }
            long now = System.currentTimeMillis();
            jdbc.update("""
                    INSERT INTO farm_remote_command(command_id,payload_sha256,gateway_id,binding_version,boot_id,epoch,
                        actor_user_id,actor_username,command_json,status,result_code,reported,created_at,updated_at)
                    VALUES(?,?,?,?,?,?,?,?,?,'RECEIVED','OK',0,?,?)
                    """, command.commandId(), hash, command.gatewayId(), command.bindingVersion(), command.bootId(), command.epoch(),
                    command.actorUserId(), command.actorUsername(), payload, now, now);
            return find(command.commandId());
        } catch (RuntimeException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException("Command persistence failed", error); }
    }

    public synchronized boolean executing(String id) {
        return jdbc.update("UPDATE farm_remote_command SET status='EXECUTING',result_code='OK',reported=0,updated_at=? WHERE command_id=? AND status='RECEIVED'",
                System.currentTimeMillis(), id) == 1;
    }

    @Transactional(rollbackFor = Exception.class)
    public synchronized void complete(CloudControlWire.Command command, CloudControlWire.Outcome outcome) {
        int changed = jdbc.update("UPDATE farm_remote_command SET status=?,result_code=?,reported=0,updated_at=? WHERE command_id=? AND status IN ('RECEIVED','EXECUTING')",
                outcome.status(), outcome.code(), System.currentTimeMillis(), command.commandId());
        if (changed == 1) {
            jdbc.update("""
                    INSERT INTO farm_audit_log(actor_id,actor_username,actor_role,action,target_type,target_id,target_label,result,error_code,occurred_at)
                    VALUES(NULL,?,'CLOUD_ACTOR','REMOTE_PAUSE','REMOTE_COMMAND',?,?,?, ?,CURRENT_TIMESTAMP)
                    """, command.actorUsername(), command.commandId(), "云端暂停设备 #" + command.localPrinterId(),
                    "CONFIRMED".equals(outcome.status()) ? "SUCCESS" : "FAILURE", outcome.code());
        }
    }

    public synchronized Entry find(String id) {
        return jdbc.query("SELECT command_json,status,result_code,reported FROM farm_remote_command WHERE command_id=?",
                (rs, row) -> entry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)), id)
                .stream().findFirst().orElse(null);
    }

    public synchronized List<Entry> pendingResults(CloudControlWire.Session session) {
        initialize();
        return jdbc.query("""
                SELECT command_json,status,result_code,reported FROM farm_remote_command
                WHERE reported=0 AND gateway_id=? AND binding_version=? AND boot_id=? AND epoch=?
                AND status NOT IN ('RECEIVED','EXECUTING') ORDER BY created_at LIMIT 16
                """, (rs, row) -> entry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)),
                session.gatewayId(), session.bindingVersion(), session.bootId(), session.epoch());
    }

    public synchronized void reported(String id) {
        jdbc.update("UPDATE farm_remote_command SET reported=1 WHERE command_id=? AND status NOT IN ('RECEIVED','EXECUTING')", id);
    }

    /** A permanent Cloud conflict is a delivery failure, never evidence of device success. */
    @Transactional(rollbackFor = Exception.class)
    public synchronized void deliveryRejected(Entry entry) {
        if (jdbc.update("UPDATE farm_remote_command SET reported=2 WHERE command_id=? AND reported=0 "
                + "AND status NOT IN ('RECEIVED','EXECUTING')", entry.command().commandId()) == 1) {
            jdbc.update("""
                    INSERT INTO farm_audit_log(actor_id,actor_username,actor_role,action,target_type,target_id,target_label,result,error_code,occurred_at)
                    VALUES(NULL,?,'CLOUD_ACTOR','REMOTE_RESULT_CONFLICT','REMOTE_COMMAND',?,?,'FAILURE','CONTROL_COMMUNICATION_FAILED',CURRENT_TIMESTAMP)
                    """, entry.command().actorUsername(), entry.command().commandId(), "云端暂停结果投递冲突，请核对命令记录");
        }
    }
    private Entry entry(String payload, String status, String code, int reported) {
        try { return new Entry(json.readValue(payload, CloudControlWire.Command.class), status, code, reported != 0); }
        catch (Exception malformed) { throw new IllegalStateException("Invalid persisted command", malformed); }
    }
}
