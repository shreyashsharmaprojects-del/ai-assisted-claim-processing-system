package com.claims.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import com.claims.api.FnolValidationException;

/**
 * S1 unit matrix: the magic-byte gate in {@link FilesystemPhotoStorage} —
 * png/jpg/gif/webp/pdf pass; spoofs (executable named .pdf, text/plain
 * renamed .png) are rejected; oversize/over-count caps still hold.
 */
class PhotoValidationTest {

    private static final long TEN_MB = 10 * 1024 * 1024L;

    @TempDir
    Path tempDir;

    // --- magic matrix -------------------------------------------------------

    @Test
    void pngPasses() {
        assertTrue(FilesystemPhotoStorage.magicMatches(png()));
    }

    @Test
    void jpegPasses() {
        assertTrue(FilesystemPhotoStorage.magicMatches(
                new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 1}));
    }

    @Test
    void gifPasses() {
        assertTrue(FilesystemPhotoStorage.magicMatches(
                new byte[] {0x47, 0x49, 0x46, 0x38, 0x39, 0x61}));
    }

    @Test
    void webpPasses() {
        assertTrue(FilesystemPhotoStorage.magicMatches(webp()));
    }

    @Test
    void pdfPasses() {
        assertTrue(FilesystemPhotoStorage.magicMatches(minimalPdf()));
    }

    @Test
    void executableSpoofedAsPdfIsRejected() {
        // MZ executable header with a .pdf name/content-type: magic says no.
        assertFalse(FilesystemPhotoStorage.magicMatches(
                new byte[] {0x4D, 0x5A, (byte) 0x90, 0, 3, 0, 0, 0}));
    }

    @Test
    void textPlainSpoofedAsPngIsRejected() {
        assertFalse(FilesystemPhotoStorage.magicMatches(
                "hello world, not a png".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void truncatedBytesAreRejected() {
        assertFalse(FilesystemPhotoStorage.magicMatches(new byte[] {(byte) 0x89, 0x50}));
        assertFalse(FilesystemPhotoStorage.magicMatches(new byte[0]));
        assertFalse(FilesystemPhotoStorage.magicMatches(null));
    }

    // --- store-level behavior ------------------------------------------------

    @Test
    void storeAcceptsPdfAndPinsShaAndSize() {
        FilesystemPhotoStorage storage = storage();
        byte[] pdf = minimalPdf();
        List<StoredPhoto> stored = storage.store(
                List.of(mockFile("bill.pdf", "application/pdf", pdf)), 9001L);
        assertEquals(1, stored.size());
        assertEquals(64, stored.get(0).sha256().length(),
                "sha256 pin must be 64 lowercase hex chars");
        assertTrue(stored.get(0).sha256().matches("[0-9a-f]{64}"), stored.get(0).sha256());
        assertEquals(pdf.length, stored.get(0).sizeBytes());
    }

    @Test
    void storeRejectsSpoofedPngWithNewMessage() {
        FilesystemPhotoStorage storage = storage();
        FnolValidationException ex = assertThrows(FnolValidationException.class,
                () -> storage.store(List.of(
                        mockFile("damage.png", "image/png",
                                "not an image".getBytes(StandardCharsets.UTF_8))),
                        9002L));
        assertTrue(ex.getMessage().contains("must be image or PDF files"), ex.getMessage());
    }

    @Test
    void storeRejectsNonImageNonPdfWithNewMessage() {
        FilesystemPhotoStorage storage = storage();
        FnolValidationException ex = assertThrows(FnolValidationException.class,
                () -> storage.store(List.of(
                        mockFile("damage.txt", "text/plain", png())), 9003L));
        assertTrue(ex.getMessage().contains("must be image or PDF files"), ex.getMessage());
    }

    @Test
    void storeStillRejectsOversizeAndOverCount() {
        FilesystemPhotoStorage storage = storage(100L, 5);
        FnolValidationException oversize = assertThrows(FnolValidationException.class,
                () -> storage.store(
                        List.of(mockFile("big.png", "image/png", png(200))), 9004L));
        assertTrue(oversize.getMessage().contains("smaller than"), oversize.getMessage());

        List<MockMultipartFile> six = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            six.add(mockFile("p" + i + ".png", "image/png", png()));
        }
        FnolValidationException overCount = assertThrows(FnolValidationException.class,
                () -> storage.store(new ArrayList<>(six), 9005L));
        assertTrue(overCount.getMessage().contains("At most 5"), overCount.getMessage());
    }

    // --- helpers -------------------------------------------------------------

    private FilesystemPhotoStorage storage() {
        return new FilesystemPhotoStorage(tempDir.toString(), TEN_MB, 5);
    }

    private FilesystemPhotoStorage storage(long maxSizeBytes, int maxCount) {
        return new FilesystemPhotoStorage(tempDir.toString(), maxSizeBytes, maxCount);
    }

    private static MockMultipartFile mockFile(String name, String contentType, byte[] bytes) {
        return new MockMultipartFile("photos", name, contentType, bytes);
    }

    private static byte[] png() {
        return png(8);
    }

    private static byte[] png(int length) {
        byte[] bytes = new byte[length];
        bytes[0] = (byte) 0x89;
        bytes[1] = 0x50;
        bytes[2] = 0x4E;
        bytes[3] = 0x47;
        return bytes;
    }

    private static byte[] webp() {
        // RIFF....WEBP: "RIFF" at 0, size at 4, "WEBP" at 8.
        return new byte[] {0x52, 0x49, 0x46, 0x46, 0x10, 0, 0, 0,
                0x57, 0x45, 0x42, 0x50};
    }

    private static byte[] minimalPdf() {
        return ("%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n"
                + "%%EOF\n").getBytes(StandardCharsets.US_ASCII);
    }
}
