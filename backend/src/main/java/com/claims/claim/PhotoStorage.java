package com.claims.claim;

import java.util.List;

import org.springframework.web.multipart.MultipartFile;

/**
 * R5 storage seam: claimant evidence photos behind an interface. The filesystem stays the
 * only implementation (and the local-dev default); moving to S3/MinIO means implementing
 * this interface against an object store — no schema migration, because
 * {@code attachment.storage_path} holds the portable object key
 * ({@code {claimId}/{uuid}{ext}}), never an absolute path. See docs/operations.md
 * ("moving to S3").
 */
public interface PhotoStorage {

    /** Validates every photo first, then stores them all; returns ready attachment data. */
    List<StoredPhoto> store(List<MultipartFile> photos, Long claimId);

    /**
     * Resolves an object key to its bytes (used when serving downloads). The key comes
     * from {@code attachment.storage_path}; legacy absolute-path rows (pre-V11) resolve
     * too, so a half-migrated database still serves.
     */
    byte[] load(String storageKeyOrPath);

    /** Removes a claim's objects (used when the surrounding transaction rolls back). */
    void deleteClaimDir(Long claimId);
}
