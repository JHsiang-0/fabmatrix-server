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
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
        lenient().when(printFileMapper.selectList(any())).thenReturn(List.of(defaultFolder(10L)));
        lenient().when(printFileMapper.selectById(10L)).thenReturn(defaultFolder(10L));
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

    @Test
    void acceptsOneGigabyteFileAndUsesSamplingWithoutReadingFullContent() throws Exception {
        MultipartFile file = sizedFile("boundary.gcode", 1024L * 1024 * 1024);
        stubSmallInput(file);
        when(rustFsClient.uploadFile(anyString(), any())).thenReturn("http://rustfs/internal");
        doAnswer(invocation -> {
            PrintFile saved = invocation.getArgument(0);
            saved.setId(22L);
            return 1;
        }).when(printFileMapper).insert(any(PrintFile.class));

        PrintFile saved = printFileService.uploadAndParseFile(file);

        assertThat(saved.getFileSize()).isEqualTo(1024L * 1024 * 1024);
        verify(file, never()).getBytes();
        verify(file, times(2)).getInputStream();
    }

    @Test
    void rejectsFileAboveOneGigabyte() throws Exception {
        MultipartFile file = sizedFile("too-large.gcode", 1024L * 1024 * 1024 + 1);

        assertThatThrownBy(() -> printFileService.uploadAndParseFile(file))
                .hasMessage("文件大小不能超过1GB")
                .extracting("code")
                .isEqualTo(400L);
        verify(rustFsClient, never()).uploadFile(anyString(), any());
    }

    @Test
    void allowsBatchTotalAboveOneGigabyteWhileKeepingPerFileLimit() throws Exception {
        MultipartFile first = sizedFile("first.gcode", 600L * 1024 * 1024);
        MultipartFile second = sizedFile("second.gcode", 600L * 1024 * 1024);
        stubSmallInput(first);
        stubSmallInput(second);
        when(rustFsClient.uploadFile(anyString(), any())).thenReturn("http://rustfs/internal");
        doAnswer(invocation -> {
            PrintFile saved = invocation.getArgument(0);
            saved.setId((long) (30 + invocation.getArgument(0, PrintFile.class).getOriginalName().length()));
            return 1;
        }).when(printFileMapper).insert(any(PrintFile.class));

        var result = printFileService.batchUploadFiles(List.of(first, second));

        assertThat(result.getSuccessCount()).isEqualTo(2);
        assertThat(result.getFailureCount()).isZero();
        verify(first, never()).getBytes();
        verify(second, never()).getBytes();
    }

    private MultipartFile sizedFile(String filename, long size) {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(size);
        lenient().when(file.getOriginalFilename()).thenReturn(filename);
        return file;
    }

    private void stubSmallInput(MultipartFile file) throws IOException {
        when(file.getInputStream()).thenAnswer(invocation ->
                new ByteArrayInputStream("G1 X1\n".getBytes(StandardCharsets.UTF_8)));
    }

    private void mockUser(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))));
    }

    private PrintFile defaultFolder(Long id) {
        PrintFile folder = new PrintFile();
        folder.setId(id);
        folder.setUserId(1L);
        folder.setParentId(null);
        folder.setIsFolder(true);
        folder.setIsDefaultFolder(true);
        folder.setOriginalName("我的文件夹");
        return folder;
    }
}
