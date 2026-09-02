package com.example.farm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.farm.entity.PrintJob;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * 打印任务 Mapper 接口
 * </p>
 *
 * @author codexiang
 * @since 2026-03-01
 */
@Mapper
public interface PrintJobMapper extends BaseMapper<PrintJob> {

}
