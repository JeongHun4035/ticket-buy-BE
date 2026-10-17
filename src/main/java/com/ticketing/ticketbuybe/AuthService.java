package com.ticketing.ticketbuybe;

import org.springframework.stereotype.Service;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.security.*;
import java.util.*;
import static com.ticketing.ticketbuybe.Store.*;
import static com.ticketing.ticketbuybe.Contracts.*;

@Service
public class AuthService {
    final Store s;
    final BCryptPasswordEncoder passwords=new BCryptPasswordEncoder(12);
    final String dummy=passwords.encode("unusable-dummy-password");
    public AuthService(Store s) { this.s=s; }
    public String hashPassword(String raw) { return passwords.encode(raw); }
    public record Principal(long id,String name,String role,String sessionId) {}
    public record Tokens(Auth auth,String refresh) {}
    static String token() {
        byte[] bytes=new byte[32]; new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    static String hash(String raw) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    @Transactional
    public Tokens login(Login req) {
        var users=s.db.queryForList("select * from app_user where username=?",req.username());
        String hashed=users.isEmpty()?dummy:users.getFirst().get("password_hash").toString();
        if(!passwords.matches(req.password(),hashed)||users.isEmpty()) throw new ApiError(401,"AUTHENTICATION_FAILED","아이디 또는 비밀번호가 올바르지 않습니다.");
        return issue(users.getFirst(),null);
    }
    Tokens issue(Map<String,Object> user,String session) {
        String access=token(),refresh=token(); Instant now=Instant.now();
        if(session==null) {
            s.db.update("insert into auth_session(id,user_id,access_hash,refresh_hash,access_expires,refresh_expires) values(?,?,?,?,?,?)",
                id(),num(user,"id"),hash(access),hash(refresh),utc(now.plusSeconds(900)),utc(now.plusSeconds(604800)));
        } else {
            s.db.update("update auth_session set access_hash=?,refresh_hash=?,access_expires=?,refresh_expires=? where id=?",
                hash(access),hash(refresh),utc(now.plusSeconds(900)),utc(now.plusSeconds(604800)),session);
        }
        return new Tokens(new Auth(access,"Bearer",now.plusSeconds(900),new User(user.get("id").toString(),user.get("name").toString())),refresh);
    }
    @Transactional
    public Tokens refresh(String refresh) {
        if(refresh==null) throw new ApiError(401,"AUTHENTICATION_FAILED","갱신 쿠키가 필요합니다.");
        var rows=s.db.queryForList("select * from auth_session where refresh_hash=? for update",hash(refresh));
        if(rows.isEmpty()||!instant(rows.getFirst(),"refresh_expires").isAfter(Instant.now()))
            throw new ApiError(401,"TOKEN_EXPIRED","다시 로그인하세요.");
        var session=rows.getFirst();
        return issue(s.one("select * from app_user where id=?",num(session,"user_id")),session.get("id").toString());
    }
    public Principal authenticate(String raw) {
        var rows=s.db.queryForList("select u.id,u.name,u.role,a.id session_id from auth_session a join app_user u on u.id=a.user_id where a.access_hash=? and a.access_expires>?",hash(raw),utc(Instant.now()));
        if(rows.isEmpty()) throw new ApiError(401,"TOKEN_EXPIRED","유효한 인증 토큰이 필요합니다.");
        var u=rows.getFirst(); return new Principal(num(u,"id"),u.get("name").toString(),u.get("role").toString(),u.get("session_id").toString());
    }
    @Transactional
    public void logout(Principal p,String refresh) {
        if(p!=null) s.db.update("delete from auth_session where id=?",p.sessionId());
        if(refresh!=null) s.db.update("delete from auth_session where refresh_hash=?",hash(refresh));
    }
}
