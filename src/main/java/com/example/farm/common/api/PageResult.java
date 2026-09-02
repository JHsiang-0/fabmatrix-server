package com.example.farm.common.api;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * 对外统一分页返回结构。
 *
 * <p>Service 层可以继续使用 MyBatis-Plus 的 IPage，Controller 对外统一转换为本对象，
 * 避免前端依赖 MyBatis-Plus 的 current/size 字段。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    private List<T> records = Collections.emptyList();
    private long total;
    private long pageNum;
    private long pageSize;
    private long pages;

    public static <T> PageResult<T> from(IPage<T> page) {
        if (page == null) {
            return new PageResult<>();
        }

        return new PageResult<>(
                page.getRecords() == null ? Collections.emptyList() : page.getRecords(),
                page.getTotal(),
                page.getCurrent(),
                page.getSize(),
                page.getPages()
        );
    }

    public static <S, T> PageResult<T> from(IPage<S> page, Function<S, T> mapper) {
        if (page == null) {
            return new PageResult<>();
        }
        List<T> records = page.getRecords() == null
                ? Collections.emptyList()
                : page.getRecords().stream().map(mapper).toList();
        return new PageResult<>(records, page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }
}
