package com.example.farm.service;

import com.example.farm.common.utils.RedisUtil;
import com.example.farm.entity.DispatchPlanItem;
import com.example.farm.entity.DispatchPlan;
import com.example.farm.entity.PrintFile;
import com.example.farm.entity.Printer;
import com.example.farm.entity.dto.request.BatchDispatchConfirmRequest;
import com.example.farm.entity.dto.request.BatchDispatchPreviewRequest;
import com.example.farm.mapper.DispatchPlanItemMapper;
import com.example.farm.mapper.DispatchPlanMapper;
import com.example.farm.mapper.PrintFileMapper;
import com.example.farm.service.impl.DispatchPlanServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DispatchPlanServiceTest {

    @Mock
    private DispatchPlanMapper planMapper;
    @Mock
    private DispatchPlanItemMapper itemMapper;
    @Mock
    private PrintFileMapper printFileMapper;
    @Mock
    private PrinterService printerService;
    @Mock
    private PrintJobService printJobService;
    @Mock
    private RedisUtil redisUtil;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void previewPersistsPlanAndHasNoTaskOrDeviceSideEffect() {
        mockUser(1L, "OPERATOR");
        PrintFile file = file(10L);
        Printer printer = printer(564L);
        when(printFileMapper.selectBatchIds(List.of(10L))).thenReturn(List.of(file));
        when(printerService.listByIds(List.of(564L))).thenReturn(List.of(printer));
        when(planMapper.insert(any(com.example.farm.entity.DispatchPlan.class))).thenReturn(1);
        when(itemMapper.insert(any(DispatchPlanItem.class))).thenReturn(1);

        BatchDispatchPreviewRequest request = request("QUEUE");
        request.setStrategy("ONE_TO_ONE");
        var result = service().preview(request);

        assertThat(result.getPlanId()).startsWith("dp_");
        assertThat(result.getConfirmationToken()).isNotBlank();
        assertThat(result.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getCanExecute()).isTrue();
            assertThat(item.getPrinterId()).isEqualTo(564L);
        });
        verify(printJobService, never()).createJob(any(), any());
    }

    @Test
    void previewReportsBusyPrinterAsConflictWithoutChangingIt() {
        mockUser(1L, "OPERATOR");
        when(printFileMapper.selectBatchIds(List.of(10L))).thenReturn(List.of(file(10L)));
        Printer printer = printer(564L);
        printer.setStatus("PRINTING");
        when(printerService.listByIds(List.of(564L))).thenReturn(List.of(printer));
        when(planMapper.insert(any(com.example.farm.entity.DispatchPlan.class))).thenReturn(1);
        when(itemMapper.insert(any(DispatchPlanItem.class))).thenReturn(1);

        var result = service().preview(request("UPLOAD_ONLY"));

        assertThat(result.getConflicts()).singleElement().satisfies(conflict ->
                assertThat(conflict.getReasonCode()).isEqualTo("PRINTER_UNAVAILABLE"));
        verify(printerService, never()).updateById(any());
        verify(printJobService, never()).createJob(any(), any());
    }

    @Test
    void confirmCreatesOneAssignedJobAndRepeatedConfirmReturnsStoredResult() {
        mockUser(1L, "OPERATOR");
        when(printFileMapper.selectBatchIds(anyList())).thenReturn(List.of(file(10L)));
        Printer printer = printer(564L);
        when(printerService.listByIds(anyList())).thenReturn(List.of(printer));
        when(printerService.getById(564L)).thenReturn(printer);
        when(planMapper.insert(any(DispatchPlan.class))).thenAnswer(invocation -> {
            capturedPlan = invocation.getArgument(0);
            return 1;
        });
        when(itemMapper.insert(any(DispatchPlanItem.class))).thenAnswer(invocation -> {
            capturedItem = invocation.getArgument(0);
            return 1;
        });
        when(itemMapper.selectList(any())).thenAnswer(invocation -> List.of(capturedItem));
        when(itemMapper.updateById(any(DispatchPlanItem.class))).thenReturn(1);
        when(redisUtil.tryLock(any(), any(), any(Long.class), any())).thenReturn(true);
        when(printJobService.createJob(any(), any())).thenReturn(9001L);

        var preview = service().preview(request("QUEUE"));
        BatchDispatchConfirmRequest confirm = new BatchDispatchConfirmRequest();
        confirm.setPlanId(preview.getPlanId());
        confirm.setVersion(preview.getVersion());
        confirm.setItemIds(List.of(capturedItem.getId()));
        confirm.setConfirmationToken(preview.getConfirmationToken());
        when(planMapper.selectById(preview.getPlanId())).thenReturn(capturedPlan);

        var result = service().confirm(confirm);

        assertThat(result.getRepeated()).isFalse();
        assertThat(result.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getJobId()).isEqualTo(9001L);
            assertThat(item.getStatus()).isEqualTo("ASSIGNED");
        });
        verify(printJobService).createJob(any(), any());

        var repeated = service().confirm(confirm);
        assertThat(repeated.getRepeated()).isTrue();
        assertThat(repeated.getItems()).singleElement()
                .extracting(item -> item.getJobId()).isEqualTo(9001L);
        verify(printJobService).createJob(any(), any());
    }

    private DispatchPlanServiceImpl service() {
        return new DispatchPlanServiceImpl(planMapper, itemMapper, printFileMapper,
                printerService, printJobService, redisUtil);
    }

    private DispatchPlanItem capturedItem;
    private DispatchPlan capturedPlan;

    private BatchDispatchPreviewRequest request(String action) {
        BatchDispatchPreviewRequest request = new BatchDispatchPreviewRequest();
        request.setFileIds(List.of(10L));
        request.setPrinterIds(List.of(564L));
        request.setStrategy("ONE_TO_ONE");
        request.setAction(action);
        return request;
    }

    private PrintFile file(Long id) {
        PrintFile file = new PrintFile();
        file.setId(id);
        file.setOriginalName("cube.gcode");
        file.setUserId(1L);
        file.setMaterialType("PLA");
        file.setNozzleSize(new BigDecimal("0.40"));
        return file;
    }

    private Printer printer(Long id) {
        Printer printer = new Printer();
        printer.setId(id);
        printer.setName("rrf-01");
        printer.setStatus("IDLE");
        printer.setCurrentMaterial("PLA");
        printer.setNozzleSize(new BigDecimal("0.40"));
        return printer;
    }

    private void mockUser(Long userId, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
