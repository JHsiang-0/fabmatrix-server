package com.example.farm.controller;

import com.example.farm.cloud.CloudGatewayAgent;
import com.example.farm.common.api.Result;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cloud")
public class CloudGatewayController {
    private final ObjectProvider<CloudGatewayAgent> agents;
    public CloudGatewayController(ObjectProvider<CloudGatewayAgent> agents) { this.agents = agents; }

    @GetMapping("/status")
    public Result<CloudGatewayAgent.Status> status() {
        var agent = agents.getIfAvailable();
        return Result.success(agent == null ? new CloudGatewayAgent.Status("DISABLED", false, null, null, null, false) : agent.status());
    }
}
