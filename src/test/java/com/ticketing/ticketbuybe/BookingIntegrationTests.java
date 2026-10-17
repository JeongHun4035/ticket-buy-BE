package com.ticketing.ticketbuybe;

import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.ticketing.ticketbuybe.Contracts.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class BookingIntegrationTests {
    @LocalServerPort int port;
    @Autowired BookingService bookings;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    long demo,other;
    @BeforeEach void clean() {
        for(String table:List.of("reservation_item","performance_seat","reservation","seat_hold","queue_entry")) {
            if(table.equals("performance_seat")) db.update("update performance_seat set hold_id=null,reservation_id=null");
            else db.update("delete from "+table);
        }
        db.update("update performance set next_ticket=0");
        demo=db.queryForObject("select id from app_user where username='demo'",Long.class);
        other=db.queryForObject("select id from app_user where username='other'",Long.class);
    }
    void error(String expected,org.junit.jupiter.api.function.Executable fn) {
        var e=assertThrows(ApiError.class,fn);assertEquals(expected,e.code);
    }
    String admitted(long user) {return bookings.enter(101,user).admissionToken();}
    @Test void duplicateQueueAdmissionOwnershipAndPromotion() {
        var first=bookings.enter(101,demo);var repeat=bookings.enter(101,demo);
        assertEquals(first.ticketNumber(),repeat.ticketNumber());assertEquals(first.admissionToken(),repeat.admissionToken());
        var second=bookings.enter(101,other);assertEquals("WAITING",second.state());assertNull(second.estimatedWaitSeconds());assertEquals(0,second.aheadCount());
        error("QUEUE_ADMISSION_REQUIRED",()->bookings.hold(101,other,first.admissionToken(),new HoldRequest(List.of(1L))));
        error("QUEUE_ADMISSION_REQUIRED",()->bookings.hold(102,demo,first.admissionToken(),new HoldRequest(List.of(1L))));
        bookings.leave(101,demo);assertEquals("ADMITTED",bookings.queue(101,other).state());
    }
    @Test void onlyOneConcurrentSeatHoldWins() throws Exception {
        String a=admitted(demo);
        // Raise admission capacity only for the fixture by inserting a second admitted identity.
        db.update("insert into queue_entry(id,performance_id,user_id,ticket,state,token,expires_at) values(?,101,?,2,'ADMITTED',?,?)",Store.id(),other,"test-other-admission",Store.utc(Instant.now().plusSeconds(600)));
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            List<Callable<String>> tasks=List.of(()->attempt(start,demo,a),()->attempt(start,other,"test-other-admission"));
            var futures=tasks.stream().map(pool::submit).toList(); start.countDown();
            var results=new ArrayList<String>(); for(var f:futures) results.add(f.get(15,TimeUnit.SECONDS));
            assertEquals(1,Collections.frequency(results,"OK"));assertEquals(1,Collections.frequency(results,"SEAT_CONFLICT"));
        }
        assertEquals(1L,db.queryForObject("select count(*) from seat_hold where state='ACTIVE'",Long.class));
    }
    String attempt(CountDownLatch start,long user,String token) throws Exception {
        start.await();try { bookings.hold(101,user,token,new HoldRequest(List.of(1L)));return "OK"; } catch(ApiError e){return e.code;}
    }
    @Test void expirationLimitsAndAdmissionExpiry() {
        String token=admitted(demo);
        var h=bookings.hold(101,demo,token,new HoldRequest(List.of(1L,2L,3L,4L)));
        error("SEAT_LIMIT_EXCEEDED",()->bookings.hold(101,demo,token,new HoldRequest(List.of(5L))));
        db.update("update seat_hold set expires_at=? where id=?",Store.utc(Instant.now().minusSeconds(1)),h.id());
        error("HOLD_EXPIRED",()->bookings.reserve(demo,token,"expired",new ReservationRequest(h.id())));
        assertEquals("AVAILABLE",bookings.seats(101,demo,token).getFirst().status());
        db.update("update queue_entry set expires_at=? where performance_id=101 and user_id=?",Store.utc(Instant.now().minusSeconds(1)),demo);
        error("QUEUE_ADMISSION_REQUIRED",()->bookings.hold(101,demo,token,new HoldRequest(List.of(1L))));
    }
    @Test void reservationIdempotencyOwnershipAndCancel() throws Exception {
        String token=admitted(demo);var h=bookings.hold(101,demo,token,new HoldRequest(List.of(1L,2L)));
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Reservation> call=()->{start.await();return bookings.reserve(demo,token,"same-key",new ReservationRequest(h.id()));};
            var a=pool.submit(call);var b=pool.submit(call);start.countDown();assertEquals(a.get().id(),b.get().id());
        }
        var r=bookings.reserve(demo,token,"same-key",new ReservationRequest(h.id()));assertEquals(300000,r.total());assertEquals("NOT_REQUIRED",r.paymentStatus());
        assertEquals(1L,db.queryForObject("select count(*) from reservation",Long.class));
        error("NOT_FOUND",()->bookings.reservation(r.id(),other));error("NOT_FOUND",()->bookings.release(h.id(),other));
        error("IDEMPOTENCY_CONFLICT",()->bookings.reserve(demo,token,"same-key",new ReservationRequest("different")));
        error("HOLD_NOT_ACTIVE",()->bookings.reserve(demo,token,"new-key",new ReservationRequest(h.id())));
        assertEquals("CANCELLED",bookings.cancel(r.id(),demo).status());assertEquals("AVAILABLE",bookings.seats(101,demo,token).getFirst().status());
    }
    HttpResponse<String> request(String method,String path,String body,String access,Map<String,String> headers) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Content-Type","application/json");
        if(access!=null) b.header("Authorization","Bearer "+access);
        headers.forEach(b::header);
        return HttpClient.newHttpClient().send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode node(HttpResponse<String> r){return json.readTree(r.body());}
    @Test void httpSwaggerAuthenticationRefreshCorsAndBookingFlow() throws Exception {
        var docs=request("GET","/v3/api-docs",null,null,Map.of());assertEquals(200,docs.statusCode());assertTrue(node(docs).path("paths").has("/api/v1/reservations"));
        assertEquals(200,request("GET","/swagger-ui/index.html",null,null,Map.of()).statusCode());
        var unauth=request("GET","/api/v1/users/me",null,null,Map.of());assertEquals(401,unauth.statusCode());assertTrue(node(unauth).has("traceId"));
        assertEquals(401,request("POST","/api/v1/auth/login","{\"username\":\"demo\",\"password\":\"bad\"}",null,Map.of()).statusCode());
        var login=request("POST","/api/v1/auth/login","{\"username\":\"demo\",\"password\":\"Demo1234!\"}",null,Map.of("Origin","http://localhost:5173"));
        assertEquals(200,login.statusCode());assertEquals("http://localhost:5173",login.headers().firstValue("access-control-allow-origin").orElseThrow());
        String access=node(login).path("accessToken").asString();String cookie=login.headers().firstValue("set-cookie").orElseThrow().split(";",2)[0];
        assertEquals(200,request("GET","/api/v1/users/me",null,access,Map.of()).statusCode());
        var events=request("GET","/api/v1/events?lang=ko&page=1&pageSize=2",null,null,Map.of());assertEquals(200,events.statusCode());assertEquals(2,node(events).path("items").size());assertTrue(events.body().contains("콘서트"));
        assertEquals(400,request("GET","/api/v1/events?page=0",null,null,Map.of()).statusCode());
        assertEquals(403,request("POST","/api/v1/admin/posts","{}",access,Map.of()).statusCode());
        var queue=request("POST","/api/v1/performances/101/queue",null,access,Map.of());assertEquals(200,queue.statusCode());String admission=node(queue).path("admissionToken").asString();
        assertEquals(403,request("GET","/api/v1/performances/101/seats",null,access,Map.of("X-Admission-Token","fake")).statusCode());
        var held=request("POST","/api/v1/performances/101/holds","{\"seatIds\":[1]}",access,Map.of("X-Admission-Token",admission));assertEquals(201,held.statusCode());
        var reservation=request("POST","/api/v1/reservations",json.writeValueAsString(Map.of("holdId",node(held).path("id").asString())),access,Map.of("X-Admission-Token",admission,"Idempotency-Key","http-flow"));assertEquals(201,reservation.statusCode());
        var refreshed=request("POST","/api/v1/auth/refresh",null,null,Map.of("Cookie",cookie));assertEquals(200,refreshed.statusCode());
        assertEquals(401,request("GET","/api/v1/users/me",null,access,Map.of()).statusCode());
        assertEquals(401,request("POST","/api/v1/auth/refresh",null,null,Map.of("Cookie",cookie)).statusCode());
        String next=node(refreshed).path("accessToken").asString();assertEquals(204,request("POST","/api/v1/auth/logout",null,next,Map.of()).statusCode());assertEquals(401,request("GET","/api/v1/users/me",null,next,Map.of()).statusCode());
    }
    @Test void salesWindowsAndAdminContent() throws Exception {
        error("BOOKING_NOT_OPEN",()->bookings.enter(501,demo));
        db.update("update performance set closes_at=? where id=102",Store.utc(Instant.now().minusSeconds(1)));
        try { error("BOOKING_CLOSED",()->bookings.enter(102,demo)); } finally {db.update("update performance set closes_at=? where id=102",Store.utc(Instant.now().plusSeconds(86400)));}
        var login=auth.login(new Login("admin","Demo1234!"));
        String body=json.writeValueAsString(Map.of("category","NOTICE","titles",Map.of("ko","한글 공지","en","Notice"),"bodies",Map.of("ko","안내 내용")));
        var created=request("POST","/api/v1/admin/posts",body,login.auth().accessToken(),Map.of());assertEquals(201,created.statusCode());String id=node(created).path("id").asString();
        var detail=request("GET","/api/v1/posts/"+id+"?lang=zh-CN",null,null,Map.of());assertEquals("한글 공지",node(detail).path("title").asString());
        assertEquals(200,request("PATCH","/api/v1/admin/posts/"+id,body,login.auth().accessToken(),Map.of()).statusCode());
        assertEquals(204,request("DELETE","/api/v1/admin/posts/"+id,null,login.auth().accessToken(),Map.of()).statusCode());
    }
}
