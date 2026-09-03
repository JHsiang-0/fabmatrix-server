package com.example.farm.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 批量预览冲突。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DispatchConflictVO {
    private String itemId;
    private Long fileId;
    private Long printerId;
    private String reasonCode;
    private String message;
}
