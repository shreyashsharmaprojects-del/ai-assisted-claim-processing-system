package com.claims.claim;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
 * The filesystem {@link PhotoStorage}: objects live under the configured upload dir as
 * {@code {claimId}/{uuid}{ext}} files, and the DB stores the relative object key (NOT an
 * absolute path) so the base dir can move between environments — and so an S3
 * implementation can adopt the same keys later.
 */
// The bean name stays "photoStorage" so every existing injection point keeps working.
@Component("photoStorage")
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
                String objectKey = claimId + "/" + UUID.randomUUID() + extensionOf(originalName);
                Path target = uploadDir.resolve(objectKey);
                Files.createDirectories(claimDir);
                byte[] bytes;
                try (var in = photo.getInputStream()) {
                    bytes = in.readAllBytes();
                }
                Files.write(target, bytes);
                stored.add(new StoredPhoto(objectKey, photo.getContentType(), originalName,
                        sha256Hex(bytes), (long) bytes.length));
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

    private void validate(MultipartFile photo) {
        String contentType = photo.getContentType();
        boolean image = contentType != null && contentType.startsWith("image/");
        boolean pdf = "application/pdf".equals(contentType);
        if (!image && !pdf) {
            throw new FnolValidationException("Attachments must be image or PDF files.");
        }
        if (photo.getSize() > maxSizeBytes) {
            throw new FnolValidationException(
                    "Photos must be smaller than " + (maxSizeBytes / 1024 / 1024) + " MB.");
        }
        if (!magicMatches(readHead(photo))) {
            throw new FnolValidationException("Attachments must be image or PDF files.");
        }
    }

    /**
     * S1 magic-byte gate: the declared content type is not trusted — the head
     * bytes must look like PNG, JPEG, GIF, WEBP, or PDF. Spoofed uploads
     * (an executable named {@code bill.pdf}, {@code text/plain} renamed to
     * {@code .png}) are rejected here.
     */
    static boolean magicMatches(byte[] head) {
        if (head == null || head.length < 3) {
            return false;
        }
        // PNG: 89 50 4E 47
        if (head.length >= 4 && (head[0] & 0xFF) == 0x89 && head[1] == 0x50
                && head[2] == 0x4E && head[3] == 0x47) {
            return true;
        }
        // JPEG: FF D8 FF
        if ((head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8
                && (head[2] & 0xFF) == 0xFF) {
            return true;
        }
        // GIF: 47 49 46 ("GIF")
        if (head[0] == 0x47 && head[1] == 0x49 && head[2] == 0x46) {
            return true;
        }
        // WEBP: 52 49 46 46 ("RIFF" at 0) .... 57 45 42 50 ("WEBP" at 8)
        if (head.length >= 12 && head[0] == 0x52 && head[1] == 0x49
                && head[2] == 0x46 && head[3] == 0x46
                && head[8] == 0x57 && head[9] == 0x45 && head[10] == 0x42
                && head[11] == 0x50) {
            return true;
        }
        // PDF: 25 50 44 46 ("%PDF")
        if (head.length >= 4 && head[0] == 0x25 && head[1] == 0x50
                && head[2] == 0x44 && head[3] == 0x46) {
            return true;
        }
        return false;
    }

    /** First 12 bytes for the magic check (WEBP's marker sits at offset 8). */
    private static byte[] readHead(MultipartFile photo) {
        try (InputStream in = photo.getInputStream()) {
            byte[] buf = new byte[12];
            int read = 0;
            while (read < buf.length) {
                int n = in.read(buf, read, buf.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            return java.util.Arrays.copyOf(buf, read);
        } catch (IOException ex) {
            throw new FnolValidationException("Attachments must be image or PDF files.");
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

    /** SHA-256 of the stored bytes, lowercase hex (integrity pin). */
    static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
