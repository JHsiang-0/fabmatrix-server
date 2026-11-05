package com.example.farm.controller;

import com.example.farm.cloud.CloudGatewayAgent;
import com.example.farm.common.api.Result;
import com.example.farm.service.CloudConnectionService;
import com.example.farm.entity.dto.CloudBindRequest;
import com.example.farm.entity.dto.CloudReportingRequest;
import com.example.farm.entity.dto.CloudRevisionRequest;
import com.example.farm.entity.vo.CloudConnectionVO;
import com.example.farm.entity.dto.CloudPairingRequest;
import com.example.farm.entity.dto.CloudPairingStartRequest;
import com.example.farm.entity.vo.CloudPairingVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

@RestController
@RequestMapping("/api/v1/cloud")
public class CloudGatewayController {
    private final CloudConnectionService connections;
    public CloudGatewayController(CloudConnectionService connections) { this.connections = connections; }

    @GetMapping("/status")
    public Result<CloudGatewayAgent.Status> status() {
        return Result.success(connections.status());
    }

    @PostMapping("/pairing/start")
    public Result<CloudPairingVO> startPairing(
            @Valid @RequestBody CloudPairingStartRequest request) {
        return Result.success(connections.startPairing(request));
    }
    @PostMapping("/pairing/poll")
    public Result<CloudPairingVO> pollPairing(
            @Valid @RequestBody CloudPairingRequest request) {
        return Result.success(connections.pollPairing(request));
    }
    @PostMapping("/pairing/cancel")
    public Result<Void> cancelPairing(@Valid @RequestBody CloudPairingRequest request) {
        connections.cancelPairing(request);
        return Result.success(null);
    }

    @GetMapping("/connection")
    public Result<CloudConnectionVO> connection() { return Result.success(connections.connection()); }

    @PostMapping("/bind")
    public Result<CloudConnectionVO> bind(@Valid @RequestBody CloudBindRequest request) {
        return Result.success(connections.bind(request));
    }

    @PutMapping("/reporting")
    public Result<CloudConnectionVO> reporting(@Valid @RequestBody CloudReportingRequest request) {
        return Result.success(connections.reporting(request));
    }

    @PostMapping("/refresh-binding")
    public Result<CloudConnectionVO> refreshBinding(@Valid @RequestBody CloudRevisionRequest request) {
        return Result.success(connections.refreshBinding(request.revision()));
    }

    @PostMapping("/unbind")
    public Result<CloudConnectionVO> unbind(@Valid @RequestBody CloudRevisionRequest request) {
        return Result.success(connections.unbind(request.revision()));
    }
}
