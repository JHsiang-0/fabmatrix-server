package com.example.farm.mapper;

import com.example.farm.protocol.file.GCodeFileInspection;
import com.example.farm.protocol.model.GCodeFileMetadata;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Persistent, execution-scoped file inspection cache. It never stores device credentials. */
@Repository
@Profile("local")
public class GCodeMetadataStore {
    public static final int CURRENT_READER_REVISION = 1;
    private static final TypeReference<List<GCodeFileInspection.LayerMark>> LAYERS = new TypeReference<>() { };
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    public GCodeMetadataStore(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.json = json;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public record Entry(String cacheKey, Long printerId, String executionUid, String deviceUid,
                        String deviceFilename, GCodeFileInspection inspection, int readerRevision) {
        public Entry(String cacheKey, Long printerId, String executionUid, String deviceUid,
                     String deviceFilename, GCodeFileInspection inspection) {
            this(cacheKey, printerId, executionUid, deviceUid, deviceFilename, inspection, CURRENT_READER_REVISION);
        }
        @Override public String toString() { return "GCodeMetadataEntry[printerId=" + printerId + "]"; }
    }

    public Entry find(String cacheKey) {
        var rows = jdbc.query("""
                SELECT cache_key, printer_id, execution_uid, device_uid, device_filename, metadata_json, layers_json, reader_revision
                FROM farm_gcode_metadata WHERE cache_key = ?
                """, (result, row) -> new Entry(result.getString(1), result.getObject(2) == null ? null : result.getLong(2), result.getString(3),
                result.getString(4), result.getString(5), read(result.getString(6), result.getString(7)), result.getInt(8)), cacheKey);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void save(Entry entry) {
        var inspection = entry.inspection();
        String metadata = write(inspection.metadata());
        String layers = write(inspection.layers());
        if (metadata.length() > 32768 || layers.length() > 8 * 1024 * 1024 || inspection.thumbnails().size() > 2
                || inspection.thumbnails().size() != inspection.metadata().thumbnails().size()) {
            throw new IllegalArgumentException("File inspection exceeds cache limits");
        }
        transactions.executeWithoutResult(status -> {
            if (entry.cacheKey().startsWith("upload:") && jdbc.queryForObject(
                    "SELECT COUNT(*) FROM farm_print_file WHERE id = ?", Integer.class,
                    Long.parseLong(entry.cacheKey().substring("upload:".length()))) == 0) return;
            jdbc.update("""
                    INSERT INTO farm_gcode_metadata
                        (cache_key, printer_id, execution_uid, device_uid, device_filename, metadata_json,
                         metadata_sha256, layers_json, created_at, reader_revision)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(cache_key) DO UPDATE SET
                        printer_id=excluded.printer_id, execution_uid=excluded.execution_uid,
                        device_uid=excluded.device_uid, device_filename=excluded.device_filename,
                        metadata_json=excluded.metadata_json, metadata_sha256=excluded.metadata_sha256,
                        layers_json=excluded.layers_json, reader_revision=excluded.reader_revision
                    """, entry.cacheKey(), entry.printerId(), entry.executionUid(), entry.deviceUid(),
                    entry.deviceFilename(), metadata, digest(metadata.getBytes(StandardCharsets.UTF_8)),
                    layers, Instant.now().toEpochMilli(), entry.readerRevision());
            jdbc.update("DELETE FROM farm_gcode_thumbnail WHERE cache_key = ?", entry.cacheKey());
            for (var image : inspection.thumbnails()) {
                if (image.png().length > 512 * 1024 || !inspection.metadata().thumbnails().contains(image.descriptor())) {
                    throw new IllegalArgumentException("Invalid cached thumbnail");
                }
                jdbc.update("INSERT INTO farm_gcode_thumbnail(cache_key, sha256, image_bytes) VALUES (?, ?, ?)",
                        entry.cacheKey(), image.descriptor().sha256(), image.png());
            }
        });
    }

    public List<GCodeFileInspection.ThumbnailImage> images(Entry entry) {
        return entry.inspection().metadata().thumbnails().stream().map(descriptor -> {
            var bytes = jdbc.query("SELECT image_bytes FROM farm_gcode_thumbnail WHERE cache_key = ? AND sha256 = ?",
                    (result, row) -> result.getBytes(1), entry.cacheKey(), descriptor.sha256());
            return bytes.isEmpty() ? null : new GCodeFileInspection.ThumbnailImage(descriptor, bytes.getFirst());
        }).filter(Objects::nonNull).toList();
    }

    public List<Entry> pending(String gatewayId, long bindingVersion) {
        return pending(gatewayId, bindingVersion, "", null);
    }

    public List<Entry> pending(String gatewayId, long bindingVersion, String afterKey, Boolean activeOnly) {
        String phase = activeOnly == null ? "" : activeOnly ? " AND e.state = 'ACTIVE'" : " AND e.state <> 'ACTIVE'";
        return jdbc.query("""
                SELECT m.cache_key, m.printer_id, m.execution_uid, m.device_uid, m.device_filename,
                       m.metadata_json, m.reader_revision
                FROM farm_gcode_metadata m
                JOIN farm_printer p ON p.id = m.printer_id AND p.device_uid = m.device_uid
                JOIN farm_printer_execution e ON e.printer_id = m.printer_id AND e.execution_uid = m.execution_uid
                WHERE m.execution_uid IS NOT NULL AND m.cache_key > ? AND NOT EXISTS (
                    SELECT 1 FROM farm_gcode_cloud_receipt r
                    WHERE r.gateway_id = ? AND r.binding_version = ?
                      AND r.execution_uid = m.execution_uid AND r.metadata_sha256 = m.metadata_sha256
                )
                """ + phase + " ORDER BY m.cache_key LIMIT 64", (result, row) -> new Entry(result.getString(1),
                result.getLong(2), result.getString(3), result.getString(4), result.getString(5),
                read(result.getString(6), "[]"), result.getInt(7)), afterKey, gatewayId, bindingVersion);
    }

    public String metadataHash(Entry entry) {
        return digest(write(entry.inspection().metadata()).getBytes(StandardCharsets.UTF_8));
    }

    public static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }

    public void acknowledge(String gatewayId, long bindingVersion, String executionUid, String metadataHash) {
        jdbc.update("""
                INSERT INTO farm_gcode_cloud_receipt
                    (gateway_id, binding_version, execution_uid, metadata_sha256, uploaded_at) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(gateway_id, binding_version, execution_uid) DO UPDATE SET
                    metadata_sha256=excluded.metadata_sha256, uploaded_at=excluded.uploaded_at
                """, gatewayId, bindingVersion, executionUid, metadataHash, Instant.now().toEpochMilli());
    }

    private GCodeFileInspection read(String metadata, String layers) {
        try {
            return new GCodeFileInspection(json.readValue(metadata, GCodeFileMetadata.class), List.of(), json.readValue(layers, LAYERS));
        } catch (Exception invalid) {
            throw new IllegalStateException("Stored file metadata is invalid", invalid);
        }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception invalid) { throw new IllegalArgumentException("File metadata cannot be serialized", invalid); }
    }
}
