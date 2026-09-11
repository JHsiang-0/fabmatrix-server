package com.example.farm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.farm.entity.DashboardLayout;
import com.example.farm.entity.vo.DashboardLayoutVO;

/** 动态看板布局读取与后续原子保存服务。 */
public interface DashboardLayoutService extends IService<DashboardLayout> {

    /** 读取唯一布局；缺失时在当前事务内创建规范默认布局。 */
    DashboardLayoutVO getLayout();
}
