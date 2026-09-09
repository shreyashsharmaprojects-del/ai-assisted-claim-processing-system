package com.claims.claim;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * S2: the S3-compatible {@link PhotoStorage}. Zero new Maven dependencies
 * (offline build): S3 REST via {@code java.net.http.HttpClient} with a
 * hand-rolled AWS SigV4 signer (PUT/GET/HEAD/DELETE). Keys are identical to
 * the filesystem backend ({@code {claimId}/{uuid}{ext}}), and sha/size are
 * computed the same way and returned via {@link StoredPhoto}.
 *
 * <p>Active only when {@code claims.storage.backend=s3}; the filesystem
 * backend stays the default.
 */
@Component("s3PhotoStorage")
@ConditionalOnProperty(name = "claims.storage.backend", havingValue = "s3")
public class S3PhotoStorage implements PhotoStorage {

    private static final Logger log = LoggerFactory.getLogger(S3PhotoStorage.class);
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final DateTimeFormatter DATE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final HttpClient http;
    private final String endpoint;
    private final String bucket;
    private final String region;
    private final String accessKey;
    private final String secretKey;
    private final long maxSizeBytes;
    private final int maxCount;

    @org.springframework.beans.factory.annotation.Autowired
    public S3PhotoStorage(
            @Value("${claims.s3.endpoint}") String endpoint,
            @Value("${claims.s3.bucket}") String bucket,
            @Value("${claims.s3.region:us-east-1}") String region,
            @Value("${claims.s3.access-key:}") String accessKey,
            @Value("${claims.s3.secret-key:}") String secretKey,
            @Value("${claims.uploads.max-size-bytes}") long maxSizeBytes,
            @Value("${claims.uploads.max-count}") int maxCount) {
        this(HttpClient.newHttpClient(), endpoint, bucket, region, accessKey, secretKey,
                maxSizeBytes, maxCount);
    }

    /** Package-visible constructor for unit tests (injectable HttpClient). */
    S3PhotoStorage(HttpClient http, String endpoint, String bucket, String region,
            String accessKey, String secretKey, long maxSizeBytes, int maxCount) {
        this.http = http;
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.bucket = bucket;
        this.region = region;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.maxSizeBytes = maxSizeBytes;
        this.maxCount = maxCount;
    }

    /** Validates every file first (shared S1 rules), then PUTs them all. */
    @Override
    public List<StoredPhoto> store(List<MultipartFile> photos, Long claimId) {
        if (photos == null || photos.isEmpty()) {
            return List.of();
        }
        PhotoValidator.validateAll(photos, maxSizeBytes, maxCount);
        List<StoredPhoto> stored = new ArrayList<>();
        List<String> written = new ArrayList<>();
        try {
            for (MultipartFile photo : photos) {
                byte[] bytes;
                try (var in = photo.getInputStream()) {
                    bytes = in.readAllBytes();
                }
                String originalName = PhotoValidator.sanitize(photo.getOriginalFilename());
                String objectKey = PhotoValidator.objectKey(claimId, photo.getOriginalFilename());
                putObject(objectKey, bytes, contentTypeOf(photo));
                written.add(objectKey);
                stored.add(new StoredPhoto(objectKey, photo.getContentType(), originalName,
                        PhotoValidator.sha256Hex(bytes), (long) bytes.length));
            }
            return stored;
        } catch (IOException | RuntimeException ex) {
            // Best-effort rollback of this batch (mirrors the filesystem behavior).
            for (String key : written) {
                try {
                    deleteObject(key);
                } catch (RuntimeException rollbackEx) {
                    log.warn("Could not roll back S3 object {} after store failure", key, rollbackEx);
                }
            }
            if (ex instanceof S3StorageException s3ex) {
                throw s3ex;
            }
            if (ex instanceof IOException ioex) {
                throw new IllegalStateException("Could not store uploaded photo", ioex);
            }
            throw (RuntimeException) ex;
        }
    }

