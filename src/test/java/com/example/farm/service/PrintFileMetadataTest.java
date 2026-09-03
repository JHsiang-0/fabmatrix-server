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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrintFileMetadataTest {

    @Mock
    private PrintFileMapper printFileMapper;

    @Mock
    private RustFsClient rustFsClient;

    private PrintFileServiceImpl printFileService;

    @BeforeEach
    void setUp() {
        printFileService = new PrintFileServiceImpl(rustFsClient, new FileUploadProperties());
        ReflectionTestUtils.setField(printFileService, "baseMapper", printFileMapper);
        mockUser(1L);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void storesShortStandardMillimeterLengthAsMeters() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "short.gcode", "text/plain",
                "; filament used [mm] = 500\n".getBytes(StandardCharsets.UTF_8));
        when(rustFsClient.uploadFile(anyString(), any())).thenReturn("http://rustfs/internal");
        doAnswer(invocation -> {
            PrintFile saved = invocation.getArgument(0);
            saved.setId(20L);
            return 1;
        }).when(printFileMapper).insert(any(PrintFile.class));

        PrintFile saved = printFileService.uploadAndParseFile(file);

        assertThat(saved.getFilamentLength()).isEqualByComparingTo("0.50");
    }

    @Test
    void cleansUploadedObjectWhenDatabaseRecordCannotBeSaved() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "database-failure.gcode", "text/plain",
                "G1 X1\n".getBytes(StandardCharsets.UTF_8));
        when(rustFsClient.uploadFile(anyString(), any())).thenReturn("http://rustfs/internal");
        doThrow(new IllegalStateException("database unavailable"))
                .when(printFileMapper).insert(any(PrintFile.class));

        assertThatThrownBy(() -> printFileService.uploadAndParseFile(file))
                .hasMessage("database unavailable");

        verify(rustFsClient).deleteFile(anyString());
    }

    @Test
    void rejectsBlankFolderNameAsValidationError() {
        assertThatThrownBy(() -> printFileService.createFolder(null, "  "))
                .hasMessage("文件夹名称不能为空")
                .extracting("code")
                .isEqualTo(400L);
    }

    @Test
    void batchUploadReturnsPerItemResultsAndKeepsSuccessfulFile() {
        MockMultipartFile valid = new MockMultipartFile(
                "files", "valid.gcode", "text/plain", "G1 X1\n".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile invalid = new MockMultipartFile(
                "files", "invalid.txt", "text/plain", "not supported".getBytes(StandardCharsets.UTF_8));
        when(rustFsClient.uploadFile(anyString(), any())).thenReturn("http://rustfs/internal");
        doAnswer(invocation -> {
            PrintFile saved = invocation.getArgument(0);
            saved.setId(21L);
            return 1;
        }).when(printFileMapper).insert(any(PrintFile.class));

        var result = printFileService.batchUploadFiles(List.of(valid, invalid));

        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getFailureCount()).isEqualTo(1);
        assertThat(result.getItems()).hasSize(2);
        assertThat(result.getItems().get(0).getFileId()).isEqualTo(21L);
        assertThat(result.getItems().get(0).getStatus()).isEqualTo("SUCCEEDED");
        assertThat(result.getItems().get(1).getErrorCode()).isEqualTo("FILE_VALIDATION_FAILED");
    }

    @Test
    void batchUploadRejectsTooManyFilesBeforeProcessing() {
        FileUploadProperties properties = new FileUploadProperties();
        properties.setBatchMaxFiles(1);
        printFileService = new PrintFileServiceImpl(rustFsClient, properties);
        ReflectionTestUtils.setField(printFileService, "baseMapper", printFileMapper);

        MockMultipartFile first = new MockMultipartFile("files", "a.gcode", "text/plain", "G1 X1".getBytes());
        MockMultipartFile second = new MockMultipartFile("files", "b.gcode", "text/plain", "G1 X2".getBytes());

        assertThatThrownBy(() -> printFileService.batchUploadFiles(List.of(first, second)))
                .hasMessage("单次最多上传1个文件")
                .extracting("code")
                .isEqualTo(400L);
        verify(rustFsClient, org.mockito.Mockito.never()).uploadFile(anyString(), any());
    }

    private void mockUser(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))));
    }
}
