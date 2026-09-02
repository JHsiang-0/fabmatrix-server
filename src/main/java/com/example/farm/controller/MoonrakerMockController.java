package com.example.farm.controller;

import com.example.farm.common.exception.BusinessException;
import com.example.farm.entity.PrintFile;
import com.example.farm.entity.dto.PrintJobCreateDTO;
import com.example.farm.service.PrintFileService;
import com.example.farm.service.PrintJobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Moonraker API 模拟控制器
 * 用于兼容 OrcaSlicer 等切片软件的 G-code 上传功能
 * 映射根路径 /server、/printer、/machine，避免 /api/v1 前缀
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Profile({"dev", "test"})
@Tag(name = "Moonraker 模拟接口", description = "兼容 OrcaSlicer 等切片软件的 API 模拟服务")
public class MoonrakerMockController {

    private final PrintFileService printFileService;
    private final PrintJobService printJobService;

    @Value("${farm.moonraker-api-key:}")
    private String moonrakerApiKey;

    /**
     * 校验 API Key
     */
    private void validateApiKey(String apiKey) {
        if (moonrakerApiKey == null || moonrakerApiKey.isBlank()) {
            log.warn("未配置 farm.moonraker-api-key，建议配置以保证安全");
            return;
        }
        if (apiKey == null || !apiKey.equals(moonrakerApiKey)) {
            throw new BusinessException(401, "API Key 无效");
        }
    }

    /**
     * 获取 Moonraker 服务器信息
     * GET /server/info
     */
    @Operation(summary = "获取服务器信息", description = "返回 Moonraker 服务器版本信息")
    @GetMapping("/server/info")
    public Map<String, Object> getServerInfo(@RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        validateApiKey(apiKey);

        Map<String, Object> result = new HashMap<>();
        Map<String, Object> serverInfo = new HashMap<>();

        // Klipper 连接状态
        serverInfo.put("klippy_connected", true);
        serverInfo.put("klippy_state", "ready");

        // 组件列表
        serverInfo.put("components", java.util.Arrays.asList(
            "secrets", "template", "klippy_connection", "jsonrpc",
            "internal_transport", "application", "websockets", "database",
            "dbus_manager", "file_manager", "authorization", "klippy_apis",
            "shell_command", "machine", "data_store", "proc_stats",
            "job_state", "job_queue", "history", "http_client",
            "announcements", "webcam", "extensions", "octoprint_compat",
            "update_manager"
        ));

        // 失败组件
        serverInfo.put("failed_components", java.util.Collections.emptyList());

        // 注册目录
        serverInfo.put("registered_directories", java.util.Arrays.asList(
            "config", "logs", "gcodes", "config_examples", "docs"
        ));

        // 警告信息
        serverInfo.put("warnings", java.util.Collections.emptyList());

        // WebSocket 连接数
        serverInfo.put("websocket_count", 1);

        // Moonraker 版本信息
        serverInfo.put("moonraker_version", "v0.9.3-130-g5b92e52");
        serverInfo.put("missing_klippy_requirements", java.util.Collections.emptyList());

        // API 版本 (数组格式)
        serverInfo.put("api_version", java.util.Arrays.asList(1, 5, 0));
        serverInfo.put("api_version_string", "1.5.0");

        result.put("result", serverInfo);
        log.debug("Moonraker /server/info called");
        return result;
    }

    /**
     * 获取打印机信息
     * GET /printer/info
     */
    @Operation(summary = "获取打印机信息", description = "返回打印机当前状态信息")
    @GetMapping("/printer/info")
    public Map<String, Object> getPrinterInfo(@RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        validateApiKey(apiKey);

        Map<String, Object> result = new HashMap<>();
        Map<String, Object> printerInfo = new HashMap<>();

        // 打印机状态
        printerInfo.put("state", "ready");
        printerInfo.put("state_message", "Printer is ready");

        // 主机信息
        printerInfo.put("hostname", "klipper");
        printerInfo.put("klipper_path", "/home/klipper/klipper");
        printerInfo.put("python_path", "/home/klipper/klippy-env/bin/python");

        // 进程信息
        printerInfo.put("process_id", 1270);
        printerInfo.put("user_id", 1000);
        printerInfo.put("group_id", 1000);

        // 日志和配置文件路径
        printerInfo.put("log_file", "/home/klipper/printer_data/logs/klippy.log");
        printerInfo.put("config_file", "/home/klipper/printer_data/config/printer.cfg");

        // 软件版本和硬件信息
        printerInfo.put("software_version", "v0.13.0-433-gdc622f4a");
        printerInfo.put("cpu_info", "4 core ?");

        result.put("result", printerInfo);
        log.debug("Moonraker /printer/info called");
        return result;
    }

