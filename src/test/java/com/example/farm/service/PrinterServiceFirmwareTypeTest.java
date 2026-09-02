package com.example.farm.service;

import com.example.farm.common.utils.MacAddressUtil;
import com.example.farm.entity.Printer;
import com.example.farm.entity.dto.PrinterAddDTO;
import com.example.farm.entity.dto.PrinterUpdateDTO;
import com.example.farm.mapper.PrinterMapper;
import com.example.farm.service.impl.PrinterServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrinterServiceFirmwareTypeTest {

    @Mock
    private PrinterMapper printerMapper;
    @Mock
    private PrinterCacheService printerCacheService;
    @Mock
    private MacAddressUtil macAddressUtil;

    @InjectMocks
    private PrinterServiceImpl printerService;

    @BeforeEach
    void injectMyBatisMapper() {
        ReflectionTestUtils.setField(printerService, "baseMapper", printerMapper);
    }

    @Test
    void addPrinterStoresCanonicalFirmwareType() {
        PrinterAddDTO request = new PrinterAddDTO();
        request.setName("rrf-01");
        request.setIpAddress("192.168.1.80");
        request.setMacAddress("AA-BB-CC-DD-EE-FF");
        request.setFirmwareType("rrf");
        when(macAddressUtil.normalizeMacAddress("AA-BB-CC-DD-EE-FF"))
                .thenReturn("aa:bb:cc:dd:ee:ff");
        when(printerMapper.selectByMacAddress("aa:bb:cc:dd:ee:ff")).thenReturn(null);
        when(printerMapper.selectByIpAddress("192.168.1.80")).thenReturn(null);
        when(printerMapper.insert(any(Printer.class))).thenReturn(1);

        printerService.addPrinter(request);

        ArgumentCaptor<Printer> printer = ArgumentCaptor.forClass(Printer.class);
        verify(printerMapper).insert(printer.capture());
        assertThat(printer.getValue().getFirmwareType()).isEqualTo("RRF");
        assertThat(printer.getValue().getStatus()).isEqualTo("UNKNOWN");
    }

    @Test
    void addPrinterFailsWhenDatabaseInsertAffectsNoRows() {
        PrinterAddDTO request = new PrinterAddDTO();
        request.setName("rrf-01");
        request.setIpAddress("192.168.1.80");
        request.setMacAddress("AA-BB-CC-DD-EE-FF");
        request.setFirmwareType("RRF");
        when(macAddressUtil.normalizeMacAddress("AA-BB-CC-DD-EE-FF"))
                .thenReturn("aa:bb:cc:dd:ee:ff");
        when(printerMapper.selectByMacAddress("aa:bb:cc:dd:ee:ff")).thenReturn(null);
        when(printerMapper.selectByIpAddress("192.168.1.80")).thenReturn(null);
        when(printerMapper.insert(any(Printer.class))).thenReturn(0);

        assertThatThrownBy(() -> printerService.addPrinter(request))
                .hasMessage("新增打印机失败");
    }

    @Test
    void updatePrinterCanonicalizesLegacyKlipperAndPreservesTypeWhenOmitted() {
        Printer existing = new Printer();
        existing.setId(403L);
        existing.setName("klipper-01");
        existing.setIpAddress("192.168.1.80");
        existing.setFirmwareType("Klipper");
        existing.setApiKey("device-secret");
        when(printerMapper.selectById(403L)).thenReturn(existing);
        when(printerMapper.updateById(any(Printer.class))).thenReturn(1);

        PrinterUpdateDTO request = new PrinterUpdateDTO();
        request.setId(403L);
        request.setName("klipper-01-renamed");
        request.setIpAddress("192.168.1.80");
        request.setFirmwareType(null);

        printerService.updatePrinter(request);

        assertThat(existing.getFirmwareType()).isEqualTo("KLIPPER");
        assertThat(existing.getApiKey()).isEqualTo("device-secret");
        verify(printerMapper).updateById(existing);
    }

    @Test
    void updatePrinterFailsWhenDatabaseUpdateAffectsNoRows() {
        Printer existing = new Printer();
        existing.setId(403L);
        existing.setIpAddress("192.168.1.80");
        existing.setFirmwareType("Klipper");
        when(printerMapper.selectById(403L)).thenReturn(existing);
        when(printerMapper.updateById(any(Printer.class))).thenReturn(0);

        PrinterUpdateDTO request = new PrinterUpdateDTO();
        request.setId(403L);
        request.setName("klipper-01-renamed");
        request.setIpAddress("192.168.1.80");

        assertThatThrownBy(() -> printerService.updatePrinter(request))
                .hasMessage("更新打印机失败");
    }

    @Test
    void batchUpsertUsesUnknownUntilProtocolProbe() {
        var scan = com.example.farm.entity.dto.PrinterScanResultDTO.of(
                "192.168.1.81", "AA-BB-CC-DD-EE-11", true);
        scan.setFirmwareType("KLIPPER");
        when(macAddressUtil.normalizeMacAddress("AA-BB-CC-DD-EE-11"))
                .thenReturn("aa:bb:cc:dd:ee:11");
        when(printerMapper.selectByIpAddress("192.168.1.81")).thenReturn(null);
        when(printerMapper.upsertByMacAddress(any(Printer.class))).thenReturn(1);

        printerService.batchUpsertPrinters(java.util.List.of(scan));

        ArgumentCaptor<Printer> printer = ArgumentCaptor.forClass(Printer.class);
        verify(printerMapper).upsertByMacAddress(printer.capture());
        assertThat(printer.getValue().getStatus()).isEqualTo("UNKNOWN");
    }

    @Test
    void rejectsInvalidSubnetBeforeStartingDeviceScan() {
        assertThatThrownBy(() -> printerService.scanDevices("192.168.1.999"))
                .hasMessage("网段前缀必须是三段 IPv4 地址，例如 192.168.1");
    }

    @Test
    void rejectsMissingPrinterQuery() {
        assertThatThrownBy(() -> printerService.pagePrinters(null))
                .hasMessage("打印机查询参数不能为空")
                .extracting("code")
                .isEqualTo(400L);
    }
}
