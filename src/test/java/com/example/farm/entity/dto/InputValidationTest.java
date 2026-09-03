package com.example.farm.entity.dto;

import com.example.farm.entity.dto.request.AssignJobRequest;
import com.example.farm.entity.dto.request.BatchDispatchConfirmRequest;
import com.example.farm.entity.dto.request.BatchDispatchPreviewRequest;
import com.example.farm.entity.dto.request.CreateFolderRequest;
import com.example.farm.entity.dto.request.StartPrintJobRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class InputValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @AfterAll
    static void tearDown() {
        validator = null;
    }

    @Test
    void rejectsInvalidPrinterConfiguration() {
        PrinterAddDTO dto = new PrinterAddDTO();
        dto.setName(" ");
        dto.setIpAddress("192.168.1.999");
        dto.setMacAddress("invalid");
        dto.setFirmwareType("OCTOPRINT");
        dto.setGridRow(5);
        dto.setGridCol(0);

        Set<jakarta.validation.ConstraintViolation<PrinterAddDTO>> violations = validator.validate(dto);

        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .contains("name", "ipAddress", "macAddress", "firmwareType", "gridRow", "gridCol");
    }

    @Test
    void rejectsInvalidJobAndFolderRequests() {
        PrintJobCreateDTO job = new PrintJobCreateDTO();
        job.setFileId(0L);
        job.setPriority(101);

        AssignJobRequest assign = new AssignJobRequest();
        assign.setJobId(0L);
        assign.setPrinterId(null);

        CreateFolderRequest folder = new CreateFolderRequest();
        folder.setParentId(0L);
        folder.setFolderName("bad/name");

        StartPrintJobRequest start = new StartPrintJobRequest();
        start.setJobId(1L);
        start.setAction("PRINT_NOW");

        assertThat(validator.validate(job)).hasSize(2);
        assertThat(validator.validate(assign)).hasSize(2);
        assertThat(validator.validate(folder)).hasSize(2);
        assertThat(validator.validate(start)).hasSize(1);
    }

    @Test
    void validatesBatchDispatchBoundsAndRequiredFields() {
        BatchDispatchPreviewRequest preview = new BatchDispatchPreviewRequest();
        preview.setStrategy("UNKNOWN");
        preview.setFileIds(java.util.List.of(0L));
        preview.setPrinterIds(java.util.List.of());

        BatchDispatchConfirmRequest confirm = new BatchDispatchConfirmRequest();
        confirm.setPlanId(" ");
        confirm.setVersion(0L);
        confirm.setItemIds(java.util.List.of(" "));
        confirm.setConfirmationToken(" ");

        assertThat(validator.validate(preview)).extracting(v -> v.getPropertyPath().toString())
                .contains("fileIds[0].<list element>", "printerIds", "strategy");
        assertThat(validator.validate(confirm)).extracting(v -> v.getPropertyPath().toString())
                .contains("planId", "version", "itemIds[0].<list element>", "confirmationToken");
    }
}
