package com.example.farm.config;

import com.example.farm.mapper.GCodeMetadataStore;
import com.example.farm.protocol.file.GCodeFileInspection;
import com.example.farm.protocol.file.GCodeFileInspector;
import com.example.farm.protocol.model.GCodeFileMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GCodeMetadataPersistenceTest {
    @TempDir Path directory;
    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;
    private GCodeMetadataStore store;

    @BeforeEach void setup() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:sqlite:" + directory.resolve("metadata.db"));
        dataSource.setMaximumPoolSize(1);
        dataSource.setConnectionInitSql("PRAGMA foreign_keys=ON");
        jdbc = new JdbcTemplate(dataSource);
        new ResourceDatabasePopulator(new ClassPathResource("db/init/sqlite-schema.sql")).execute(dataSource);
        new LocalSchemaMigration(jdbc).createGCodeMetadataTablesIfMissing();
        jdbc.update("INSERT INTO farm_print_file(id,original_name,safe_name) VALUES (1,'model.gcode','safe.gcode')");
        store = new GCodeMetadataStore(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()),
                new DataSourceTransactionManager(dataSource));
    }

    @AfterEach void close() { dataSource.close(); }

    @Test void upgradeAndFreshInitializationHaveSameShapeAndPreserveExistingData() throws Exception {
        var inspection = inspect();
        store.save(new GCodeMetadataStore.Entry("upload:1", null, null, null, null, inspection));
        var before = jdbc.queryForList("PRAGMA table_info(farm_gcode_metadata)");
        new LocalSchemaMigration(jdbc).createGCodeMetadataTablesIfMissing();
        new ResourceDatabasePopulator(new ClassPathResource("db/init/sqlite-schema.sql")).execute(dataSource);
        assertEquals(before, jdbc.queryForList("PRAGMA table_info(farm_gcode_metadata)"));
        assertEquals(inspection.metadata(), store.find("upload:1").inspection().metadata());
    }

    @Test void roundTripsLayerIndexAndScopesDurableReceiptsByGatewayAndBinding() throws Exception {
        jdbc.update("INSERT INTO farm_printer(id,name,device_uid) VALUES (1,'Fixture','device-1')");
        jdbc.update("""
                INSERT INTO farm_printer_execution(printer_id,execution_uid,source,started_at,last_seen_at)
                VALUES (1,'e1','EXTERNAL',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """);
        var inspection = inspect();
        var entry = new GCodeMetadataStore.Entry("execution:e1", 1L, "e1", "device-1", "model.gcode", inspection);
        store.save(entry);
        var restored = store.find(entry.cacheKey());
        assertEquals(entry.inspection().metadata(), restored.inspection().metadata());
        assertEquals(entry.inspection().layers(), restored.inspection().layers());
        assertEquals(1, store.pending("gateway-1", 1).size());
        store.acknowledge("gateway-1", 1, "e1", store.metadataHash(restored));
        assertTrue(store.pending("gateway-1", 1).isEmpty());
        assertEquals(1, store.pending("gateway-1", 2).size());
        assertEquals(1, store.pending("gateway-2", 1).size());
        assertNotNull(restored.inspection().currentLayerAt(inspection.metadata().sizeBytes()));
    }

    @Test void paginationSeparatesActiveWorkAndSkipsRemovedOrReidentifiedDevices() throws Exception {
        jdbc.update("INSERT INTO farm_printer(id,name,device_uid) VALUES (1,'Fixture','device-1')");
        var inspection = inspect();
        for (int index = 0; index < 66; index++) {
            String key = "execution:" + String.format("%03d", index);
            jdbc.update("""
                    INSERT INTO farm_printer_execution(printer_id,execution_uid,source,state,started_at,last_seen_at)
                    VALUES (1,?,'EXTERNAL','COMPLETED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """, "e" + index);
            store.save(new GCodeMetadataStore.Entry(key, 1L, "e" + index, "device-1", "model.gcode", inspection));
        }
        var first = store.pending("gateway", 1, "", false);
        assertEquals(64, first.size());
        assertEquals(2, store.pending("gateway", 1, first.getLast().cacheKey(), false).size());
        assertTrue(store.pending("gateway", 1, "", true).isEmpty());
        jdbc.update("UPDATE farm_printer SET device_uid='replacement' WHERE id=1");
        assertTrue(store.pending("gateway", 1).isEmpty());
    }

    @Test void partialImageFailureRollsBackMetadataAndEarlierImageWrites() throws Exception {
        var inspection = inspect();
        var clean = new GCodeMetadataStore.Entry("upload:1", null, null, null, null, inspection);
        store.save(clean);
        var descriptor = new GCodeFileMetadata.Thumbnail("a".repeat(64), "image/png", 1, 1, 1);
        var original = inspection.metadata();
        var invalid = new GCodeFileMetadata(original.schemaVersion(), "replacement",
                original.fileName(), original.sizeBytes(), original.sha256(), original.source(), original.parsedAt(),
                original.slicerName(), original.slicerVersion(), original.estimatedPrintSeconds(), original.totalLayers(),
                original.layerHeightMm(), original.firstLayerHeightMm(), original.nozzleDiameterMm(), original.objectHeightMm(),
                original.nozzleTemperatureC(), original.firstLayerNozzleTemperatureC(), original.bedTemperatureC(),
                original.firstLayerBedTemperatureC(), original.filaments(), List.of(descriptor, descriptor));
        var image = new GCodeFileInspection.ThumbnailImage(descriptor, new byte[]{1});
        var broken = new GCodeFileInspection(invalid, List.of(image, image), inspection.layers());
        assertThrows(RuntimeException.class, () -> store.save(
                new GCodeMetadataStore.Entry("upload:1", null, null, null, null, broken)));
        assertEquals(original, store.find("upload:1").inspection().metadata());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM farm_gcode_thumbnail", Integer.class));
    }

    @Test void deletingAnUploadedFileRemovesItsPrivateInspectionCache() throws Exception {
        store.save(new GCodeMetadataStore.Entry("upload:1", null, null, null, null, inspect()));
        jdbc.update("DELETE FROM farm_print_file WHERE id=1");
        store.save(new GCodeMetadataStore.Entry("upload:1", null, null, null, null, inspect()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM farm_gcode_metadata", Integer.class));
    }

    private GCodeFileInspection inspect() throws Exception {
        byte[] bytes = "; generated by OrcaSlicer 2.3.0\n; total layer number: 2\n;CHANGE_LAYER\nG1 Z0.2\n;CHANGE_LAYER\nG1 Z0.4\n"
                .getBytes(StandardCharsets.UTF_8);
        return new GCodeFileInspector().inspect(new ByteArrayInputStream(bytes), "model.gcode", bytes.length, "SERVER_UPLOAD");
    }
}
