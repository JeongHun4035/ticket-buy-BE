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
    public record User(String id,String name) {}
    public record Login(@NotBlank @Size(max=80) @Schema(example="demo") String username,
                        @NotBlank @Size(max=100) @Schema(example="Demo1234!") String password) {}
    public record Auth(String accessToken,String tokenType,Instant expiresAt,User user) {}
    public record Event(long id,String title,String description,Category category,String venue,
                        Instant date,Long price,String posterUrl,@Schema(allowableValues={"ON_SALE","UPCOMING","CLOSED"}) String status) {}
    public record Performance(long id,long eventId,long venueId,String venue,Instant startsAt,Instant opensAt,Instant closesAt,@Schema(allowableValues={"ON_SALE","UPCOMING","CLOSED"}) String status) {}
    public record Queue(@Schema(allowableValues={"WAITING","ADMITTED","EXPIRED","CANCELLED"}) String state,long ticketNumber,long aheadCount,@Schema(nullable=true,description="측정 근거가 없어 null") Long estimatedWaitSeconds,
                        String admissionToken,Instant admissionExpiresAt,int pollAfterSeconds) {}
    public record Seat(String id,String row,int number,int x,int y,@Schema(allowableValues={"VIP","R","S"}) String grade,long price,@Schema(allowableValues={"AVAILABLE","HELD","RESERVED"}) String status) {}
    public record HoldRequest(@NotEmpty @Size(max=4) List<@NotNull @Positive Long> seatIds) {}
    public record Hold(String id,long performanceId,Instant expiresAt,@Schema(allowableValues={"ACTIVE"}) String status,List<Seat> seats,long total,@Schema(allowableValues={"KRW"}) String currency) {}
    public record ReservationRequest(@NotBlank @Size(max=36) String holdId) {}
    public record Reservation(String id,long performanceId,@Schema(allowableValues={"CONFIRMED","CANCELLED"}) String status,@Schema(allowableValues={"NOT_REQUIRED"}) String paymentStatus,long total,@Schema(allowableValues={"KRW"}) String currency,
                              Instant createdAt,List<ReservationSeat> seats) {}
    public record ReservationSeat(String id,String row,int number,String grade,long price) {}
    public record Content(long id,String category,String title,String content,Instant date) {}
    public record ContentRequest(@NotNull ContentCategory category,
        @NotEmpty Map<@Pattern(regexp="ko|en|zh-CN") String,@NotBlank @Size(max=200) String> titles,
        @NotEmpty Map<@Pattern(regexp="ko|en|zh-CN") String,@NotBlank @Size(max=20000) String> bodies) {}
}
