package com.ticketing.ticketbuybe;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import static com.ticketing.ticketbuybe.Contracts.*;

@RestController @RequestMapping("/api/v1/admin") @Validated @Tag(name="4. Administration")
public class EventAdminController {
    final EventAdminService service;
    public EventAdminController(EventAdminService service){this.service=service;}
    @PostMapping(value="/events",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary="이미지를 첨부해 행사·회차 등록",description="ADMIN Bearer 필요. multipart event 파트는 application/json, image 파트는 필수 PNG/JPEG 파일(최대 5MiB, 각 변 최대 6000px/총 2000만 픽셀). 제목/설명 ko 필수, en/zh-CN 선택. 최대 20회차, 각 sectionPrices로 지정한 구역 전체 좌석만 편성하며 구역별 등급·정수 KRW 가격을 지정합니다. opensAt < closesAt <= startsAt, 시작/마감은 미래. 전체 작업은 트랜잭션이며 실패 시 이미지·행사·회차·재고를 함께 rollback합니다. 성공 201에 행사/회차 ID와 공개 posterUrl 반환. 잘못된 날짜·구역 400, 이미지 형식 415, 용량 413, 권한 401/403.")
    public EventCreated create(@Parameter(description="JSON 행사·회차 정보. 이 파트의 Content-Type은 application/json") @Valid @RequestPart("event") EventCreate req,
        @Parameter(description="PNG/JPEG 포스터 파일. SVG/GIF/WebP 업로드는 지원하지 않음") @RequestPart("image") MultipartFile image) {
        return service.create(req,image);
    }
    @GetMapping("/venues") @Operation(summary="행사 등록용 공연장 목록",description="ADMIN Bearer 필요. 대기열 입장 불필요. page 1부터, pageSize 기본 20/최대 100. ID순, 전체 배치 좌석 수 포함.")
    public Page<VenueInfo> venues(@RequestParam(defaultValue="1") @Min(1) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int pageSize){return service.venues(page,pageSize);}
    @GetMapping("/venues/{venueId}/sections") @Operation(summary="행사 등록용 공연장 구역 배치",description="ADMIN Bearer 필요. 대기열 없이 해당 공연장의 층·구역·좌석 수를 조회합니다. 한국어 표시. 선택한 구역 ID를 행사 등록 sectionPrices에 전달합니다. 전체 구역 1페이지이며 재고 상태는 포함하지 않습니다. 없는 공연장은 404.")
    public VenueLayout layout(@PathVariable @Positive long venueId){return service.layout(venueId);}
}
