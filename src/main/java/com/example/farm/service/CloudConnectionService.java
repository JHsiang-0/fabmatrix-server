package com.example.farm.service;

import com.example.farm.cloud.CloudGatewayAgent;
import com.example.farm.entity.dto.CloudBindRequest;
import com.example.farm.entity.dto.CloudReportingRequest;
import com.example.farm.entity.vo.CloudConnectionVO;
import com.example.farm.entity.dto.CloudPairingRequest;
import com.example.farm.entity.dto.CloudPairingStartRequest;
import com.example.farm.entity.vo.CloudPairingVO;

public interface CloudConnectionService {
    CloudPairingVO startPairing(CloudPairingStartRequest request);
    CloudPairingVO pollPairing(CloudPairingRequest request);
    void cancelPairing(CloudPairingRequest request);
    CloudConnectionVO connection();
    CloudGatewayAgent.Status status();
    CloudConnectionVO bind(CloudBindRequest request);
    CloudConnectionVO reporting(CloudReportingRequest request);
    CloudConnectionVO refreshBinding(long revision);
    CloudConnectionVO unbind(long revision);
}
