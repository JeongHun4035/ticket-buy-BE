package com.ticketing.ticketbuybe;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import io.swagger.v3.oas.annotations.Operation;
import java.time.Duration;

@RestController
public class EventImageController {
    final EventImages images;
    public EventImageController(EventImages images){this.images=images;}
    @GetMapping("/api/v1/event-images/{imageId:[0-9a-fA-F\\-]{36}}")
    @Operation(summary="등록된 행사 이미지",description="공개 GET. posterUrl이 가리키는 PNG/JPEG 바이트를 반환합니다. 이미지 URL은 불변이며 public cache/ETag를 사용합니다. 없는 이미지는 공통 오류 404.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",description="이미지 바이너리",content={
        @io.swagger.v3.oas.annotations.media.Content(mediaType="image/png",schema=@io.swagger.v3.oas.annotations.media.Schema(type="string",format="binary")),
        @io.swagger.v3.oas.annotations.media.Content(mediaType="image/jpeg",schema=@io.swagger.v3.oas.annotations.media.Schema(type="string",format="binary"))})
    public ResponseEntity<byte[]> get(@PathVariable String imageId) {
        var image=images.get(imageId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.contentType()))
            .header("X-Content-Type-Options","nosniff").cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable()).eTag(imageId).body(image.bytes());
    }
}
