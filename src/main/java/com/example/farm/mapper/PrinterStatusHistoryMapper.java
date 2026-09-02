package com.example.farm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.farm.entity.PrinterStatusHistory;
import org.apache.ibatis.annotations.Mapper;

/**
 * 打印机状态历史 Mapper。
 */
@Mapper
public interface PrinterStatusHistoryMapper extends BaseMapper<PrinterStatusHistory> {
}
