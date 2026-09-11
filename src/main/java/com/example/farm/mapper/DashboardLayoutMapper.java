package com.example.farm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.farm.entity.DashboardLayout;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** 动态看板布局单例 Mapper。 */
@Mapper
public interface DashboardLayoutMapper extends BaseMapper<DashboardLayout> {

    /** 锁定布局单例；SQLite 分支使用普通读取（单连接本地事务已提供串行化）。 */
    DashboardLayout selectForUpdate(Long id);

    /** 锁定布局保存期间的全部设备坐标，避免并发旧位置穿透校验。 */
    List<com.example.farm.entity.Printer> selectPrintersForUpdate();
}
