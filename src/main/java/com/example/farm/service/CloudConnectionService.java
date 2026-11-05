package com.example.farm.service;

import com.example.farm.cloud.CloudGatewayAgent;
import com.example.farm.entity.dto.CloudBindRequest;
import com.example.farm.entity.dto.CloudReportingRequest;
import com.example.farm.entity.vo.CloudConnectionVO;

public interface CloudConnectionService {
    CloudConnectionVO connection();
    CloudGatewayAgent.Status status();
    CloudConnectionVO bind(CloudBindRequest request);
    CloudConnectionVO reporting(CloudReportingRequest request);
    CloudConnectionVO refreshBinding(long revision);
    CloudConnectionVO unbind(long revision);
}
