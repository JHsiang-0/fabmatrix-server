package com.example.farm.cloud;

import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.utils.SecurityContextUtil;
import com.example.farm.entity.dto.CloudBindRequest;
import com.example.farm.entity.dto.CloudReportingRequest;
import com.example.farm.entity.vo.CloudConnectionVO;
import com.example.farm.service.AuditLogService;
import com.example.farm.service.CloudConnectionService;
import com.example.farm.service.PrinterEventOutbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import com.example.farm.entity.dto.CloudPairingStartRequest;
import com.example.farm.entity.dto.CloudPairingRequest;
import com.example.farm.entity.vo.CloudPairingVO;

/** 序列化管理当前 Server 的持久化配置和单个上报实例。 */
@Service
public class CloudConnectionManager implements CloudConnectionService, SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(CloudConnectionManager.class);
    private final CloudGatewayProperties defaults;
    private final CloudSnapshotSource source;
    private final ObjectProvider<PrinterEventOutbox> outboxes;
    private final AuditLogService audit;
    private final CloudConnectionClient remote;
    private final ObjectMapper json = new ObjectMapper();
    private Path settingsFile;
    private CloudConnectionSettings settings;
    private CloudGatewayAgent agent;
    private boolean running;
    private boolean managed;
    private String configurationError;
    private PendingPairing pairing;
    public record PendingPairing(CloudConnectionSettings candidate, CloudConnectionClient.PairingStart session,
                                 String credential) {
        @Override public String toString() { return "PendingPairing[redacted]"; }
    }

    public CloudConnectionManager(CloudGatewayProperties defaults, CloudSnapshotSource source,
                                  ObjectProvider<PrinterEventOutbox> outboxes, AuditLogService audit,
                                  CloudConnectionClient remote) {
        this.defaults = defaults;
        this.source = source;
        this.outboxes = outboxes;
        this.audit = audit;
        this.remote = remote;
    }

    @Override public synchronized void start() {
        if (running) { return; }
        running = true;
        try {
            if (defaults.getCredentialFile() == null || defaults.getCredentialFile().isBlank()) {
                throw new IllegalArgumentException("Cloud credential path must be configured");
            }
            settingsFile = Path.of(defaults.getCredentialFile()).toAbsolutePath().resolveSibling("cloud-connection.json");
            managed = Files.exists(settingsFile);
            settings = managed ? json.readValue(CloudAgentFiles.read(settingsFile, 196608), CloudConnectionSettings.class)
                    : legacySettings();
            if (settings.revision() < 0) { throw new IllegalArgumentException("Invalid cloud settings revision"); }
            if (settings.apiUrl() == null || settings.mqttUrl() == null || settings.caCertificate() == null
                    || settings.caCertificatePem() == null || managed && settings.enabled() && settings.credential() == null) {
                throw new IllegalArgumentException("Invalid cloud configuration");
            }
            if (settings.credential() != null) { CloudGatewayAgent.validate(settings.credential()); }
            configurationError = null;
            pairing = null;
            if (settings.credential() == null && Files.exists(pairingFile())) {
                try {
                    var restored = json.readValue(CloudAgentFiles.read(pairingFile(), 196608), PendingPairing.class);
                    if (restored.candidate().revision() == settings.revision() + 1
                            && restored.session().expiresAt() > System.currentTimeMillis()) { pairing = restored; }
                } catch (Exception ignored) {
                    // Temporary pairing recovery must not disable the configured cloud runtime or local production.
                    LOG.warn("Pending cloud pairing could not be restored; generate a new pairing code");
                }
            }
            startAgent();
        } catch (Exception exception) {
            settings = emptySettings(0);
            configurationError = "CLOUD_CONFIGURATION_UNREADABLE";
            LOG.warn("Cloud connection configuration could not be loaded ({})", exception.getClass().getSimpleName());
        }
    }

    @Override public synchronized void stop() { stopAgent(); running = false; }
    @Override public synchronized boolean isRunning() { return running; }

    @Override public synchronized CloudGatewayAgent.Status status() {
        if (agent != null) { return agent.status(); }
        var credential = settings == null ? null : settings.credential();
        String state = configurationError != null ? "CONFIG_ERROR" : "DISABLED";
        return new CloudGatewayAgent.Status(state, false, credential == null ? null : credential.gatewayId(),
                settings == null ? null : settings.lastAckAt(), configurationError, false);
    }

    @Override public synchronized CloudConnectionVO connection() {
        requireAdmin();
        importLegacyCredential();
        var state = status();
        var credential = settings.credential();
        var binding = settings.binding();
        return new CloudConnectionVO(settings.revision(), settings.enabled(), credential != null,
                state.state(), state.connected(), settings.apiUrl(), settings.mqttUrl(),
                !settings.caCertificate().isBlank() || !settings.caCertificatePem().isBlank(),
                credential == null ? null : credential.gatewayId(), binding == null ? null : binding.gatewayName(),
                credential == null ? null : credential.organizationId(), binding == null ? null : binding.organizationName(),
                credential == null ? null : credential.farmId(), binding == null ? null : binding.farmName(),
                credential == null ? null : credential.expiresAt(), state.lastAckAt(), state.errorCode(),
                state.pending(), source.printerCount(), settings.observationV3());
    }

    @Override public synchronized CloudConnectionVO bind(CloudBindRequest request) {
        requireRevision(request.revision());
        if (pairing != null && settings.credential() == null && pairing.session().expiresAt() > System.currentTimeMillis()) {
            throw new BusinessException(409, "请先完成或取消扫码配对");
        }
        if (!request.bindingToken().matches("fmc_b_[A-Za-z0-9_-]{43}")) {
            throw new BusinessException(400, "绑定码格式无效");
        }
        String pem = request.useSystemTrust() ? "" : text(request.caCertificatePem());
        String certificate = request.useSystemTrust() || !pem.isBlank() ? "" : settings.caCertificate();
        if (!request.useSystemTrust() && pem.isBlank()) { pem = settings.caCertificatePem(); }
        var candidate = new CloudConnectionSettings(settings.revision() + 1, false, request.apiUrl().trim(),
                request.mqttUrl().trim(), certificate, pem, true, true, null, null, null);
        try {
            var properties = properties(candidate);
            CloudConnectionClient.validateConfiguration(properties);
            // Verify durable protected storage before consuming the single-use binding ticket.
            CloudAgentFiles.verifyWritable(settingsFile);
            var credential = remote.bind(properties, request.bindingToken());
            var next = new CloudConnectionSettings(candidate.revision(), false, candidate.apiUrl(), candidate.mqttUrl(),
                    certificate, pem, true, true, credential, null, null);
            // Save the credential before optional metadata retrieval so a consumed ticket is not lost on timeout.
            commit(next, "CLOUD_BIND");
            try { refreshProfile(); } catch (Exception ignored) { /* 管理员可在连接页重新获取绑定名称。 */ }
            return connection();
        } catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw remoteFailure(exception); }
    }

    @Override public synchronized CloudPairingVO startPairing(CloudPairingStartRequest request) {
        requireRevision(request.revision());
        importLegacyCredential();
        if (settings.credential() != null) { throw new BusinessException(409, "当前已绑定网关，请先核对已有绑定"); }
        try {
            if (pairing != null && pairing.session().expiresAt() > System.currentTimeMillis()) { return pairingView("WAITING"); }
            String pem = request.useSystemTrust() ? "" : text(request.caCertificatePem());
            String certificate = request.useSystemTrust() || !pem.isBlank() ? "" : settings.caCertificate();
            if (!request.useSystemTrust() && pem.isBlank()) { pem = settings.caCertificatePem(); }
            var candidate = new CloudConnectionSettings(settings.revision() + 1, false, request.apiUrl().trim(),
                    request.mqttUrl().trim(), certificate, pem, true, true, null, null, null);
            var properties = properties(candidate);
            CloudConnectionClient.validateConfiguration(properties);
            CloudAgentFiles.verifyWritable(settingsFile);
            CloudAgentFiles.verifyWritable(pairingFile());
            var session = remote.startPairing(properties);
            UUID.fromString(session.pairingId());
            if (session.deviceToken() == null || !session.deviceToken().matches("fmc_pd_[A-Za-z0-9_-]{43}")
                    || session.approvalToken() == null || !session.approvalToken().matches("fmc_pa_[A-Za-z0-9_-]{43}")
                    || session.expiresAt() <= System.currentTimeMillis()
                    || session.expiresAt() > System.currentTimeMillis() + 660_000) {
                throw new IllegalArgumentException("INVALID_PAIRING_RESPONSE");
            }
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            var pending = new PendingPairing(candidate, session, "fmc_g_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
            CloudAgentFiles.write(pairingFile(), json.writeValueAsString(pending));
            pairing = pending;
            return pairingView("WAITING");
        } catch (Exception exception) { throw remoteFailure(exception); }
    }

    @Override public synchronized CloudPairingVO pollPairing(CloudPairingRequest request) {
        requireAdmin();
        requirePairing(request);
        // Idempotent local completion after a response or pending-file cleanup failure.
        if (settings.credential() != null && settings.credential().credential().equals(pairing.credential())) {
            return pairingView("COMPLETED");
        }
        requireRevision(request.revision());
        if (pairing.session().expiresAt() <= System.currentTimeMillis()) { return pairingView("EXPIRED"); }
        try {
            var result = remote.pollPairing(properties(pairing.candidate()), pairing.session(), pairing.credential());
            if ("COMPLETED".equals(result.state())) {
                CloudGatewayAgent.validate(result.credential());
                if (!pairing.credential().equals(result.credential().credential())) { throw new IllegalArgumentException("PAIRING_CREDENTIAL_MISMATCH"); }
                var c = pairing.candidate();
                commit(new CloudConnectionSettings(c.revision(), false, c.apiUrl(), c.mqttUrl(), c.caCertificate(),
                        c.caCertificatePem(), true, true, result.credential(), null, null), "CLOUD_PAIR");
                // Credential is durable now; no temporary device secret needs to remain on disk.
                try { Files.deleteIfExists(pairingFile()); }
                catch (Exception ignored) { LOG.warn("Completed cloud pairing temporary file could not be removed"); }
                try { refreshProfile(); } catch (Exception ignored) { /* 名称可稍后刷新，不丢弃已保存凭据。 */ }
                return pairingView("COMPLETED");
            }
            if (!"WAITING".equals(result.state())) { throw new IllegalArgumentException("INVALID_PAIRING_STATE"); }
            return pairingView("WAITING");
        } catch (Exception exception) { throw remoteFailure(exception); }
    }

    @Override public synchronized void cancelPairing(CloudPairingRequest request) {
        requireRevision(request.revision());
        requirePairing(request);
        try {
            if (pairing.session().expiresAt() > System.currentTimeMillis()) {
                remote.cancelPairing(properties(pairing.candidate()), pairing.session(), pairing.credential());
            }
            Files.deleteIfExists(pairingFile());
            pairing = null;
        } catch (Exception exception) { throw remoteFailure(exception); }
    }
    private Path pairingFile() { return settingsFile.resolveSibling("cloud-pairing.json"); }
    private void requirePairing(CloudPairingRequest request) {
        if (pairing == null || !pairing.session().pairingId().equals(request.pairingId().toString())) {
            throw new BusinessException(409, "配对会话已变化，请重新生成二维码");
        }
    }
    private CloudPairingVO pairingView(String state) {
        String qr = "";
        if ("WAITING".equals(state)) {
            try {
                qr = json.writeValueAsString(Map.of("type", "fabmatrix.gateway-pairing", "version", 1,
                        "pairingId", pairing.session().pairingId(), "approvalToken", pairing.session().approvalToken(),
                        "cloudUrl", pairing.candidate().apiUrl()));
            } catch (Exception exception) { throw new IllegalStateException("PAIRING_ENCODING_FAILED"); }
        }
        return new CloudPairingVO(pairing.session().pairingId(), state, qr, pairing.session().expiresAt());
    }

    @Override public synchronized CloudConnectionVO reporting(CloudReportingRequest request) {
        requireRevision(request.revision());
        importLegacyCredential();
        if (request.enabled() && (settings.credential() == null
                || settings.credential().expiresAt() <= System.currentTimeMillis())) {
            throw new BusinessException(400, "请先绑定有效的云网关，凭据过期时需要重新绑定");
        }
        if (request.enabled() == settings.enabled()) { return connection(); }
        var next = new CloudConnectionSettings(settings.revision() + 1, request.enabled(), settings.apiUrl(),
                settings.mqttUrl(), settings.caCertificate(), settings.caCertificatePem(), settings.observationV2(),
                settings.observationV3(), settings.credential(), settings.binding(), status().lastAckAt());
        try {
            if (request.enabled()) { CloudConnectionClient.validateConfiguration(properties(next)); }
            commit(next, request.enabled() ? "CLOUD_ENABLE" : "CLOUD_DISABLE");
            return connection();
        } catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw remoteFailure(exception); }
    }

    @Override public synchronized CloudConnectionVO refreshBinding(long revision) {
        requireRevision(revision);
        requireBinding();
        try { refreshProfile(); return connection(); }
        catch (Exception exception) { throw remoteFailure(exception); }
    }

    @Override public synchronized CloudConnectionVO unbind(long revision) {
        requireRevision(revision);
        requireBinding();
        // Persist the stop before contacting Cloud, including when Cloud is unavailable.
        if (settings.enabled()) { reporting(new CloudReportingRequest(revision, false)); }
        try {
            remote.disconnect(properties(settings), settings.credential());
            Files.deleteIfExists(pairingFile());
            pairing = null;
            commit(emptySettings(settings.revision() + 1), "CLOUD_UNBIND");
            return connection();
        } catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw remoteFailure(exception); }
    }

    private void refreshProfile() throws Exception {
        var binding = remote.profile(properties(settings), settings.credential());
        if (!settings.credential().gatewayId().equals(binding.gatewayId())
                || !settings.credential().organizationId().equals(binding.organizationId())
                || !settings.credential().farmId().equals(binding.farmId())) {
            throw new IllegalArgumentException("Cloud binding scope mismatch");
        }
        var next = new CloudConnectionSettings(settings.revision(), settings.enabled(), settings.apiUrl(), settings.mqttUrl(),
                settings.caCertificate(), settings.caCertificatePem(), settings.observationV2(), settings.observationV3(),
                settings.credential(), binding, settings.lastAckAt());
        CloudAgentFiles.write(settingsFile, json.writeValueAsString(next));
        settings = next;
        managed = true;
    }

    private void commit(CloudConnectionSettings next, String action) throws Exception {
        String gatewayId = next.credential() != null ? next.credential().gatewayId()
                : settings.credential() == null ? null : settings.credential().gatewayId();
        // Write first so a failed save leaves the existing runtime and configuration intact.
        CloudAgentFiles.write(settingsFile, json.writeValueAsString(next));
        stopAgent();
        settings = next;
        managed = true;
        configurationError = null;
        startAgent();
        audit.recordSuccess(action, "CLOUD_GATEWAY", gatewayId,
                "当前 Server 云端连接");
    }

    private void startAgent() {
        if (!settings.enabled()) { return; }
        agent = createAgent(properties(settings), settings.credential());
        agent.start();
    }

    CloudGatewayAgent createAgent(CloudGatewayProperties properties, CloudWire.Credential credential) {
        return new CloudGatewayAgent(properties, source, outboxes, credential);
    }

    private void stopAgent() {
        if (agent != null) { agent.stop(); agent = null; }
    }

    private CloudGatewayProperties properties(CloudConnectionSettings value) {
        var properties = new CloudGatewayProperties();
        properties.setApiUrl(value.apiUrl());
        properties.setMqttUrl(value.mqttUrl());
        properties.setCaCertificate(value.caCertificate());
        properties.setCaCertificatePem(value.caCertificatePem());
        properties.setObservationV2Enabled(value.observationV2());
        properties.setObservationV3Enabled(value.observationV3());
        properties.setCredentialFile(defaults.getCredentialFile());
        properties.setStateDirectory(defaults.getStateDirectory());
        properties.setBindingTokenFile(managed ? "" : defaults.getBindingTokenFile());
        properties.setMaxUploadBytesPerSecond(defaults.getMaxUploadBytesPerSecond());
        return properties;
    }

    private CloudConnectionSettings legacySettings() throws Exception {
        var file = Path.of(defaults.getCredentialFile());
        var credential = Files.exists(file) ? json.readValue(CloudAgentFiles.read(file, 8192), CloudWire.Credential.class) : null;
        return new CloudConnectionSettings(0, defaults.isEnabled(), defaults.getApiUrl(), defaults.getMqttUrl(),
                defaults.getCaCertificate(), defaults.getCaCertificatePem(), defaults.isObservationV2Enabled(),
                defaults.isObservationV3Enabled(), credential, null, null);
    }

    private void importLegacyCredential() {
        if (managed || settings.credential() != null) { return; }
        try {
            var legacy = legacySettings();
            if (legacy.credential() != null) {
                CloudGatewayAgent.validate(legacy.credential());
                settings = legacy;
            }
        } catch (Exception ignored) { /* 原代理继续提供自身的脱敏错误状态。 */ }
    }

    private CloudConnectionSettings emptySettings(long revision) {
        return new CloudConnectionSettings(revision, false, "", "", "", "", true, true, null, null, null);
    }

    private void requireRevision(long revision) {
        requireAdmin();
        if (configurationError != null) { throw new BusinessException(409, "云配置读取失败，请先检查 Server 配置文件"); }
        if (settings.revision() != revision) { throw new BusinessException(409, "配置已被其他管理员更新，请刷新后重试"); }
    }

    private void requireBinding() {
        importLegacyCredential();
        if (settings.credential() == null) { throw new BusinessException(400, "当前 Server 尚未绑定云网关"); }
    }

    private void requireAdmin() {
        if (!"ADMIN".equals(SecurityContextUtil.getCurrentRole())) { throw new BusinessException(403, "仅管理员可管理云端连接"); }
    }

    private String text(String value) { return value == null ? "" : value.trim(); }

    private BusinessException remoteFailure(Exception exception) {
        String code = exception instanceof CloudConnectionClient.RemoteException ? exception.getMessage()
                : exception.getClass().getSimpleName();
        LOG.warn("Cloud connection management failed ({})", code);
        audit.recordFailure("CLOUD_CONNECTION_CHANGE", "CLOUD_GATEWAY", null, "当前 Server 云端连接", code);
        return new BusinessException(503, "云端连接操作失败，请检查地址、证书、网络及绑定码后重试（" + code + "）");
    }
}
