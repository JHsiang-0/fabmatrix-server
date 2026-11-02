package com.example.farm.controller;

import com.example.farm.common.api.Result;
import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.utils.MacAddressUtil;
import com.example.farm.entity.dto.PrinterAddDTO;
import com.example.farm.protocol.PrinterProtocolDetector;
import com.example.farm.protocol.PrinterProtocolType;
import com.example.farm.service.PrinterService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 手动新增时探测协议；只提供管理员使用的只读探测和受控新增入口。 */
@RestController
@RequestMapping("/api/v1/printers")
public class PrinterProtocolDetectionController {
    private final PrinterProtocolDetector detector;
    private final PrinterService printerService;

    public PrinterProtocolDetectionController(PrinterProtocolDetector detector, PrinterService printerService) {
        this.detector = detector;
        this.printerService = printerService;
    }

    public record ProbeRequest(@NotBlank String ipAddress) { }
    public record ProbeResult(String firmwareType) { }

    @PostMapping("/detect-protocol")
    public Result<ProbeResult> detect(@Valid @RequestBody ProbeRequest request) {
        PrinterProtocolType type = probe(request.ipAddress());
        return Result.success(new ProbeResult(type == null ? null : type.name()));
    }

    @PostMapping("/add-detected")
    public Result<String> add(@Valid @RequestBody PrinterAddDTO request) {
        PrinterProtocolType type = probe(request.getIpAddress());
        if (type == null) throw new BusinessException(422, "无法识别设备协议，请检查设备连接后重试");
        request.setFirmwareType(type.name());
        printerService.addPrinter(request);
        return Result.success(null, "打印机添加成功");
    }

    private PrinterProtocolType probe(String ipAddress) {
        if (!MacAddressUtil.isIpv4Address(ipAddress)) throw new BusinessException(400, "请输入有效的 IPv4 地址");
        return detector.detect(ipAddress);
    }
}
