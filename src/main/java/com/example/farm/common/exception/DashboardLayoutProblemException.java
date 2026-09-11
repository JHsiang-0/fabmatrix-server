package com.example.farm.common.exception;

import com.example.farm.entity.vo.DashboardLayoutProblemVO;
import lombok.Getter;

/** 带安全结构化详情的布局保存业务异常。 */
@Getter
public class DashboardLayoutProblemException extends BusinessException {
    private final DashboardLayoutProblemVO problem;

    public DashboardLayoutProblemException(long code, String message, DashboardLayoutProblemVO problem) {
        super(code, message);
        this.problem = problem;
    }
}
