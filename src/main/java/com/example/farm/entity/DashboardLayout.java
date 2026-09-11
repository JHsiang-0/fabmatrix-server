package com.example.farm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 农场级共享动态看板布局的持久化实体。 */
@Data
@TableName("farm_dashboard_layout")
@Schema(name = "DashboardLayout", description = "农场级共享动态看板布局内部实体")
public class DashboardLayout {

    /** 当前版本固定使用单例主键 1。 */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    @TableField("row_count")
    private Integer rowCount;

    @TableField("column_count")
    private Integer columnCount;

    /** 过道数组的规范 JSON；对外响应由服务层解析，不直接暴露。 */
    @TableField("aisles_json")
    private String aislesJson;

    private Long version;

    @TableField("updated_by")
    private Long updatedBy;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
