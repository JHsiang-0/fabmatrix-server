package com.example.farm.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.farm.common.utils.MacAddressUtil;
import com.example.farm.entity.Printer;
import com.example.farm.entity.dto.PrinterAddDTO;
import com.example.farm.entity.dto.PrinterQueryDTO;
import com.example.farm.entity.dto.PrinterUpdateDTO;
import com.example.farm.mapper.PrinterMapper;
import com.example.farm.protocol.PrinterProtocolDetector;
import com.example.farm.protocol.PrinterEndpoint;
import com.example.farm.protocol.PrinterProtocolType;
import com.example.farm.protocol.RrfApiClient;
import com.example.farm.service.impl.PrinterServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
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
    @Mock
    private PrinterProtocolDetector protocolDetector;
    @Mock
    private RrfApiClient rrfApiClient;

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
        assertThat(printer.getValue().getGridRow()).isNull();
        assertThat(printer.getValue().getGridCol()).isNull();
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
        existing.setMacAddress("aa:bb:cc:dd:ee:ff");
        existing.setApiKey("device-secret");
        existing.setGridRow(2);
        existing.setGridCol(3);
        when(printerMapper.selectById(403L)).thenReturn(existing);
        when(printerMapper.updateById(any(Printer.class))).thenReturn(1);

        PrinterUpdateDTO request = new PrinterUpdateDTO();
        request.setId(403L);
        request.setName("klipper-01-renamed");
        request.setIpAddress("192.168.1.80");
        request.setFirmwareType(null);

        printerService.updatePrinter(request);

        assertThat(existing.getFirmwareType()).isEqualTo("KLIPPER");
        assertThat(existing.getMacAddress()).isEqualTo("aa:bb:cc:dd:ee:ff");
        assertThat(existing.getApiKey()).isEqualTo("device-secret");
        assertThat(existing.getGridRow()).isEqualTo(2);
        assertThat(existing.getGridCol()).isEqualTo(3);
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
    void batchUpsertWithoutMacUpdatesExistingIpInsteadOfCreatingDuplicate() {
        Printer existing = new Printer();
        existing.setId(81L);
        existing.setName("existing-rrf");
        existing.setIpAddress("192.168.1.81");
        existing.setFirmwareType("RRF");

        var scan = com.example.farm.entity.dto.PrinterScanResultDTO.of(
                "192.168.1.81", null, true);
        scan.setFirmwareType("RRF");
        scan.setSuggestedName("existing-rrf");

        when(printerMapper.selectByIpAddress("192.168.1.81")).thenReturn(existing);
        when(printerMapper.updateById(existing)).thenReturn(1);

        PrinterService.BatchUpsertResult result = printerService.batchUpsertPrinters(List.of(scan));

        assertThat(result.getInsertedCount()).isZero();
        assertThat(result.getUpdatedCount()).isEqualTo(1);
        assertThat(result.getFailedCount()).isZero();
        verify(printerMapper).updateById(existing);
        verify(printerMapper, never()).insert(any(Printer.class));
        verify(macAddressUtil, never()).getMacAddress(anyString());
    }

    @Test
    void batchUpsertBackfillsRrfMacForExistingIp() {
        Printer existing = new Printer();
        existing.setId(81L);
        existing.setName("existing-rrf");
        existing.setIpAddress("192.168.1.81");
        existing.setFirmwareType("RRF");

        var scan = com.example.farm.entity.dto.PrinterScanResultDTO.of(
                "192.168.1.81", "5C:8E:8B:10:CB:FD", false);
        scan.setFirmwareType("RRF");
        scan.setSuggestedName("existing-rrf");

        when(macAddressUtil.normalizeMacAddress("5C:8E:8B:10:CB:FD"))
                .thenReturn("5c:8e:8b:10:cb:fd");
        when(printerMapper.selectByMacAddress("5c:8e:8b:10:cb:fd")).thenReturn(null);
        when(printerMapper.selectByIpAddress("192.168.1.81")).thenReturn(existing);
        when(printerMapper.updateById(existing)).thenReturn(1);

        PrinterService.BatchUpsertResult result = printerService.batchUpsertPrinters(List.of(scan));

        assertThat(result.getInsertedCount()).isZero();
        assertThat(result.getUpdatedCount()).isEqualTo(1);
        assertThat(existing.getMacAddress()).isEqualTo("5c:8e:8b:10:cb:fd");
        verify(printerMapper).updateById(existing);
        verify(printerMapper, never()).insert(any(Printer.class));
    }

    @Test
    void scanWithoutMacClassifiesExistingIpAsKnownDevice() {
        Printer existing = new Printer();
        existing.setId(81L);
        existing.setName("existing-rrf");
        existing.setIpAddress("192.168.1.81");
        existing.setFirmwareType("RRF");

        when(printerMapper.selectList(any())).thenReturn(List.of(existing));
        when(protocolDetector.detect(anyString())).thenAnswer(invocation ->
                "192.168.1.81".equals(invocation.getArgument(0)) ? PrinterProtocolType.RRF : null);
        when(macAddressUtil.getMacAddress("192.168.1.81")).thenReturn(null);

        var results = printerService.scanDevices("192.168.1");

        assertThat(results).singleElement().satisfies(device -> {
            assertThat(device.getIpAddress()).isEqualTo("192.168.1.81");
            assertThat(device.getMacAddress()).isNull();
            assertThat(device.getIsNewDevice()).isFalse();
            assertThat(device.getStatus()).isEqualTo("EXISTING");
            assertThat(device.getSuggestedName()).isEqualTo("existing-rrf");
        });
    }

    @Test
    void scanMatchesRrfAtNewIpByNetworkModelMac() {
        Printer existing = new Printer();
        existing.setId(81L);
        existing.setName("existing-rrf");
        existing.setIpAddress("192.168.0.117");
        existing.setMacAddress("5c:8e:8b:10:cb:fd");
        existing.setFirmwareType("RRF");

        when(printerMapper.selectList(any())).thenReturn(List.of(existing));
        when(protocolDetector.detect(anyString())).thenAnswer(invocation ->
                "192.168.1.81".equals(invocation.getArgument(0)) ? PrinterProtocolType.RRF : null);
        when(rrfApiClient.getNetworkIdentity(any(PrinterEndpoint.class)))
                .thenReturn(new RrfApiClient.RrfNetworkIdentity(
                        "192.168.1.81", "5C:8E:8B:10:CB:FD"));
        when(macAddressUtil.normalizeMacAddress(anyString()))
                .thenAnswer(invocation -> invocation.<String>getArgument(0).toLowerCase(Locale.ROOT));

        var results = printerService.scanDevices("192.168.1");

        assertThat(results).singleElement().satisfies(device -> {
            assertThat(device.getIpAddress()).isEqualTo("192.168.1.81");
            assertThat(device.getMacAddress()).isEqualTo("5c:8e:8b:10:cb:fd");
            assertThat(device.getIsNewDevice()).isFalse();
            assertThat(device.getStatus()).isEqualTo("EXISTING");
            assertThat(device.getSuggestedName()).isEqualTo("existing-rrf");
        });
        verify(macAddressUtil, never()).getMacAddress("192.168.1.81");
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

    @Test
    void rejectsMissingPrinterIpAsValidationError() {
        PrinterAddDTO request = new PrinterAddDTO();

        assertThatThrownBy(() -> printerService.addPrinter(request))
                .hasMessage("IP 地址不能为空")
                .extracting("code")
                .isEqualTo(400L);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void pagePrintersFiltersByCanonicalFirmwareType() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "printer-query-test"), Printer.class);
        PrinterQueryDTO query = new PrinterQueryDTO();
        query.setFirmwareType("rrf");
        when(printerMapper.selectPage(any(Page.class), any(Wrapper.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        printerService.pagePrinters(query);

        ArgumentCaptor<LambdaQueryWrapper> wrapper = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(printerMapper).selectPage(any(Page.class), wrapper.capture());
        assertThat(wrapper.getValue().getSqlSegment()).contains("firmware_type");
        assertThat(wrapper.getValue().getParamNameValuePairs()).containsValue("RRF");
    }

    @Test
    void confirmSafeUsesConditionalUpdateAndRefreshesCache() {
        when(printerMapper.confirmSafeIfBound(403L, 1001L)).thenReturn(1);

        assertThat(printerService.confirmSafeIfBound(403L, 1001L)).isTrue();

        verify(printerMapper).confirmSafeIfBound(403L, 1001L);
        verify(printerCacheService).refreshPrinterCache();
        verify(printerMapper, never()).updateById(any(Printer.class));
    }

    @Test
    void repeatedSafeConfirmationIsIdempotentWhenBindingIsUnchanged() {
        Printer current = new Printer();
        current.setId(403L);
        current.setCurrentJobId(1001L);
        current.setIsSafeToPrint(true);
        when(printerMapper.confirmSafeIfBound(403L, 1001L)).thenReturn(0);
        when(printerMapper.selectById(403L)).thenReturn(current);

        assertThat(printerService.confirmSafeIfBound(403L, 1001L)).isTrue();

        verify(printerCacheService, never()).refreshPrinterCache();
        verify(printerMapper, never()).updateById(any(Printer.class));
    }
}
