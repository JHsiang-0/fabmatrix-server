package com.example.farm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.farm.entity.DashboardLayout;
import com.example.farm.entity.vo.DashboardLayoutVO;
import com.example.farm.entity.dto.DashboardLayoutSaveRequest;
import com.example.farm.entity.dto.PrinterPositionUpdateDTO;
import com.example.farm.entity.vo.DashboardLayoutSaveResultVO;

/** 动态看板布局读取与后续原子保存服务。 */
public interface DashboardLayoutService extends IService<DashboardLayout> {

    /** 读取唯一布局；缺失时在当前事务内创建规范默认布局。 */
    DashboardLayoutVO getLayout();

    /** 以版本和最终坐标态为边界原子保存布局。 */
    DashboardLayoutSaveResultVO saveLayout(DashboardLayoutSaveRequest request);

    /** 将旧位置数组转换为当前布局保存请求，保留旧接口的事务和响应兼容性。 */
    DashboardLayoutSaveResultVO saveLegacyPositions(java.util.List<PrinterPositionUpdateDTO> positionUpdates);
}
