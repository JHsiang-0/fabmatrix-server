package com.example.farm.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.farm.common.api.Result;
import com.example.farm.common.exception.BusinessException;
import com.example.farm.entity.PrintFile;
import com.example.farm.entity.dto.PrintFileQueryDTO;
import com.example.farm.entity.dto.request.CreateFolderRequest;
import com.example.farm.service.PrintFileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import java.util.Map;

/**
 * 打印文件管理接口。
 */
@Tag(name = "打印文件管理")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/print-files")
public class PrintFileController {

    private final PrintFileService farmPrintFileService;

    /**
     * 上传并解析切片文件。
     */
    @Operation(summary = "上传并解析切片文件")
    @PostMapping("/upload")
    public Result<PrintFile> uploadFile(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }
        PrintFile savedFile = farmPrintFileService.uploadAndParseFile(file);
        return Result.success(savedFile, "文件上传成功");
    }

    /**
     * 分页查询当前用户文件列表。
     */
    @Operation(summary = "分页查询打印文件列表")
    @PostMapping("/page")
    public Result<Page<PrintFile>> pageFiles(@RequestBody PrintFileQueryDTO queryDTO) {
        return Result.success(farmPrintFileService.pageFiles(queryDTO));
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

    /**
     * 批量删除文件。
     * 请求格式: {"ids": [1, 2, 3]}
     */
    @Operation(summary = "批量删除文件")
    @DeleteMapping("/batch")
    public Result<Void> batchDeleteFiles(@RequestBody Map<String, List<Long>> request) {
        List<Long> ids = request.get("ids");
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException("请选择要删除的文件");
        }
        farmPrintFileService.batchDeleteFiles(ids);
        return Result.success(null, "批量删除成功");
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
    public Result<List<PrintFile>> getFolderContent(@RequestParam(required = false) Long parentId) {
        List<PrintFile> contents = farmPrintFileService.getFolderContent(parentId);
        return Result.success(contents, "获取目录内容成功");
    }

    /**
     * 创建虚拟文件夹。
     *
     * @param req 创建请求（parentId, folderName）
     * @return 创建的文件夹信息
     */
    @Operation(summary = "创建文件夹")
    @PostMapping("/folder/create")
    public Result<PrintFile> createFolder(@RequestBody CreateFolderRequest req) {
        PrintFile folder = farmPrintFileService.createFolder(req.getParentId(), req.getFolderName());
        return Result.success(folder, "文件夹创建成功");
    }
}