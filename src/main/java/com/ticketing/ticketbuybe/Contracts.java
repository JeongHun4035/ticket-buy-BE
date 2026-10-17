package com.ticketing.ticketbuybe;

import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.*;

public class Contracts {
    public enum Category { CONCERT, MUSICAL, THEATER, EXHIBITION, CLASSICAL }
    public enum Sort { RECOMMENDED, DATE_ASC, PRICE_ASC }
    public enum Language { ko, en, zh_CN }
    public enum ContentCategory { NOTICE, GUIDE, UPDATE }
    public record Page<T>(List<T> items,long total,int page,int pageSize) {}
    public enum Role { USER, ADMIN }
    public enum Grade { VIP, R, S }
    public record SectionPrice(@NotNull @Positive Long sectionId,@NotNull Grade grade,
        @NotNull @Min(0) @Max(100000000) Long price) {}
    public record PerformanceCreate(@NotNull @Positive Long venueId,@NotNull Instant startsAt,
        @NotNull Instant opensAt,@NotNull Instant closesAt,
        @NotEmpty @Size(max=1000) List<@jakarta.validation.Valid SectionPrice> sectionPrices) {}
    public record EventCreate(
        @NotEmpty Map<@Pattern(regexp="ko|en|zh-CN") String,@NotBlank @Size(max=200) String> titles,
        @NotEmpty Map<@Pattern(regexp="ko|en|zh-CN") String,@NotBlank @Size(max=20000) String> descriptions,
        @NotNull Category category,@Min(0) @Max(100000) int featuredRank,
        @NotEmpty @Size(max=20) List<@jakarta.validation.Valid PerformanceCreate> performances) {}
    public record EventCreated(Event event,List<Performance> performances) {}
    public record VenueSection(String id,String name,String floor,String floorId,long totalSeats,int x,int y,int width,int height,int sortOrder) {}
    public record VenueLayout(VenueInfo venue,List<FloorInfo> floors,List<VenueSection> items,long total,int page,int pageSize) {}
    public record User(String id,String name,@Schema(description="권한 코드: USER(일반 사용자), ADMIN(관리자)") Role role) {}
    public record SignUp(
        @NotBlank @Pattern(regexp="[A-Za-z0-9_]{4,80}")
        @Schema(description="영문·숫자·밑줄 4~80자. 대소문자를 구분합니다.",example="ticket_user") String username,
        @NotBlank @Pattern(regexp="(?=.*[A-Za-z])(?=.*[0-9])[!-~]{8,72}")
        @Schema(description="영문과 숫자를 포함한 공백 없는 ASCII 8~72자. 특수문자 사용 가능.",example="Ticket1234!",accessMode=Schema.AccessMode.WRITE_ONLY) String password,
        @NotBlank @Size(max=100) @Pattern(regexp="[^\\p{Cntrl}]{1,100}")
        @Schema(description="표시 이름. 앞뒤 공백 제거, 최대 100자.",example="티켓 사용자") String name) {
        @Override public String toString() { return "SignUp[REDACTED]"; }
    }
    public record Login(@NotBlank @Size(max=80) @Schema(example="demo") String username,
                        @NotBlank @Size(max=100) @Schema(example="Demo1234!") String password) {}
    public record Auth(String accessToken,String tokenType,Instant expiresAt,User user) {}
    public record Event(long id,String title,String description,Category category,String venue,
                        Instant date,Long price,String posterUrl,@Schema(allowableValues={"ON_SALE","UPCOMING","CLOSED"}) String status,Long venueId,Long venueCapacity) {}
    public record Performance(long id,long eventId,long venueId,String venue,Instant startsAt,Instant opensAt,Instant closesAt,@Schema(allowableValues={"ON_SALE","UPCOMING","CLOSED"}) String status,long venueCapacity) {}
    public record Queue(@Schema(allowableValues={"WAITING","ADMITTED","EXPIRED","CANCELLED"}) String state,long ticketNumber,long aheadCount,@Schema(nullable=true,description="현재 대기순서. WAITING일 때 aheadCount+1, 그 외 null") Long position,@Schema(nullable=true,description="측정 근거가 없어 null") Long estimatedWaitSeconds,
                        String admissionToken,Instant admissionExpiresAt,int pollAfterSeconds) {}
    public record Seat(String id,String row,int number,int x,int y,@Schema(allowableValues={"VIP","R","S"}) String grade,long price,@Schema(allowableValues={"AVAILABLE","HELD","RESERVED"}) String status,String sectionId,String section,String floor,String floorId) {}
    public record VenueInfo(long id,String name,long capacity,int mapWidth,int mapHeight) {}
    public record FloorInfo(String id,String name,int level) {}
    public record Section(String id,String name,String floor,String floorId,long totalSeats,@Schema(description="조회 시점 참고 값. 최종 확보 성공을 보장하지 않음") long availableSeats,@Schema(description="해당 층 전체 지도에서 구역 사각형 좌상단 X") int x,int y,int width,int height,int sortOrder) {}
    public record Sections(VenueInfo venue,List<FloorInfo> floors,List<Section> items,long total,int page,int pageSize) {}
    public record HoldRequest(@NotEmpty @Size(max=4) List<@NotNull @Positive Long> seatIds) {}
    public record Hold(String id,long performanceId,Instant expiresAt,@Schema(allowableValues={"ACTIVE"}) String status,List<Seat> seats,long total,@Schema(allowableValues={"KRW"}) String currency) {}
    public record ReservationRequest(@NotBlank @Size(max=36) String holdId) {}
    public record Reservation(String id,long performanceId,@Schema(allowableValues={"CONFIRMED","CANCELLED"}) String status,@Schema(allowableValues={"NOT_REQUIRED"}) String paymentStatus,long total,@Schema(allowableValues={"KRW"}) String currency,
                              Instant createdAt,List<ReservationSeat> seats) {}
    public record ReservationSeat(String id,String row,int number,String grade,long price,String sectionId,String section,String floor,String floorId) {}
    public record Content(long id,String category,String title,String content,Instant date) {}
    public record ContentRequest(@NotNull ContentCategory category,
        @NotEmpty Map<@Pattern(regexp="ko|en|zh-CN") String,@NotBlank @Size(max=200) String> titles,
        @NotEmpty Map<@Pattern(regexp="ko|en|zh-CN") String,@NotBlank @Size(max=20000) String> bodies) {}
}
