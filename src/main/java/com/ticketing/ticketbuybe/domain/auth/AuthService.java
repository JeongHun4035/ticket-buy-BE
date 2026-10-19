package com.ticketing.ticketbuybe.domain.auth;

import com.ticketing.ticketbuybe.global.error.ApiError;
import com.ticketing.ticketbuybe.infrastructure.persistence.Store;

import org.springframework.stereotype.Service;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.security.*;
import java.util.*;
import static com.ticketing.ticketbuybe.infrastructure.persistence.Store.*;
import static com.ticketing.ticketbuybe.global.dto.Contracts.*;

@Service
public class AuthService {
    final Store s;
    final BCryptPasswordEncoder passwords=new BCryptPasswordEncoder(12);
    final String dummy=passwords.encode("unusable-dummy-password");
    public AuthService(Store s) { this.s=s; }
    public String hashPassword(String raw) { return passwords.encode(raw); }
    public record Principal(long id,String name,Role role,String sessionId) {}
    public record Tokens(Auth auth,String refresh) {}
    @Transactional
    public User signUp(SignUp req) {
        String passwordHash=hashPassword(req.password());
        var key=new org.springframework.jdbc.support.GeneratedKeyHolder();
        try {
            s.jdbc().update(connection->{
                var ps=connection.prepareStatement("insert into app_user(username,password_hash,name,role) values(?,?,?,'USER')",new String[]{"id"});
                ps.setString(1,req.username());
                ps.setString(2,passwordHash);
                ps.setString(3,req.name().strip());
                return ps;
            },key);
        } catch(org.springframework.dao.DuplicateKeyException e) {
            throw new ApiError(409,"USERNAME_ALREADY_EXISTS","이미 사용 중인 아이디입니다.");
        }
        return new User(Objects.requireNonNull(key.getKey()).toString(),req.name().strip(),Role.USER);
    }
    public static String token() {
        byte[] bytes=new byte[32]; new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    static String hash(String raw) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    @Transactional
    public Tokens login(Login req) {
        var users=s.jdbc().queryForList("select * from app_user where username=?",req.username());
        String hashed=users.isEmpty()?dummy:users.getFirst().get("password_hash").toString();
        if(!passwords.matches(req.password(),hashed)||users.isEmpty()) throw new ApiError(401,"AUTHENTICATION_FAILED","아이디 또는 비밀번호가 올바르지 않습니다.");
        return issue(users.getFirst(),null);
    }
    Tokens issue(Map<String,Object> user,String session) {
        String access=token(),refresh=token(); Instant now=Instant.now();
        if(session==null) {
            s.jdbc().update("insert into auth_session(id,user_id,access_hash,refresh_hash,access_expires,refresh_expires) values(?,?,?,?,?,?)",
                id(),num(user,"id"),hash(access),hash(refresh),utc(now.plusSeconds(900)),utc(now.plusSeconds(604800)));
        } else {
            s.jdbc().update("update auth_session set access_hash=?,refresh_hash=?,access_expires=?,refresh_expires=? where id=?",
                hash(access),hash(refresh),utc(now.plusSeconds(900)),utc(now.plusSeconds(604800)),session);
        }
        return new Tokens(new Auth(access,"Bearer",now.plusSeconds(900),new User(user.get("id").toString(),user.get("name").toString(),Role.valueOf(user.get("role").toString()))),refresh);
    }
    @Transactional
    public Tokens refresh(String refresh) {
        if(refresh==null) throw new ApiError(401,"AUTHENTICATION_FAILED","갱신 쿠키가 필요합니다.");
        var rows=s.jdbc().queryForList("select * from auth_session where refresh_hash=? for update",hash(refresh));
        if(rows.isEmpty()||!instant(rows.getFirst(),"refresh_expires").isAfter(Instant.now()))
            throw new ApiError(401,"TOKEN_EXPIRED","다시 로그인하세요.");
        var session=rows.getFirst();
        return issue(s.one("select * from app_user where id=?",num(session,"user_id")),session.get("id").toString());
    }
    public Principal authenticate(String raw) {
        var rows=s.jdbc().queryForList("select u.id,u.name,u.role,a.id session_id from auth_session a join app_user u on u.id=a.user_id where a.access_hash=? and a.access_expires>?",hash(raw),utc(Instant.now()));
        if(rows.isEmpty()) throw new ApiError(401,"TOKEN_EXPIRED","유효한 인증 토큰이 필요합니다.");
        var u=rows.getFirst(); return new Principal(num(u,"id"),u.get("name").toString(),Role.valueOf(u.get("role").toString()),u.get("session_id").toString());
    }
    @Transactional
    public void logout(Principal p,String refresh) {
        if(p!=null) s.jdbc().update("delete from auth_session where id=?",p.sessionId());
        if(refresh!=null) s.jdbc().update("delete from auth_session where refresh_hash=?",hash(refresh));
    }
}
