package com.example.farm.controller;

import com.example.farm.common.api.Result;
import com.example.farm.service.PrinterBatchDeletionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 管理员批量删除设备；不删除历史任务或执行记录。 */
@RestController
@RequestMapping("/api/v1/printers")
public class PrinterBatchDeletionController {
    private final PrinterBatchDeletionService service;

    public PrinterBatchDeletionController(PrinterBatchDeletionService service) { this.service = service; }

    public record Request(@NotEmpty @Size(max = 100) List<@NotNull @Positive Long> ids) { }

    @PostMapping("/batch-delete")
    public Result<PrinterBatchDeletionService.Result> delete(@Valid @RequestBody Request request) {
        return Result.success(service.delete(request.ids()));
    }
}
