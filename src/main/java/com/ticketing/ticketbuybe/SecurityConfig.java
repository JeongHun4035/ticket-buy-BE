package com.ticketing.ticketbuybe;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean org.springframework.security.core.userdetails.UserDetailsService noFormLogin() {
        return username->{throw new org.springframework.security.core.userdetails.UsernameNotFoundException("Bearer authentication only");};
    }
    @Bean SecurityFilterChain chain(HttpSecurity http,AuthService auth,ObjectMapper json,ReadRequestLimiter reads,OriginPolicy origins) throws Exception {
        var cors=new CorsConfiguration() {
            @Override public String checkOrigin(String origin) {return origins.allows(origin)?origin:null;}
        };
        cors.setAllowedOrigins(origins.allowedOrigins()); cors.setAllowCredentials(true);
        cors.setAllowedMethods(List.of("GET","POST","PATCH","DELETE","OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization","Content-Type","X-Admission-Token","Idempotency-Key"));
        cors.setExposedHeaders(List.of("X-Trace-Id","Retry-After","X-Poll-After"));
        var source=new UrlBasedCorsConfigurationSource(); source.registerCorsConfiguration("/**",cors);
        return http.csrf(c->c.disable()).cors(c->c.configurationSource(source))
            .sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(c->c.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/users/**","/api/v1/performances/**","/api/v1/holds/**","/api/v1/reservations/**").authenticated()
                .anyRequest().permitAll())
            .exceptionHandling(c->c.authenticationEntryPoint((r,w,e)->write(w,json,401,ApiError.body("AUTHENTICATION_REQUIRED","로그인이 필요합니다.",r)))
                .accessDeniedHandler((r,w,e)->write(w,json,403,ApiError.body("FORBIDDEN","권한이 없습니다.",r))))
            .addFilterBefore(new OncePerRequestFilter() {
                final Map<String,Bucket> limits=new ConcurrentHashMap<>();
                protected void doFilterInternal(HttpServletRequest r,HttpServletResponse w,FilterChain chain) throws ServletException,IOException {
                    r.setAttribute("traceId",UUID.randomUUID().toString()); w.setHeader("X-Trace-Id",r.getAttribute("traceId").toString());
                    try {
                        if(r.getRequestURI().startsWith("/api/v1/auth/") && !r.getMethod().equals("GET")) {
                            String origin=r.getHeader("Origin");
                            if(origin!=null&&!origins.allows(origin)) throw new ApiError(403,"ORIGIN_NOT_ALLOWED","허용되지 않은 Origin입니다. 서버의 CORS_ORIGINS와 PUBLIC_BASE_URL 설정을 확인하세요.");
                        }
                        String header=r.getHeader("Authorization");
                        if(header!=null) {
                            if(!header.startsWith("Bearer ")) throw new ApiError(401,"AUTHENTICATION_FAILED","Bearer 토큰이 필요합니다.");
                            var p=auth.authenticate(header.substring(7));
                            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(p,null,List.of(new SimpleGrantedAuthority("ROLE_"+p.role()))));
                        }
                        var authentication=SecurityContextHolder.getContext().getAuthentication();
                        var principal=authentication!=null&&authentication.getPrincipal() instanceof AuthService.Principal p?p:null;
                        String readPath=r.getMethod().equals("GET")?reads.canonicalPath(r.getServletPath()):null;
                        if(readPath!=null) {
                            w.setHeader("Cache-Control","no-store");
                            w.setHeader("X-Poll-After",Integer.toString(reads.intervalSeconds(readPath)));
                            if(principal!=null) {
                                long retry=reads.retryAfter(principal.id(),readPath);
                                if(retry>0) {w.setHeader("Retry-After",Long.toString(retry));throw new ApiError(429,"RATE_LIMITED","권장 조회 주기 이후 다시 요청하세요.");}
                            }
                        }
                        if(r.getRequestURI().startsWith("/api/")) {
                            long minute=System.currentTimeMillis()/60000;
                            if(limits.size()>10000) limits.entrySet().removeIf(e->e.getValue().minute<minute);
                            // Direct peer address only; untrusted forwarded headers are ignored.
                            String key=r.getRequestURI().startsWith("/api/v1/auth/")?r.getRemoteAddr()+":auth":(principal==null?r.getRemoteAddr():"user:"+principal.id())+":api";
                            int max=key.endsWith(":auth")?30:240;
                            var b=limits.compute(key,(k,v)->v==null||v.minute!=minute?new Bucket(minute):v);
                            if(b.hits.incrementAndGet()>max) { w.setHeader("Retry-After","60"); throw new ApiError(429,"RATE_LIMITED","요청이 너무 많습니다."); }
                        }
                        chain.doFilter(r,w);
                    } catch(ApiError e) { write(w,json,e.status,ApiError.body(e.code,e.getMessage(),r)); }
                }
            },UsernamePasswordAuthenticationFilter.class).build();
    }
    static class Bucket { final long minute; final java.util.concurrent.atomic.AtomicInteger hits=new java.util.concurrent.atomic.AtomicInteger(); Bucket(long m){minute=m;} }
    static void write(HttpServletResponse w,ObjectMapper json,int status,Object body) throws IOException {
        w.setStatus(status); w.setContentType("application/json;charset=UTF-8"); w.getWriter().write(json.writeValueAsString(body));
    }
}
