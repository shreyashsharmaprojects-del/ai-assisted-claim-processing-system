package com.claims.claim;

/** A photo persisted to disk, ready for an attachment row. */
public record StoredPhoto(String storagePath, String contentType, String originalName,
        String sha256, long sizeBytes) {

    /** Backwards-compatible constructor for callers without integrity fields. */
    public StoredPhoto(String storagePath, String contentType, String originalName) {
        this(storagePath, contentType, originalName, null, 0L);
    }
}
