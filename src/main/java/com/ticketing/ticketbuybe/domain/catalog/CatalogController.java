package com.ticketing.ticketbuybe.domain.catalog;


import org.springframework.web.bind.annotation.*;
import jakarta.validation.constraints.*;
import org.springframework.validation.annotation.Validated;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.*;
import static com.ticketing.ticketbuybe.global.dto.Contracts.*;

@RestController @RequestMapping("/api/v1") @Validated
@Tag(name="2. Catalog and support")
public class CatalogController {
    final CatalogService service;
    public CatalogController(CatalogService service) { this.service=service; }
    @GetMapping("/events")
    @Operation(summary="행사 검색·목록",description="공개. q는 선택 언어의 행사명/장소 부분 검색. category 코드로 필터. RECOMMENDED는 추천 순위 내림차순, DATE_ASC는 날짜순, PRICE_ASC는 최저 가격순. 동률은 id순. page 1부터, pageSize 1~100. lang ko/en/zh-CN, 누락 번역은 ko로 대체.")
    public Page<Event> events(@RequestParam(required=false) @Size(max=100) String q,@RequestParam(required=false) Category category,
        @RequestParam(defaultValue="RECOMMENDED") Sort sort,@RequestParam(defaultValue="1") @Min(1) int page,
        @RequestParam(defaultValue="20") @Min(1) @Max(100) int pageSize,@RequestParam(defaultValue="ko") @Pattern(regexp="ko|en|zh-CN") String lang) {
        return service.events(q,category,sort,page,pageSize,lang,false);
    }
    @GetMapping("/events/featured") @Operation(summary="추천 행사",description="공개. 추천 순위순. page 1부터, pageSize 1~100. lang ko/en/zh-CN.")
    public Page<Event> featured(@RequestParam(defaultValue="1") @Min(1) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int pageSize,
        @RequestParam(defaultValue="ko") @Pattern(regexp="ko|en|zh-CN") String lang) {return service.events(null,null,Sort.RECOMMENDED,page,pageSize,lang,true);}
    @GetMapping("/events/{eventId}") @Operation(summary="행사 상세",description="공개. status는 ON_SALE/UPCOMING/CLOSED. date는 표시용 대표 회차이며 예매는 performances에서 회차를 선택합니다.")
    public Event event(@PathVariable @Positive long eventId,@RequestParam(defaultValue="ko") @Pattern(regexp="ko|en|zh-CN") String lang) {return service.event(eventId,lang);}
    @GetMapping("/events/{eventId}/performances") @Operation(summary="행사 회차",description="공개. 시작일시순으로 회차와 판매 기간을 반환합니다. ON_SALE만 대기열 진입 가능. 날짜는 ISO 8601 UTC. page 1부터, pageSize 1~100.")
    public Page<Performance> performances(@PathVariable @Positive long eventId,@RequestParam(defaultValue="1") @Min(1) int page,
        @RequestParam(defaultValue="20") @Min(1) @Max(100) int pageSize,@RequestParam(defaultValue="ko") @Pattern(regexp="ko|en|zh-CN") String lang) {return service.performances(eventId,page,pageSize,lang);}
    @GetMapping("/categories") @Operation(summary="장르 코드",description="공개. 언어 독립 코드와 ko/en/zh-CN 표시명을 반환합니다.")
    public Page<Map<String,Object>> categories() {
        var labels=List.of(List.of("콘서트","Concert","演唱会"),List.of("뮤지컬","Musical","音乐剧"),List.of("연극","Theater","话剧"),List.of("전시","Exhibition","展览"),List.of("클래식","Classical","古典音乐"));
        List<Map<String,Object>> rows=new ArrayList<>(); int i=0;
        for(var c:Category.values()) {var l=labels.get(i++);rows.add(Map.of("code",c,"names",Map.of("ko",l.get(0),"en",l.get(1),"zh-CN",l.get(2))));}
        return new Page<>(rows,rows.size(),1,rows.size());
    }
    @GetMapping("/support/info") @Operation(summary="고객센터 안내",description="공개. 실제 연락처가 설정되기 전까지 placeholder 대신 null을 반환합니다.")
    public Map<String,Object> support() {var result=new LinkedHashMap<String,Object>();result.put("serviceName","TICKET BUY");result.put("phone",null);result.put("email",null);result.put("timezone","Asia/Seoul");result.put("message","문의 연락처는 운영자가 별도로 공지합니다.");return result;}
    @GetMapping("/faqs") @Operation(summary="FAQ 검색",description="공개. q는 제목/답변 부분 검색, 최신순. page 1부터, pageSize 1~100. lang ko/en/zh-CN.")
    public Page<Content> faqs(@RequestParam(required=false) @Size(max=100) String q,@RequestParam(defaultValue="1") @Min(1) int page,
        @RequestParam(defaultValue="20") @Min(1) @Max(100) int pageSize,@RequestParam(defaultValue="ko") @Pattern(regexp="ko|en|zh-CN") String lang) {return service.contents("FAQ",q,page,pageSize,lang);}
    @GetMapping("/posts") @Operation(summary="게시글 검색",description="공개. q는 제목/내용 부분 검색, 최신순. page 1부터, pageSize 1~100. lang ko/en/zh-CN.")
    public Page<Content> posts(@RequestParam(required=false) @Size(max=100) String q,@RequestParam(defaultValue="1") @Min(1) int page,
        @RequestParam(defaultValue="20") @Min(1) @Max(100) int pageSize,@RequestParam(defaultValue="ko") @Pattern(regexp="ko|en|zh-CN") String lang) {return service.contents("POST",q,page,pageSize,lang);}
    @GetMapping("/posts/{postId}") @Operation(summary="게시글 상세",description="공개. lang ko/en/zh-CN, 번역 누락 시 ko.")
    public Content post(@PathVariable @Positive long postId,@RequestParam(defaultValue="ko") @Pattern(regexp="ko|en|zh-CN") String lang) {return service.content("POST",postId,lang);}
}
