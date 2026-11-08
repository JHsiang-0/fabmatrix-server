package com.example.farm.cloud;

import com.example.farm.service.CurrentExecutionPauseService;
import com.example.farm.service.HeaterTargetControlService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
public record CloudRemoteControlWorkerFactory(CloudSnapshotSource source, CloudRemoteControlPolicyStore policy,
        CloudRemoteCommandInbox inbox, CurrentExecutionPauseService pause, HeaterTargetControlService heaters) {
    public CloudRemoteControlWorker create(CloudGatewayProperties properties) {
        return new CloudRemoteControlWorker(properties, source, policy, inbox, pause, heaters);
    }
}
