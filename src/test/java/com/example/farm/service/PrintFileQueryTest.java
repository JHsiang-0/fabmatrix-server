package com.example.farm.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.farm.common.utils.RustFsClient;
import com.example.farm.config.FileUploadProperties;
import com.example.farm.entity.PrintFile;
import com.example.farm.entity.dto.PrintFileQueryDTO;
import com.example.farm.mapper.PrintFileMapper;
import com.example.farm.service.impl.PrintFileServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrintFileQueryTest {

    @Mock
    private PrintFileMapper printFileMapper;

    @Mock
    private RustFsClient rustFsClient;

    @Mock
    private FileUploadProperties fileUploadProperties;

    private PrintFileServiceImpl printFileService;

    @BeforeEach
    void setUp() {
        printFileService = new PrintFileServiceImpl(rustFsClient, fileUploadProperties);
        ReflectionTestUtils.setField(printFileService, "baseMapper", printFileMapper);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void normalizesFileNameAndMaterialTypeBeforeBuildingQuery() {
        mockUser(1L, "ADMIN");
        when(printFileMapper.selectFilePage(any(), eq(1L), eq(true), isNull(), eq("cube"), eq("PLA")))
                .thenReturn(new Page<>(1, 10));

        PrintFileQueryDTO query = new PrintFileQueryDTO();
        query.setFileName("  cube  ");
        query.setMaterialType(" pla ");

        printFileService.pageFiles(query);

        verify(printFileMapper).selectFilePage(any(Page.class), eq(1L), eq(true), isNull(),
                eq("cube"), eq("PLA"));
    }

    @Test
    void operatorQueryAlwaysIncludesOwnerFilterAndIgnoresRequestedUserId() {
        mockUser(7L, "OPERATOR");
        when(printFileMapper.selectFilePage(any(), eq(7L), eq(false), isNull(), isNull(), isNull()))
                .thenReturn(new Page<>(1, 10));

        PrintFileQueryDTO query = new PrintFileQueryDTO();
        query.setUserId(99L);

        printFileService.pageFiles(query);

        verify(printFileMapper).selectFilePage(any(Page.class), eq(7L), eq(false), isNull(), isNull(), isNull());
    }

    private void mockUser(Long userId, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
