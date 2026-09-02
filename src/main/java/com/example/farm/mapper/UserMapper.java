package com.example.farm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.farm.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * 农场用户表 Mapper 接口
 * </p>
 *
 * @author codexiang
 * @since 2026-03-01
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

}
