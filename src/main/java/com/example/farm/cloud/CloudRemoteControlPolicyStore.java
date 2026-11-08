package com.example.farm.cloud;

import com.example.farm.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Explicit local authorization, bound to one gateway credential generation. */
@Component
public class CloudRemoteControlPolicyStore {
    public record View(boolean enabled, boolean temperatureEnabled, boolean temperatureSupported, long revision) {
        public View(boolean enabled, long revision) { this(enabled, false, true, revision); }
    }
    public record State(boolean enabled, boolean temperatureEnabled, long revision, String gatewayId, long bindingVersion) { }
    private final Path file;
    private final ObjectMapper json = new ObjectMapper();

    public CloudRemoteControlPolicyStore(CloudGatewayProperties defaults) {
        Path resolved = null;
        try {
            if (defaults.getCredentialFile() != null && !defaults.getCredentialFile().isBlank()) {
                resolved = Path.of(defaults.getCredentialFile()).toAbsolutePath().resolveSibling("cloud-remote-control.json");
            }
        } catch (RuntimeException invalidPath) { /* A cloud-only configuration error cannot stop local production. */ }
        file = resolved;
    }

    public synchronized View view(CloudWire.Credential binding) {
        State state = read();
        return new View(matches(state, binding) && state.enabled(), matches(state, binding) && state.temperatureEnabled(), true, state.revision());
    }

    public synchronized View update(CloudWire.Credential binding, boolean enabled, long expectedRevision) {
        return update(binding, enabled, null, expectedRevision);
    }

    public synchronized View update(CloudWire.Credential binding, Boolean enabled, Boolean temperatureEnabled, long expectedRevision) {
        if (enabled == null && temperatureEnabled == null) throw new BusinessException(400, "至少修改一项远程授权");
        State state = read();
        if (state.revision() != expectedRevision) {
            throw new BusinessException(409, "远程控制配置已变化，请刷新后重试");
        }
        if ((Boolean.TRUE.equals(enabled) || Boolean.TRUE.equals(temperatureEnabled)) && binding == null) {
            throw new BusinessException(409, "请先绑定云网关再授权远程控制");
        }
        State next = new State(enabled == null ? matches(state, binding) && state.enabled() : enabled,
                temperatureEnabled == null ? matches(state, binding) && state.temperatureEnabled() : temperatureEnabled,
                Math.addExact(state.revision(), 1),
                binding == null ? null : binding.gatewayId(), binding == null ? 0 : binding.bindingVersion());
        write(next);
        return new View(next.enabled(), next.temperatureEnabled(), true, next.revision());
    }

    /** A changed binding cannot inherit a previous authorization, even on later rebinding. */
    public synchronized void bindingChanged(CloudWire.Credential previous, CloudWire.Credential next) {
        if (same(previous, next)) return;
        State state = read();
        write(new State(false, false, Math.addExact(state.revision(), 1),
                next == null ? null : next.gatewayId(), next == null ? 0 : next.bindingVersion()));
    }

    public boolean allows(CloudControlWire.Session session, long revision) {
        return allows(session, revision, "PAUSE");
    }
    public boolean allows(CloudControlWire.Session session, long revision, String action) {
        try {
            synchronized (this) {
                State state = read();
                return ("PAUSE".equals(action) ? state.enabled() : "SET_HEATER_TARGET".equals(action) && state.temperatureEnabled())
                        && state.revision() == revision
                        && Objects.equals(state.gatewayId(), session.gatewayId())
                        && state.bindingVersion() == session.bindingVersion();
            }
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    private State read() {
        try {
            if (file == null) throw new IllegalStateException("Cloud configuration unavailable");
            if (!Files.exists(file)) return new State(false, false, 1, null, 0);
            State state = json.readValue(CloudAgentFiles.read(file, 4096), State.class);
            if (state.revision() < 1 || state.bindingVersion() < 0
                    || (state.enabled() || state.temperatureEnabled()) && (state.gatewayId() == null || state.bindingVersion() < 1)) {
                throw new IllegalArgumentException("Invalid control policy");
            }
            return state;
        } catch (Exception error) {
            throw new BusinessException(503, "远程控制配置无法读取，远程控制已禁用");
        }
    }

    private void write(State state) {
        try { CloudAgentFiles.write(file, json.writeValueAsString(state)); }
        catch (Exception error) { throw new BusinessException(503, "远程控制配置保存失败，原授权未变更"); }
    }
    private boolean matches(State state, CloudWire.Credential binding) {
        return binding != null && Objects.equals(state.gatewayId(), binding.gatewayId())
                && state.bindingVersion() == binding.bindingVersion();
    }
    private boolean same(CloudWire.Credential left, CloudWire.Credential right) {
        return left == null && right == null || left != null && right != null
                && Objects.equals(left.gatewayId(), right.gatewayId()) && left.bindingVersion() == right.bindingVersion();
    }
}
