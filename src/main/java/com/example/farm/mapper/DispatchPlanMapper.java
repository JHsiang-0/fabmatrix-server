package com.example.farm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.farm.entity.DispatchPlan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface DispatchPlanMapper extends BaseMapper<DispatchPlan> {

    /**
     * 仅允许一个请求把预览计划原子认领为执行中，避免重复确认跨进程重复创建任务。
     */
    int claimForExecution(@Param("planId") String planId,
                          @Param("confirmedAt") LocalDateTime confirmedAt);
}