    /** GETs the object bytes by key (legacy absolute-path rows are rejected loudly). */
    @Override
    public byte[] load(String storageKeyOrPath) {
        if (storageKeyOrPath != null && storageKeyOrPath.startsWith("/")) {
            throw new IllegalStateException(
                    "Legacy absolute-path attachment cannot be served from S3: " + storageKeyOrPath);
        }
        return getObject(storageKeyOrPath);
    }

    /** DELETEs every object under the {@code {claimId}/} prefix. */
    @Override
    public void deleteClaimDir(Long claimId) {
        // List by prefix, then delete each key (path-style ListObjectsV2).
        String prefix = claimId + "/";
        List<String> keys = listObjects(prefix);
        for (String key : keys) {
            try {
                deleteObject(key);
            } catch (RuntimeException ex) {
                log.warn("Could not delete S3 object {} during rollback", key, ex);
            }
        }
    }

    /** True when a HEAD on the key returns 200. */
    public boolean exists(String storageKey) {        try {
            headObject(storageKey);
            return true;
        } catch (S3StorageException ex) {
            if (ex.status() == 404) {
                return false;
            }
            throw ex;
        }
    }

    // --- S3 REST primitives ----------------------------------------------------

    /**
     * PUTs raw bytes under an explicit key (the backfill path: legacy keys are
     * content-independent, so the runner reuses the row's existing key).
     */
    public void putForBackfill(String key, byte[] bytes, String contentType) {
        try {
            putObject(key, bytes, contentType == null ? "application/octet-stream" : contentType);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not backfill S3 object " + key, ex);
        }
    }

