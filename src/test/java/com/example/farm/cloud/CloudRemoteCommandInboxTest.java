package com.example.farm.cloud;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CloudRemoteCommandInboxTest {
    @TempDir Path temporary;

    @Test void sqliteRestartNeverReplaysAnInterruptedAttemptAndKeepsBootScopedReceipt() {
        var jdbc = database();
        var inbox = new CloudRemoteCommandInbox(jdbc);
        var command = command();
        assertThat(inbox.accept(command).status()).isEqualTo("RECEIVED");
        assertThat(inbox.executing(command.commandId())).isTrue();
        assertThat(inbox.executing(command.commandId())).isFalse();
        var restarted = new CloudRemoteCommandInbox(jdbc);
        assertThat(restarted.accept(command).status()).isEqualTo("UNKNOWN");
        assertThat(restarted.executing(command.commandId())).isFalse();
        var session = session(command);
        assertThat(restarted.pendingResults(session)).hasSize(1);
        assertThat(restarted.pendingResults(new CloudControlWire.Session(command.gatewayId(), 1,
                UUID.randomUUID().toString(), 2, command.sourceInstanceId()))).isEmpty();
        restarted.reported(command.commandId());
        assertThat(restarted.pendingResults(session)).isEmpty();
    }

    @Test void sameCommandReturnsPreviousResultAndDifferentPayloadIsRejectedWithCloudActorAudit() {
        var jdbc = database();
        var inbox = new CloudRemoteCommandInbox(jdbc);
        var command = command();
        inbox.accept(command);
        inbox.executing(command.commandId());
        inbox.complete(command, new CloudControlWire.Outcome("CONFIRMED", "DEVICE_PAUSED"));
        assertThat(inbox.accept(command).status()).isEqualTo("CONFIRMED");
        inbox.complete(command, new CloudControlWire.Outcome("FAILED", "PAUSE_FAILED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM farm_audit_log", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT actor_user_id FROM farm_remote_command", String.class))
                .isEqualTo(command.actorUserId());
        assertThat(jdbc.queryForObject("SELECT actor_role FROM farm_audit_log", String.class)).isEqualTo("CLOUD_ACTOR");
        var conflict = new CloudControlWire.Command(command.schemaVersion(), command.commandId(), "CANCEL",
                command.gatewayId(), command.bindingVersion(), command.bootId(), command.epoch(), command.sourceInstanceId(),
                command.deviceUid(), command.localPrinterId(), command.expectedExecutionId(), command.expectedFileName(),
                command.expectedFileSizeBytes(), command.expectedFilePositionBytes(), command.policyRevision(),
                command.actorUserId(), command.actorUsername(), command.issuedAt(), command.expiresAt());
        assertThatThrownBy(() -> inbox.accept(conflict)).isInstanceOf(IllegalArgumentException.class);
    }

    private JdbcTemplate database() {
        var data = new DriverManagerDataSource("jdbc:sqlite:" + temporary.resolve("inbox.db"));
        data.setDriverClassName("org.sqlite.JDBC");
        var jdbc = new JdbcTemplate(data);
        CloudRemoteCommandInbox.createSchema(jdbc);
        CloudRemoteCommandInbox.createSchema(jdbc);
        jdbc.execute("CREATE TABLE farm_audit_log(actor_id INTEGER,actor_username TEXT,actor_role TEXT,action TEXT,"
                + "target_type TEXT,target_id TEXT,target_label TEXT,result TEXT,error_code TEXT,occurred_at TIMESTAMP)");
        return jdbc;
    }
    static CloudControlWire.Command command() {
        long now = System.currentTimeMillis();
        return new CloudControlWire.Command(1, UUID.randomUUID().toString(), "PAUSE", UUID.randomUUID().toString(),
                1, UUID.randomUUID().toString(), 1, UUID.randomUUID().toString(), UUID.randomUUID().toString(), "1",
                UUID.randomUUID().toString(), "part.gcode", 10000L, 100L, 2,
                UUID.randomUUID().toString(), "cloud-operator", now, now + 15000);
    }
    static CloudControlWire.Session session(CloudControlWire.Command command) {
        return new CloudControlWire.Session(command.gatewayId(), command.bindingVersion(), command.bootId(),
                command.epoch(), command.sourceInstanceId());
    }
}
