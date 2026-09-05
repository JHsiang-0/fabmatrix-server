package com.example.farm.service;

import com.example.farm.entity.AuditLog;
import com.example.farm.mapper.AuditLogMapper;
import com.example.farm.service.impl.AuditLogServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.slf4j.MDC;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditLogServiceTest {

    private final AuditLogMapper auditLogMapper = mock(AuditLogMapper.class);
    private final AuditLogServiceImpl auditLogService = new AuditLogServiceImpl(auditLogMapper);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    void recordsSafeMetadataFromAuthenticatedActorForCriticalJobAction() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(7L, "operator",
                        List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))));
        MDC.put("traceId", "trace-205");
        when(auditLogMapper.insert(any(AuditLog.class))).thenReturn(1);

        auditLogService.recordSuccess("JOB_SAFE_CONFIRM", "PRINTER", 12L,
                "打印机\n12\tready");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        AuditLog record = captor.getValue();
        assertThat(record.getActorId()).isEqualTo(7L);
        assertThat(record.getActorUsername()).isEqualTo("operator");
        assertThat(record.getActorRole()).isEqualTo("OPERATOR");
        assertThat(record.getAction()).isEqualTo("JOB_SAFE_CONFIRM");
        assertThat(record.getTargetId()).isEqualTo("12");
        assertThat(record.getTargetLabel()).isEqualTo("打印机 12 ready");
        assertThat(record.getResult()).isEqualTo("SUCCESS");
        assertThat(record.getTraceId()).isEqualTo("trace-205");
        assertThat(record.getClass().getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .doesNotContain("password", "token", "requestBody", "responseBody", "stackTrace");
    }
}
