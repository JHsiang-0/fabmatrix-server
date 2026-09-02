package com.example.farm.common.utils;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * G-code 元数据解析器
 * 支持 OrcaSlicer / BambuStudio / PrusaSlicer 的 G-code Header 解析
 * 线程安全，不使用静态可变变量
 */
@Slf4j
public class GCodeParser {

    // ==================== 时间解析 ====================
    private static final Pattern TIME_SECONDS_PATTERN = Pattern.compile("(?im)^\\s*;?\\s*TIME\\s*[:=]\\s*(\\d+)\\s*$");
    private static final Pattern EST_TIME_PATTERN = Pattern.compile("(?im)^\\s*;?\\s*estimated_time\\s*[:=]\\s*(\\d+)\\s*$");
    private static final Pattern EST_PRINTING_TIME_PATTERN = Pattern.compile(
            "(?im)^\\s*;?\\s*estimated printing time(?:\\s*\\([^)]*\\))?\\s*[:=]\\s*([^\\r\\n]+)");
    private static final Pattern DURATION_TOKEN_PATTERN = Pattern.compile("(?i)(\\d+)\\s*([dhms])");
    private static final Pattern DURATION_HMS_COLON_PATTERN = Pattern.compile("^(\\d{1,2}):(\\d{1,2})(?::(\\d{1,2}))?$");

    // ==================== 耗材统计解析 ====================
    private static final Pattern FILAMENT_USED_MM_PATTERN = Pattern.compile(
            "(?im)^\\s*;?\\s*filament used\\s*\\[mm\\]\\s*[:=]\\s*([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern FILAMENT_USED_G_PATTERN = Pattern.compile(
            "(?im)^\\s*;?\\s*(?:filament used\\s*\\[g\\]|total filament used\\s*\\[g\\])\\s*[:=]\\s*([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern FILAMENT_USED_CM3_PATTERN = Pattern.compile(
            "(?im)^\\s*;?\\s*filament used\\s*\\[cm3\\]\\s*[:=]\\s*([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern FILAMENT_COST_PATTERN = Pattern.compile(
            "(?im)^\\s*;?\\s*filament cost\\s*[:=]\\s*([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern TOTAL_LAYERS_PATTERN = Pattern.compile(
            "(?im)^\\s*;?\\s*total layers count\\s*[:=]\\s*(\\d+)");

    // ==================== 耗材相关（兼容旧版）====================
    private static final Pattern FILAMENT_WEIGHT_PATTERN = Pattern.compile(
            "(?im)^\\s*;?\\s*(?:filament used|filament_used|total filament used|filament weight)\\s*[:=]\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(?:g|gram|grams)?");
    private static final Pattern FILAMENT_LENGTH_PATTERN = Pattern.compile(
            "(?im)^\\s*;?\\s*(?:filament used|filament_used|total filament used)\\s*[:=]\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(mm|millimeters?|meters?|m)?\\s*$");

    // ==================== 材料解析 ====================
    private static final Pattern MATERIAL_TOKEN_PATTERN = Pattern.compile("(?i)^[a-z][a-z0-9+\\-_.]*$");
    private static final Pattern MATERIAL_PATTERN = Pattern.compile(
            "(?im)^\\s*;?\\s*(?:filament_type|filament type|material_type|material)\\s*[:=]\\s*([^\\r\\n]+)");
    private static final Pattern ORCA_FILENAME_META_PATTERN = Pattern.compile(
            "(?i)^.*_([0-9]+(?:\\.[0-9]+)?)mm_([^_]+)_(.+)_([^_]+)$");

    // ==================== 喷嘴直径解析 ====================
    private static final Pattern NOZZLE_PATTERN = Pattern.compile(
            "(?im)^\\s*;?\\s*nozzle_diameter\\s*[:=]\\s*\\[?\\s*([0-9]+(?:\\.[0-9]+)?)");

    // ==================== 线宽解析 ====================
    private static final Pattern LINE_WIDTH_PATTERN = Pattern.compile(
            "(?im)^\\s*;?\\s*(?:line_width|default_line_width|outer_wall_line_width|inner_wall_line_width|wall_line_width|perimeter_line_width|external_perimeter_line_width)\\s*[:=]\\s*\\[?\\s*([0-9]+(?:\\.[0-9]+)?)");

