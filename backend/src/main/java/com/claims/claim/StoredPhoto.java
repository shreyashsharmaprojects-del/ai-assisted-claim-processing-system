package com.claims.claim;

/** A photo persisted to disk, ready for an attachment row. */
public record StoredPhoto(String storagePath, String contentType, String originalName) {
}
