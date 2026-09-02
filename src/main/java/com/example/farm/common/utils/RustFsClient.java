package com.example.farm.common.utils;

import com.example.farm.common.exception.StorageException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

@Slf4j
@Component
public class RustFsClient {

    @Value("${rustfs.endpoint}")
    private String endpoint;

    @Value("${rustfs.access-key}")
    private String accessKey;

    @Value("${rustfs.secret-key}")
    private String secretKey;

    @Value("${rustfs.bucket}")
    private String bucket;

    private S3Client s3Client;
    private S3Presigner s3Presigner;

    @PostConstruct
    public void init() {
        log.info("正在初始化 RustFS(S3) 客户端: endpoint={}", endpoint);
        try {
            this.s3Client = S3Client.builder()
                    .endpointOverride(URI.create(endpoint))
                    // RustFS 不校验 region，写固定值即可。
                    .region(Region.US_EAST_1)
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKey, secretKey)
                    ))
                    // 适配 RustFS 的 Path-Style 地址。
                    .forcePathStyle(true)
                    .build();

            // 初始化预签名客户端
            this.s3Presigner = S3Presigner.builder()
                    .endpointOverride(URI.create(endpoint))
                    .region(Region.US_EAST_1)
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKey, secretKey)
                    ))
                    .build();

            // 启动时自动检查并创建 Bucket。
            createBucketIfNotExists();
            log.info("RustFS(S3) 客户端初始化成功");
        } catch (Exception e) {
            log.error("RustFS(S3) 客户端初始化失败，请检查配置或网络", e);
        }
    }

    private void createBucketIfNotExists() {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException e) {
            log.info("对象存储桶不存在，开始自动创建: bucket={}", bucket);
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        }
    }

    /**
     * 流式上传文件到 RustFS。
     *
     * @return 文件最终可访问 URL
     */
    public String uploadFile(String filename, MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(filename)
                    // G-code 本质为纯文本。
                    .contentType("text/plain")
                    .build();

            // 通过流式方式上传，避免临时文件和高内存占用。
            s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(inputStream, file.getSize()));

            // 组装 Path-Style URL，供 Klipper 下载。
            String fileUrl = String.format("%s/%s/%s", endpoint, bucket, filename);
            log.info("文件上传成功: {}", fileUrl);
            return fileUrl;

        } catch (Exception e) {
            log.error("上传切片文件到对象存储失败: 文件名={}", filename, e);
            throw new StorageException("对象存储服务异常", e);
        }
    }

    /**
     * 读取文件头部片段（8KB），用于快速元数据探测。
     */
    public String readHeader(String filename) {
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(filename)
                    .range("bytes=0-8191")
                    .build();

            byte[] bytes = s3Client.getObjectAsBytes(getObjectRequest).asByteArray();
            return new String(bytes);
        } catch (Exception e) {
            log.warn("读取文件头失败: 文件名={}，原因={}", filename, e.getMessage());
            return "";
        }
    }

    /**
     * 从 RustFS 获取文件流。
     */
    public org.springframework.core.io.InputStreamResource getFileStream(String filename) {
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(filename)
                    .build();

            software.amazon.awssdk.core.ResponseInputStream<GetObjectResponse> s3Stream = s3Client.getObject(getObjectRequest);

            // 包装成 Spring 可识别的资源流，供后续转发上传等场景使用。
            return new org.springframework.core.io.InputStreamResource(s3Stream) {
                @Override
                public String getFilename() {
                    return filename;
                }

                @Override
                public long contentLength() {
                    return s3Stream.response().contentLength();
                }
            };
        } catch (Exception e) {
            log.error("读取对象存储文件流失败: 文件名={}", filename, e);
            throw new StorageException("读取对象存储文件失败", e);
        }
    }

    /**
     * 按对象 Key 删除 RustFS 文件。
     */
    public void deleteFile(String filename) {
        try {
            DeleteObjectRequest request = DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(filename)
                    .build();
            s3Client.deleteObject(request);
            log.info("RustFS 对象删除成功: bucket={}, key={}", bucket, filename);
        } catch (Exception e) {
            log.error("RustFS 对象删除失败: bucket={}, key={}", bucket, filename, e);
            throw new StorageException("对象存储删除失败", e);
        }
    }

    /**
     * 获取文件的预签名 URL（用于直接下载或分享）。
     *
     * @param filename 对象 Key
     * @param expiration 过期时间（默认 1 小时）
     * @return 预签名 URL 字符串
     */
    public String getPresignedUrl(String filename, Duration expiration) {
        try {
            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(expiration != null ? expiration : Duration.ofHours(1))
                    .getObjectRequest(GetObjectRequest.builder()
                            .bucket(bucket)
                            .key(filename)
                            .build())
                    .build();

            return s3Presigner.presignGetObject(presignRequest).url().toString();
        } catch (Exception e) {
            log.error("生成预签名 URL 失败: key={}", filename, e);
            throw new StorageException("生成预签名 URL 失败", e);
        }
    }

    /**
     * 获取文件的预签名 URL（默认 1 小时过期）。
     *
     * @param filename 对象 Key
     * @return 预签名 URL 字符串
     */
    public String getPresignedUrl(String filename) {
        return getPresignedUrl(filename, null);
    }

    /**
     * 将历史上保存的对象直连 URL 转换为短期预签名 URL。
     * 对外接口不应直接返回 uploadBytes/uploadFile 产生的内部地址。
     */
    public String getPresignedUrlForObjectUrl(String objectUrl, Duration expiration) {
        return getPresignedUrl(objectKeyFromObjectUrl(objectUrl), expiration);
    }

    /**
     * 删除历史上保存的对象直连 URL 对应的对象。
     */
    public void deleteFileByObjectUrl(String objectUrl) {
        deleteFile(objectKeyFromObjectUrl(objectUrl));
    }

    private String objectKeyFromObjectUrl(String objectUrl) {
        if (objectUrl == null || objectUrl.isBlank()) {
            throw new StorageException("对象存储地址不能为空");
        }
        try {
            String path = URI.create(objectUrl).getPath();
            String bucketPrefix = "/" + bucket + "/";
            int prefixIndex = path.indexOf(bucketPrefix);
            if (prefixIndex < 0) {
                throw new StorageException("对象存储地址格式不正确");
            }
            String objectKey = path.substring(prefixIndex + bucketPrefix.length());
            if (objectKey.isBlank()) {
                throw new StorageException("对象存储对象不存在");
            }
            return objectKey;
        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("对象存储地址格式不正确", e);
        }
    }

    /**
     * 上传字节数组到 RustFS（用于缩略图等小文件）。
     *
     * @param key         对象 Key
     * @param data        字节数组
     * @param contentType Content-Type
     * @return 文件 URL
     */
    public String uploadBytes(String key, byte[] data, String contentType) {
        try {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(contentType != null ? contentType : "application/octet-stream")
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromBytes(data));

            String fileUrl = String.format("%s/%s/%s", endpoint, bucket, key);
            log.info("字节数组上传成功: key={}, size={} bytes", key, data.length);
            return fileUrl;

        } catch (Exception e) {
            log.error("字节数组上传失败: key={}", key, e);
            throw new StorageException("对象存储上传失败", e);
        }
    }
}
