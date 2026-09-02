package com.example.farm.config;

import com.example.farm.FarmApplication;
import com.example.farm.entity.dto.UserUpdateDTO;
import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.utils.JwtUtils;
import com.example.farm.common.utils.LoginProtectUtil;
import com.example.farm.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = FarmApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityResponseTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private LoginProtectUtil loginProtectUtil;

    @Test
    void unauthenticatedRequestUsesUnified401Response() throws Exception {
        mockMvc.perform(get("/api/v1/printers/page"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void unauthenticatedCannotAccessCurrentUser() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void currentUserMissingUsesUnified404Response() throws Exception {
        when(userService.getCurrentUser(1L)).thenThrow(new BusinessException(404, "用户不存在"));

        mockMvc.perform(get("/api/v1/auth/me").with(authentication(adminAuthentication())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("用户不存在"));
    }

    @Test
    void unexpectedControllerFailureUsesUnified500Response() throws Exception {
        when(userService.getCurrentUser(1L)).thenThrow(new IllegalStateException("unexpected failure"));

        mockMvc.perform(get("/api/v1/auth/me").with(authentication(adminAuthentication())))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500));
    }

    @Test
    void invalidTokenUsesUnified401Response() throws Exception {
        mockMvc.perform(get("/api/v1/printers/page")
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void disabledUserTokenUsesUnified403Response() throws Exception {
        when(loginProtectUtil.isUserDisabled(7L)).thenReturn(true);
        String token = JwtUtils.generateToken(7L, "operator", "OPERATOR");

        mockMvc.perform(get("/api/v1/printers/page")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void operatorCannotAccessAdminEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/auth/admin/users")
                        .with(user("operator").roles("OPERATOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void rejectsInvalidPrinterPaginationParameters() throws Exception {
        mockMvc.perform(get("/api/v1/printers/page")
                        .param("pageNum", "0")
                        .param("pageSize", "101")
                        .with(user("operator").roles("OPERATOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void operatorCannotScanPrinters() throws Exception {
        mockMvc.perform(get("/api/v1/printers/scan")
                        .param("subnet", "192.168.1")
                        .with(user("operator").roles("OPERATOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void unauthenticatedCannotResumePrinter() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/control/403/resume"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void standardCreateJobEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/print-jobs")
                        .contentType("application/json")
                        .content("{\"fileId\":20,\"priority\":0}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void validatesPrinterHistoryPaginationBeforeServiceCall() throws Exception {
        mockMvc.perform(get("/api/v1/printers/403/history")
                        .param("pageNum", "0")
                        .param("pageSize", "101")
                        .with(user("operator").roles("OPERATOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void adminCanCreateOperator() throws Exception {
        when(userService.register(any())).thenReturn(2L);

        mockMvc.perform(post("/api/v1/auth/admin/users")
                        .with(user("1").roles("ADMIN"))
                        .contentType("application/json")
                        .content("{\"username\":\"operator1\",\"password\":\"Operator1\","
                                + "\"confirmPassword\":\"Operator1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(2));

        verify(userService).register(any());
    }

    @Test
    void adminCanDisableUser() throws Exception {
        doNothing().when(userService).disableUser(2L, 1L);

        mockMvc.perform(post("/api/v1/auth/admin/users/2/disable")
                        .with(authentication(adminAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(userService).disableUser(2L, 1L);
    }

    @Test
    void adminCanEnableUser() throws Exception {
        doNothing().when(userService).enableUser(2L, 1L);

        mockMvc.perform(post("/api/v1/auth/admin/users/2/enable")
                        .with(authentication(adminAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(userService).enableUser(2L, 1L);
    }

    @Test
    void adminCanUpdateUserRole() throws Exception {
        doNothing().when(userService).updateUserInfo(any(UserUpdateDTO.class));

        mockMvc.perform(put("/api/v1/auth/admin/users/2")
                        .with(user("1").roles("ADMIN"))
                        .contentType("application/json")
                        .content("{\"role\":\"OPERATOR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(userService).updateUserInfo(any(UserUpdateDTO.class));
    }

    @Test
    void adminSecretMustBeProvidedInHeader() throws Exception {
        mockMvc.perform(get("/api/v1/auth/admin/password-status")
                        .with(authentication(adminAuthentication()))
                        .param("adminSecret", "test-admin-key"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void adminSecretHeaderAuthorizesPasswordStatus() throws Exception {
        when(userService.checkPasswordStatus())
                .thenReturn(new com.example.farm.entity.dto.PasswordStatusResultDTO(1, 0, 1));

        mockMvc.perform(get("/api/v1/auth/admin/password-status")
                        .with(authentication(adminAuthentication()))
                        .header("X-Admin-Secret", "test-admin-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalCount").value(1));

        verify(userService).checkPasswordStatus();
    }

    @Test
    void operatorCannotCreateOperator() throws Exception {
        mockMvc.perform(post("/api/v1/auth/admin/users")
                        .with(user("2").roles("OPERATOR"))
                        .contentType("application/json")
                        .content("{\"username\":\"operator1\",\"password\":\"Operator1\","
                                + "\"confirmPassword\":\"Operator1\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void operatorCannotDisableUser() throws Exception {
        mockMvc.perform(post("/api/v1/auth/admin/users/2/disable")
                        .with(user("2").roles("OPERATOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void operatorCannotEnableUser() throws Exception {
        mockMvc.perform(post("/api/v1/auth/admin/users/2/enable")
                        .with(user("2").roles("OPERATOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void operatorCannotUpdateUserRole() throws Exception {
        mockMvc.perform(put("/api/v1/auth/admin/users/2")
                        .with(user("2").roles("OPERATOR"))
                        .contentType("application/json")
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void unauthenticatedCannotAccessPrintFilePage() throws Exception {
        mockMvc.perform(post("/api/v1/print-files/page")
                        .contentType("application/json")
                        .content("{\"pageNum\":1,\"pageSize\":20}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void unauthenticatedCannotAccessPrintJobQueue() throws Exception {
        mockMvc.perform(get("/api/v1/print-jobs/queue"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void healthProbeDoesNotRequireAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(
                        result.getResponse().getStatus()).isNotIn(401, 403));
    }

    @Test
    void operatorCannotAddPrinter() throws Exception {
        mockMvc.perform(post("/api/v1/printers/add")
                        .with(user("2").roles("OPERATOR"))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void operatorCannotDeletePrinter() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/printers/delete/403")
                        .with(user("2").roles("OPERATOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    private UsernamePasswordAuthenticationToken adminAuthentication() {
        return new UsernamePasswordAuthenticationToken(
                1L,
                null,
                java.util.List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }
}
