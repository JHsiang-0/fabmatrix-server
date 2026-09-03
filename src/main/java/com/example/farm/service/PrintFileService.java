package com.example.farm.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.example.farm.common.exception.BusinessException;
import com.example.farm.entity.PrintFile;
import com.example.farm.entity.dto.PrintFileQueryDTO;
import com.example.farm.entity.vo.FileNodeVO;
import com.example.farm.entity.vo.PrintFilePreviewVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.ArrayList;

/**
 * 打印文件服务接口。
 */
public interface PrintFileService extends IService<PrintFile> {

    /**
     * 获取指定目录下的所有文件夹和文件列表（扁平存储，虚拟目录）
     *
     * @param parentId 父目录ID（NULL 表示根目录）
     * @return 目录下的文件和文件夹列表
     */
    List<PrintFile> getFolderContent(Long parentId);

    /**
     * 获取当前用户可访问的完整文件目录树。
     *
     * @return 根节点列表
     */
    List<FileNodeVO> getFileTree();

    /**
     * 获取文件的安全预览元数据。
     *
     * @param id 文件 ID
     * @return 预览信息
     */
    PrintFilePreviewVO getPreview(Long id);

    /**
     * 在指定目录下创建一个虚拟文件夹
     *
     * @param parentId  父目录ID（NULL 表示在根目录创建）
     * @param folderName 文件夹名称
     * @return 创建的文件夹实体
     * @throws BusinessException 当名称为空或已存在同名文件夹时抛出
     */
    PrintFile createFolder(Long parentId, String folderName);

    /**
     * 分页查询当前用户的文件列表。
     *
     * @param queryDTO 查询参数
     * @return 文件分页结果
     */
    Page<PrintFile> pageFiles(PrintFileQueryDTO queryDTO);

    /**
     * 上传并解析切片文件。
     *
     * @param file 上传文件
     * @return 入库后的文件实体
     * @throws BusinessException 当文件为空、解析失败或存储失败时抛出
     */
    PrintFile uploadAndParseFile(MultipartFile file);

    /**
     * 批量上传并解析文件。每个文件独立处理，允许部分成功并返回逐项原因。
     */
    BatchUploadResult batchUploadFiles(List<MultipartFile> files);

    /**
     * 删除文件。
     *
     * @param id 文件 ID
     * @throws BusinessException 当文件不存在或当前用户无权删除时抛出
     */
    void deleteFile(Long id);

    /**
     * 下载文件。
     *
     * @param id 文件 ID
     * @return 文件流和资源
     * @throws BusinessException 当文件不存在或当前用户无权访问时抛出
     */
    org.springframework.core.io.InputStreamResource downloadFile(Long id);

    /**
     * 批量删除文件。
     *
     * @param ids 文件 ID 列表
     * @throws BusinessException 当任一文件不存在或当前用户无权删除时抛出
     */
    BatchDeleteResult batchDeleteFiles(java.util.List<Long> ids);

    /**
     * 获取文件的预签名下载 URL。
     *
     * @param id 文件 ID
     * @param expirationMinutes 过期时间（分钟），默认 60 分钟
     * @return 预签名 URL
     * @throws BusinessException 当文件不存在或当前用户无权访问时抛出
     */
    String getPresignedDownloadUrl(Long id, Integer expirationMinutes);

    /**
     * 获取文件缩略图的短期预签名 URL；没有缩略图时返回 null。
     */
    String getPresignedThumbnailUrl(Long id, Integer expirationMinutes);

    /**
     * 批量删除结果，保留每个 ID 的处理原因，便于前端逐项提示。
     */
    @lombok.Data
    class BatchDeleteResult {
        private int totalCount;
        private int deletedCount;
        private int failedCount;
        private java.util.List<BatchDeleteItemResult> items = new java.util.ArrayList<>();
        private String message;
    }

    /**
     * 单个文件的批量删除结果。
     */
    @lombok.Data
    class BatchDeleteItemResult {
        private Long id;
        private boolean success;
        private String reason;

        public BatchDeleteItemResult(Long id, boolean success, String reason) {
            this.id = id;
            this.success = success;
            this.reason = reason;
        }
    }

    /** 批量上传结果。 */
    @lombok.Data
    class BatchUploadResult {
        private int totalCount;
        private int successCount;
        private int failureCount;
        private List<BatchUploadItemResult> items = new ArrayList<>();
        private String message;
    }

    /** 批量上传单项结果。 */
    @lombok.Data
    class BatchUploadItemResult {
        private int index;
        private Long fileId;
        private String fileName;
        private String status;
        private String errorCode;
        private String message;
        private boolean retryable;

        public BatchUploadItemResult() {
        }

        public BatchUploadItemResult(int index, Long fileId, String fileName, String status,
                                     String errorCode, String message, boolean retryable) {
            this.index = index;
            this.fileId = fileId;
            this.fileName = fileName;
            this.status = status;
            this.errorCode = errorCode;
            this.message = message;
            this.retryable = retryable;
        }
    }
}
