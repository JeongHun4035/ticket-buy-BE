package com.ticketing.ticketbuybe;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.validation.annotation.Validated;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import static com.ticketing.ticketbuybe.Contracts.*;

@RestController @RequestMapping("/api/v1") @Validated
@Tag(name="3. Queue and booking")
public class BookingController {
    final BookingService service;
    public BookingController(BookingService service) { this.service=service; }
    @PostMapping("/performances/{performanceId}/queue")
    @Operation(summary="대기열 진입",description="Bearer 필요. 동일 사용자 재진입은 기존 상태를 반환합니다. EXPIRED/CANCELLED에서 진입하면 새 순번을 받습니다. ticketNumber는 회차별 누적 발급 순번(1부터), aheadCount는 앞선 WAITING 인원입니다. 용량이 있으면 즉시 ADMITTED입니다.")
    public Queue enter(@PathVariable @Positive long performanceId,@AuthenticationPrincipal AuthService.Principal p) {return service.enter(performanceId,p.id());}
    @GetMapping("/performances/{performanceId}/queue/me")
    @Operation(summary="내 대기 상태",description="Bearer 필요. WAITING → ADMITTED → EXPIRED 또는 CANCELLED. estimatedWaitSeconds는 추정 근거가 없어 null. pollAfterSeconds 후 조회하세요. ADMITTED 응답의 admissionToken을 X-Admission-Token에 전달합니다.")
    public Queue queue(@PathVariable @Positive long performanceId,@AuthenticationPrincipal AuthService.Principal p) {return service.queue(performanceId,p.id());}
    @DeleteMapping("/performances/{performanceId}/queue/me") @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary="대기 취소",description="본인의 입장 권한 및 활성 확보를 해제합니다. Bearer 필요.")
    public void leave(@PathVariable @Positive long performanceId,@AuthenticationPrincipal AuthService.Principal p) {service.leave(performanceId,p.id());}
    @GetMapping("/performances/{performanceId}/seats")
    @Operation(summary="회차 좌석 배치·재고",description="Bearer 및 해당 사용자·회차의 X-Admission-Token 필요. 좌표 x/y, 등급 VIP/R/S, 정수 KRW, AVAILABLE/HELD/RESERVED를 반환합니다. 목록은 items/total/page/pageSize이며 좌석 전체를 한 페이지로 반환합니다.")
    public Page<Seat> seats(@PathVariable @Positive long performanceId,@AuthenticationPrincipal AuthService.Principal p,@RequestHeader("X-Admission-Token") String token) {
        var rows=service.seats(performanceId,p.id(),token);return new Page<>(rows,rows.size(),1,rows.size());
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
