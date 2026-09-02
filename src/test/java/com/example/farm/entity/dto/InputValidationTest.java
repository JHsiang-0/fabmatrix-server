package com.example.farm.entity.dto;

import com.example.farm.entity.dto.request.AssignJobRequest;
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
}
