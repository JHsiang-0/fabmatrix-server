package com.example.farm.common.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PageResultTest {

    @Test
    void convertsMybatisPageToFrontendContract() {
        Page<String> page = new Page<>(2, 20);
        page.setTotal(45);
        page.setRecords(List.of("item-21"));

        PageResult<String> result = PageResult.from(page);

        assertThat(result.getRecords()).containsExactly("item-21");
        assertThat(result.getTotal()).isEqualTo(45);
        assertThat(result.getPageNum()).isEqualTo(2);
        assertThat(result.getPageSize()).isEqualTo(20);
        assertThat(result.getPages()).isEqualTo(3);
    }
}
