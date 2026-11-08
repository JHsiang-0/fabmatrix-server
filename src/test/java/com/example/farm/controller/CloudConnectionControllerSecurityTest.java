package com.example.farm.controller;

import com.example.farm.FarmApplication;
import com.example.farm.entity.dto.CloudBindRequest;
import com.example.farm.entity.dto.CloudReportingRequest;
import com.example.farm.service.CloudConnectionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = FarmApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CloudConnectionControllerSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean CloudConnectionService connections;

    @Test void everyManagementEndpointRequiresLocalAdministrator() throws Exception {
        mvc.perform(get("/api/v1/cloud/connection")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/cloud/remote-control")).andExpect(status().isUnauthorized());
        for (String role : new String[]{"OPERATOR", "VIEWER"}) {
            mvc.perform(get("/api/v1/cloud/remote-control").with(user("staff").roles(role))).andExpect(status().isForbidden());
            mvc.perform(put("/api/v1/cloud/remote-control").with(user("staff").roles(role))
                    .contentType("application/json").content("{\"revision\":1,\"enabled\":true}"))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/cloud/connection").with(user("staff").roles(role))).andExpect(status().isForbidden());
            for (String action : new String[]{"bind", "unbind", "refresh-binding"}) {
                mvc.perform(post("/api/v1/cloud/" + action).with(user("staff").roles(role))
                        .contentType("application/json").content("{\"revision\":0}"))
                        .andExpect(status().isForbidden());
            }
            mvc.perform(put("/api/v1/cloud/reporting").with(user("staff").roles(role))
                    .contentType("application/json").content("{\"revision\":0,\"enabled\":true}"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(connections);
    }

    @Test void administratorCanUseTypedActionsAndMalformedBindingIsRejected() throws Exception {
        mvc.perform(get("/api/v1/cloud/connection").with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/cloud/bind").with(user("admin").roles("ADMIN"))
                .contentType("application/json").content("{\"revision\":0}"))
                .andExpect(status().isBadRequest());
        String body = "{\"revision\":0,\"apiUrl\":\"https://cloud.example.test\","
                + "\"mqttUrl\":\"ssl://mqtt.example.test:8883\",\"useSystemTrust\":true,\"bindingToken\":\"fmc_b_" + "b".repeat(43) + "\"}";
        mvc.perform(post("/api/v1/cloud/bind").with(user("admin").roles("ADMIN"))
                .contentType("application/json").content(body)).andExpect(status().isOk());
        mvc.perform(put("/api/v1/cloud/reporting").with(user("admin").roles("ADMIN"))
                .contentType("application/json").content("{\"revision\":1,\"enabled\":true}"))
                .andExpect(status().isOk());
        verify(connections).bind(any(CloudBindRequest.class));
        verify(connections).reporting(any(CloudReportingRequest.class));
    }

    @Test void remoteControlUsesExplicitRevisionAndOnlyAdministratorMayChangeIt() throws Exception {
        mvc.perform(get("/api/v1/cloud/remote-control").with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
        mvc.perform(put("/api/v1/cloud/remote-control").with(user("admin").roles("ADMIN"))
                .contentType("application/json").content("{\"revision\":0,\"enabled\":true}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/cloud/remote-control").with(user("admin").roles("ADMIN"))
                .contentType("application/json").content("{\"revision\":1,\"enabled\":true}"))
                .andExpect(status().isOk());
        verify(connections).remoteControlPolicy();
        verify(connections).updateRemoteControlPolicy(true, 1);
    }
}
