package com.github.vad_ik.imadjOnline.controller;

import com.github.vad_ik.imadjOnline.service.ImageStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.IOException;
import java.util.Map;

@Tag(name = "Images", description = "Прокси картинок из MinIO для браузера.")
@RestController
@RequestMapping("/api/images")
public class ImageController {

    private final ImageStorageService imageStorageService;

    public ImageController(ImageStorageService imageStorageService) {
        this.imageStorageService = imageStorageService;
    }

    @Operation(summary = "Загрузить изображение в MinIO")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, String> upload(@RequestParam("file") MultipartFile file) throws IOException {
        String key = imageStorageService.upload(file);
        return Map.of(
                "key", key,
                "url", imageStorageService.presignedUrl(key)
        );
    }

    /**
     * Short-lived MinIO URL for &lt;img src&gt; — frontend loads the file directly from MinIO.
     */
    @Operation(summary = "URL прокси для ключа")
    @GetMapping("/url")
    public Map<String, String> url(@RequestParam String key) {
        return Map.of(
                "key", key,
                "url", imageStorageService.presignedUrl(key)
        );
    }

    /**
     * Image proxy for &lt;img src&gt; — works from the browser when MinIO is only on the Docker network.
     */
    @Operation(summary = "Отдать байты картинки (для <img src>)")
    @GetMapping("/content")
    public ResponseEntity<byte[]> content(@RequestParam String key) throws IOException {
        try (ResponseInputStream<GetObjectResponse> stream = imageStorageService.download(key)) {
            String contentType = stream.response().contentType();
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE,
                            contentType != null ? contentType : MediaType.IMAGE_JPEG_VALUE)
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                    .body(stream.readAllBytes());
        }
    }

    @GetMapping("/download")
    public ResponseEntity<byte[]> download(@RequestParam String key) throws IOException {
        return content(key);
    }
}
