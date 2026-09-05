package com.example.farm.common.api;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(name = "PageResult", description = "REST 统一分页返回结构")
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    @Schema(description = "当前页数据")
    private List<T> records = Collections.emptyList();
    @Schema(description = "符合条件的总记录数")
    private long total;
    @Schema(description = "当前页码，从 1 开始")
    private long pageNum;
    @Schema(description = "每页数量")
    private long pageSize;
    @Schema(description = "总页数")
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
