package com.example.farm.service;

import com.example.farm.common.exception.BusinessException;
import com.example.farm.entity.vo.PrinterModelVO;
import com.example.farm.protocol.model.CanonicalPrinterModel;
import com.example.farm.protocol.model.CanonicalPrinterModelProjection;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/** 查询已持久化的设备模型，不在 HTTP 请求中触发设备采集。 */
@Service
@RequiredArgsConstructor
public class PrinterModelService {
    private static final Duration FRESHNESS = Duration.ofSeconds(30);

    private final PrinterService printerService;
    private final ObjectMapper objectMapper;
    // 统一模型持久化仅由 local profile 提供，未启用时返回无采样状态。
    private final ObjectProvider<CanonicalPrinterStateService> stateServiceProvider;

    public PrinterModelVO getModel(Long printerId) {
        if (printerId == null || printerId <= 0) {
            throw new BusinessException(400, "打印机 ID 必须为正整数");
        }
        if (printerService.getById(printerId) == null) {
            throw new BusinessException(404, "打印机不存在");
        }
        var stateService = stateServiceProvider.getIfAvailable();
        var model = stateService == null ? null : stateService.findLatest(printerId);
        Instant now = Instant.now();
        if (model == null) {
            return new PrinterModelVO(now, true, null);
        }
        if (!printerId.toString().equals(model.identity().localPrinterId())) {
            throw new BusinessException(500, "设备模型身份不一致");
        }
        var observation = model.observation();
        var connection = observation.connection();
        Instant observedAt = observation.observedAt();
        boolean stale = !connection.monitorEnabled()
                || connection.status() != CanonicalPrinterModel.ConnectionStatus.ONLINE
                || observedAt == null || observedAt.isBefore(now.minus(FRESHNESS))
                || observedAt.isAfter(now.plusSeconds(5));
        // JSON 标量边界，避免 HTTP 的 Jackson 3 把内部 Jackson 2 JsonNode 当作 Java Bean 输出。
        return new PrinterModelVO(now, stale, objectMapper.convertValue(
                CanonicalPrinterModelProjection.project(model), new TypeReference<>() { }));
    }
}