    // ==================== 温度解析（分开独立解析）====================
    // nozzle_temperature - 喷头温度（支持多种格式）
    private static final Pattern NOZZLE_TEMP_PATTERN = Pattern.compile(
            "(?im)^\\s*;\\s*nozzle_temperature\\s*[:=]\\s*(\\d+)");
    // nozzle_temperature_initial_layer - 首层喷头温度
    private static final Pattern NOZZLE_TEMP_INITIAL_LAYER_PATTERN = Pattern.compile(
            "(?im)^\\s*;\\s*nozzle_temperature_initial_layer\\s*[:=]\\s*(\\d+)");
    // nozzle_temperature_first_layer - 首层喷头温度（备用）
    private static final Pattern NOZZLE_TEMP_FIRST_LAYER_PATTERN = Pattern.compile(
            "(?im)^\\s*;\\s*nozzle_temperature_first_layer\\s*[:=]\\s*(\\d+)");
    // first_layer_temperature - 首层喷头温度（OrcaSlicer 备用）
    private static final Pattern NOZZLE_TEMP_FIRST_LAYER_PATTERN2 = Pattern.compile(
            "(?im)^\\s*;\\s*first_layer_temperature\\s*[:=]\\s*(\\d+)");
    // bed_temperature - 热床温度（标准）
    private static final Pattern BED_TEMP_PATTERN = Pattern.compile(
            "(?im)^\\s*;\\s*bed_temperature\\s*[:=]\\s*(\\d+)");
    // hot_plate_temp - OrcaSlicer 热床温度
    private static final Pattern BED_TEMP_PATTERN_ORCA = Pattern.compile(
            "(?im)^\\s*;\\s*hot_plate_temp\\s*[:=]\\s*(\\d+)");
    // first_layer_bed_temperature - 首层热床温度
    private static final Pattern BED_TEMP_FIRST_LAYER_PATTERN = Pattern.compile(
            "(?im)^\\s*;\\s*first_layer_bed_temperature\\s*[:=]\\s*(\\d+)");
    // bed_temperature_first_layer - 首层热床温度（备用）
    private static final Pattern BED_TEMP_FIRST_LAYER_PATTERN2 = Pattern.compile(
            "(?im)^\\s*;\\s*bed_temperature_first_layer\\s*[:=]\\s*(\\d+)");
    // hot_plate_temp_initial_layer - OrcaSlicer 首层热床温度
    private static final Pattern BED_TEMP_FIRST_LAYER_PATTERN_ORCA = Pattern.compile(
            "(?im)^\\s*;\\s*hot_plate_temp_initial_layer\\s*[:=]\\s*(\\d+)");

    // ==================== 层高解析（分开独立解析）====================
    // layer_height - 标准层高
    private static final Pattern LAYER_HEIGHT_PATTERN = Pattern.compile(
            "(?im)^\\s*;\\s*layer_height\\s*[:=]\\s*([0-9]+(?:\\.[0-9]+)?)");
    // first_layer_height - 首层层高
    private static final Pattern FIRST_LAYER_HEIGHT_PATTERN = Pattern.compile(
            "(?im)^\\s*;\\s*first_layer_height\\s*[:=]\\s*([0-9]+(?:\\.[0-9]+)?)");
    // initial_layer_print_height - 初始打印层高
    private static final Pattern INITIAL_LAYER_PRINT_HEIGHT_PATTERN = Pattern.compile(
            "(?im)^\\s*;\\s*initial_layer_print_height\\s*[:=]\\s*([0-9]+(?:\\.[0-9]+)?)");

    // ==================== 缩略图解析 ====================
    private static final Pattern THUMBNAIL_START_PATTERN = Pattern.compile(
            "(?im)^\\s*;\\s*(?:thumbnail|thumbnail_JPG|thumbnail_PNG)\\s*(?:begin|start)");
    private static final Pattern THUMBNAIL_DATA_PATTERN = Pattern.compile(
            "(?im)^\\s*;\\s*([A-Za-z0-9+/=]{50,})");
    private static final Pattern THUMBNAIL_END_PATTERN = Pattern.compile(
            "(?im)^\\s*;\\s*(?:thumbnail|thumbnail_JPG|thumbnail_PNG)\\s*(?:end|stop)");

    /**
     * G-code 元数据
     */
    @Data
    public static class GCodeMeta {
        // 时间
        private Integer estimatedPrintTimeSeconds = 0;

        // 材料
        private String materialType;

        // 喷嘴
        private BigDecimal nozzleSize;
        private BigDecimal lineWidth;

        // 温度（区分首层和普通层）
        private Integer nozzleTemp;              // nozzle_temperature
        private Integer firstLayerNozzleTemp;    // nozzle_temperature_initial_layer
        private Integer bedTemp;                 // bed_temperature
        private Integer firstLayerBedTemp;       // first_layer_bed_temperature

