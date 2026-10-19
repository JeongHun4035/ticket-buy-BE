package com.ticketing.ticketbuybe.domain.catalog;


import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import static com.ticketing.ticketbuybe.global.dto.Contracts.*;

@RestController @RequestMapping("/api/v1/admin") @Tag(name="4. Administration")
public class AdminController {
    final CatalogService service;
    public AdminController(CatalogService service){this.service=service;}
    @PostMapping("/faqs") @ResponseStatus(HttpStatus.CREATED) @Operation(summary="FAQ 등록",description="ADMIN Bearer 필요. titles/bodies는 ko 필수, en/zh-CN 선택. HTML 대신 plain text로 저장/렌더링하세요.")
    public Content createFaq(@Valid @RequestBody ContentRequest req){return service.save("FAQ",null,req);}
    @PatchMapping("/faqs/{id}") @Operation(summary="FAQ 수정",description="ADMIN Bearer 필요. 번역 맵 전체 교체. ko 필수.")
    public Content updateFaq(@PathVariable long id,@Valid @RequestBody ContentRequest req){return service.save("FAQ",id,req);}
    @DeleteMapping("/faqs/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @Operation(summary="FAQ 삭제",description="ADMIN Bearer 필요.")
    public void deleteFaq(@PathVariable long id){service.delete("FAQ",id);}
    @PostMapping("/posts") @ResponseStatus(HttpStatus.CREATED) @Operation(summary="게시글 등록",description="ADMIN Bearer 필요. category NOTICE/GUIDE/UPDATE. titles/bodies는 ko 필수, en/zh-CN 선택.")
    public Content createPost(@Valid @RequestBody ContentRequest req){return service.save("POST",null,req);}
    @PatchMapping("/posts/{id}") @Operation(summary="게시글 수정",description="ADMIN Bearer 필요. 번역 맵 전체 교체. ko 필수.")
    public Content updatePost(@PathVariable long id,@Valid @RequestBody ContentRequest req){return service.save("POST",id,req);}
    @DeleteMapping("/posts/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @Operation(summary="게시글 삭제",description="ADMIN Bearer 필요.")
    public void deletePost(@PathVariable long id){service.delete("POST",id);}
}
