package com.example.farm.local;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.example.farm.common.utils.InMemoryCache;
import com.example.farm.common.utils.PrinterLock;
import com.example.farm.mapper.PrintJobMapper;
import com.example.farm.mapper.AuditLogMapper;
import com.example.farm.mapper.PrinterExecutionMapper;
import com.example.farm.mapper.PrinterMapper;
import com.example.farm.service.impl.AuditLogServiceImpl;
import com.example.farm.service.impl.PrinterServiceImpl;
import com.example.farm.common.utils.MacAddressUtil;
import com.example.farm.protocol.PrinterProtocolDetector;
import com.example.farm.protocol.RrfApiClient;
import com.example.farm.service.PrinterBatchDeletionService;
import com.example.farm.service.PrinterCacheService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PrinterBatchDeletionPersistenceTest {
    @TempDir Path temp;

    @Test
    void deletesOfflineDevicesKeepsHistoryAndProtectsTasksAndAudit() throws Exception {
        try (var dataSource = new HikariDataSource()) {
            dataSource.setJdbcUrl("jdbc:sqlite:" + temp.resolve("farm.db"));
            dataSource.setDriverClassName("org.sqlite.JDBC");
            dataSource.setMaximumPoolSize(1);
            new ResourceDatabasePopulator(new ClassPathResource("db/init/sqlite-schema.sql")).execute(dataSource);
            var jdbc = new JdbcTemplate(dataSource);
            var configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(PrinterMapper.class);
            configuration.addMapper(PrintJobMapper.class);
            configuration.addMapper(PrinterExecutionMapper.class);
            configuration.addMapper(AuditLogMapper.class);
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            var session = new SqlSessionTemplate(factory.getObject());
            var audit = new AuditLogServiceImpl(session.getMapper(AuditLogMapper.class));
            jdbc.execute("CREATE TRIGGER reject_audit BEFORE INSERT ON farm_audit_log "
                    + "WHEN NEW.target_id = '5' AND NEW.result = 'SUCCESS' "
                    + "BEGIN SELECT RAISE(ABORT, 'simulated audit failure'); END");
            var release = new CountDownLatch(1);
            var entered = new CountDownLatch(1);
            var cache = mock(PrinterCacheService.class);
            doAnswer(invocation -> { release.countDown(); return null; }).when(cache).clearStatusCache(1L);
            var service = new PrinterBatchDeletionService(session.getMapper(PrinterMapper.class),
                    session.getMapper(PrintJobMapper.class), session.getMapper(PrinterExecutionMapper.class),
                    cache, audit, new InMemoryCache(), new DataSourceTransactionManager(dataSource));
            String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            for (int id = 1; id <= 9; id++) {
                jdbc.update("INSERT INTO farm_printer(id,name,ip_address,status,monitor_enabled,connection_status,last_seen_at,consecutive_failure_count) VALUES(?,?,?,'IDLE',1,'ONLINE',?,0)",
                        id, "Printer " + id, "192.0.2." + id, now);
            }
            jdbc.update("UPDATE farm_printer SET status='PAUSED' WHERE id=2");
            jdbc.update("UPDATE farm_printer SET connection_status='OFFLINE' WHERE id=6");
            jdbc.update("UPDATE farm_printer SET monitor_enabled=0, status='UNKNOWN', last_seen_at=NULL WHERE id=7");
            jdbc.update("UPDATE farm_printer SET status='PRINTING' WHERE id=8");
            jdbc.update("UPDATE farm_printer SET connection_status='OFFLINE' WHERE id=9");
            jdbc.update("INSERT INTO farm_print_job(id,user_id,printer_id,status,printer_name_snapshot) VALUES(10,1,1,'COMPLETED','historical')");
            jdbc.update("INSERT INTO farm_print_job(id,user_id,printer_id,status) VALUES(11,1,3,'ASSIGNED')");
            jdbc.update("INSERT INTO farm_printer_execution(printer_id,source,state,started_at,last_seen_at) VALUES(4,'EXTERNAL','ACTIVE',?,?)", now, now);

            var executor = Executors.newSingleThreadExecutor();
            var holder = executor.submit(() -> PrinterLock.execute(6L, null, () -> {
                entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return null;
            }));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var result = service.delete(List.of(6L, 1L, 2L, 3L, 4L, 5L, 7L, 8L));
            holder.get(5, TimeUnit.SECONDS);
            executor.shutdownNow();
            var singleService = new PrinterServiceImpl(mock(PrinterCacheService.class), mock(MacAddressUtil.class),
                    mock(PrinterProtocolDetector.class), mock(RrfApiClient.class));
            ReflectionTestUtils.setField(singleService, "printerBatchDeletionService", service);
            singleService.deletePrinter(9L);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> singleService.deletePrinter(8L))
                    .isInstanceOf(com.example.farm.common.exception.BusinessException.class);

            assertThat(result.deletedCount()).as("results: %s", result.items()).isEqualTo(3);
            assertThat(result.failedCount()).isEqualTo(5);
            assertThat(result.items().getFirst().id()).isEqualTo(6L);
            assertThat(result.items().getFirst().success()).isTrue();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM farm_printer WHERE id=1", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM farm_printer WHERE id IN (2,3,4,5,8)", Integer.class)).isEqualTo(5);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM farm_printer WHERE id IN (6,7)", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM farm_print_job WHERE id=10", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM farm_printer WHERE id=9", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM farm_audit_log WHERE result='SUCCESS'", Integer.class)).isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM farm_audit_log WHERE result='FAILURE'", Integer.class)).isEqualTo(6);

            var held = new CountDownLatch(1);
            var finish = new CountDownLatch(1);
            var secondExecutor = Executors.newSingleThreadExecutor();
            try {
                var busyHolder = secondExecutor.submit(() -> PrinterLock.execute(8L, null, () -> {
                    held.countDown();
                    try { finish.await(20, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return null;
                }));
                assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
                var busyResult = service.delete(List.of(8L));
                assertThat(busyResult.failedCount()).isEqualTo(1);
                assertThat(busyResult.items().getFirst().message()).contains("繁忙");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM farm_audit_log WHERE result='FAILURE'", Integer.class)).isEqualTo(7);
                finish.countDown();
                busyHolder.get(5, TimeUnit.SECONDS);
            } finally {
                finish.countDown();
                secondExecutor.shutdownNow();
            }
        }
    }
}
