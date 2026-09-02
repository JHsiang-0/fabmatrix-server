package com.example.farm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.utils.GCodeParser;
import com.example.farm.common.utils.RustFsClient;
import com.example.farm.common.utils.SecurityContextUtil;
import com.example.farm.entity.PrintFile;
import com.example.farm.entity.PrintJob;
import com.example.farm.entity.dto.PrintFileQueryDTO;
import com.example.farm.mapper.PrintFileMapper;
import com.example.farm.service.PrintFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PrintFileServiceImpl extends ServiceImpl<PrintFileMapper, PrintFile> implements PrintFileService {

    private static final int META_SAMPLE_SIZE = 8192;
    private static final int DEEP_TAIL_SAMPLE_SIZE = 512 * 1024; // 512KB
    private static final long FULL_PARSE_MAX_BYTES = 100L * 1024 * 1024; // 100MB

    private final RustFsClient rustFsClient;

    @Override
    public List<PrintFile> getFolderContent(Long parentId) {
        // parentId 为 NULL 时查询根目录（parent_id IS NULL）
        LambdaQueryWrapper<PrintFile> wrapper = new LambdaQueryWrapper<>();
        if (parentId == null) {
            wrapper.isNull(PrintFile::getParentId);
        } else {
            wrapper.eq(PrintFile::getParentId, parentId);
        }
        // 文件夹排在前面，文件按创建时间倒序
        wrapper.orderByAsc(PrintFile::getIsFolder)
                .orderByDesc(PrintFile::getCreatedAt);
        return this.list(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PrintFile createFolder(Long parentId, String folderName) {
        if (folderName == null || folderName.isBlank()) {
            throw new BusinessException("文件夹名称不能为空");
        }

        // 检查同名文件夹是否已存在（复用 originalName 字段存储文件夹名称）
        LambdaQueryWrapper<PrintFile> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PrintFile::getIsFolder, true)
                .eq(PrintFile::getOriginalName, folderName);

        if (parentId == null) {
            wrapper.isNull(PrintFile::getParentId);
        } else {
            wrapper.eq(PrintFile::getParentId, parentId);
        }

        PrintFile existing = this.getOne(wrapper, false);
        if (existing != null) {
            throw new BusinessException("该目录下已存在同名文件夹");
        }

        PrintFile folder = new PrintFile();
        folder.setParentId(parentId);
        folder.setIsFolder(true);
        folder.setOriginalName(folderName); // 复用 originalName 字段存储文件夹名称
        folder.setSafeName("folder_" + System.currentTimeMillis()); // 文件夹占位符
        folder.setCreatedAt(LocalDateTime.now());

        this.save(folder);
        log.info("创建虚拟文件夹成功: folderId={}, parentId={}, name={}", folder.getId(), parentId, folderName);
        return folder;
    }

    @Override
    public Page<PrintFile> pageFiles(PrintFileQueryDTO queryDTO) {
        Long userId = SecurityContextUtil.getCurrentUserId();
        Page<PrintFile> page = new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize());

        // 分页查询当前用户文件列表
        LambdaQueryWrapper<PrintFile> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PrintFile::getUserId, userId)
                .orderByDesc(PrintFile::getCreatedAt);
        Page<PrintFile> resultPage = this.page(page, wrapper);

        // 统计每个文件的打印次数和成功率
        if (resultPage.getRecords() != null && !resultPage.getRecords().isEmpty()) {
            for (PrintFile printFile : resultPage.getRecords()) {
                calculatePrintStats(printFile);
            }
        }

        return resultPage;
    }

    /**
     * 计算打印统计信息（打印次数和成功率）
     */
    private void calculatePrintStats(PrintFile printFile) {
        if (printFile.getId() == null) {
            return;
        }

        // 查询该文件的所有打印任务
        LambdaQueryWrapper<PrintJob> jobWrapper = new LambdaQueryWrapper<>();
        jobWrapper.eq(PrintJob::getFileId, printFile.getId());
        // 只统计已完成和失败的任务（排除正在进行的）
        jobWrapper.in(PrintJob::getStatus, "COMPLETED", "FAILED", "CANCELED");

        Long userId = SecurityContextUtil.getCurrentUserId();
        Long fileId = printFile.getId();

        // 使用原生 SQL 进行统计查询
        Integer totalCount = baseMapper.countPrintJobsByFileId(fileId, userId, null);
        Integer completedCount = baseMapper.countPrintJobsByFileId(fileId, userId, "COMPLETED");

        printFile.setPrintCount(totalCount != null ? totalCount : 0);

        if (totalCount != null && totalCount > 0 && completedCount != null) {
            // 计算成功率 = 完成数 / 总数 * 100
            BigDecimal rate = new BigDecimal(completedCount)
                    .divide(new BigDecimal(totalCount), 4, RoundingMode.HALF_UP)
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
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }

        Long userId = SecurityContextUtil.getCurrentUserId();
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            originalName = "unknown.gcode";
        }
        String safeName = System.currentTimeMillis() + "_" + originalName;

        // 统一从完整文件解析
        String fullContent = extractFullContentIfAffordable(file);
        GCodeParser.GCodeMeta meta;
        String parseSource;

        if (!fullContent.isEmpty()) {
            // 完整文件解析
            meta = GCodeParser.parseMetadata(fullContent);
            parseSource = "full-file";
        } else if (file.getSize() > FULL_PARSE_MAX_BYTES) {
            // 文件太大，只解析头部
            String headTail = extractHeadAndTail(file);
            meta = GCodeParser.parseMetadata(headTail);
            parseSource = "head-tail (skipped large file)";
        } else {
            // 回退到头部解析
            String headTail = extractHeadAndTail(file);
            meta = GCodeParser.parseMetadata(headTail);
            parseSource = "head-tail";
        }

        // 提取缩略图（从头部）
        String headTailForThumb = extractHeadAndTail(file);

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
        if (filamentLength != null && filamentLength.compareTo(BigDecimal.ZERO) > 0) {
            // 转换为米（原始数据是 mm）
            if (filamentLength.compareTo(new BigDecimal("1000")) > 0) {
                filamentLength = filamentLength.divide(new BigDecimal("1000"), 2, java.math.RoundingMode.HALF_UP);
            }
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

        // 提取并上传缩略图
        try {
            String thumbnailBase64 = GCodeParser.extractThumbnailBase64(headTailForThumb);
            if (thumbnailBase64 != null && !thumbnailBase64.isEmpty()) {
                String thumbnailUrl = uploadThumbnailToRustFS(thumbnailBase64, safeName);
                if (thumbnailUrl != null) {
                    printFile.setThumbnailUrl(thumbnailUrl);
                    log.info("缩略图提取并上传成功: fileId={}, thumbnailUrl={}", printFile.getId(), thumbnailUrl);
                }
            } else {
                log.debug("G-code 中未找到缩略图: safeName={}", safeName);
            }
        } catch (Exception e) {
            log.warn("缩略图提取或上传失败，继续保存文件: safeName={}", safeName, e);
            // 缩略图失败不影响主流程
        }

        this.save(printFile);
        log.info("切片文件入库成功: fileId={}, userId={}, safeName={}", printFile.getId(), userId, safeName);
        return printFile;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteFile(Long id) {
        Long userId = SecurityContextUtil.getCurrentUserId();
        LambdaQueryWrapper<PrintFile> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PrintFile::getId, id)
                .eq(PrintFile::getUserId, userId);
        PrintFile target = this.getOne(wrapper, false);
        if (target == null) {
            log.warn("delete print file ignored: not found or no permission, fileId={}, userId={}", id, userId);
            return;
        }

        String objectKey = target.getSafeName();
        rustFsClient.deleteFile(objectKey);
        this.removeById(target.getId());
        log.info("print file deleted from rustfs and db: fileId={}, userId={}, key={}", id, userId, objectKey);
    }

    @Override
    public String getPresignedDownloadUrl(Long id, Integer expirationMinutes) {
        Long userId = SecurityContextUtil.getCurrentUserId();
        PrintFile file = this.getById(id);
        if (file == null || !file.getUserId().equals(userId)) {
            throw new BusinessException("文件不存在或无权限访问");
        }

        Duration expiration = expirationMinutes != null && expirationMinutes > 0
                ? Duration.ofMinutes(expirationMinutes)
                : Duration.ofHours(1);

        return rustFsClient.getPresignedUrl(file.getSafeName(), expiration);
    }

    @Override
    public org.springframework.core.io.InputStreamResource downloadFile(Long id) {
        Long userId = SecurityContextUtil.getCurrentUserId();
        PrintFile file = this.getById(id);
        if (file == null || !file.getUserId().equals(userId)) {
            throw new BusinessException("文件不存在或无权限访问");
        }
        return rustFsClient.getFileStream(file.getSafeName());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchDeleteFiles(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        Long userId = SecurityContextUtil.getCurrentUserId();

        // 查询用户拥有的文件
        LambdaQueryWrapper<PrintFile> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(PrintFile::getId, ids)
                .eq(PrintFile::getUserId, userId);
        List<PrintFile> files = this.list(wrapper);

        if (files.isEmpty()) {
            return;
        }

        // 删除 RustFS 中的文件
        for (PrintFile file : files) {
            try {
                rustFsClient.deleteFile(file.getSafeName());
            } catch (Exception e) {
                log.warn("删除 RustFS 文件失败: key={}, error={}", file.getSafeName(), e.getMessage());
            }
        }

        // 批量删除数据库记录
        List<Long> idsToDelete = files.stream().map(PrintFile::getId).toList();
        this.removeByIds(idsToDelete);
        log.info("批量删除文件完成: userId={}, deletedCount={}", userId, idsToDelete.size());
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
