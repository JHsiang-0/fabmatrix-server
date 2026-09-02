package com.example.farm.service;

import com.example.farm.entity.PrintJob;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
