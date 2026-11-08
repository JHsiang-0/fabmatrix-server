package com.example.farm.controller;

import com.example.farm.service.CloudConnectionService;
import com.example.farm.cloud.CloudRemoteControlPolicyStore;
import com.example.farm.common.api.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A local administrator explicitly grants this binding permission to pause. */
@RestController
@RequestMapping("/api/v1/cloud/remote-control")
public class CloudRemoteControlController {
    public record Update(boolean enabled, @Min(1) long revision) { }
    private final CloudConnectionService connections;
    public CloudRemoteControlController(CloudConnectionService connections) { this.connections = connections; }
    @GetMapping public Result<CloudRemoteControlPolicyStore.View> get() {
        return Result.success(connections.remoteControlPolicy());
    }
    @PutMapping public Result<CloudRemoteControlPolicyStore.View> update(@Valid @RequestBody Update request) {
        return Result.success(connections.updateRemoteControlPolicy(request.enabled(), request.revision()));
    }
}
