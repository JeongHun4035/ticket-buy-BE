package com.ticketing.ticketbuybe.domain.booking;

import com.ticketing.ticketbuybe.domain.auth.AuthService;
import com.ticketing.ticketbuybe.infrastructure.persistence.Store;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.validation.annotation.Validated;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import static com.ticketing.ticketbuybe.global.dto.Contracts.*;

@RestController @RequestMapping("/api/v1") @Validated
@Tag(name="3. Queue and booking")
public class BookingController {
    final BookingService service;
    public BookingController(BookingService service) { this.service=service; }
    @PostMapping("/performances/{performanceId}/queue")
    @Operation(summary="대기열 진입",description="Bearer 필요. 입장 용량에 여유가 있고 앞선 대기자가 없으면 즉시 ADMITTED, 그 외 WAITING으로 진입하며 작업자가 순번대로 입장시킵니다. 동일 사용자 활성 재진입은 기존 순번 유지. EXPIRED/CANCELLED 재진입은 새 순번. ticketNumber는 누적 발급 순번, aheadCount는 앞선 WAITING 인원, position은 WAITING일 때 현재 대기순서입니다.")
    public Queue enter(@PathVariable @Positive long performanceId,@AuthenticationPrincipal AuthService.Principal p) {return service.enter(performanceId,p.id());}
    @GetMapping("/performances/{performanceId}/queue/me")
    @Operation(summary="내 대기 상태",description="Bearer 필요. DB 행 잠금·갱신 없이 상태와 순서를 조회합니다. WAITING → ADMITTED → EXPIRED 또는 CANCELLED. position=앞선 WAITING 인원+1(다른 상태 null). 예상 시간은 null. pollAfterSeconds 이후 폴링, 빠른 반복 조회는 429/Retry-After. ADMITTED의 admissionToken을 X-Admission-Token에 전달합니다. Cache-Control: no-store.")
    public Queue queue(@PathVariable @Positive long performanceId,@AuthenticationPrincipal AuthService.Principal p) {return service.queue(performanceId,p.id());}
    @DeleteMapping("/performances/{performanceId}/queue/me") @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary="대기 취소",description="본인의 입장 권한 및 활성 확보를 해제합니다. Bearer 필요.")
    public void leave(@PathVariable @Positive long performanceId,@AuthenticationPrincipal AuthService.Principal p) {service.leave(performanceId,p.id());}
    @GetMapping("/performances/{performanceId}/seats")
    @Operation(summary="회차 좌석 배치·재고",description="Bearer 및 해당 사용자·회차의 X-Admission-Token 필요. 잠금·갱신 없는 조회 스냅샷이며 확보 성공을 보장하지 않습니다. 만료 확보는 AVAILABLE로 표시. 기본 2초 최소 조회 간격, X-Poll-After 참고. 초과는 429/Retry-After. Cache-Control: no-store. 좌표 x/y, 등급 VIP/R/S, 정수 KRW, AVAILABLE/HELD/RESERVED. 좌석 전체를 items/total/page/pageSize 1페이지로 반환합니다. availableSeats는 해당 회차 전체 AVAILABLE 좌석 수입니다.")
    public SeatPage seats(@PathVariable @Positive long performanceId,@AuthenticationPrincipal AuthService.Principal p,@RequestHeader("X-Admission-Token") String token,@RequestParam(defaultValue="ko") @Pattern(regexp="ko|en|zh-CN") String lang) {
        return SeatPage.of(service.seats(performanceId,p.id(),token,lang));
    }
    @GetMapping("/performances/{performanceId}/sections")
    @Operation(summary="공연장·층·구역 및 잔여 좌석",description="Bearer 및 해당 회차 X-Admission-Token 필수. lang=ko/en/zh-CN(기본 ko, 누락 번역 ko). venue의 capacity/mapWidth/mapHeight, 층 목록과 구역 집계 목록을 반환합니다. 구역 x/y/width/height는 해당 층 전체 지도 좌표입니다. totalSeats는 회차에 편성된 좌석, availableSeats는 유효 확보·예매를 제외한 조회 시점 참고 값입니다. DB 집계로 처리하며 좌석 객체를 전부 로딩하지 않습니다. 전체 구역 1페이지, 순서 층 level/구역 sortOrder/id. 기본 2초 제한/429 Retry-After, Cache-Control: no-store. 없는 회차는 404, 입장 권한 오류 403, 판매 기간 오류 409.")
    public Sections sections(@PathVariable @Positive long performanceId,@AuthenticationPrincipal AuthService.Principal p,@RequestHeader("X-Admission-Token") String token,@RequestParam(defaultValue="ko") @Pattern(regexp="ko|en|zh-CN") String lang) {
        return service.sections(performanceId,p.id(),token,lang);
    }
    @GetMapping("/performances/{performanceId}/sections/{sectionId}/seats")
    @Operation(summary="구역별 좌석 배치·재고",description="Bearer 및 해당 회차 X-Admission-Token 필수. lang=ko/en/zh-CN. 해당 회차·구역에 편성된 좌석만 1페이지로 반환합니다. availableSeats는 해당 구역 AVAILABLE 좌석 수입니다. 좌석 x/y는 구역 내부 좌표, sectionId/section/floor/floorId로 위치를 식별합니다. 다른 공연장 구역 또는 회차에 없는 구역은 404. 재고는 AVAILABLE/HELD/RESERVED, 만료 확보는 AVAILABLE. 조회는 확보를 보장하지 않고 최종 hold에서 검증합니다. 전체 좌석 API와 사용자·회차별 최소 2초 간격을 공유합니다. 초과 429/Retry-After, no-store.")
    public SeatPage sectionSeats(@PathVariable @Positive long performanceId,@PathVariable @Positive long sectionId,@AuthenticationPrincipal AuthService.Principal p,@RequestHeader("X-Admission-Token") String token,@RequestParam(defaultValue="ko") @Pattern(regexp="ko|en|zh-CN") String lang) {
        return SeatPage.of(service.sectionSeats(performanceId,sectionId,p.id(),token,lang));
    }
    @PostMapping("/performances/{performanceId}/holds") @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary="최대 4석 임시 확보",description="Bearer와 X-Admission-Token 필요. 기존 ACTIVE 확보와 합쳐 최대 4석. 전부 확보되거나 전부 실패합니다. 409 SEAT_CONFLICT이면 좌석을 다시 조회하세요. expiresAt 이후 사용 불가, 기본 300초. 가격은 서버에서 계산합니다.")
    public Hold hold(@PathVariable @Positive long performanceId,@AuthenticationPrincipal AuthService.Principal p,@RequestHeader("X-Admission-Token") String token,@Valid @RequestBody HoldRequest req) {return service.hold(performanceId,p.id(),token,req);}
    @DeleteMapping("/holds/{holdId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary="임시 확보 해제",description="Bearer 필요. 본인의 확보만 해제하며 반복 요청 가능합니다. 타인 리소스는 404입니다.")
    public void release(@PathVariable String holdId,@AuthenticationPrincipal AuthService.Principal p) {service.release(holdId,p.id());}
    @PostMapping("/reservations") @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary="확보한 좌석으로 예매 확정",description="Bearer, X-Admission-Token, Idempotency-Key 필요. holdId 소유권·만료·입장 권한을 검증합니다. 동일 키/holdId 재시도는 기존 결과, 키에 다른 holdId는 409. 실제 결제 없는 정책으로 CONFIRMED/NOT_REQUIRED이며 PG 결제를 의미하지 않습니다.")
    public Reservation reserve(@AuthenticationPrincipal AuthService.Principal p,@RequestHeader("X-Admission-Token") String token,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody ReservationRequest req) {return service.reserve(p.id(),token,key,req);}
    @GetMapping("/reservations/me")
    @Operation(summary="내 예매 목록",description="Bearer 필요. 최신 생성순. page 1부터, pageSize 1~100.")
    public Page<Reservation> reservations(@AuthenticationPrincipal AuthService.Principal p,@RequestParam(defaultValue="1") @Min(1) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int pageSize) {return service.reservations(p.id(),page,pageSize);}
    @GetMapping("/reservations/{reservationId}")
    @Operation(summary="내 예매 상세",description="Bearer 필요. 타인 예매 및 없는 예매는 동일하게 404를 반환합니다.")
    public Reservation reservation(@PathVariable String reservationId,@AuthenticationPrincipal AuthService.Principal p) {return service.reservation(reservationId,p.id());}
    @PostMapping("/reservations/{reservationId}/cancel")
    @Operation(summary="예매 취소",description="Bearer 필요. 공연 시작 전 본인 예매만 취소 가능. 반복 취소 가능. 좌석 재판매를 허용하며 원래 가격 스냅샷은 보존합니다. 실제 결제/환불은 수행하지 않습니다.")
    public Reservation cancel(@PathVariable String reservationId,@AuthenticationPrincipal AuthService.Principal p) {return service.cancel(reservationId,p.id());}
}
