package com.claims.claim;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * The filesystem {@link PhotoStorage}: objects live under the configured upload dir as
 * {@code {claimId}/{uuid}{ext}} files, and the DB stores the relative object key (NOT an
 * absolute path) so the base dir can move between environments — and so an S3
 * implementation can adopt the same keys later.
 */
@Component("photoStorage")
@ConditionalOnProperty(name = "claims.storage.backend", havingValue = "filesystem",
        matchIfMissing = true)
public class FilesystemPhotoStorage implements PhotoStorage {

    private static final Logger log = LoggerFactory.getLogger(FilesystemPhotoStorage.class);

    private final Path uploadDir;
    private final long maxSizeBytes;
    private final int maxCount;

    public FilesystemPhotoStorage(@Value("${claims.uploads.dir}") String uploadDir,
            @Value("${claims.uploads.max-size-bytes}") long maxSizeBytes,
            @Value("${claims.uploads.max-count}") int maxCount) {
        this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
        this.maxSizeBytes = maxSizeBytes;
        this.maxCount = maxCount;
    }

    /** Validates every photo first, then stores them all; returns ready attachment data. */
    @Override
    public List<StoredPhoto> store(List<MultipartFile> photos, Long claimId) {
        if (photos == null || photos.isEmpty()) {
            return List.of();
        }
        // Pass 1: validate everything before touching the disk (shared S1 rules).
        PhotoValidator.validateAll(photos, maxSizeBytes, maxCount);
        // Pass 2: write; remove anything already written if a later write fails.
        Path claimDir = uploadDir.resolve(claimId.toString());
        List<StoredPhoto> stored = new ArrayList<>();
        try {
            for (MultipartFile photo : photos) {
                String originalName = PhotoValidator.sanitize(photo.getOriginalFilename());
                String objectKey = PhotoValidator.objectKey(claimId, photo.getOriginalFilename());
                Path target = uploadDir.resolve(objectKey);
                Files.createDirectories(claimDir);
                byte[] bytes;
                try (var in = photo.getInputStream()) {
                    bytes = in.readAllBytes();
                }
                Files.write(target, bytes);
                stored.add(new StoredPhoto(objectKey, photo.getContentType(), originalName,
                        PhotoValidator.sha256Hex(bytes), (long) bytes.length));
            }
            return stored;
        } catch (IOException ex) {
            deleteClaimDir(claimId);
            throw new IllegalStateException("Could not store uploaded photo", ex);
        }
    }

    /** Resolves an object key under the upload dir (legacy absolute paths pass through). */
    @Override
    public byte[] load(String storageKeyOrPath) {
        Path file = resolve(storageKeyOrPath);
        try {
            return Files.readAllBytes(file);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read attachment " + storageKeyOrPath, ex);
        }
    }

    /** True when the file for this key-or-path exists on disk. */
    public boolean exists(String storageKeyOrPath) {
        return Files.isRegularFile(resolve(storageKeyOrPath));
    }

    /** Removes a claim's upload directory (used when the surrounding transaction rolls back). */
    @Override
    public void deleteClaimDir(Long claimId) {
        Path claimDir = uploadDir.resolve(claimId.toString());
        if (!Files.exists(claimDir)) {
            return;
        }
        try (var paths = Files.walk(claimDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    log.warn("Could not delete upload path {} during rollback", path, ex);
                }
            });
        } catch (IOException ex) {
            log.warn("Could not walk upload dir {} during rollback", claimDir, ex);
        }
    }

    /**
     * Object keys resolve under the upload dir; legacy absolute-path rows (pre-V11
     * migration) resolve as-is, so a half-migrated database still serves downloads.
     */
    Path resolve(String storageKeyOrPath) {
        Path candidate = Path.of(storageKeyOrPath);
        if (candidate.isAbsolute()) {
            return candidate.normalize();
        }
        return uploadDir.resolve(candidate).normalize();
    }

    /**
     * S1 magic-byte gate (kept as a delegate so the S1 unit matrix still resolves
     * against this class): the declared content type is not trusted — the head
     * bytes must look like PNG, JPEG, GIF, WEBP, or PDF.
     */
    static boolean magicMatches(byte[] head) {
        return PhotoValidator.magicMatches(head);
    }

    /** SHA-256 delegate (ClaimWorkService pins downloads through this helper). */
    static String sha256Hex(byte[] bytes) {
        return PhotoValidator.sha256Hex(bytes);
    }
}
