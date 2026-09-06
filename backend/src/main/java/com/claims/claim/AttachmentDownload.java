package com.claims.claim;

/** An attachment's binary payload plus the headers needed to serve it safely. */
public record AttachmentDownload(byte[] bytes, String contentType, String originalName) {
}
