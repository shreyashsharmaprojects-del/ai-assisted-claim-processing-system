package com.claims.claim;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import com.claims.api.FnolValidationException;

/**
 * Claimant evidence photos: files on disk (configurable upload dir), paths in the DB
 * (see docs/decisions.md — photo storage decision).
 *
 * <p>All photos are validated before any file is written; if a later write fails, files
 * already written by this call are removed so a failed submission leaves no orphans.
 */
@Component
public class PhotoStorage {

    private static final Logger log = LoggerFactory.getLogger(PhotoStorage.class);

    private final Path uploadDir;
    private final long maxSizeBytes;
    private final int maxCount;

    public PhotoStorage(@Value("${claims.uploads.dir}") String uploadDir,
            @Value("${claims.uploads.max-size-bytes}") long maxSizeBytes,
            @Value("${claims.uploads.max-count}") int maxCount) {
        this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
        this.maxSizeBytes = maxSizeBytes;
        this.maxCount = maxCount;
    }

    /** Validates every photo first, then stores them all; returns ready attachment data. */
    public List<StoredPhoto> store(List<MultipartFile> photos, Long claimId) {
        if (photos == null || photos.isEmpty()) {
            return List.of();
        }
        if (photos.size() > maxCount) {
            throw new FnolValidationException("At most " + maxCount + " photos may be attached.");
        }
        // Pass 1: validate everything before touching the disk.
        for (MultipartFile photo : photos) {
            validate(photo);
        }
        // Pass 2: write; remove anything already written if a later write fails.
        Path claimDir = uploadDir.resolve(claimId.toString());
        List<StoredPhoto> stored = new ArrayList<>();
        try {
            for (MultipartFile photo : photos) {
                String originalName = sanitize(photo.getOriginalFilename());
                String storageName = UUID.randomUUID() + extensionOf(originalName);
                Path target = claimDir.resolve(storageName);
                Files.createDirectories(claimDir);
                try (var in = photo.getInputStream()) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                stored.add(new StoredPhoto(target.toString(), photo.getContentType(), originalName));
            }
            return stored;
        } catch (IOException ex) {
            deleteClaimDir(claimId);
            throw new IllegalStateException("Could not store uploaded photo", ex);
        }
    }

    /** Removes a claim's upload directory (used when the surrounding transaction rolls back). */
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

    private void validate(MultipartFile photo) {
        String contentType = photo.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new FnolValidationException("Attachments must be image files.");
        }
        if (photo.getSize() > maxSizeBytes) {
            throw new FnolValidationException(
                    "Photos must be smaller than " + (maxSizeBytes / 1024 / 1024) + " MB.");
        }
    }

    private static String sanitize(String filename) {
        if (filename == null) {
            return "photo";
        }
        String base = Path.of(filename).getFileName().toString();
        return base.isBlank() ? "photo" : base;
    }

    private static String extensionOf(String filename) {
        int dot = filename == null ? -1 : filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot).toLowerCase() : ".img";
    }
}
