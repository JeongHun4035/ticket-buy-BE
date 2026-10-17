package com.ticketing.ticketbuybe;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import static com.ticketing.ticketbuybe.Contracts.*;

@RestController
@RequestMapping("/api/v1")
@Tag(name="1. Authentication")
public class AuthController {
    final AuthService auth; final boolean secure;
    public AuthController(AuthService auth,@Value("${app.cookie-secure}") boolean secure){this.auth=auth;this.secure=secure;}
    String cookie(String value,int seconds) {
        return ResponseCookie.from("refresh_token",value).httpOnly(true).secure(secure).sameSite("Strict").path("/api/v1/auth").maxAge(seconds).build().toString();
    }
    @PostMapping("/auth/login")
    @Operation(summary="로그인",description="공개 API. 아이디/비밀번호로 15분 Bearer access token과 7일 HttpOnly refresh 쿠키를 발급합니다.")
    public ResponseEntity<Auth> login(@Valid @RequestBody Login req) {
        var result=auth.login(req); return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,cookie(result.refresh(),604800)).header(HttpHeaders.CACHE_CONTROL,"no-store").body(result.auth());
    }
    @PostMapping("/auth/refresh")
    @Operation(summary="인증 갱신",description="refresh_token 쿠키 필수. 토큰을 회전하며 이전 access/refresh 토큰은 즉시 무효화됩니다. 브라우저는 credentials: include를 사용합니다.")
    public ResponseEntity<Auth> refresh(@CookieValue(name="refresh_token",required=false) String refresh) {
        var result=auth.refresh(refresh); return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,cookie(result.refresh(),604800)).header(HttpHeaders.CACHE_CONTROL,"no-store").body(result.auth());
    }
    @PostMapping("/auth/logout") @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary="로그아웃",description="Bearer 또는 refresh 쿠키의 세션을 폐기하고 쿠키를 제거합니다. 반복 호출 가능합니다.")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal AuthService.Principal p,@CookieValue(name="refresh_token",required=false) String refresh) {
        auth.logout(p,refresh); return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE,cookie("",0)).build();
    }
    @GetMapping("/users/me")
    @Operation(summary="내 정보",description="Bearer 인증 필요. 프론트 AuthUser와 동일한 id/name을 반환합니다.")
    public User me(@AuthenticationPrincipal AuthService.Principal p) { return new User(Long.toString(p.id()),p.name()); }
}
