package com.example.farm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.farm.entity.AuditLog;
import com.example.farm.entity.dto.AuditLogQueryDTO;
import com.example.farm.entity.vo.AuditLogVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 操作日志 Mapper。 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {

    Page<AuditLogVO> selectAuditLogPage(Page<AuditLogVO> page,
                                        @Param("query") AuditLogQueryDTO query);
}
