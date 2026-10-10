package com.github.vad_ik.imadjOnline.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

@Service
public class ImageStorageService {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final String bucket;
    private final Duration urlTtl;

    public ImageStorageService(
            S3Client s3Client,
            S3Presigner s3Presigner,
            @Value("${minio.bucket}") String bucket,
            @Value("${minio.presign-ttl-minutes:60}") long presignTtlMinutes
    ) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.bucket = bucket;
        this.urlTtl = Duration.ofMinutes(presignTtlMinutes);
    }

    @PostConstruct
    void ensureBucket() {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException e) {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        }
    }

    /**
     * Uploads an image and returns the object key to store in DB / game state.
     */
    public String upload(MultipartFile file) throws IOException {
        return upload(file.getBytes(), file.getContentType(), file.getOriginalFilename());
    }

    public String upload(byte[] bytes, String contentType, String filename) {
        return uploadWithPrefix("cards/", bytes, contentType, filename);
    }

    public String uploadAvatar(byte[] bytes, String contentType, String filename) {
        return uploadWithPrefix("avatars/", bytes, contentType, filename);
    }

    private String uploadWithPrefix(String prefix, byte[] bytes, String contentType, String filename) {
        String key = prefix + UUID.randomUUID() + extension(filename);

        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType != null ? contentType : "application/octet-stream")
                .build();

        s3Client.putObject(request, RequestBody.fromBytes(bytes));
        return key;
    }

    /**
     * Browser-reachable URL. Goes through the backend so Docker hostname {@code minio}
     * never leaks into the UI (presigned MinIO URLs are unusable from the host browser).
     */
    public String presignedUrl(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return "/api/images/content?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8);
    }

    /** Direct MinIO presign — keep for debugging only. */
    public String directPresignedUrl(String key) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(urlTtl)
                .getObjectRequest(getObjectRequest)
                .build();

        return s3Presigner.presignGetObject(presignRequest).url().toString();
    }

    /**
     * Download through backend — useful for admin/debug, not for game UI.
     */
    public ResponseInputStream<GetObjectResponse> download(String key) {
        return s3Client.getObject(GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build());
    }

    public byte[] downloadBytes(String key) throws IOException {
        try (ResponseInputStream<GetObjectResponse> stream = download(key)) {
            return stream.readAllBytes();
        }
    }

    /** Overwrite an existing object (same key) — used when rotating cards in place. */
    public void putBytes(String key, byte[] bytes, String contentType) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key required");
        }
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType != null ? contentType : "image/jpeg")
                .build();
        s3Client.putObject(request, RequestBody.fromBytes(bytes));
    }

    public boolean exists(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        try {
            s3Client.headObject(software.amazon.awssdk.services.s3.model.HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
            return true;
        } catch (software.amazon.awssdk.services.s3.model.NoSuchKeyException e) {
            return false;
        } catch (software.amazon.awssdk.services.s3.model.S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    public void delete(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build());
    }

    private static String extension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.'));
    }
}
