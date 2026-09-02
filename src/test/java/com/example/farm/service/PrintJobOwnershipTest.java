package com.example.farm.service;

import com.example.farm.entity.PrintJob;
import com.example.farm.entity.PrintFile;
import com.example.farm.entity.dto.request.FileJobsQueryDTO;
import com.example.farm.mapper.PrintFileMapper;
import com.example.farm.mapper.PrintJobMapper;
import com.example.farm.service.impl.PrintJobServiceImpl;
import com.example.farm.service.PrinterService;
import com.example.farm.common.utils.MoonrakerApiClient;
import com.example.farm.common.utils.RustFsClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrintJobOwnershipTest {

    @Mock
    private PrintJobMapper printJobMapper;

    @Mock
    private PrintFileMapper printFileMapper;

    @Mock
    private PrinterService printerService;

    @Mock
    private RustFsClient rustFsClient;

    @Mock
    private MoonrakerApiClient moonrakerApiClient;

    @Mock
    private WebSocketEventPublisher eventPublisher;

    @InjectMocks
    private PrintJobServiceImpl printJobService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void operatorCannotReadAnotherUsersJob() {
        mockUser(2L, "OPERATOR");
        when(printJobMapper.selectById(100L)).thenReturn(job(100L, 1L));

        assertThatThrownBy(() -> printJobService.getAccessibleJob(100L))
                .hasMessage("任务不存在");
    }

    @Test
    void operatorCanReadOwnJob() {
        mockUser(1L, "OPERATOR");
        PrintJob job = job(100L, 1L);
        when(printJobMapper.selectById(100L)).thenReturn(job);

        assertThat(printJobService.getAccessibleJob(100L)).isSameAs(job);
    }

    @Test
    void adminCanReadAnyJob() {
        mockUser(2L, "ADMIN");
        PrintJob job = job(100L, 1L);
        when(printJobMapper.selectById(100L)).thenReturn(job);

        assertThat(printJobService.getAccessibleJob(100L)).isSameAs(job);
    }

    @Test
    void operatorCanQueryOnlyOwnFileJobs() {
        mockUser(1L, "OPERATOR");
        PrintFile file = new PrintFile();
        file.setId(20L);
        file.setUserId(1L);
        Page<PrintJob> page = new Page<>(1, 10);
        when(printFileMapper.selectById(20L)).thenReturn(file);
        when(printJobMapper.selectPageByFileId(any(Page.class), eq(20L), eq(1L), eq(false)))
                .thenReturn(page);

        Page<PrintJob> actual = printJobService.queryJobsByFileId(20L, new FileJobsQueryDTO());

        assertThat(actual).isSameAs(page);
        verify(printJobMapper).selectPageByFileId(any(Page.class), eq(20L), eq(1L), eq(false));
    }

    @Test
    void operatorCannotQueryAnotherUsersFileJobs() {
        mockUser(2L, "OPERATOR");
        PrintFile file = new PrintFile();
        file.setId(20L);
        file.setUserId(1L);
        when(printFileMapper.selectById(20L)).thenReturn(file);

        assertThatThrownBy(() -> printJobService.queryJobsByFileId(20L, new FileJobsQueryDTO()))
                .hasMessage("文件不存在");
        verify(printJobMapper, never()).selectPageByFileId(any(Page.class), eq(20L), eq(2L), eq(false));
    }

    private PrintJob job(Long id, Long userId) {
        PrintJob job = new PrintJob();
        job.setId(id);
        job.setUserId(userId);
        return job;
    }

    private void mockUser(Long userId, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
