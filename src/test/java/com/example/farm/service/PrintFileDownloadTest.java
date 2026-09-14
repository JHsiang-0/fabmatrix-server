package com.example.farm.service;

import com.example.farm.common.utils.RustFsClient;
import com.example.farm.config.FileUploadProperties;
import com.example.farm.entity.PrintFile;
import com.example.farm.mapper.PrintFileMapper;
import com.example.farm.service.impl.PrintFileServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.InputStreamResource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrintFileDownloadTest {

    @Mock
    private PrintFileMapper printFileMapper;

    @Mock
    private RustFsClient rustFsClient;

    private PrintFileServiceImpl printFileService;

    @BeforeEach
    void setUp() {
        printFileService = new PrintFileServiceImpl(rustFsClient, new FileUploadProperties());
        ReflectionTestUtils.setField(printFileService, "baseMapper", printFileMapper);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void localThumbnailKeyReadsThumbnailInsteadOfMainFile() {
        String thumbnailKey = "thumbnails/thumbnail.png";
        String safeName = "20_model.gcode";
        PrintFile file = new PrintFile();
        file.setId(20L);
        file.setUserId(1L);
        file.setIsFolder(false);
        file.setVisibility("PUBLIC");
        file.setSafeName(safeName);
        file.setThumbnailUrl("local://" + thumbnailKey);

        InputStreamResource thumbnail = new InputStreamResource(
                new ByteArrayInputStream(new byte[]{1, 2, 3}));
        when(printFileMapper.selectOne(any(), eq(true))).thenReturn(null, file);
        when(printFileMapper.selectById(20L)).thenReturn(file);
        when(rustFsClient.getFileStream(thumbnailKey)).thenReturn(thumbnail);

        InputStreamResource result = printFileService.downloadBySafeName(thumbnailKey);

        assertThat(result).isSameAs(thumbnail);
        verify(rustFsClient).getFileStream(thumbnailKey);
        verify(rustFsClient, never()).getFileStream(safeName);
    }
}
