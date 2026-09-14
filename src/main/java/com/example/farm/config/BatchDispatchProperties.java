package com.example.farm.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** V1 批量派发功能开关；V3 验收完成前必须保持关闭。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "farm.features.batch-dispatch")
public class BatchDispatchProperties {

    /** 默认关闭；仅通过显式配置开启。 */
    private boolean enabled = false;
}