    /**
     * 获取机器更新状态
     * GET /machine/update/status
     * 返回空版本信息避免 Slicer 轮询报错
     */
    @Operation(summary = "获取机器更新状态", description = "返回空的版本信息，避免切片软件轮询报错")
    @GetMapping("/machine/update/status")
    public Map<String, Object> getMachineUpdateStatus(@RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        validateApiKey(apiKey);

        Map<String, Object> result = new HashMap<>();
        Map<String, Object> versionInfo = new HashMap<>();
        versionInfo.put("configured", Collections.emptyList());
        versionInfo.put("offline", Collections.emptyMap());

        result.put("result", versionInfo);
        log.debug("Moonraker /machine/update/status called");
        return result;
    }

    /**
     * 上传 G-code 文件
     * POST /server/files/upload
     *
     * @param file   上传的文件
     * @param print  是否立即打印
     * @param apiKey API Key
     * @return Moonraker 格式的响应
     */
    @Operation(summary = "上传 G-code 文件", description = "接收切片软件上传的 G-code 文件，自动解析并上传到存储服务")
    @PostMapping("/server/files/upload")
    public Map<String, Object> uploadFile(
            @Parameter(description = "上传的 G-code 文件") @RequestParam("file") MultipartFile file,
            @Parameter(description = "是否立即创建打印任务") @RequestParam(value = "print", required = false, defaultValue = "false") Boolean print,
            @Parameter(description = "API Key 认证") @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {

        validateApiKey(apiKey);

        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "文件不能为空");
        }

        log.info("Moonraker 收到文件上传: filename={}, size={}, print={}",
                file.getOriginalFilename(), file.getSize(), print);

        try {
            // 调用现有的文件解析、S3 上传和入库逻辑
            PrintFile printFile = printFileService.uploadAndParseFile(file);
            log.info("文件入库成功: fileId={}, safeName={}", printFile.getId(), printFile.getSafeName());

            // 如果 print=true，创建排队中的打印任务
            Long jobId = null;
            if (print) {
                PrintJobCreateDTO jobReq = new PrintJobCreateDTO();
                jobReq.setFileId(printFile.getId());
                jobReq.setPriority(0);
//                jobReq.setAutoAssign(true);

                // 使用默认用户 ID (切片软件上传时没有用户上下文)
                // 这里可以从 API Key 中提取用户信息，目前简化为使用系统默认
                jobId = printJobService.submitJob(printFile.getId(), 1L, 0);
                log.info("创建打印任务成功: jobId={}", jobId);
            }

            // 返回 Moonraker 格式的响应
            Map<String, Object> result = new HashMap<>();
            Map<String, Object> item = new HashMap<>();
            item.put("path", printFile.getSafeName());
            item.put("size", file.getSize());
            item.put("modified", printFile.getCreatedAt().toString());
            if (jobId != null) {
                item.put("job_id", jobId);
            }

            result.put("result", Collections.singletonMap("item", item));
            return result;

        } catch (Exception e) {
            log.error("文件上传失败", e);
            throw new BusinessException("文件上传失败: " + e.getMessage());
        }
    }

    /**
     * 获取文件列表
     * GET /server/files
     */
    @Operation(summary = "获取文件列表", description = "返回已上传的 G-code 文件列表")
    @GetMapping("/server/files")
    public Map<String, Object> getFileList(
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        validateApiKey(apiKey);

        Map<String, Object> result = new HashMap<>();
        result.put("result", Collections.emptyList());
        log.debug("Moonraker /server/files called");
        return result;
    }

    /**
     * 删除文件
     * DELETE /server/files/{filename}
     */
    @Operation(summary = "删除 G-code 文件", description = "根据文件名删除指定的 G-code 文件")
    @DeleteMapping("/server/files/{filename:.+}")
    public Map<String, Object> deleteFile(
            @Parameter(description = "要删除的文件名") @PathVariable String filename,
            @Parameter(description = "API Key 认证") @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {

        validateApiKey(apiKey);

        log.info("Moonraker 收到文件删除请求: filename={}", filename);

        Map<String, Object> result = new HashMap<>();
        result.put("result", Collections.singletonMap("deleted", filename));
        return result;
    }
}
