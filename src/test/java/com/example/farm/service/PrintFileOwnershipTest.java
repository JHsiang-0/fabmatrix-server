package com.example.farm.service;

import com.example.farm.common.utils.RustFsClient;
import com.example.farm.entity.PrintFile;
import com.example.farm.mapper.PrintFileMapper;
import com.example.farm.service.impl.PrintFileServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrintFileOwnershipTest {

    @Mock
    private PrintFileMapper printFileMapper;

    @Mock
    private RustFsClient rustFsClient;

    @InjectMocks
    private PrintFileServiceImpl printFileService;

    @org.junit.jupiter.api.BeforeEach
    void injectMyBatisPlusBaseMapper() {
        // ServiceImpl 的 baseMapper 位于父类，Mockito 不会自动注入该继承字段。
        ReflectionTestUtils.setField(printFileService, "baseMapper", printFileMapper);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void operatorCannotDownloadAnotherUsersFile() {
        mockUser(2L, "OPERATOR");
        PrintFile file = file(20L, 1L);
        when(printFileMapper.selectById(20L)).thenReturn(file);

        assertThatThrownBy(() -> printFileService.getPresignedDownloadUrl(20L, 60))
                .hasMessage("文件不存在");
        verify(rustFsClient, never()).getPresignedUrl(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void adminCanDownloadAnotherUsersFile() {
        mockUser(2L, "ADMIN");
        PrintFile file = file(20L, 1L);
        when(printFileMapper.selectById(20L)).thenReturn(file);
        when(rustFsClient.getPresignedUrl("20_demo.gcode", java.time.Duration.ofMinutes(60)))
                .thenReturn("https://example.test/download");

        printFileService.getPresignedDownloadUrl(20L, 60);

        verify(rustFsClient).getPresignedUrl("20_demo.gcode", java.time.Duration.ofMinutes(60));
    }

    private PrintFile file(Long id, Long userId) {
        PrintFile file = new PrintFile();
        file.setId(id);
        file.setUserId(userId);
        file.setSafeName("20_demo.gcode");
        file.setIsFolder(false);
        return file;
    }

    private void mockUser(Long userId, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
