package com.example.farm.task;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.farm.config.PrinterMonitorProperties;
import com.example.farm.protocol.FailureCategory;
import com.example.farm.protocol.PrinterOperation;
import com.example.farm.protocol.PrinterProtocolException;
import com.example.farm.protocol.PrinterProtocolType;
import com.example.farm.protocol.PrinterProtocolAdapterFactory;
import com.example.farm.service.PrinterService;
import com.example.farm.service.PrinterCacheService;
import com.example.farm.service.PrintJobService;
import com.example.farm.service.WebSocketEventPublisher;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PrinterMonitorFailureLogTest {
    @Test
    void expectedTimeoutProducesOneSafeLineAndUnexpectedFailureRetainsStack() {
        var task = new PrinterMonitorTask(mock(PrinterService.class), mock(PrinterCacheService.class),
                mock(PrinterProtocolAdapterFactory.class), mock(PrintJobService.class),
                mock(WebSocketEventPublisher.class), new PrinterMonitorProperties());
        Logger logger = (Logger) LoggerFactory.getLogger(PrinterMonitorTask.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var timeout = new PrinterProtocolException(PrinterOperation.GET_STATUS, PrinterProtocolType.RRF,
                    FailureCategory.TIMEOUT, "private device response", new RuntimeException("secret"));
            ReflectionTestUtils.invokeMethod(task, "logOfflineQueryFailure", 563L, "printer", timeout);
            ReflectionTestUtils.invokeMethod(task, "logOfflineQueryFailure", 563L, "printer", timeout);
            var warnings = appender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
            assertThat(warnings).hasSize(1);
            assertThat(warnings.getFirst().getFormattedMessage()).contains("printerId=563", "RRF", "设备响应超时")
                    .doesNotContain("secret", "private device response");
            assertThat(warnings.getFirst().getThrowableProxy()).isNull();
            ReflectionTestUtils.invokeMethod(task, "logOfflineQueryFailure", 564L, "printer", new IllegalStateException("bug"));
            assertThat(appender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList().getLast()
                    .getThrowableProxy()).isNotNull();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            task.destroy();
        }
    }
}