    private void putObject(String key, byte[] bytes, String contentType) throws IOException {
        HttpRequest request = signed("PUT", key, null,
                HttpRequest.BodyPublishers.ofByteArray(bytes),
                bytes, contentType);
        HttpResponse<byte[]> response = send(request);
        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new S3StorageException("PUT " + key + " failed", response.statusCode());
        }
    }

    private byte[] getObject(String key) {
        HttpRequest request = signed("GET", key, null,
                HttpRequest.BodyPublishers.noBody(), new byte[0], null);
        HttpResponse<byte[]> response = send(request);
        if (response.statusCode() == 404) {
            throw new IllegalStateException("Could not read attachment " + key
                    + " (missing in bucket " + bucket + ")");
        }
        if (response.statusCode() != 200) {
            throw new S3StorageException("GET " + key + " failed", response.statusCode());
        }
        return response.body();
    }

    private void headObject(String key) {
        HttpRequest request = signed("HEAD", key, null,
                HttpRequest.BodyPublishers.noBody(), new byte[0], null);
        HttpResponse<Void> response = sendVoid(request);
        if (response.statusCode() == 404) {
            throw new S3StorageException("HEAD " + key + " missing", 404);
        }
        if (response.statusCode() != 200) {
            throw new S3StorageException("HEAD " + key + " failed", response.statusCode());
        }
    }

    private void deleteObject(String key) {
        HttpRequest request = signed("DELETE", key, null,
                HttpRequest.BodyPublishers.noBody(), new byte[0], null);
        HttpResponse<Void> response = sendVoid(request);
        if (response.statusCode() != 200 && response.statusCode() != 204
                && response.statusCode() != 404) {
            throw new S3StorageException("DELETE " + key + " failed", response.statusCode());
        }
    }

    private List<String> listObjects(String prefix) {
        String query = "list-type=2&prefix=" + urlEncode(prefix);
        HttpRequest request = signed("GET", "", query,
                HttpRequest.BodyPublishers.noBody(), new byte[0], null);
        HttpResponse<String> response = sendString(request);
        if (response.statusCode() != 200) {
            throw new S3StorageException("LIST prefix " + prefix + " failed",
                    response.statusCode());
        }
        // Minimal XML parse: collect every <Key>…</Key> under this bucket.
        List<String> keys = new ArrayList<>();
        String body = response.body();
        int from = 0;
        while (true) {
            int open = body.indexOf("<Key>", from);
            if (open < 0) {
                break;
            }
            int close = body.indexOf("</Key>", open);
            if (close < 0) {
                break;
            }
            keys.add(body.substring(open + 5, close));
            from = close + 6;
        }
        return keys;
    }

    private HttpResponse<byte[]> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException | InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("S3 request failed: " + request.uri(), ex);
        }
    }

    private HttpResponse<Void> sendVoid(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (IOException | InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("S3 request failed: " + request.uri(), ex);
        }
    }

    private HttpResponse<String> sendString(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("S3 request failed: " + request.uri(), ex);
        }
    }

    private static String contentTypeOf(MultipartFile photo) {
        return photo.getContentType() == null ? "application/octet-stream" : photo.getContentType();
    }

    // --- SigV4 signing (path-style, UNSIGNED-PAYLOAD-free: we hash the body) ----

    private HttpRequest signed(String method, String key, String query,
            HttpRequest.BodyPublisher body, byte[] payload, String contentType) {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        String amzDate = now.format(AMZ_DATE);
        String dateStamp = now.format(DATE_STAMP);
        String payloadHash = hexOf(sha256(payload));
        String encodedKey = encodeKey(key);
        String canonicalQuery = query == null ? "" : query;
        String uri = endpoint + "/" + bucket + (encodedKey.isEmpty() ? "" : "/" + encodedKey)
                + (canonicalQuery.isEmpty() ? "" : "?" + canonicalQuery);

        TreeMap<String, String> headers = new TreeMap<>();
        headers.put("host", URI.create(endpoint).getAuthority());
        headers.put("x-amz-content-sha256", payloadHash);
        headers.put("x-amz-date", amzDate);
        if (contentType != null) {
            headers.put("content-type", contentType);
        }

        StringBuilder signedHeaders = new StringBuilder();
        StringBuilder canonicalHeaders = new StringBuilder();
        for (var entry : headers.entrySet()) {
            if (signedHeaders.length() > 0) {
                signedHeaders.append(';');
            }
            signedHeaders.append(entry.getKey());
            canonicalHeaders.append(entry.getKey()).append(':')
                    .append(entry.getValue().trim()).append('\n');
        }
        String canonicalRequest = method + "\n"
                + "/" + bucket + (encodedKey.isEmpty() ? "" : "/" + encodedKey) + "\n"
                + canonicalQuery + "\n"
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + payloadHash;
        String scope = dateStamp + "/" + region + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                + hexOf(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        byte[] signingKey = signingKey(secretKey, dateStamp, region);
        String signature = hexOf(hmacSha256(signingKey, stringToSign.getBytes(StandardCharsets.UTF_8)));
        String authorization = "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(uri))
                .method(method, body)
                .header("Authorization", authorization)
                .header("x-amz-date", amzDate)
                .header("x-amz-content-sha256", payloadHash);
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        return builder.build();
    }

    private static byte[] signingKey(String secret, String dateStamp, String region) {
        byte[] kSecret = ("AWS4" + secret).getBytes(StandardCharsets.UTF_8);
        byte[] kDate = hmacSha256(kSecret, dateStamp.getBytes(StandardCharsets.UTF_8));
        byte[] kRegion = hmacSha256(kDate, region.getBytes(StandardCharsets.UTF_8));
        byte[] kService = hmacSha256(kRegion, "s3".getBytes(StandardCharsets.UTF_8));
        return hmacSha256(kService, "aws4_request".getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception ex) {
            throw new IllegalStateException("HmacSHA256 unavailable", ex);
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String hexOf(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private static String encodeKey(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        String[] segments = key.split("/", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                out.append('/');
            }
            out.append(urlEncode(segments[i]));
        }
        return out.toString();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** S3-side failure with the HTTP status preserved (404 = missing key). */
    public static final class S3StorageException extends RuntimeException {
        private final int status;

        public S3StorageException(String message, int status) {
            super(message + " (status " + status + ")");
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
