package com.example.farm.controller;

import com.example.farm.service.CloudConnectionService;
import com.example.farm.cloud.CloudRemoteControlPolicyStore;
import com.example.farm.common.api.Result;
import com.example.farm.common.exception.BusinessException;
import tools.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A local administrator explicitly grants this binding permission to pause. */
@RestController
@RequestMapping("/api/v1/cloud/remote-control")
public class CloudRemoteControlController {
    private final CloudConnectionService connections;
    public CloudRemoteControlController(CloudConnectionService connections) { this.connections = connections; }
    @GetMapping public Result<CloudRemoteControlPolicyStore.View> get() {
        return Result.success(connections.remoteControlPolicy());
    }
    @PutMapping public Result<CloudRemoteControlPolicyStore.View> update(@RequestBody JsonNode request) {
        if (!request.isObject() || !request.path("revision").isIntegralNumber() || !request.path("revision").canConvertToLong()
                || request.path("revision").longValue() < 1) throw new BusinessException(400, "请提供有效配置版本");
        long revision = request.path("revision").longValue();
        if (request.has("remoteEnabled")) {
            if (request.size() != 3 || !request.path("remoteEnabled").isBoolean()
                    || !request.path("authorizationVersion").isIntegralNumber()
                    || !request.path("authorizationVersion").canConvertToInt()
                    || request.path("authorizationVersion").intValue() != 1) throw new BusinessException(400, "请明确确认统一远程控制授权版本");
            return Result.success(connections.updateUnifiedRemoteControlPolicy(request.path("remoteEnabled").booleanValue(), 1, revision));
        }
        for (String field : request.propertyNames()) if (!java.util.Set.of("enabled", "temperatureEnabled", "revision").contains(field)) throw new BusinessException(400, "远程控制参数无效");
        if (!request.has("enabled") && !request.has("temperatureEnabled")) throw new BusinessException(400, "请提供远程控制授权");
        for (String field : java.util.List.of("enabled", "temperatureEnabled")) {
            if (request.has(field) && !request.path(field).isBoolean()) throw new BusinessException(400, "远程控制参数无效");
            if (request.has(field) && request.path(field).booleanValue()) throw new BusinessException(409, "请升级客户端并确认统一远程控制授权");
        }
        return Result.success(connections.updateRemoteControlPolicy(request.has("enabled") ? false : null,
                request.has("temperatureEnabled") ? false : null, revision));
    }
}
