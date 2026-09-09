package com.claims.claim;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * S2 one-shot backfill: local files → S3 under the same keys. Disabled by
 * default ({@code claims.storage.backfill=false}); run once with {@code =true}.
 * Iterates every attachment row with a portable key, PUTs each local file to
 * the S3 backend, verifies the sha pin when present, and logs missing-file
 * orphans. Never deletes local files — the operator removes them after the
 * orphan check (see docs/operations.md).
 *
 * <p>The local base dir comes from {@code claims.uploads.dir} (no filesystem
 * bean needed — the active {@code photoStorage} is the S3 one when this runs).
 */
@Component
@ConditionalOnProperty(name = "claims.storage.backfill", havingValue = "true")
public class S3BackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(S3BackfillRunner.class);

    private final AttachmentRepository attachments;
    private final ObjectProvider<S3PhotoStorage> s3Provider;
    private final Path uploadDir;

    public S3BackfillRunner(AttachmentRepository attachments,
            ObjectProvider<S3PhotoStorage> s3Provider,
            @Value("${claims.uploads.dir}") String uploadDir) {
        this.attachments = attachments;
        this.s3Provider = s3Provider;
        this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    @Override
    public void run(ApplicationArguments args) {
        S3PhotoStorage s3 = s3Provider.getIfAvailable();
        if (s3 == null) {
            log.warn("S3 backfill enabled but no S3PhotoStorage bean is active "
                    + "(claims.storage.backend is not s3) — nothing to backfill.");
            return;
        }
        List<Attachment> rows = attachments.findAll();
        int uploaded = 0;
        int verified = 0;
        int orphans = 0;
        int skipped = 0;
        for (Attachment row : rows) {
            String key = row.getStoragePath();
            if (key == null || key.startsWith("/")) {
                // Legacy absolute-path rows are a filesystem concern, not S3 keys.
                skipped++;
                log.warn("Backfill skipping non-portable storage_path for attachment {}: {}",
                        row.getId(), key);
                continue;
            }
            Path local = uploadDir.resolve(key).normalize();
            if (!Files.isRegularFile(local)) {
                orphans++;
                log.warn("Backfill orphan: attachment {} (claim {}) has no local file: {}",
                        row.getId(), row.getClaimId(), key);
                continue;
            }
            try {
                byte[] bytes = Files.readAllBytes(local);
                if (s3.exists(key)) {
                    if (row.getSha256() == null) {
                        verified++;
                        log.info("Backfill already present, verified attachment {}: {}",
                                row.getId(), key);
                        continue;
                    }
                    byte[] remote = s3.load(key);
                    if (row.getSha256().equalsIgnoreCase(PhotoValidator.sha256Hex(remote))) {
                        verified++;
                        log.info("Backfill already present, verified attachment {}: {}",
                                row.getId(), key);
                        continue;
                    }
                    log.warn("Backfill sha mismatch on existing key, re-uploading: {}", key);
                }
                s3.putForBackfill(key, bytes, contentTypeOf(row));
                uploaded++;
                if (row.getSha256() != null
                        && !row.getSha256().equalsIgnoreCase(PhotoValidator.sha256Hex(bytes))) {
                    log.error("Backfill sha verification FAILED for attachment {}: {}",
                            row.getId(), key);
                } else {
                    verified++;
                    log.info("Backfill uploaded attachment {}: {}", row.getId(), key);
                }
            } catch (Exception ex) {
                log.error("Backfill failed for attachment {} ({}): {}",
                        row.getId(), key, ex.toString());
            }
        }
        log.info("S3 backfill complete: rows={} uploaded={} verified={} orphans={} skipped={}",
                rows.size(), uploaded, verified, orphans, skipped);
    }

    private static String contentTypeOf(Attachment row) {
        return row.getContentType() == null ? "application/octet-stream" : row.getContentType();
    }
}
