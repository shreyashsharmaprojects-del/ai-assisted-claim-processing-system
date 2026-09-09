package com.claims.claim;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.springframework.web.multipart.MultipartFile;

import com.claims.api.FnolValidationException;

/**
 * S2: the S1 validation rules extracted from {@link FilesystemPhotoStorage} so both
 * storage backends enforce byte-identical gates. Validation order and every
 * user-facing message are unchanged (count → content-type → size → magic bytes).
 */
public final class PhotoValidator {

    private PhotoValidator() {
    }

    /**
     * Validates every file first (count cap, then per-file rules) — call before
     * touching any store so a batch never half-writes.
     */
    public static void validateAll(List<MultipartFile> photos, long maxSizeBytes, int maxCount) {
        if (photos == null || photos.isEmpty()) {
            return;
        }
        if (photos.size() > maxCount) {
            throw new FnolValidationException("At most " + maxCount + " photos may be attached.");
        }
        for (MultipartFile photo : photos) {
            validate(photo, maxSizeBytes);
        }
    }

    /** Per-file gate: allowlist (image/* + application/pdf), size cap, magic bytes. */
    public static void validate(MultipartFile photo, long maxSizeBytes) {
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
    public static boolean magicMatches(byte[] head) {
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
    public static byte[] readHead(MultipartFile photo) {
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
            return Arrays.copyOf(buf, read);
        } catch (IOException ex) {
            throw new FnolValidationException("Attachments must be image or PDF files.");
        }
    }

    /** SHA-256 of the stored bytes, lowercase hex (integrity pin). */
    public static String sha256Hex(byte[] bytes) {
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

    /** Portable object key shared by both backends: {@code {claimId}/{uuid}{ext}}. */
    public static String objectKey(Long claimId, String originalFilename) {
        return claimId + "/" + UUID.randomUUID() + extensionOf(sanitize(originalFilename));
    }

    public static String sanitize(String filename) {
        if (filename == null) {
            return "photo";
        }
        String base = java.nio.file.Path.of(filename).getFileName().toString();
        return base.isBlank() ? "photo" : base;
    }

    public static String extensionOf(String filename) {
        int dot = filename == null ? -1 : filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot).toLowerCase() : ".img";
    }
}
