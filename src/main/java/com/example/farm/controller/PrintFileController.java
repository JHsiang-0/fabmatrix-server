package com.example.farm.controller;

import com.example.farm.common.api.PageResult;
import com.example.farm.common.api.Result;
import com.example.farm.common.exception.BusinessException;
import com.example.farm.entity.dto.PrintFileQueryDTO;
import com.example.farm.entity.dto.request.FileJobsQueryDTO;
import com.example.farm.entity.dto.request.CreateFolderRequest;
import com.example.farm.entity.dto.request.BatchDeleteFilesRequest;
import com.example.farm.entity.PrintFile;
import com.example.farm.entity.vo.PrintFileVO;
import com.example.farm.entity.vo.FileNodeVO;
import com.example.farm.entity.vo.PrintJobVO;
import com.example.farm.entity.vo.PrintFilePreviewVO;
import com.example.farm.service.PrintFileService;
import com.example.farm.service.PrintJobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 打印文件管理接口。
 */
@Tag(name = "打印文件管理")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/print-files")
public class PrintFileController {

    private final PrintFileService farmPrintFileService;
    private final PrintJobService printJobService;

    /**
     * 上传并解析切片文件。
     */
    @Operation(summary = "上传并解析切片文件")
    @PostMapping("/upload")
    public Result<PrintFileVO> uploadFile(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }
        PrintFile savedFile = farmPrintFileService.uploadAndParseFile(file);
        return Result.success(PrintFileVO.from(savedFile), "文件上传成功");
    }

    /**
     * 分页查询当前用户文件列表。
     */
    @Operation(summary = "分页查询打印文件列表")
    @PostMapping("/page")
    public Result<PageResult<PrintFileVO>> pageFiles(@Valid @RequestBody PrintFileQueryDTO queryDTO) {
        queryDTO = requireBody(queryDTO);
        return Result.success(PageResult.from(farmPrintFileService.pageFiles(queryDTO), PrintFileVO::from));
    }

    /**
     * 获取当前用户可访问的完整文件目录树。
     */
    @Operation(summary = "获取文件目录树", description = "返回完整目录树，不分页；目录和文件节点均包含 children 数组")
    @GetMapping("/tree")
    public Result<List<FileNodeVO>> getFileTree() {
        return Result.success(farmPrintFileService.getFileTree(), "获取文件目录树成功");
    }

    /**
     * 分页查询指定文件关联的打印任务。
     */
    @Operation(summary = "查询文件关联任务", description = "按文件 ID 分页查询任务，并按当前用户权限隔离")
    @GetMapping("/{id}/jobs")
    public Result<PageResult<PrintJobVO>> getFileJobs(
            @PathVariable Long id, @Valid FileJobsQueryDTO queryDTO) {
        return Result.success(PageResult.from(
                printJobService.queryJobsByFileId(id, queryDTO), PrintJobVO::from));
    }

    /**
     * 获取文件的安全预览元数据，不返回 G-code 原文或存储内部字段。
     */
    @Operation(summary = "获取文件安全预览", description = "返回已解析元数据和缩略图，不返回文件内容、存储 key 或下载地址")
    @GetMapping("/{id}/preview")
    public Result<PrintFilePreviewVO> getPreview(@PathVariable Long id) {
        return Result.success(farmPrintFileService.getPreview(id), "获取文件预览成功");
    }

    /**
     * 删除文件。
     */
    @Operation(summary = "删除文件")
    @DeleteMapping("/{id}")
    public Result<Void> deleteFile(@PathVariable Long id) {
        farmPrintFileService.deleteFile(id);
        return Result.success(null, "删除成功");
    }

    /**
     * 获取文件下载链接。
     * 返回预签名 URL，前端通过 window.location.href 或 <a> 标签直接下载，避免 Java 服务代理大文件。
     *
     * @param id      文件 ID
     * @param expires 预签名 URL 过期时间（分钟），默认 60
     * @return 预签名下载 URL
     */
    @Operation(summary = "获取文件下载链接")
    @GetMapping("/{id}/download")
    public Result<String> getDownloadUrl(
            @PathVariable Long id,
            @RequestParam(value = "expires", required = false, defaultValue = "60") Integer expires) {

        String presignedUrl = farmPrintFileService.getPresignedDownloadUrl(id, expires);
        return Result.success(presignedUrl, "获取下载链接成功");
    }

    @Operation(summary = "获取缩略图链接", description = "返回短期预签名缩略图 URL；没有缩略图时 data=null")
    @GetMapping("/{id}/thumbnail")
    public Result<String> getThumbnailUrl(
            @PathVariable Long id,
            @RequestParam(value = "expires", required = false, defaultValue = "60") Integer expires) {
        String presignedUrl = farmPrintFileService.getPresignedThumbnailUrl(id, expires);
        return Result.success(presignedUrl, "获取缩略图链接成功");
    }

    /**
     * 批量删除文件。
     * 请求格式: {"ids": [1, 2, 3]}
     */
    @Operation(summary = "批量删除文件")
    @DeleteMapping("/batch")
    public Result<PrintFileService.BatchDeleteResult> batchDeleteFiles(
            @Valid @RequestBody BatchDeleteFilesRequest request) {
        request = requireBody(request);
        PrintFileService.BatchDeleteResult result = farmPrintFileService.batchDeleteFiles(request.getIds());
        return Result.success(result, result.getMessage());
    }

    // =============================================
    // 虚拟目录管理接口
    // =============================================

    /**
     * 获取指定目录下的文件和文件夹列表。
     *
     * @param parentId 父目录ID（不传或传null表示根目录）
     * @return 目录内容列表（文件夹在前，文件按时间倒序）
     */
    @Operation(summary = "获取目录内容")
    @GetMapping("/folder/content")
    public Result<List<PrintFileVO>> getFolderContent(@RequestParam(required = false) Long parentId) {
        List<PrintFile> contents = farmPrintFileService.getFolderContent(parentId);
        return Result.success(contents.stream().map(PrintFileVO::from).toList(), "获取目录内容成功");
    }

    /**
     * 创建虚拟文件夹。
     *
     * @param req 创建请求（parentId, folderName）
     * @return 创建的文件夹信息
     */
    @Operation(summary = "创建文件夹")
    @PostMapping("/folder/create")
    public Result<PrintFileVO> createFolder(@Valid @RequestBody CreateFolderRequest req) {
        req = requireBody(req);
        PrintFile folder = farmPrintFileService.createFolder(req.getParentId(), req.getFolderName());
        return Result.success(PrintFileVO.from(folder), "文件夹创建成功");
    }

    private <T> T requireBody(T body) {
        if (body == null) {
            throw new BusinessException(400, "请求体不能为空");
        }
        return body;
    }
}
