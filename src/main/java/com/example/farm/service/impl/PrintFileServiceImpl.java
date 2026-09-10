package com.example.farm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.exception.StorageException;
import com.example.farm.common.utils.GCodeParser;
import com.example.farm.common.storage.FileStorage;
import com.example.farm.common.utils.SecurityContextUtil;
import com.example.farm.common.utils.PrintFileAccess;
import com.example.farm.config.FileUploadProperties;
import com.example.farm.entity.PrintFile;
import com.example.farm.entity.dto.PrintFileQueryDTO;
import com.example.farm.entity.vo.FileNodeVO;
import com.example.farm.entity.vo.PrintFilePreviewVO;
import com.example.farm.mapper.PrintFileMapper;
import com.example.farm.service.PrintFileService;
import com.example.farm.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class PrintFileServiceImpl extends ServiceImpl<PrintFileMapper, PrintFile> implements PrintFileService {

    private static final String PRIVATE = "PRIVATE";
    private static final String PUBLIC = "PUBLIC";

    private static final int META_SAMPLE_SIZE = 8192;
    private static final int DEEP_TAIL_SAMPLE_SIZE = 512 * 1024; // 512KB
    private static final long FULL_PARSE_MAX_BYTES = 100L * 1024 * 1024; // 100MB
    private static final int MAX_BATCH_SIZE = 100;
    private static final int MAX_FILENAME_LENGTH = 255;

    private final FileStorage rustFsClient;
    private final FileUploadProperties fileUploadProperties;

    @Autowired(required = false)
    private AuditLogService auditLogService;

    @Override
    public List<PrintFile> getFolderContent(Long parentId) {
        Long currentUserId = SecurityContextUtil.getCurrentUserId();
        // parentId 为 NULL 时查询根目录（parent_id IS NULL）
        LambdaQueryWrapper<PrintFile> wrapper = new LambdaQueryWrapper<>();
        if (!SecurityContextUtil.isAdmin()) {
            wrapper.and(query -> query.eq(PrintFile::getVisibility, PUBLIC)
                    .or().eq(PrintFile::getUserId, currentUserId));
        }
        if (parentId == null) {
            wrapper.isNull(PrintFile::getParentId);
        } else {
            requireAccessibleFolder(parentId);
            wrapper.eq(PrintFile::getParentId, parentId);
        }
        // 文件夹排在前面，文件按创建时间倒序
        wrapper.orderByDesc(PrintFile::getIsFolder)
                .orderByDesc(PrintFile::getCreatedAt);
        List<PrintFile> result = this.list(wrapper).stream()
                .filter(this::isAccessibleRecord)
                .toList();
        result.forEach(file -> enrichCapabilities(file, null));
        return result;
    }

    @Override
    public List<FileNodeVO> getFileTree() {
        Long currentUserId = SecurityContextUtil.getCurrentUserId();
        boolean admin = SecurityContextUtil.isAdmin();
        List<PrintFile> files = baseMapper.selectAccessibleFileTree(currentUserId, admin);
        if (files == null || files.isEmpty()) {
            return List.of();
        }

        Map<Long, FileNodeVO> nodes = new LinkedHashMap<>();
        Map<Long, PrintFile> filesById = new LinkedHashMap<>();
        for (PrintFile file : files) {
            if (file.getId() == null || !Boolean.TRUE.equals(file.getIsFolder())) {
                continue;
            }
            if (!isAccessibleRecord(file)) {
                log.warn("忽略无效或无权访问的文件夹树节点: folderId={}", file.getId());
                continue;
            }
            enrichCapabilities(file, null);
            nodes.put(file.getId(), FileNodeVO.from(file));
            filesById.put(file.getId(), file);
        }

        List<FileNodeVO> roots = new ArrayList<>();
        for (PrintFile file : files) {
            FileNodeVO node = nodes.get(file.getId());
            if (node == null) {
                continue;
            }
            if (hasInvalidFolderPath(file, filesById)) {
                log.warn("忽略异常文件夹树节点: folderId={}, parentId={}, userId={}",
                        file.getId(), file.getParentId(), file.getUserId());
                continue;
            }
            int level = getFolderLevel(file, filesById);
            if (level < 1 || level > 2) {
                log.warn("忽略超过两级的文件夹树节点: folderId={}, level={}", file.getId(), level);
                continue;
            }
            node.setLevel(level);
            if (file.getParentId() == null) {
                roots.add(node);
                continue;
            }
            FileNodeVO parent = nodes.get(file.getParentId());
            if (parent != null) {
                parent.getChildren().add(node);
            }
        }
        return roots;
    }

    @Override
    public PrintFilePreviewVO getPreview(Long id) {
        PrintFile file = getAccessibleFile(id);
        if (Boolean.TRUE.equals(file.getIsFolder())) {
            throw new BusinessException(422, "目录不支持文件预览");
        }
        enrichCapabilities(file, null);
        return PrintFilePreviewVO.from(file);
    }

    private boolean hasInvalidFolderPath(PrintFile file, Map<Long, PrintFile> filesById) {
        Set<Long> visited = new HashSet<>();
        Long ancestorId = file.getParentId();
        while (ancestorId != null) {
            if (Objects.equals(ancestorId, file.getId()) || !visited.add(ancestorId)) {
                return true;
            }
            PrintFile ancestor = filesById.get(ancestorId);
            if (ancestor == null) {
                return true;
            }
            if (!sameVisibility(file, ancestor)) {
                return true;
            }
            ancestorId = ancestor.getParentId();
        }
        return false;
    }

    private int getFolderLevel(PrintFile file, Map<Long, PrintFile> filesById) {
        int level = 1;
        Set<Long> visited = new HashSet<>();
        Long ancestorId = file.getParentId();
        while (ancestorId != null) {
            if (!visited.add(ancestorId)) {
                return -1;
            }
            PrintFile ancestor = filesById.get(ancestorId);
            if (ancestor == null) {
                return -1;
            }
            level++;
            ancestorId = ancestor.getParentId();
        }
        return level;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PrintFile createFolder(Long parentId, String folderName) {
        return createFolder(parentId, folderName, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PrintFile createFolder(Long parentId, String folderName, String requestedVisibility) {
        Long currentUserId = SecurityContextUtil.getCurrentUserId();
        if (folderName == null || folderName.isBlank()) {
            throw new BusinessException(400, "文件夹名称不能为空");
        }

        PrintFile parent = parentId == null ? null : requireFolderParent(parentId);
        String visibility = parent == null
                ? normalizeVisibility(requestedVisibility)
                : normalizeVisibility(parent.getVisibility());
        if (parent == null && PUBLIC.equals(visibility) && !SecurityContextUtil.isAdmin()) {
            throw new BusinessException(403, "只有管理员可以创建公共目录");
        }
        if (parent != null && requestedVisibility != null
                && !visibility.equalsIgnoreCase(requestedVisibility.trim())) {
            throw new BusinessException(403, "目录可见性必须与父目录一致");
        }

        String normalizedFolderName = folderName.trim();
        if (normalizedFolderName.length() > 100 || containsUnsafeFolderCharacter(normalizedFolderName)) {
            throw new BusinessException(400, "文件夹名称长度或格式不正确");
        }

        // 检查同名文件夹是否已存在（复用 originalName 字段存储文件夹名称）
        LambdaQueryWrapper<PrintFile> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PrintFile::getIsFolder, true)
                .eq(PrintFile::getOriginalName, normalizedFolderName);

        applyFolderNameNamespace(wrapper, parentId, visibility, currentUserId);

        PrintFile existing = this.getOne(wrapper, false);
        if (existing != null) {
            throw new BusinessException(409, "该目录下已存在同名文件夹");
        }

        PrintFile folder = new PrintFile();
        folder.setParentId(parentId);
        folder.setIsFolder(true);
        folder.setIsDefaultFolder(false);
        folder.setVisibility(visibility);
        folder.setOriginalName(normalizedFolderName); // 复用 originalName 字段存储文件夹名称
        folder.setSafeName("folder_" + System.currentTimeMillis()); // 文件夹占位符
        folder.setUserId(currentUserId);
        folder.setCreatedAt(LocalDateTime.now());

        if (!this.save(folder)) {
            throw new BusinessException("创建文件夹失败");
        }
        enrichCapabilities(folder, null);
        log.info("创建文件夹成功: folderId={}, parentId={}, level={}, name={}",
                folder.getId(), parentId, parent == null ? 1 : 2, normalizedFolderName);
        return folder;
    }

    @Override
    public Page<PrintFile> pageFiles(PrintFileQueryDTO queryDTO) {
        if (queryDTO == null) {
            throw new BusinessException(400, "文件查询参数不能为空");
        }
        if (queryDTO.getParentId() != null) {
            requirePageFolderParent(queryDTO.getParentId());
        }
        Long userId = SecurityContextUtil.getCurrentUserId();
        Page<PrintFile> page = new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize());

        String fileName = normalizeFileName(queryDTO.getFileName());
        String materialType = normalizeMaterialType(queryDTO.getMaterialType());

        boolean admin = SecurityContextUtil.isAdmin();
        Long filterUserId = admin ? queryDTO.getUserId() : null;

        // 使用显式 Mapper SQL，固定 fileName/materialType 到真实数据库列的映射。
        Page<PrintFile> resultPage = baseMapper.selectFilePage(
                page, userId, admin, filterUserId, fileName, materialType, queryDTO.getParentId(), queryDTO.getFolder());

        // 统计每个文件的打印次数和成功率
        if (resultPage.getRecords() != null && !resultPage.getRecords().isEmpty()) {
            for (PrintFile printFile : resultPage.getRecords()) {
                calculatePrintStats(printFile);
            }
        }

        List<PrintFile> accessibleRecords = resultPage.getRecords().stream()
                .filter(this::isAccessibleRecord)
                .toList();
        resultPage.setRecords(accessibleRecords);
        resultPage.getRecords().forEach(file -> enrichCapabilities(file, null));
        return resultPage;
    }

    private String normalizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        return fileName.trim();
    }

    private String normalizeMaterialType(String materialType) {
        if (materialType == null || materialType.isBlank()) {
            return null;
        }
        return materialType.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 计算打印统计信息（打印次数和成功率）
     */
    private void calculatePrintStats(PrintFile printFile) {
        if (printFile.getId() == null) {
            return;
        }

        Long userId = SecurityContextUtil.isAdmin() ? null : SecurityContextUtil.getCurrentUserId();
        Long fileId = printFile.getId();

        // 只统计已结束的打印尝试；排队和执行中的任务不应影响文件成功率。
        Integer completedCount = baseMapper.countPrintJobsByFileId(fileId, userId, "COMPLETED");
        Integer failedCount = baseMapper.countPrintJobsByFileId(fileId, userId, "FAILED");
        Integer cancelledCount = baseMapper.countPrintJobsByFileId(fileId, userId, "CANCELLED");

        int completed = completedCount != null ? completedCount : 0;
        int failed = failedCount != null ? failedCount : 0;
        int cancelled = cancelledCount != null ? cancelledCount : 0;
        int totalCount = completed + failed + cancelled;

        printFile.setPrintCount(totalCount);

        // 成功率 = 完成数 /（完成数 + 失败数）* 100；取消任务不计入分母。
        int evaluatedCount = completed + failed;
        if (evaluatedCount > 0) {
            BigDecimal rate = new BigDecimal(completed)
                    .divide(new BigDecimal(evaluatedCount), 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"))
                    .setScale(2, RoundingMode.HALF_UP);
            printFile.setSuccessRate(rate);
        } else {
            printFile.setSuccessRate(BigDecimal.ZERO);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PrintFile uploadAndParseFile(MultipartFile file) {
        return uploadAndParseFile(file, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PrintFile uploadAndParseFile(MultipartFile file, Long parentId) {
        validateUpload(file);
        Long userId = SecurityContextUtil.getCurrentUserId();
        Long resolvedParentId = resolveUploadParent(parentId, userId).getId();
        requireUploadParent(resolvedParentId);
        String originalName = file.getOriginalFilename().trim();
        String safeName = System.currentTimeMillis() + "_" + originalName;

        // 统一从完整文件解析
        String fullContent = extractFullContentIfAffordable(file);
        GCodeParser.GCodeMeta meta;
        String parseSource;
        String sampledHeadTail = null;

        if (!fullContent.isEmpty()) {
            // 完整文件解析
            meta = GCodeParser.parseMetadata(fullContent);
            parseSource = "full-file";
        } else if (file.getSize() > FULL_PARSE_MAX_BYTES) {
            // 文件太大，只解析头部
            sampledHeadTail = extractHeadAndTail(file);
            meta = GCodeParser.parseMetadata(sampledHeadTail);
            parseSource = "head-tail (skipped large file)";
        } else {
            // 回退到头部解析
            sampledHeadTail = extractHeadAndTail(file);
            meta = GCodeParser.parseMetadata(sampledHeadTail);
            parseSource = "head-tail";
        }

        // 提取缩略图（从头部）
        String headTailForThumb = sampledHeadTail != null ? sampledHeadTail : extractHeadAndTail(file);

        log.info("gcode 解析: file={}, source={}, time={}, material={}, nozzle={}, temp={}, bedTemp={}, layerHeight={}, filamentWeight={}, filamentLength={}, filamentUsedG={}, filamentUsedMM={}, firstLayerBedTemp={}, nozzleSizeFromGcode={}",
                originalName, parseSource, meta.getEstimatedPrintTimeSeconds(), meta.getMaterialType(), meta.getNozzleSize(),
                meta.getNozzleTemp(), meta.getBedTemp(), meta.getLayerHeight(),
                meta.getFilamentWeight(), meta.getFilamentLength(),
                meta.getFilamentUsedG(), meta.getFilamentUsedMM(),
                meta.getFirstLayerBedTemp(), meta.getNozzleSize());

        // 调试：打印耗材字段原始值
        log.debug("耗材调试 - filamentUsedG={}, filamentUsedMM={}, filamentWeight={}, filamentLength={}",
                meta.getFilamentUsedG(), meta.getFilamentUsedMM(), meta.getFilamentWeight(), meta.getFilamentLength());

        String fileUrl = rustFsClient.uploadFile(safeName, file);

        PrintFile printFile = new PrintFile();
        printFile.setOriginalName(originalName);
        printFile.setSafeName(safeName);
        printFile.setFileUrl(fileUrl);
        printFile.setFileSize(file.getSize());
        printFile.setUserId(userId);
        printFile.setParentId(resolvedParentId);
        printFile.setIsFolder(false);
        printFile.setIsDefaultFolder(false);
        printFile.setVisibility(normalizeVisibility(resolveUploadParent(resolvedParentId, userId).getVisibility()));
        printFile.setCreatedAt(LocalDateTime.now());

        printFile.setEstTime(meta.getEstimatedPrintTimeSeconds());
        printFile.setMaterialType(meta.getMaterialType() != null ? meta.getMaterialType() : "PLA");
        printFile.setNozzleSize(meta.getNozzleSize() != null ? meta.getNozzleSize() : new BigDecimal("0.40"));

        // 设置耗材重量和长度
        // filamentUsedG 单位是克，直接使用
        // filamentUsedMM 是 mm，需要转换为米
        BigDecimal filamentWeight = meta.getFilamentUsedG();
        if (filamentWeight == null && meta.getFilamentWeight() != null) {
            filamentWeight = meta.getFilamentWeight();
        }
        printFile.setFilamentWeight(filamentWeight);

        BigDecimal filamentLength = meta.getFilamentUsedMM();
        if (filamentLength != null) {
            // filamentUsedMM 的单位固定为 mm，无论长度大小都转换为数据库约定的米。
            filamentLength = filamentLength.divide(new BigDecimal("1000"), 2, java.math.RoundingMode.HALF_UP);
        }
        if (filamentLength == null && meta.getFilamentLength() != null) {
            filamentLength = meta.getFilamentLength();
        }
        printFile.setFilamentLength(filamentLength);

        // 设置温度和层高（OrcaSlicer 解析）
        printFile.setNozzleTemp(meta.getNozzleTemp());
        printFile.setBedTemp(meta.getBedTemp());
        printFile.setLayerHeight(meta.getLayerHeight());
        printFile.setFirstLayerNozzleTemp(meta.getFirstLayerNozzleTemp());
        printFile.setFirstLayerBedTemp(meta.getFirstLayerBedTemp());
        printFile.setFirstLayerHeight(meta.getFirstLayerHeight());

        String uploadedThumbnailUrl = null;

        // 提取并上传缩略图
        try {
            String thumbnailBase64 = GCodeParser.extractThumbnailBase64(headTailForThumb);
            if (thumbnailBase64 != null && !thumbnailBase64.isEmpty()) {
                String thumbnailUrl = uploadThumbnailToRustFS(thumbnailBase64, safeName);
                if (thumbnailUrl != null) {
                    uploadedThumbnailUrl = thumbnailUrl;
                    printFile.setThumbnailUrl(thumbnailUrl);
                    log.info("缩略图提取并上传成功: fileId={}", printFile.getId());
                }
            } else {
                log.debug("G-code 中未找到缩略图: safeName={}", safeName);
            }
        } catch (Exception e) {
            log.warn("缩略图提取或上传失败，继续保存文件: safeName={}", safeName, e);
            // 缩略图失败不影响主流程
        }

        try {
            if (!this.save(printFile)) {
                throw new BusinessException("文件记录保存失败");
            }
        } catch (RuntimeException exception) {
            cleanupUploadedObjects(safeName, uploadedThumbnailUrl);
            throw exception;
        }
        recordSuccess("FILE_UPLOAD", "FILE", printFile.getId(), printFile.getOriginalName());
        enrichCapabilities(printFile, null);
        log.info("切片文件入库成功: fileId={}, userId={}, safeName={}", printFile.getId(), userId, safeName);
        return printFile;
    }

    @Override
    public PrintFileService.BatchUploadResult batchUploadFiles(List<MultipartFile> files) {
        return batchUploadFiles(files, null);
    }

    @Override
    public PrintFileService.BatchUploadResult batchUploadFiles(List<MultipartFile> files, Long parentId) {
        if (files == null || files.isEmpty()) {
            throw new BusinessException(400, "上传文件不能为空");
        }
        int maxFiles = fileUploadProperties != null && fileUploadProperties.getBatchMaxFiles() != null
                ? fileUploadProperties.getBatchMaxFiles() : MAX_BATCH_SIZE;
        if (files.size() > maxFiles) {
            throw new BusinessException(400, "单次最多上传" + maxFiles + "个文件");
        }

        Long resolvedParentId = resolveUploadParent(parentId, SecurityContextUtil.getCurrentUserId()).getId();
        requireUploadParent(resolvedParentId);

        PrintFileService.BatchUploadResult result = new PrintFileService.BatchUploadResult();
        result.setTotalCount(files.size());
        Set<String> requestFileFingerprints = new HashSet<>();
        for (int index = 0; index < files.size(); index++) {
            MultipartFile file = files.get(index);
            String fileName = file == null ? null : file.getOriginalFilename();
            String fingerprint = file == null ? "null" : fileName + "#" + file.getSize();
            if (!requestFileFingerprints.add(fingerprint)) {
                result.getItems().add(new PrintFileService.BatchUploadItemResult(
                        index, null, fileName, "SKIPPED", "DUPLICATE_FILE",
                        "本次请求中存在同名同大小重复文件", false));
                result.setFailureCount(result.getFailureCount() + 1);
                continue;
            }
            try {
                PrintFile saved = uploadAndParseFile(file, resolvedParentId);
                result.getItems().add(new PrintFileService.BatchUploadItemResult(
                        index, saved.getId(), saved.getOriginalName(), "SUCCEEDED", null, "上传成功", false));
                result.setSuccessCount(result.getSuccessCount() + 1);
            } catch (StorageException exception) {
                log.warn("批量上传对象存储失败: index={}, fileName={}, reason={}", index, fileName, exception.getMessage());
                result.getItems().add(new PrintFileService.BatchUploadItemResult(
                        index, null, fileName, "FAILED", "STORAGE_UNAVAILABLE", "对象存储服务异常", true));
                result.setFailureCount(result.getFailureCount() + 1);
            } catch (BusinessException exception) {
                log.warn("批量上传文件校验失败: index={}, fileName={}, code={}", index, fileName, exception.getCode());
                result.getItems().add(new PrintFileService.BatchUploadItemResult(
                        index, null, fileName, "FAILED", "FILE_VALIDATION_FAILED", exception.getMessage(), false));
                result.setFailureCount(result.getFailureCount() + 1);
            } catch (Exception exception) {
                log.error("批量上传文件失败: index={}, fileName={}", index, fileName, exception);
                result.getItems().add(new PrintFileService.BatchUploadItemResult(
                        index, null, fileName, "FAILED", "FILE_UPLOAD_FAILED", "文件上传失败，请稍后重试", true));
                result.setFailureCount(result.getFailureCount() + 1);
            }
        }
        result.setMessage("批量上传完成：成功 " + result.getSuccessCount() + " 个，失败 "
                + result.getFailureCount() + " 个");
        return result;
    }

    /**
     * 数据库写入失败时补偿删除已经上传的对象。对象存储不参与本地事务，不能依赖
     * {@code @Transactional} 自动回滚；清理失败只记录日志，保留原始数据库异常。
     */
    private void cleanupUploadedObjects(String safeName, String thumbnailUrl) {
        if (thumbnailUrl != null && !thumbnailUrl.isBlank()) {
            try {
                rustFsClient.deleteFileByObjectUrl(thumbnailUrl);
            } catch (RuntimeException cleanupException) {
                log.warn("数据库保存失败后清理缩略图对象失败", cleanupException);
            }
        }
        try {
            rustFsClient.deleteFile(safeName);
        } catch (RuntimeException cleanupException) {
            log.warn("数据库保存失败后清理文件对象失败", cleanupException);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteFile(Long id) {
        Long userId = SecurityContextUtil.getCurrentUserId();
        PrintFile target = getAccessibleFile(id);
        if (!canDeleteFile(target)) {
            throw new BusinessException(403, "无权删除此文件");
        }
        ensureDeletable(target);

        deleteThumbnailIfPresent(target);
        String objectKey = target.getSafeName();
        rustFsClient.deleteFile(objectKey);
        if (!this.removeById(target.getId())) {
            throw new BusinessException("文件记录删除失败");
        }
        recordSuccess("FILE_DELETE", "FILE", target.getId(), target.getOriginalName());
        log.info("print file deleted from rustfs and db: fileId={}, userId={}", id, userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteFolder(Long id) {
        PrintFile folder = getAccessibleFile(id);
        if (!Boolean.TRUE.equals(folder.getIsFolder())) {
            throw new BusinessException(422, "目标资源不是文件夹");
        }
        if (Boolean.TRUE.equals(folder.getIsDefaultFolder())) {
            throw new BusinessException(422, "默认目录不可删除");
        }
        if (!canManageFolder(folder)) {
            throw new BusinessException(403, "无权管理该文件夹");
        }

        Integer childCount = baseMapper.countChildrenByParentId(id);
        if (childCount != null && childCount > 0) {
            throw new BusinessException(409, "文件夹非空，包含文件或子目录，无法删除");
        }

        Long userId = SecurityContextUtil.getCurrentUserId();
        boolean admin = SecurityContextUtil.isAdmin();
        if (baseMapper.deleteEmptyFolder(id, userId, admin) != 1) {
            // 重新读取子节点数量，给并发新增子节点返回稳定的业务冲突，而不是误报删除成功。
            Integer currentChildCount = baseMapper.countChildrenByParentId(id);
            if (currentChildCount != null && currentChildCount > 0) {
                throw new BusinessException(409, "文件夹非空，包含文件或子目录，无法删除");
            }
            throw new BusinessException("文件夹记录删除失败");
        }
        recordSuccess("FOLDER_DELETE", "FOLDER", folder.getId(), folder.getOriginalName());
        log.info("print folder deleted from db: folderId={}, userId={}, admin={}", id, userId, admin);
    }

    @Override
    public String getPresignedDownloadUrl(Long id, Integer expirationMinutes) {
        PrintFile file = getAccessibleFile(id);

        return rustFsClient.getPresignedUrl(file.getSafeName(), resolvePresignedExpiration(expirationMinutes));
    }

    @Override
    public String getPresignedThumbnailUrl(Long id, Integer expirationMinutes) {
        PrintFile file = getAccessibleFile(id);
        if (file.getThumbnailUrl() == null || file.getThumbnailUrl().isBlank()) {
            return null;
        }
        return rustFsClient.getPresignedUrlForObjectUrl(
                file.getThumbnailUrl(), resolvePresignedExpiration(expirationMinutes));
    }

    private Duration resolvePresignedExpiration(Integer expirationMinutes) {
        int requestedMinutes = expirationMinutes != null && expirationMinutes > 0
                ? expirationMinutes : 60;
        int maxMinutes = fileUploadProperties != null
                && fileUploadProperties.getPresignedUrlMaxMinutes() != null
                && fileUploadProperties.getPresignedUrlMaxMinutes() > 0
                ? fileUploadProperties.getPresignedUrlMaxMinutes() : 120;
        return Duration.ofMinutes(Math.min(requestedMinutes, maxMinutes));
    }

    @Override
    public org.springframework.core.io.InputStreamResource downloadFile(Long id) {
        PrintFile file = getAccessibleFile(id);
        return rustFsClient.getFileStream(file.getSafeName());
    }

    @Override
    public org.springframework.core.io.InputStreamResource downloadBySafeName(String safeName) {
        if (safeName == null || safeName.isBlank()) {
            throw new BusinessException(400, "文件 key 不能为空");
        }
        PrintFile file = this.getOne(new LambdaQueryWrapper<PrintFile>()
                .eq(PrintFile::getSafeName, safeName)
                .or()
                .eq(PrintFile::getThumbnailUrl, "local://" + safeName));
        if (file == null || Boolean.TRUE.equals(file.getIsFolder())) {
            throw new BusinessException(404, "文件不存在");
        }
        if (!canRead(file)) {
            throw new BusinessException(404, "文件不存在");
        }
        return rustFsClient.getFileStream(file.getSafeName());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PrintFileService.BatchDeleteResult batchDeleteFiles(List<Long> ids) {
        PrintFileService.BatchDeleteResult result = new PrintFileService.BatchDeleteResult();
        List<Long> distinctIds = ids == null ? List.of() : ids.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        result.setTotalCount(distinctIds.size());
        result.setItems(new ArrayList<>());

        if (distinctIds.isEmpty()) {
            result.setMessage("没有需要删除的文件");
            return result;
        }
        if (distinctIds.size() > MAX_BATCH_SIZE) {
            throw new BusinessException(400, "单次最多删除" + MAX_BATCH_SIZE + "个文件");
        }

        Long userId = SecurityContextUtil.getCurrentUserId();
        for (Long id : distinctIds) {
            try {
                PrintFile target = getAccessibleFile(id);
                ensureDeletable(target);
                deleteThumbnailIfPresent(target);
                rustFsClient.deleteFile(target.getSafeName());
                if (!this.removeById(target.getId())) {
                    throw new BusinessException("数据库记录删除失败");
                }
                recordSuccess("FILE_DELETE", "FILE", target.getId(), target.getOriginalName());
                result.getItems().add(new PrintFileService.BatchDeleteItemResult(id, true, "删除成功"));
                result.setDeletedCount(result.getDeletedCount() + 1);
            } catch (BusinessException e) {
                result.getItems().add(new PrintFileService.BatchDeleteItemResult(id, false, e.getMessage()));
                result.setFailedCount(result.getFailedCount() + 1);
            } catch (StorageException e) {
                log.warn("批量删除文件时对象存储失败: fileId={}, userId={}, reason={}", id, userId, e.getMessage());
                result.getItems().add(new PrintFileService.BatchDeleteItemResult(id, false, "对象存储删除失败"));
                result.setFailedCount(result.getFailedCount() + 1);
            } catch (Exception e) {
                log.error("批量删除文件失败: fileId={}, userId={}", id, userId, e);
                result.getItems().add(new PrintFileService.BatchDeleteItemResult(id, false, "删除失败，请稍后重试"));
                result.setFailedCount(result.getFailedCount() + 1);
            }
        }
        result.setMessage(String.format("批量删除完成：成功 %d 个，失败 %d 个",
                result.getDeletedCount(), result.getFailedCount()));
        log.info("批量删除文件完成: userId={}, deletedCount={}, failedCount={}",
                userId, result.getDeletedCount(), result.getFailedCount());
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PrintFileService.BatchMoveResult batchMoveFiles(List<Long> ids, Long parentId) {
        PrintFileService.BatchMoveResult result = new PrintFileService.BatchMoveResult();
        List<Long> distinctIds = ids == null ? List.of() : ids.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        result.setTotalCount(distinctIds.size());
        result.setItems(new ArrayList<>());

        if (distinctIds.isEmpty()) {
            result.setMessage("没有需要移动的文件");
            return result;
        }
        if (distinctIds.size() > MAX_BATCH_SIZE) {
            throw new BusinessException(400, "单次最多移动" + MAX_BATCH_SIZE + "个文件");
        }

        // 目标目录是请求级参数：先校验存在、可访问且确实为文件夹，再逐项处理文件。
        PrintFile target = parentId == null ? null : requireAccessibleFolder(parentId);
        Long userId = SecurityContextUtil.getCurrentUserId();
        for (Long id : distinctIds) {
            try {
                PrintFile file = getAccessibleFile(id);
                if (Boolean.TRUE.equals(file.getIsFolder())) {
                    throw new BusinessException(422, "文件夹不能通过文件移动接口移动");
                }
                if (!canMoveFile(file)) {
                    throw new BusinessException(403, "无权移动此文件");
                }
                if (target != null && !sameVisibility(file, target)) {
                    throw new BusinessException(403, "禁止跨可见性移动文件");
                }
                if (target != null && !isPublic(file)
                        && !Objects.equals(file.getUserId(), target.getUserId())) {
                    throw new BusinessException(403, "目标文件夹必须属于文件所属用户");
                }
                if (Objects.equals(file.getParentId(), parentId)) {
                    throw new BusinessException(422, "文件已经位于目标目录");
                }
                int updatedRows = baseMapper.update(null, new UpdateWrapper<PrintFile>()
                        .eq("id", id)
                        .set("parent_id", parentId));
                if (updatedRows != 1) {
                    throw new BusinessException("文件记录移动失败");
                }
                recordSuccess("FILE_MOVE", "FILE", file.getId(), file.getOriginalName());
                result.getItems().add(new PrintFileService.BatchMoveItemResult(id, true, "移动成功"));
                result.setMovedCount(result.getMovedCount() + 1);
            } catch (BusinessException exception) {
                result.getItems().add(new PrintFileService.BatchMoveItemResult(id, false, exception.getMessage()));
                result.setFailedCount(result.getFailedCount() + 1);
            } catch (Exception exception) {
                log.error("批量移动文件失败: fileId={}, userId={}", id, userId, exception);
                result.getItems().add(new PrintFileService.BatchMoveItemResult(id, false, "移动失败，请稍后重试"));
                result.setFailedCount(result.getFailedCount() + 1);
            }
        }
        result.setMessage(String.format("批量移动完成：成功 %d 个，失败 %d 个",
                result.getMovedCount(), result.getFailedCount()));
        log.info("批量移动文件完成: userId={}, parentId={}, movedCount={}, failedCount={}",
                userId, parentId, result.getMovedCount(), result.getFailedCount());
        return result;
    }

    private void recordSuccess(String action, String targetType, Object targetId, String targetLabel) {
        if (auditLogService != null) {
            auditLogService.recordSuccess(action, targetType, targetId, targetLabel);
        }
    }

    private void validateUpload(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() <= 0) {
            throw new BusinessException(400, "上传文件不能为空");
        }
        long maxBytes = fileUploadProperties != null && fileUploadProperties.getMaxFileSize() != null
                ? fileUploadProperties.getMaxFileSize().toBytes()
                : 1024L * 1024 * 1024;
        if (file.getSize() > maxBytes) {
            throw new BusinessException(400, "文件大小不能超过" + formatDataSize(maxBytes));
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            throw new BusinessException(400, "文件名不能为空");
        }
        originalName = originalName.trim();
        if (originalName.length() > MAX_FILENAME_LENGTH
                || originalName.equals(".")
                || originalName.equals("..")
                || containsUnsafeFilenameCharacter(originalName)) {
            throw new BusinessException(400, "文件名长度或格式不正确");
        }

        int dot = originalName.lastIndexOf('.');
        if (dot <= 0 || dot == originalName.length() - 1) {
            throw new BusinessException(400, "文件必须包含受支持的扩展名");
        }
        String extension = originalName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        List<String> allowedTypes = fileUploadProperties == null ? List.of("gcode", "g", "3mf", "stl")
                : fileUploadProperties.getAllowedTypes();
        boolean allowed = allowedTypes != null && allowedTypes.stream()
                .filter(Objects::nonNull)
                .map(type -> type.trim().toLowerCase(java.util.Locale.ROOT))
                .map(type -> type.startsWith(".") ? type.substring(1) : type)
                .anyMatch(extension::equals);
        if (!allowed) {
            throw new BusinessException(400, "不支持的文件类型: ." + extension);
        }
    }

    private boolean containsUnsafeFilenameCharacter(String filename) {
        return filename.indexOf('/') >= 0 || filename.indexOf('\\') >= 0
                || filename.chars().anyMatch(Character::isISOControl);
    }

    private boolean containsUnsafeFolderCharacter(String folderName) {
        return containsUnsafeFilenameCharacter(folderName)
                || folderName.chars().anyMatch(character -> ":*?\"<>|".indexOf(character) >= 0);
    }

    private String normalizeFolderName(String folderName) {
        if (folderName == null || folderName.isBlank()) {
            throw new BusinessException(400, "文件夹名称不能为空");
        }
        String normalizedFolderName = folderName.trim();
        if (normalizedFolderName.length() > 100
                || containsUnsafeFolderCharacter(normalizedFolderName)) {
            throw new BusinessException(400, "文件夹名称长度或格式不正确");
        }
        return normalizedFolderName;
    }

    private PrintFile requireEditableFolder(Long id, String defaultFolderMessage) {
        PrintFile folder = getAccessibleFile(id);
        if (!Boolean.TRUE.equals(folder.getIsFolder())) {
            throw new BusinessException(422, "目标资源不是文件夹");
        }
        if (Boolean.TRUE.equals(folder.getIsDefaultFolder())) {
            throw new BusinessException(422, defaultFolderMessage);
        }
        if (!canManageFolder(folder)) {
            throw new BusinessException(403, "无权管理该文件夹");
        }
        return folder;
    }

    private int getFolderLevelForMove(PrintFile folder, Map<Long, PrintFile> foldersById,
                                      Map<Long, Long> overriddenParents) {
        int level = 1;
        Set<Long> visited = new HashSet<>();
        Long currentId = folder.getId();
        while (currentId != null) {
            if (!visited.add(currentId)) {
                return -1;
            }
            PrintFile current = foldersById.get(currentId);
            if (current == null || !Boolean.TRUE.equals(current.getIsFolder())) {
                return -1;
            }
            Long parentId = overriddenParents.containsKey(currentId)
                    ? overriddenParents.get(currentId) : current.getParentId();
            if (parentId == null) {
                return level;
            }
            PrintFile parent = foldersById.get(parentId);
            if (parent == null || !Boolean.TRUE.equals(parent.getIsFolder())
                    || !sameVisibility(folder, parent)) {
                return -1;
            }
            level++;
            currentId = parentId;
        }
        return level;
    }

    private boolean isSameOrDescendant(PrintFile candidate, PrintFile ancestor,
                                       Map<Long, PrintFile> foldersById) {
        Set<Long> visited = new HashSet<>();
        Long currentId = candidate.getId();
        while (currentId != null && visited.add(currentId)) {
            if (Objects.equals(currentId, ancestor.getId())) {
                return true;
            }
            PrintFile current = foldersById.get(currentId);
            if (current == null) {
                return false;
            }
            currentId = current.getParentId();
        }
        return false;
    }

    private String formatDataSize(long bytes) {
        if (bytes % (1024L * 1024 * 1024) == 0) {
            return (bytes / (1024L * 1024 * 1024)) + "GB";
        }
        return (bytes / (1024L * 1024)) + "MB";
    }

    private String normalizeVisibility(String visibility) {
        return PUBLIC.equalsIgnoreCase(String.valueOf(visibility)) ? PUBLIC : PRIVATE;
    }

    private boolean isPublic(PrintFile file) {
        return file != null && PUBLIC.equalsIgnoreCase(file.getVisibility());
    }

    private boolean isOwner(PrintFile file) {
        return file != null && Objects.equals(file.getUserId(), SecurityContextUtil.getCurrentUserId());
    }

    private boolean canRead(PrintFile file) {
        if (file == null || file.getId() == null) {
            return false;
        }
        try {
            PrintFileAccess.requireAccessible(file.getId(), this::getById);
            return true;
        } catch (BusinessException exception) {
            return false;
        }
    }

    private boolean canUpload(PrintFile folder) {
        return folder != null && Boolean.TRUE.equals(folder.getIsFolder())
                && (isPublic(folder) || isOwner(folder));
    }

    private boolean canCreateFolder(PrintFile folder) {
        return folder != null && Boolean.TRUE.equals(folder.getIsFolder())
                && (SecurityContextUtil.isAdmin() && isPublic(folder)
                || !isPublic(folder) && isOwner(folder));
    }

    private boolean canManageFolder(PrintFile folder) {
        return folder != null && Boolean.TRUE.equals(folder.getIsFolder())
                && !Boolean.TRUE.equals(folder.getIsDefaultFolder())
                && ((!isPublic(folder) && isOwner(folder))
                || (SecurityContextUtil.isAdmin() && isPublic(folder)));
    }

    private boolean canDeleteFile(PrintFile file) {
        return file != null && !Boolean.TRUE.equals(file.getIsFolder())
                && (SecurityContextUtil.isAdmin() || isOwner(file));
    }

    private boolean canMoveFile(PrintFile file) {
        return file != null && !Boolean.TRUE.equals(file.getIsFolder())
                && ((!isPublic(file) && isOwner(file))
                || (SecurityContextUtil.isAdmin() && isPublic(file)));
    }

    private boolean sameVisibility(PrintFile left, PrintFile right) {
        return normalizeVisibility(left == null ? null : left.getVisibility())
                .equals(normalizeVisibility(right == null ? null : right.getVisibility()));
    }

    /**
     * Folder names are unique in their real namespace: PRIVATE roots are
     * separated by owner, PUBLIC roots are shared, and children are separated
     * by their parent folder ID.
     */
    private void applyFolderNameNamespace(LambdaQueryWrapper<PrintFile> wrapper,
                                          Long parentId, String visibility, Long ownerUserId) {
        if (parentId == null) {
            wrapper.isNull(PrintFile::getParentId)
                    .eq(PrintFile::getVisibility, visibility);
            if (PRIVATE.equals(visibility)) {
                wrapper.eq(PrintFile::getUserId, ownerUserId);
            }
            return;
        }
        wrapper.eq(PrintFile::getParentId, parentId);
    }

    private void enrichCapabilities(PrintFile file, Integer level) {
        if (file == null) {
            return;
        }
        file.setVisibility(normalizeVisibility(file.getVisibility()));
        file.setCanManage(canManageFolder(file));
        file.setCanUpload(canUpload(file));
        file.setCanCreate(canCreateFolder(file));
        file.setCanDelete(canDeleteFile(file));
        file.setCanMove(canMoveFile(file));
        boolean readable = canRead(file);
        file.setCanPrint(readable && !Boolean.TRUE.equals(file.getIsFolder()));
        file.setCanDownload(readable && !Boolean.TRUE.equals(file.getIsFolder()));
        file.setLevel(level != null ? level : (file.getParentId() == null ? 1 : 2));
    }

    private PrintFile getAccessibleFile(Long id) {
        PrintFile file = PrintFileAccess.requireAccessible(id, this::getById);
        enrichCapabilities(file, null);
        return file;
    }

    private boolean isAccessibleRecord(PrintFile file) {
        if (file == null || file.getId() == null) {
            return false;
        }
        try {
            PrintFileAccess.requireAccessible(file.getId(), this::getById);
            return true;
        } catch (BusinessException exception) {
            return false;
        }
    }

    private void ensureDeletable(PrintFile file) {
        if (Boolean.TRUE.equals(file.getIsFolder())) {
            if (Boolean.TRUE.equals(file.getIsDefaultFolder())) {
                throw new BusinessException(422, "默认目录不可删除");
            }
            throw new BusinessException(422, "目录不能通过文件删除接口删除");
        }
        if (!canDeleteFile(file)) {
            throw new BusinessException(403, "无权删除此文件");
        }
        Integer jobCount = baseMapper.countPrintJobsByFileId(file.getId(), null, null);
        if (jobCount != null && jobCount > 0) {
            throw new BusinessException(409, "文件已关联打印任务，禁止删除");
        }
    }

    private void deleteThumbnailIfPresent(PrintFile file) {
        if (file.getThumbnailUrl() == null || file.getThumbnailUrl().isBlank()) {
            return;
        }
        rustFsClient.deleteFileByObjectUrl(file.getThumbnailUrl());
    }

    private PrintFile requireAccessibleFolder(Long folderId) {
        PrintFile folder = PrintFileAccess.requireAccessible(folderId, this::getById);
        if (!Boolean.TRUE.equals(folder.getIsFolder())) {
            throw new BusinessException(422, "父级资源不是文件夹");
        }
        return folder;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PrintFile renameFolder(Long id, String folderName) {
        PrintFile folder = requireEditableFolder(id, "默认目录不可重命名");
        String normalizedFolderName = normalizeFolderName(folderName);

        LambdaQueryWrapper<PrintFile> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PrintFile::getIsFolder, true)
                .eq(PrintFile::getVisibility, normalizeVisibility(folder.getVisibility()))
                .eq(PrintFile::getOriginalName, normalizedFolderName)
                .ne(PrintFile::getId, folder.getId());
        applyFolderNameNamespace(wrapper, folder.getParentId(),
                normalizeVisibility(folder.getVisibility()), folder.getUserId());
        if (this.getOne(wrapper, false) != null) {
            throw new BusinessException(409, "该目录下已存在同名文件夹");
        }

        folder.setOriginalName(normalizedFolderName);
        if (!this.updateById(folder)) {
            throw new BusinessException("文件夹重命名失败");
        }
        recordSuccess("FOLDER_RENAME", "FOLDER", folder.getId(), folder.getOriginalName());
        log.info("print folder renamed: folderId={}, userId={}, name={}",
                folder.getId(), folder.getUserId(), normalizedFolderName);
        return folder;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PrintFile moveFolder(Long id, Long parentId) {
        if (parentId != null && parentId <= 0) {
            throw new BusinessException(400, "目标目录 ID 必须为正数");
        }

        PrintFile folder = requireEditableFolder(id, "默认目录不可移动");
        LambdaQueryWrapper<PrintFile> folderQuery = new LambdaQueryWrapper<>();
        folderQuery.eq(PrintFile::getIsFolder, true);
        if (!SecurityContextUtil.isAdmin()) {
            folderQuery.eq(PrintFile::getUserId, folder.getUserId());
        } else {
            folderQuery.eq(PrintFile::getVisibility, normalizeVisibility(folder.getVisibility()));
        }
        List<PrintFile> userFolders = this.list(folderQuery);
        Map<Long, PrintFile> foldersById = new LinkedHashMap<>();
        userFolders.forEach(item -> foldersById.put(item.getId(), item));
        foldersById.put(folder.getId(), folder);

        int sourceLevel = getFolderLevelForMove(folder, foldersById, Map.of());
        if (sourceLevel < 1 || sourceLevel > 2) {
            throw new BusinessException(422, "文件夹层级数据异常");
        }

        PrintFile target = null;
        if (parentId != null) {
            target = requireAccessibleFolder(parentId);
            if (!sameVisibility(folder, target)) {
                throw new BusinessException(403, "禁止跨可见性移动文件夹");
            }
            if (!isPublic(folder) && !Objects.equals(target.getUserId(), folder.getUserId())) {
                throw new BusinessException(403, "目标文件夹必须属于源文件夹用户");
            }
            foldersById.put(target.getId(), target);
            int targetLevel = getFolderLevelForMove(target, foldersById, Map.of());
            if (targetLevel != 1 || target.getParentId() != null) {
                throw new BusinessException(422, "移动目标必须是一级文件夹");
            }
            if (isSameOrDescendant(target, folder, foldersById)) {
                throw new BusinessException(422, "不能移动到自身或子孙目录");
            }
        }

        Map<Long, Long> overriddenParents = new LinkedHashMap<>();
        overriddenParents.put(folder.getId(), parentId);
        for (PrintFile candidate : userFolders) {
            if (!isSameOrDescendant(candidate, folder, foldersById)) {
                continue;
            }
            int proposedLevel = getFolderLevelForMove(candidate, foldersById, overriddenParents);
            if (proposedLevel < 1 || proposedLevel > 2) {
                throw new BusinessException(422, "移动后文件夹最多支持两级目录");
            }
        }

        LambdaQueryWrapper<PrintFile> duplicateQuery = new LambdaQueryWrapper<>();
        duplicateQuery.eq(PrintFile::getIsFolder, true)
                .eq(PrintFile::getVisibility, normalizeVisibility(folder.getVisibility()))
                .eq(PrintFile::getOriginalName, folder.getOriginalName())
                .ne(PrintFile::getId, folder.getId());
        applyFolderNameNamespace(duplicateQuery, parentId,
                normalizeVisibility(folder.getVisibility()), folder.getUserId());
        if (this.getOne(duplicateQuery, false) != null) {
            throw new BusinessException(409, "目标目录下已存在同名文件夹");
        }

        folder.setParentId(parentId);
        if (!this.updateById(folder)) {
            throw new BusinessException("文件夹移动失败");
        }
        recordSuccess("FOLDER_MOVE", "FOLDER", folder.getId(), folder.getOriginalName());
        log.info("print folder moved: folderId={}, userId={}, parentId={}",
                folder.getId(), folder.getUserId(), parentId);
        return folder;
    }

    private PrintFile resolveUploadParent(Long parentId, Long userId) {
        if (parentId != null) {
            return requireUploadParent(parentId);
        }

        LambdaQueryWrapper<PrintFile> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PrintFile::getUserId, userId)
                .eq(PrintFile::getIsFolder, true)
                .eq(PrintFile::getIsDefaultFolder, true)
                .isNull(PrintFile::getParentId);
        List<PrintFile> defaults = this.list(wrapper);
        if (defaults == null || defaults.size() != 1) {
            throw new BusinessException(422, "默认文件夹不可用，请先完成目录迁移");
        }
        return defaults.get(0);
    }

    private void requirePageFolderParent(Long folderId) {
        PrintFile folder = requireAccessibleFolder(folderId);
        int level = getFolderLevelForQuery(folder);
        if (level < 1 || level > 2) {
            throw new BusinessException(422, "文件夹最多支持两级目录");
        }
    }

    private int getFolderLevelForQuery(PrintFile folder) {
        if (folder.getParentId() == null) {
            return 1;
        }
        PrintFile parent = baseMapper.selectById(folder.getParentId());
        if (parent == null || !Boolean.TRUE.equals(parent.getIsFolder())
                || !sameVisibility(parent, folder) || parent.getParentId() != null) {
            return -1;
        }
        return 2;
    }

    private PrintFile requireFolderParent(Long folderId) {
        PrintFile folder = requireAccessibleFolder(folderId);
        if (!canCreateFolder(folder)) {
            throw new BusinessException(403, "无权在该文件夹下创建子目录");
        }
        int level = getFolderLevelFromStorage(folder);
        if (level < 1 || level >= 2) {
            throw new BusinessException(422, "文件夹最多支持两级目录");
        }
        return folder;
    }

    private PrintFile requireUploadParent(Long folderId) {
        PrintFile folder = requireAccessibleFolder(folderId);
        if (!canUpload(folder)) {
            throw new BusinessException(403, "无权向该文件夹上传文件");
        }
        int level = getFolderLevelFromStorage(folder);
        if (level < 1 || level > 2) {
            throw new BusinessException(422, "文件夹层级数据异常");
        }
        return folder;
    }

    private int getFolderLevelFromStorage(PrintFile folder) {
        if (folder.getParentId() == null) {
            return 1;
        }
        PrintFile parent = requireAccessibleFolder(folder.getParentId());
        if (!Boolean.TRUE.equals(parent.getIsFolder()) || parent.getParentId() != null
                || !sameVisibility(folder, parent)) {
            return -1;
        }
        return 2;
    }

    private String extractHeadAndTail(MultipartFile file) {
        try {
            long size = file.getSize();
            if (size <= META_SAMPLE_SIZE * 2L) {
                return new String(file.getBytes(), StandardCharsets.UTF_8);
            }

            byte[] head = readFirstBytes(file, META_SAMPLE_SIZE);
            byte[] tail = readLastBytes(file, META_SAMPLE_SIZE);
            return new String(head, StandardCharsets.UTF_8) + "\n...\n" + new String(tail, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("读取切片文件头尾信息失败", e);
            return "";
        }
    }

    private String extractTail(MultipartFile file, int bytes) {
        try {
            byte[] tail = readLastBytes(file, bytes);
            return new String(tail, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("failed to read full gcode content for metadata fallback", e);
            return "";
        }
    }

    private byte[] readFirstBytes(MultipartFile file, int maxBytes) throws IOException {
        try (InputStream is = file.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream(maxBytes)) {
            byte[] buffer = new byte[4096];
            int remaining = maxBytes;
            int n;
            while (remaining > 0 && (n = is.read(buffer, 0, Math.min(buffer.length, remaining))) != -1) {
                out.write(buffer, 0, n);
                remaining -= n;
            }
            return out.toByteArray();
        }
    }

    private byte[] readLastBytes(MultipartFile file, int maxBytes) throws IOException {
        try (InputStream is = file.getInputStream()) {
            byte[] ring = new byte[maxBytes];
            byte[] buffer = new byte[8192];
            long total = 0;
            int n;
            while ((n = is.read(buffer)) != -1) {
                for (int i = 0; i < n; i++) {
                    ring[(int) ((total + i) % maxBytes)] = buffer[i];
                }
                total += n;
            }

            if (total == 0) {
                return new byte[0];
            }

            int actual = (int) Math.min(total, maxBytes);
            int start = (int) ((total - actual) % maxBytes);
            byte[] tail = new byte[actual];
            for (int i = 0; i < actual; i++) {
                tail[i] = ring[(start + i) % maxBytes];
            }
            return tail;
        }
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private boolean hasUsableMeta(GCodeParser.GCodeMeta meta) {
        if (meta == null) {
            return false;
        }
        boolean hasMaterial = isNotBlank(meta.getMaterialType());
        boolean hasTime = meta.getEstimatedPrintTimeSeconds() != null && meta.getEstimatedPrintTimeSeconds() > 0;
        return hasMaterial || hasTime;
    }

    private String extractFullContentIfAffordable(MultipartFile file) {
        try {
            if (file.getSize() <= 0 || file.getSize() > FULL_PARSE_MAX_BYTES) {
                return "";
            }
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("failed to read full gcode content for metadata fallback", e);
            return "";
        }
    }

    /**
     * 将 Base64 缩略图解码并上传到 RustFS。
     *
     * @param base64Data Base64 编码的图片数据
     * @param safeName   原文件的安全名称（用于生成缩略图 key）
     * @return 缩略图的 URL，失败返回 null
     */
    private String uploadThumbnailToRustFS(String base64Data, String safeName) {
        try {
            // 提取图片格式
            String contentType = "image/jpeg"; // 默认 JPEG
            if (base64Data.contains("/9j/")) {
                contentType = "image/jpeg";
            } else if (base64Data.startsWith("iVBOR")) {
                contentType = "image/png";
            }

            // Base64 解码
            byte[] imageBytes = java.util.Base64.getDecoder().decode(base64Data);

            // 生成缩略图 key
            String thumbnailKey = "thumbnails/" + safeName.replace(".gcode", "." + contentType.split("/")[1]);

            return rustFsClient.uploadBytes(thumbnailKey, imageBytes, contentType);
        } catch (Exception e) {
            log.warn("缩略图上传失败: safeName={}, error={}", safeName, e.getMessage());
            return null;
        }
    }
}