        // 层高（区分首层和普通层）
        private BigDecimal layerHeight;          // layer_height
        private BigDecimal firstLayerHeight;     // first_layer_height / initial_layer_print_height

        // 统计信息
        private Integer totalLayers;             // total layers count
        private BigDecimal filamentCost;         // filament cost

        // 耗材用量
        private BigDecimal filamentUsedG;        // filament used [g]
        private BigDecimal filamentUsedMM;       // filament used [mm]
        private BigDecimal filamentUsedCM3;      // filament used [cm3]

        // 兼容旧字段
        private BigDecimal filamentWeight;       // 耗材重量(g) - 兼容
        private BigDecimal filamentLength;       // 耗材长度(m) - 兼容
    }

    /**
     * 解析 G-code 元数据
     * 解析顺序：explicit slicer header -> CONFIG_BLOCK -> fallback patterns
     *
     * @param content G-code 文件内容
     * @return 解析后的元数据
     */
    public static GCodeMeta parseMetadata(String content) {
        GCodeMeta meta = new GCodeMeta();
        if (content == null || content.isEmpty()) {
            return meta;
        }

        // 1. 解析时间（优先）
        meta.setEstimatedPrintTimeSeconds(parsePrintTime(content));

        // 2. 解析材料类型
        parseMaterialType(content, meta);

        // 3. 解析喷嘴直径
        meta.setNozzleSize(parseDecimal(content, NOZZLE_PATTERN));

        // 4. 解析线宽
        meta.setLineWidth(parseDecimal(content, LINE_WIDTH_PATTERN));

        // 5. 解析温度（分别解析，尝试多个备用模式）
        meta.setNozzleTemp(parseInteger(content, NOZZLE_TEMP_PATTERN));

        // 首层喷头温度：优先尝试 initial_layer，然后尝试 first_layer，最后尝试 OrcaSlicer 格式
        Integer firstNozzleTemp = parseInteger(content, NOZZLE_TEMP_INITIAL_LAYER_PATTERN);
        if (firstNozzleTemp == null) {
            firstNozzleTemp = parseInteger(content, NOZZLE_TEMP_FIRST_LAYER_PATTERN);
        }
        if (firstNozzleTemp == null) {
            firstNozzleTemp = parseInteger(content, NOZZLE_TEMP_FIRST_LAYER_PATTERN2);
        }
        meta.setFirstLayerNozzleTemp(firstNozzleTemp);

        // 热床温度：优先标准格式，然后尝试 OrcaSlicer 格式
        Integer bedTemp = parseInteger(content, BED_TEMP_PATTERN);
        if (bedTemp == null) {
            bedTemp = parseInteger(content, BED_TEMP_PATTERN_ORCA);
        }
        meta.setBedTemp(bedTemp);

        // 首层热床温度：优先 first_layer_bed_temperature，然后尝试 bed_temperature_first_layer，最后尝试 OrcaSlicer 格式
        Integer firstBedTemp = parseInteger(content, BED_TEMP_FIRST_LAYER_PATTERN);
        if (firstBedTemp == null) {
            firstBedTemp = parseInteger(content, BED_TEMP_FIRST_LAYER_PATTERN2);
        }
        if (firstBedTemp == null) {
            firstBedTemp = parseInteger(content, BED_TEMP_FIRST_LAYER_PATTERN_ORCA);
        }
        meta.setFirstLayerBedTemp(firstBedTemp);

        // 6. 解析层高（分别解析，不合并正则）
        meta.setLayerHeight(parseDecimal(content, LAYER_HEIGHT_PATTERN));
        BigDecimal firstLayerHeight = parseDecimal(content, FIRST_LAYER_HEIGHT_PATTERN);
        if (firstLayerHeight == null) {
            firstLayerHeight = parseDecimal(content, INITIAL_LAYER_PRINT_HEIGHT_PATTERN);
        }
        meta.setFirstLayerHeight(firstLayerHeight);

        // 7. 解析 OrcaSlicer 统计信息
        meta.setTotalLayers(parseInteger(content, TOTAL_LAYERS_PATTERN));
        meta.setFilamentCost(parseDecimal(content, FILAMENT_COST_PATTERN));
        meta.setFilamentUsedMM(parseDecimal(content, FILAMENT_USED_MM_PATTERN));
        meta.setFilamentUsedG(parseDecimal(content, FILAMENT_USED_G_PATTERN));
        meta.setFilamentUsedCM3(parseDecimal(content, FILAMENT_USED_CM3_PATTERN));

        // 8. 兼容旧版解析（fallback）
        if (meta.getFilamentUsedG() == null) {
            BigDecimal weight = parseDecimal(content, FILAMENT_WEIGHT_PATTERN);
            meta.setFilamentWeight(weight);
            meta.setFilamentUsedG(weight);
        }

        if (meta.getFilamentUsedMM() == null) {
            FilamentLengthValue legacyLength = parseLegacyFilamentLength(content);
            if (legacyLength != null) {
                meta.setFilamentLength(legacyLength.meters());
                meta.setFilamentUsedMM(legacyLength.millimeters());
            }
        }
        return meta;
    }
    /**
     * 解析没有明确方括号单位的旧版耗材长度字段。
     * 未声明单位的旧格式沿用历史约定按 mm 处理；显式 m/meter 则直接按米处理。
     */
    private static FilamentLengthValue parseLegacyFilamentLength(String content) {
        Matcher matcher = FILAMENT_LENGTH_PATTERN.matcher(content);
        if (!matcher.find()) {
            return null;
        }
        BigDecimal value = new BigDecimal(matcher.group(1));
        String unit = matcher.group(2);
        if (unit == null || !unit.toLowerCase(java.util.Locale.ROOT).startsWith("m")
                || "mm".equalsIgnoreCase(unit) || "millimeter".equalsIgnoreCase(unit)
                || "millimeters".equalsIgnoreCase(unit)) {
            return new FilamentLengthValue(value,
                    value.divide(new BigDecimal("1000"), 2, java.math.RoundingMode.HALF_UP));
        }
        return new FilamentLengthValue(null, value);
    }

