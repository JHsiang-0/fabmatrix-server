package com.example.farm.service;

import com.example.farm.common.utils.RustFsClient;
import com.example.farm.config.FileUploadProperties;
import com.example.farm.entity.PrintFile;
import com.example.farm.entity.vo.PrintFilePreviewVO;
import com.example.farm.mapper.PrintFileMapper;
import com.example.farm.service.AuditLogService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrintFileOwnershipTest {

    @Mock
    private PrintFileMapper printFileMapper;

    @Mock
    private RustFsClient rustFsClient;

    @Mock
    private FileUploadProperties fileUploadProperties;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private PrintFileServiceImpl printFileService;

    @org.junit.jupiter.api.BeforeEach
    void injectMyBatisPlusBaseMapper() {
        // ServiceImpl 的 baseMapper 位于父类，Mockito 不会自动注入该继承字段。
        ReflectionTestUtils.setField(printFileService, "baseMapper", printFileMapper);
        ReflectionTestUtils.setField(printFileService, "auditLogService", auditLogService);
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

    @Test
    void downloadExpirationIsCappedByConfiguredMaximum() {
        mockUser(1L, "OPERATOR");
        PrintFile file = file(20L, 1L);
        when(printFileMapper.selectById(20L)).thenReturn(file);
        when(fileUploadProperties.getPresignedUrlMaxMinutes()).thenReturn(120);
        when(rustFsClient.getPresignedUrl("20_demo.gcode", java.time.Duration.ofMinutes(120)))
                .thenReturn("https://example.test/download");

        printFileService.getPresignedDownloadUrl(20L, 1000);

        verify(rustFsClient).getPresignedUrl("20_demo.gcode", java.time.Duration.ofMinutes(120));
    }

    @Test
    void operatorCannotGetThumbnailForAnotherUsersFile() {
        mockUser(2L, "OPERATOR");
        PrintFile file = file(20L, 1L);
        file.setThumbnailUrl("http://rustfs:9000/farm/thumbnails/20_demo.jpeg");
        when(printFileMapper.selectById(20L)).thenReturn(file);

        assertThatThrownBy(() -> printFileService.getPresignedThumbnailUrl(20L, 60))
                .hasMessage("文件不存在");
        verify(rustFsClient, never()).getPresignedUrlForObjectUrl(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void thumbnailUrlIsPermissionCheckedAndExpirationIsCapped() {
        mockUser(1L, "OPERATOR");
        PrintFile file = file(20L, 1L);
        String thumbnailUrl = "http://rustfs:9000/farm/thumbnails/20_demo.jpeg";
        file.setThumbnailUrl(thumbnailUrl);
        when(printFileMapper.selectById(20L)).thenReturn(file);
        when(fileUploadProperties.getPresignedUrlMaxMinutes()).thenReturn(120);
        when(rustFsClient.getPresignedUrlForObjectUrl(thumbnailUrl, java.time.Duration.ofMinutes(120)))
                .thenReturn("https://example.test/thumbnail");

        assertThat(printFileService.getPresignedThumbnailUrl(20L, 1000))
                .isEqualTo("https://example.test/thumbnail");

        verify(rustFsClient).getPresignedUrlForObjectUrl(thumbnailUrl, java.time.Duration.ofMinutes(120));
    }

    @Test
    void presignedUrlStorageFailureIsPropagatedForUnifiedHandler() {
        mockUser(1L, "OPERATOR");
        PrintFile file = file(20L, 1L);
        when(printFileMapper.selectById(20L)).thenReturn(file);
        when(rustFsClient.getPresignedUrl("20_demo.gcode", java.time.Duration.ofMinutes(60)))
                .thenThrow(new com.example.farm.common.exception.StorageException("生成预签名 URL 失败"));

        assertThatThrownBy(() -> printFileService.getPresignedDownloadUrl(20L, 60))
                .isInstanceOf(com.example.farm.common.exception.StorageException.class);
    }

    @Test
    void operatorCanPreviewOwnFileMetadataWithoutStorageFields() {
        mockUser(1L, "OPERATOR");
        PrintFile file = file(20L, 1L);
        file.setOriginalName("demo.gcode");
        file.setFileSize(123L);
        file.setMaterialType("PLA");
        file.setSafeName("internal-name");
        file.setRustfsKey("internal/key");
        when(printFileMapper.selectById(20L)).thenReturn(file);

        PrintFilePreviewVO preview = printFileService.getPreview(20L);

        assertThat(preview.getId()).isEqualTo(20L);
        assertThat(preview.getOriginalName()).isEqualTo("demo.gcode");
        assertThat(preview.getFileSize()).isEqualTo(123L);
        assertThat(preview).hasNoNullFieldsOrPropertiesExcept(
                "estTime", "nozzleSize", "filamentWeight", "filamentLength",
                "nozzleTemp", "bedTemp", "layerHeight", "firstLayerNozzleTemp",
                "firstLayerBedTemp", "firstLayerHeight");
    }

    @Test
    void folderCannotBePreviewedAsFile() {
        mockUser(1L, "OPERATOR");
        PrintFile folder = file(20L, 1L);
        folder.setIsFolder(true);
        when(printFileMapper.selectById(20L)).thenReturn(folder);

        assertThatThrownBy(() -> printFileService.getPreview(20L))
                .hasMessage("目录不支持文件预览");
    }

    @Test
    void defaultFolderCannotBeDeleted() {
        mockUser(1L, "OPERATOR");
        PrintFile folder = file(30L, 1L);
        folder.setIsFolder(true);
        folder.setIsDefaultFolder(true);
        when(printFileMapper.selectById(30L)).thenReturn(folder);

        assertThatThrownBy(() -> printFileService.deleteFolder(30L))
                .hasMessage("默认目录不可删除");
        verify(printFileMapper, never()).countChildrenByParentId(30L);
        verify(printFileMapper, never()).deleteEmptyFolder(30L, 1L, false);
        verify(auditLogService, never()).recordSuccess(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void operatorCannotDeleteAnotherUsersFolder() {
        mockUser(2L, "OPERATOR");
        PrintFile folder = file(30L, 1L);
        folder.setIsFolder(true);
        folder.setIsDefaultFolder(false);
        when(printFileMapper.selectById(30L)).thenReturn(folder);

        assertThatThrownBy(() -> printFileService.deleteFolder(30L))
                .hasMessage("文件不存在");
        verify(printFileMapper, never()).countChildrenByParentId(30L);
        verify(printFileMapper, never()).deleteEmptyFolder(30L, 2L, false);
        verify(auditLogService, never()).recordSuccess(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void nonEmptyFolderCannotBeDeleted() {
        mockUser(1L, "OPERATOR");
        PrintFile folder = file(30L, 1L);
        folder.setIsFolder(true);
        folder.setIsDefaultFolder(false);
        when(printFileMapper.selectById(30L)).thenReturn(folder);
        when(printFileMapper.countChildrenByParentId(30L)).thenReturn(1);

        assertThatThrownBy(() -> printFileService.deleteFolder(30L))
                .hasMessage("文件夹非空，包含文件或子目录，无法删除");
        verify(printFileMapper, never()).deleteEmptyFolder(30L, 1L, false);
        verify(auditLogService, never()).recordSuccess(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void emptyFolderIsDeletedWithoutTouchingObjectStorageAndIsAudited() {
        mockUser(1L, "OPERATOR");
        PrintFile folder = file(30L, 1L);
        folder.setIsFolder(true);
        folder.setIsDefaultFolder(false);
        folder.setOriginalName("Empty folder");
        when(printFileMapper.selectById(30L)).thenReturn(folder);
        when(printFileMapper.countChildrenByParentId(30L)).thenReturn(0);
        when(printFileMapper.deleteEmptyFolder(30L, 1L, false)).thenReturn(1);

        printFileService.deleteFolder(30L);

        verify(printFileMapper).deleteEmptyFolder(30L, 1L, false);
        verify(rustFsClient, never()).deleteFile(org.mockito.ArgumentMatchers.anyString());
        verify(rustFsClient, never()).deleteFileByObjectUrl(org.mockito.ArgumentMatchers.anyString());
        verify(auditLogService).recordSuccess("FOLDER_DELETE", "FOLDER", 30L, "Empty folder");
    }

    @Test
    void renamesOwnFolderWithoutTouchingObjectStorageAndAuditsChange() {
        mockUser(1L, "OPERATOR");
        PrintFile folder = folder(30L, 1L, null, "Before");
        when(printFileMapper.selectById(30L)).thenReturn(folder);
        when(printFileMapper.selectOne(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(false)))
                .thenReturn(null);
        org.mockito.Mockito.doReturn(1).when(printFileMapper)
                .updateById(org.mockito.ArgumentMatchers.any(PrintFile.class));

        PrintFile result = printFileService.renameFolder(30L, " After ");

        assertThat(result.getOriginalName()).isEqualTo("After");
        verify(printFileMapper).updateById(org.mockito.ArgumentMatchers.any(PrintFile.class));
        verify(rustFsClient, never()).deleteFile(org.mockito.ArgumentMatchers.anyString());
        verify(auditLogService).recordSuccess("FOLDER_RENAME", "FOLDER", 30L, "After");
    }

    @Test
    void refusesRenamingDefaultFolderAndSameLevelDuplicate() {
        mockUser(1L, "OPERATOR");
        PrintFile defaultFolder = folder(30L, 1L, null, "我的文件夹");
        defaultFolder.setIsDefaultFolder(true);
        when(printFileMapper.selectById(30L)).thenReturn(defaultFolder);

        assertThatThrownBy(() -> printFileService.renameFolder(30L, "新的名称"))
                .hasMessage("默认目录不可重命名");
        verify(printFileMapper, never()).updateById(org.mockito.ArgumentMatchers.any(PrintFile.class));

        PrintFile folder = folder(31L, 1L, null, "Before");
        when(printFileMapper.selectById(31L)).thenReturn(folder);
        when(printFileMapper.selectOne(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(false)))
                .thenReturn(folder(32L, 1L, null, "After"));

        assertThatThrownBy(() -> printFileService.renameFolder(31L, "After"))
                .hasMessage("该目录下已存在同名文件夹");
    }

    @Test
    void defaultFolderCannotBeMoved() {
        mockUser(1L, "OPERATOR");
        PrintFile defaultFolder = folder(30L, 1L, null, "operator的文件");
        defaultFolder.setIsDefaultFolder(true);
        when(printFileMapper.selectById(30L)).thenReturn(defaultFolder);

        assertThatThrownBy(() -> printFileService.moveFolder(30L, null))
                .hasMessage("默认目录不可移动");
        verify(printFileMapper, never()).updateById(org.mockito.ArgumentMatchers.any(PrintFile.class));
    }

    @Test
    void movesFolderWithinOwnerAndAuditsWithoutTouchingObjectStorage() {
        mockUser(1L, "ADMIN");
        PrintFile source = folder(30L, 1L, null, "Source");
        PrintFile target = folder(40L, 1L, null, "Target");
        when(printFileMapper.selectById(30L)).thenReturn(source);
        when(printFileMapper.selectById(40L)).thenReturn(target);
        when(printFileMapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(source, target));
        when(printFileMapper.selectOne(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(false)))
                .thenReturn(null);
        org.mockito.Mockito.doReturn(1).when(printFileMapper)
                .updateById(org.mockito.ArgumentMatchers.any(PrintFile.class));

        PrintFile result = printFileService.moveFolder(30L, 40L);

        assertThat(result.getParentId()).isEqualTo(40L);
        verify(printFileMapper).updateById(org.mockito.ArgumentMatchers.any(PrintFile.class));
        verify(rustFsClient, never()).deleteFile(org.mockito.ArgumentMatchers.anyString());
        verify(auditLogService).recordSuccess("FOLDER_MOVE", "FOLDER", 30L, "Source");
    }

    @Test
    void rejectsInvalidFolderMoves() {
        mockUser(1L, "ADMIN");
        PrintFile source = folder(30L, 1L, null, "Source");
        PrintFile child = folder(31L, 1L, 30L, "Child");
        PrintFile target = folder(40L, 1L, null, "Target");
        PrintFile otherUserTarget = folder(50L, 2L, null, "Other");
        when(printFileMapper.selectById(30L)).thenReturn(source);
        when(printFileMapper.selectById(31L)).thenReturn(child);
        when(printFileMapper.selectById(40L)).thenReturn(target);
        when(printFileMapper.selectById(50L)).thenReturn(otherUserTarget);
        when(printFileMapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(source, child, target));

        assertThatThrownBy(() -> printFileService.moveFolder(30L, 31L))
                .hasMessage("移动目标必须是一级文件夹");
        assertThatThrownBy(() -> printFileService.moveFolder(30L, 40L))
                .hasMessage("移动后文件夹最多支持两级目录");
        assertThatThrownBy(() -> printFileService.moveFolder(30L, 50L))
                .hasMessage("目标文件夹必须属于源文件夹用户");
        verify(printFileMapper, never()).updateById(org.mockito.ArgumentMatchers.any(PrintFile.class));
    }

    @Test
    void cannotDeleteFileAlreadyReferencedByPrintJob() {
        mockUser(1L, "OPERATOR");
        PrintFile file = file(20L, 1L);
        when(printFileMapper.selectById(20L)).thenReturn(file);
        when(printFileMapper.countPrintJobsByFileId(20L, null, null)).thenReturn(1);

        assertThatThrownBy(() -> printFileService.deleteFile(20L))
                .hasMessage("文件已关联打印任务，禁止删除");
        verify(rustFsClient, never()).deleteFile(org.mockito.ArgumentMatchers.anyString());
        verify(printFileMapper, never()).deleteById(20L);
    }

    @Test
    void storageDeleteFailureDoesNotRemoveDatabaseRecord() {
        mockUser(1L, "OPERATOR");
        PrintFile file = file(20L, 1L);
        when(printFileMapper.selectById(20L)).thenReturn(file);
        when(printFileMapper.countPrintJobsByFileId(20L, null, null)).thenReturn(0);
        doThrow(new com.example.farm.common.exception.StorageException("对象存储删除失败"))
                .when(rustFsClient).deleteFile("20_demo.gcode");

        assertThatThrownBy(() -> printFileService.deleteFile(20L))
                .isInstanceOf(com.example.farm.common.exception.StorageException.class);
        verify(printFileMapper, never()).deleteById(20L);
    }

    @Test
    void databaseDeleteFailureDoesNotReturnSuccess() {
        mockUser(1L, "OPERATOR");
        PrintFile file = file(20L, 1L);
        when(printFileMapper.selectById(20L)).thenReturn(file);
        when(printFileMapper.countPrintJobsByFileId(20L, null, null)).thenReturn(0);
        when(printFileMapper.deleteById(20L)).thenReturn(0);

        assertThatThrownBy(() -> printFileService.deleteFile(20L))
                .hasMessage("文件记录删除失败");

        verify(rustFsClient).deleteFile("20_demo.gcode");
    }

    @Test
    void deletingFileAlsoRemovesStoredThumbnail() {
        mockUser(1L, "OPERATOR");
        PrintFile file = file(20L, 1L);
        String thumbnailUrl = "http://rustfs:9000/farm/thumbnails/20_demo.jpeg";
        file.setThumbnailUrl(thumbnailUrl);
        when(printFileMapper.selectById(20L)).thenReturn(file);
        when(printFileMapper.countPrintJobsByFileId(20L, null, null)).thenReturn(0);
        when(printFileMapper.deleteById(20L)).thenReturn(1);

        printFileService.deleteFile(20L);

        verify(rustFsClient).deleteFileByObjectUrl(thumbnailUrl);
        verify(rustFsClient).deleteFile("20_demo.gcode");
        verify(printFileMapper).deleteById(20L);
    }

    @Test
    void batchDeleteReportsReferencedFileWithoutBlockingOtherFiles() {
        mockUser(1L, "OPERATOR");
        PrintFile referenced = file(20L, 1L);
        PrintFile removable = file(21L, 1L);
        removable.setSafeName("21_demo.gcode");
        when(printFileMapper.selectById(20L)).thenReturn(referenced);
        when(printFileMapper.selectById(21L)).thenReturn(removable);
        when(printFileMapper.countPrintJobsByFileId(20L, null, null)).thenReturn(1);
        when(printFileMapper.countPrintJobsByFileId(21L, null, null)).thenReturn(0);
        when(printFileMapper.deleteById(21L)).thenReturn(1);

        PrintFileService.BatchDeleteResult result = printFileService.batchDeleteFiles(List.of(20L, 21L));

        assertThat(result.getDeletedCount()).isEqualTo(1);
        assertThat(result.getFailedCount()).isEqualTo(1);
        assertThat(result.getItems()).extracting(PrintFileService.BatchDeleteItemResult::getId)
                .containsExactly(20L, 21L);
        verify(rustFsClient, never()).deleteFile("20_demo.gcode");
        verify(rustFsClient).deleteFile("21_demo.gcode");
    }

    @Test
    void batchMoveReportsFolderAndAlreadyMovedFileWithoutTouchingObjectStorage() {
        mockUser(1L, "OPERATOR");
        PrintFile folder = folder(30L, 1L, null, "Folder");
        PrintFile alreadyMoved = file(20L, 1L);
        alreadyMoved.setParentId(40L);
        PrintFile movable = file(21L, 1L);
        PrintFile target = folder(40L, 1L, null, "Target");
        when(printFileMapper.selectById(30L)).thenReturn(folder);
        when(printFileMapper.selectById(20L)).thenReturn(alreadyMoved);
        when(printFileMapper.selectById(21L)).thenReturn(movable);
        when(printFileMapper.selectById(40L)).thenReturn(target);
        org.mockito.Mockito.doReturn(1).when(printFileMapper)
                .update(org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any());

        PrintFileService.BatchMoveResult result = printFileService.batchMoveFiles(
                List.of(30L, 20L, 21L), 40L);

        assertThat(result.getMovedCount()).isEqualTo(1);
        assertThat(result.getFailedCount()).isEqualTo(2);
        assertThat(result.getItems()).extracting(PrintFileService.BatchMoveItemResult::getId)
                .containsExactly(30L, 20L, 21L);
        verify(printFileMapper).update(org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any());
        verify(printFileMapper, never()).updateById(org.mockito.ArgumentMatchers.any(PrintFile.class));
        verify(rustFsClient, never()).deleteFile(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void batchMoveRejectsCrossUserTargetForEachFile() {
        mockUser(1L, "ADMIN");
        PrintFile file = file(20L, 1L);
        PrintFile target = folder(40L, 2L, null, "Other user target");
        when(printFileMapper.selectById(40L)).thenReturn(target);
        when(printFileMapper.selectById(20L)).thenReturn(file);

        PrintFileService.BatchMoveResult result = printFileService.batchMoveFiles(List.of(20L), 40L);

        assertThat(result.getMovedCount()).isZero();
        assertThat(result.getFailedCount()).isEqualTo(1);
        assertThat(result.getItems().get(0).getReason()).isEqualTo("目标文件夹必须属于文件所属用户");
        verify(printFileMapper, never()).updateById(org.mockito.ArgumentMatchers.any(PrintFile.class));
        verify(rustFsClient, never()).deleteFile(org.mockito.ArgumentMatchers.anyString());
    }

    private PrintFile file(Long id, Long userId) {
        PrintFile file = new PrintFile();
        file.setId(id);
        file.setUserId(userId);
        file.setSafeName("20_demo.gcode");
        file.setIsFolder(false);
        return file;
    }

    private PrintFile folder(Long id, Long userId, Long parentId, String name) {
        PrintFile folder = file(id, userId);
        folder.setParentId(parentId);
        folder.setIsFolder(true);
        folder.setIsDefaultFolder(false);
        folder.setOriginalName(name);
        return folder;
    }

    private void mockUser(Long userId, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
