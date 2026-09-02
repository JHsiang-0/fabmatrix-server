package com.example.farm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.farm.entity.PrintFile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * <p>
 * 打印文件表 Mapper 接口
 * </p>
 *
 * @author codexiang
 * @since 2026-03-01
 */
@Mapper
public interface PrintFileMapper extends BaseMapper<PrintFile> {

    /**
     * 按当前用户和筛选条件分页查询文件。
     */
    Page<PrintFile> selectFilePage(Page<PrintFile> page,
                                    @Param("userId") Long userId,
                                    @Param("admin") boolean admin,
                                    @Param("filterUserId") Long filterUserId,
                                    @Param("fileName") String fileName,
                                    @Param("materialType") String materialType);

    /**
     * 统计指定文件的打印任务数量
     *
     * @param fileId  文件 ID
     * @param userId  用户 ID（用于权限校验）
     * @param status  任务状态筛选（null 表示统计所有状态）
     * @return 打印任务数量
     */
    Integer countPrintJobsByFileId(@Param("fileId") Long fileId,
                                    @Param("userId") Long userId,
                                    @Param("status") String status);
}