    private record FilamentLengthValue(BigDecimal millimeters, BigDecimal meters) {
    }

    /**
     * 从文件名解析元数据（备选方案）
     */
    public static GCodeMeta parseMetadataFromFilename(String filename) {
        GCodeMeta meta = new GCodeMeta();
        if (filename == null || filename.isBlank()) {
            return meta;
        }

        String base = stripExtension(filename.trim());
        if (base.isEmpty()) {
            return meta;
        }

        String timeToken = null;
        String materialToken = null;

        Matcher orcaMatcher = ORCA_FILENAME_META_PATTERN.matcher(base);
        if (orcaMatcher.matches()) {
            materialToken = orcaMatcher.group(2);
            timeToken = orcaMatcher.group(4);
        } else {
            String[] parts = base.split("_");
            if (parts.length >= 4) {
                timeToken = parts[parts.length - 1];
                materialToken = parts[parts.length - 3];
            }
        }

        Integer seconds = tryParseDurationText(timeToken);
        if (seconds != null && seconds > 0) {
            meta.setEstimatedPrintTimeSeconds(seconds);
        }

        String material = normalizeMaterial(materialToken);
        if (material != null && MATERIAL_TOKEN_PATTERN.matcher(material).matches()) {
            meta.setMaterialType(material.toUpperCase());
        }

        return meta;
    }

    /**
     * 解析打印时间（统一转换为秒）
     */
    private static Integer parsePrintTime(String content) {
        // 1. TIME = seconds 格式
        Matcher timeMatcher = TIME_SECONDS_PATTERN.matcher(content);
        if (timeMatcher.find()) {
            try {
                return Integer.parseInt(timeMatcher.group(1));
            } catch (NumberFormatException ignore) {
                log.debug("TIME parse failed: {}", timeMatcher.group(1));
            }
        }

        // 2. estimated_time = seconds 格式 (OrcaSlicer)
        Matcher estTimeMatcher = EST_TIME_PATTERN.matcher(content);
        if (estTimeMatcher.find()) {
            try {
                return Integer.parseInt(estTimeMatcher.group(1));
            } catch (NumberFormatException ignore) {
                log.debug("estimated_time parse failed: {}", estTimeMatcher.group(1));
            }
        }

        // 3. human-readable 格式: "estimated printing time = 14m 5s" 或 "1h 23m"
        Matcher textMatcher = EST_PRINTING_TIME_PATTERN.matcher(content);
        if (!textMatcher.find()) {
            return 0;
        }

        String raw = textMatcher.group(1).trim();
        Integer durationSeconds = tryParseDurationText(raw);
        if (durationSeconds != null) {
            return durationSeconds;
        }

        log.debug("Unrecognized estimated time format: {}", raw);
        return 0;
    }

    /**
     * 解析材料类型
     */
    private static void parseMaterialType(String content, GCodeMeta meta) {
        Matcher matMatcher = MATERIAL_PATTERN.matcher(content);
        if (matMatcher.find()) {
            String normalizedMaterial = normalizeMaterial(matMatcher.group(1));
            if (normalizedMaterial != null && !normalizedMaterial.isEmpty()) {
                meta.setMaterialType(normalizedMaterial.toUpperCase());
            }
        }
    }

