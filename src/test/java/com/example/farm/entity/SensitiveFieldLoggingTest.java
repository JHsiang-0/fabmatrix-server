package com.example.farm.entity;

import com.example.farm.entity.dto.BootstrapTokenDTO;
import com.example.farm.entity.dto.ChangePasswordDTO;
import com.example.farm.entity.dto.LoginResultDTO;
import com.example.farm.entity.dto.PrinterAddDTO;
import com.example.farm.entity.dto.PrinterScanResultDTO;
import com.example.farm.entity.dto.PrinterUpdateDTO;
import com.example.farm.entity.dto.UserLoginDTO;
import com.example.farm.entity.dto.UserRegisterDTO;
import com.example.farm.entity.dto.request.BatchDispatchConfirmRequest;
import com.example.farm.entity.vo.DispatchPlanPreviewVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveFieldLoggingTest {

    private static final String SECRET = "synthetic-sensitive-log-value";

    @Test
    void loggingAuthenticationAndDeviceObjectsDoesNotExposeCredentials() {
        UserLoginDTO login = new UserLoginDTO();
        login.setUsername("fixture-user");
        login.setPassword(SECRET);
        UserRegisterDTO register = new UserRegisterDTO();
        register.setPassword(SECRET);
        register.setConfirmPassword(SECRET);
        register.setBootstrapToken(SECRET);
        ChangePasswordDTO change = new ChangePasswordDTO();
        change.setOldPassword(SECRET);
        change.setNewPassword(SECRET);
        change.setConfirmPassword(SECRET);
        LoginResultDTO result = new LoginResultDTO(SECRET, 60L, 1L, "fixture-user", "ADMIN");
        User user = new User();
        user.setPasswordHash(SECRET);
        Printer printer = new Printer();
        printer.setApiKey(SECRET);
        PrinterAddDTO add = new PrinterAddDTO();
        add.setApiKey(SECRET);
        PrinterUpdateDTO update = new PrinterUpdateDTO();
        update.setApiKey(SECRET);
        PrinterScanResultDTO scan = new PrinterScanResultDTO();
        scan.setApiKey(SECRET);
        BatchDispatchConfirmRequest confirm = new BatchDispatchConfirmRequest();
        confirm.setConfirmationToken(SECRET);
        DispatchPlanPreviewVO preview = new DispatchPlanPreviewVO();
        preview.setConfirmationToken(SECRET);
        DispatchPlan plan = new DispatchPlan();
        plan.setConfirmationTokenHash(SECRET);
        PrintFile file = new PrintFile();
        file.setRustfsKey(SECRET);
        file.setSafeName(SECRET);
        file.setFileUrl(SECRET);

        for (Object value : List.of(login, register, change, result, user, printer, add,
                update, scan, confirm, preview, plan, file, new BootstrapTokenDTO(SECRET, 123L))) {
            assertThat(String.valueOf(value)).doesNotContain(SECRET);
        }
        assertThat(login.toString()).contains("fixture-user");
    }
}