    /**
     * 解析数字（BigDecimal）
     */
    private static BigDecimal parseDecimal(String content, Pattern pattern) {
        if (content == null || pattern == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(content);
        if (!matcher.find()) {
            return null;
        }
        try {
            return new BigDecimal(matcher.group(1));
        } catch (Exception e) {
            log.warn("Numeric parse failed: {}", matcher.group(1));
            return null;
        }
    }

    /**
     * 解析整数
     */
    private static Integer parseInteger(String content, Pattern pattern) {
        if (content == null || pattern == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(content);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            log.warn("Integer parse failed: {}", matcher.group(1));
            return null;
        }
    }

    /**
     * 解析时间文本（支持 14m 5s, 1h 23m, 3600 等格式）
     */
    private static Integer tryParseDurationText(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }

        // 直接是数字（秒）
        if (raw.matches("\\d+")) {
            try {
                return Integer.parseInt(raw);
            } catch (NumberFormatException ignore) {
                return null;
            }
        }

        // HH:MM:SS 或 HH:MM 格式
        Matcher hmsMatcher = DURATION_HMS_COLON_PATTERN.matcher(raw);
        if (hmsMatcher.matches()) {
            int part1 = Integer.parseInt(hmsMatcher.group(1));
            int part2 = Integer.parseInt(hmsMatcher.group(2));
            String part3 = hmsMatcher.group(3);
            if (part3 == null) {
                return part1 * 60 + part2;
            }
            return part1 * 3600 + part2 * 60 + Integer.parseInt(part3);
        }

        // 文本格式: 1h 23m 45s / 14m 5s / 1h 30m
        Matcher tokenMatcher = DURATION_TOKEN_PATTERN.matcher(raw);
        int total = 0;
        int found = 0;
        while (tokenMatcher.find()) {
            found++;
            try {
                int num = Integer.parseInt(tokenMatcher.group(1));
                char unit = Character.toLowerCase(tokenMatcher.group(2).charAt(0));
                if (unit == 'd') {
                    total += num * 86400;
                } else if (unit == 'h') {
                    total += num * 3600;
                } else if (unit == 'm') {
                    total += num * 60;
                } else if (unit == 's') {
                    total += num;
                }
            } catch (NumberFormatException ignore) {
                // 忽略解析失败的单个 token
            }
        }
        return found > 0 ? total : null;
    }

    /**
     * 标准化材料类型
     */
    private static String normalizeMaterial(String raw) {
        if (raw == null) {
            return null;
        }

        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }

        value = value.replace("[", "").replace("]", "").trim();
        String[] tokens = value.split("[;,]");
        for (String token : tokens) {
            String item = token == null ? "" : token.trim();
            if ((item.startsWith("\"") && item.endsWith("\""))
                    || (item.startsWith("'") && item.endsWith("'"))) {
                if (item.length() > 1) {
                    item = item.substring(1, item.length() - 1).trim();
                }
            }
            if (!item.isEmpty()) {
                return item;
            }
        }
        return null;
    }

    /**
     * 去除文件扩展名
     */
    private static String stripExtension(String filename) {
        int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        String name = slash >= 0 ? filename.substring(slash + 1) : filename;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * 从 G-code 内容中提取 Base64 编码的缩略图
     *
     * @param content G-code 文件内容
     * @return 缩略图的 Base64 字符串，如果没有找到则返回 null
     */
    public static String extractThumbnailBase64(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }

        StringBuilder base64Builder = new StringBuilder();
        boolean inThumbnail = false;

        String[] lines = content.split("\\r?\\n");

        for (String line : lines) {
            // 检测缩略图开始
            if (THUMBNAIL_START_PATTERN.matcher(line).find()) {
                inThumbnail = true;
                base64Builder.setLength(0);
                log.debug("检测到缩略图开始标记");
                continue;
            }

            // 检测缩略图结束
            if (THUMBNAIL_END_PATTERN.matcher(line).find()) {
                inThumbnail = false;
                log.debug("检测到缩略图结束标记");
                break;
            }

            // 提取 Base64 数据
            if (inThumbnail) {
                Matcher dataMatcher = THUMBNAIL_DATA_PATTERN.matcher(line);
                if (dataMatcher.find()) {
                    base64Builder.append(dataMatcher.group(1));
                }
            }
        }

        String base64Data = base64Builder.toString();
        if (base64Data.isEmpty()) {
            return null;
        }

        log.info("成功从 G-code 提取缩略图，Base64 长度: {} 字符", base64Data.length());
        return base64Data;
    }
}
