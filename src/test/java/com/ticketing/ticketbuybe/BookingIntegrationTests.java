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
    @Autowired CatalogService catalog;
    @Autowired ExpiryWorker worker;
    @Autowired VenueScaleSeed venueSeed;
    @Autowired DevPosterSeed posterSeed;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    long demo,other;
    @BeforeEach void clean() {
        for(String table:List.of("reservation_item","performance_seat","reservation","seat_hold","queue_entry")) {
            if(table.equals("performance_seat")) db.update("update performance_seat set hold_id=null,reservation_id=null where hold_id is not null or reservation_id is not null");
            else db.update("delete from "+table);
        }
        db.update("update performance set next_ticket=0");
        demo=db.queryForObject("select id from app_user where username='demo'",Long.class);
        other=db.queryForObject("select id from app_user where username='other'",Long.class);
    }
    void error(String expected,org.junit.jupiter.api.function.Executable fn) {
        var e=assertThrows(ApiError.class,fn);assertEquals(expected,e.code);
    }
    String admitted(long user) {bookings.enter(101,user);worker.expire();return bookings.queue(101,user).admissionToken();}
    String admitted(long performance,long user) {bookings.enter(performance,user);worker.expire();return bookings.queue(performance,user).admissionToken();}
    @Test void venueSizesSectionsAndRepeatableSeed() {
        for(var fixture:Map.of(10L,500L,20L,5000L,30L,30000L).entrySet()) {
            long venue=fixture.getKey(),capacity=fixture.getValue(),performance=venue*100+1;
            assertEquals(capacity,db.queryForObject("select count(*) from venue_seat where venue_id=?",Long.class,venue));
            assertEquals(capacity,db.queryForObject("select count(*) from performance_seat where performance_id=?",Long.class,performance));
            assertEquals(capacity,db.queryForObject("select count(*) from performance_seat where performance_id=?",Long.class,performance+1));
            assertEquals(capacity,catalog.event(venue,"ko").venueCapacity());
            assertEquals(venue,catalog.event(venue,"ko").venueId());
            assertEquals(capacity,catalog.performances(venue,1,20,"ko").items().getFirst().venueCapacity());
            String token=admitted(performance,demo);var overview=bookings.sections(performance,demo,token,"ko");
            assertEquals(capacity,overview.venue().capacity());assertEquals(capacity,overview.items().stream().mapToLong(Section::totalSeats).sum());
            assertEquals(venue==10?1:venue==20?2:3,overview.floors().size());
            assertEquals(capacity,overview.items().stream().mapToLong(Section::availableSeats).sum());
            assertEquals(capacity/250,overview.total());
            var section=overview.items().getFirst();var seats=bookings.sectionSeats(performance,Long.parseLong(section.id()),demo,token,"en");
            assertEquals(250,seats.size());assertEquals("Section 1",seats.getFirst().section());assertEquals("Floor 1",seats.getFirst().floor());
        }
        assertEquals(2L,db.queryForObject("select count(*) from venue_seat where venue_id=10 and row_label='A' and seat_number=1",Long.class));
        var before=db.queryForList("select id,starts_at,opens_at,closes_at from performance order by id");
        long seats=db.queryForObject("select count(*) from venue_seat",Long.class),inventory=db.queryForObject("select count(*) from performance_seat",Long.class);
        venueSeed.run();venueSeed.run();
        assertEquals(seats,db.queryForObject("select count(*) from venue_seat",Long.class));assertEquals(inventory,db.queryForObject("select count(*) from performance_seat",Long.class));
        assertEquals(before,db.queryForList("select id,starts_at,opens_at,closes_at from performance order by id"));
    }
    @Test void crossSectionBookingExpiryAndPerformanceIsolation() {
        String token=admitted(1001,demo),otherPerformanceToken=admitted(1002,demo);
        var held=bookings.hold(1001,demo,token,new HoldRequest(List.of(10000001L,10000251L)));
        assertEquals(260000,held.total());assertEquals(2,held.seats().stream().map(Seat::sectionId).distinct().count());
        error("SEAT_LIMIT_EXCEEDED",()->bookings.hold(1001,demo,token,new HoldRequest(List.of(10000002L,10000003L,10000252L))));
        assertEquals(249,bookings.sections(1001,demo,token,"ko").items().getFirst().availableSeats());
        assertEquals(250,bookings.sections(1002,demo,otherPerformanceToken,"ko").items().getFirst().availableSeats());
        assertEquals(155000,bookings.sectionSeats(1002,1000001,demo,otherPerformanceToken,"ko").getFirst().price());
        error("QUEUE_ADMISSION_REQUIRED",()->bookings.sectionSeats(1002,1000001,demo,token,"ko"));
        error("NOT_FOUND",()->bookings.sectionSeats(1001,2000001,demo,token,"ko"));
        var reservation=bookings.reserve(demo,token,"section-booking",new ReservationRequest(held.id()));
        assertEquals(reservation.id(),bookings.reserve(demo,token,"section-booking",new ReservationRequest(held.id())).id());
        assertEquals(2,reservation.seats().stream().map(ReservationSeat::sectionId).distinct().count());
        venueSeed.run();assertEquals("RESERVED",bookings.sectionSeats(1001,1000001,demo,token,"ko").getFirst().status());
        var expiring=bookings.hold(1001,demo,token,new HoldRequest(List.of(10000002L)));
        assertEquals(248,bookings.sections(1001,demo,token,"ko").items().getFirst().availableSeats());
        db.update("update seat_hold set expires_at=? where id=?",Store.utc(Instant.now().minusSeconds(1)),expiring.id());
        assertEquals(249,bookings.sections(1001,demo,token,"ko").items().getFirst().availableSeats());
        error("HOLD_EXPIRED",()->bookings.reserve(demo,token,"expired-section",new ReservationRequest(expiring.id())));
        var reclaimed=bookings.hold(1001,demo,token,new HoldRequest(List.of(10000002L)));
        assertNotEquals(expiring.id(),reclaimed.id());bookings.release(reclaimed.id(),demo);
        error("SEAT_CONFLICT",()->bookings.hold(1001,demo,token,new HoldRequest(List.of(10000003L,20000001L))));
        assertEquals("AVAILABLE",bookings.sectionSeats(1001,1000001,demo,token,"ko").get(2).status());
        bookings.cancel(reservation.id(),demo);assertEquals(500,bookings.seats(1001,demo,token).size());
        assertEquals(500,bookings.sections(1001,demo,token,"ko").items().stream().mapToLong(Section::availableSeats).sum());
    }
    @Test void largeVenueConcurrentSeatHoldOnlyOneWins() throws Exception {
        String token=admitted(3001,demo);
        db.update("insert into queue_entry(id,performance_id,user_id,ticket,state,token,expires_at) values(?,3001,?,2,'ADMITTED',?,?)",Store.id(),other,"large-other-admission",Store.utc(Instant.now().plusSeconds(600)));
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<String> a=()->{start.await();try{bookings.hold(3001,demo,token,new HoldRequest(List.of(30000001L)));return "OK";}catch(ApiError e){return e.code;}};
            Callable<String> b=()->{start.await();try{bookings.hold(3001,other,"large-other-admission",new HoldRequest(List.of(30000001L)));return "OK";}catch(ApiError e){return e.code;}};
            var first=pool.submit(a);var second=pool.submit(b);start.countDown();var results=List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));
            assertEquals(1,Collections.frequency(results,"OK"));assertEquals(1,Collections.frequency(results,"SEAT_CONFLICT"));
        }
        assertEquals(249,bookings.sections(3001,demo,token,"ko").items().getFirst().availableSeats());
        assertEquals(30000,bookings.seats(3001,demo,token).size());
    }
    @Test void sectionHttpContractPermissionsAndInventoryLimit() throws Exception {
        String access=auth.login(new Login("demo","Demo1234!")).auth().accessToken(),token=admitted(3001,demo);
        assertEquals(401,request("GET","/api/v1/performances/3001/sections",null,null,Map.of()).statusCode());
        var overview=request("GET","/api/v1/performances/3001/sections?lang=en",null,access,Map.of("X-Admission-Token",token));
        assertEquals(200,overview.statusCode());assertEquals(30000,node(overview).path("venue").path("capacity").asLong());assertEquals(3,node(overview).path("floors").size());assertEquals(120,node(overview).path("items").size());
        var section=request("GET","/api/v1/performances/3001/sections/3000001/seats?lang=en",null,access,Map.of("X-Admission-Token",token));
        assertEquals(200,section.statusCode());assertEquals(250,node(section).path("total").asLong());assertEquals("3000001",node(section).path("items").get(0).path("sectionId").asString());
        assertEquals(429,request("GET","/api/v1/performances/3001/seats",null,access,Map.of("X-Admission-Token",token)).statusCode());
        assertEquals(403,request("GET","/api/v1/performances/2001/sections",null,access,Map.of("X-Admission-Token","fake")).statusCode());
        String hallToken=admitted(2001,demo);
        assertEquals(404,request("GET","/api/v1/performances/2001/sections/3000001/seats",null,access,Map.of("X-Admission-Token",hallToken)).statusCode());
        var docs=node(request("GET","/v3/api-docs",null,null,Map.of()));assertTrue(docs.path("paths").has("/api/v1/performances/{performanceId}/sections/{sectionId}/seats"));
    }
    @Test void pollingDoesNotPromoteAndConcurrentWorkersRespectCapacity() throws Exception {
        var first=bookings.enter(101,demo);var second=bookings.enter(101,other);
        assertEquals("WAITING",first.state());assertEquals(1L,first.position());assertEquals(2L,second.position());
        assertEquals(1,second.aheadCount());assertNull(second.admissionToken());
        for(int i=0;i<3;i++) assertEquals("WAITING",bookings.queue(101,demo).state());
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Void> call=()->{start.await();worker.expire();return null;};
            var a=pool.submit(call);var b=pool.submit(call);start.countDown();a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
        }
        assertEquals(1L,db.queryForObject("select count(*) from queue_entry where performance_id=101 and state='ADMITTED'",Long.class));
        assertEquals("ADMITTED",bookings.queue(101,demo).state());assertNull(bookings.queue(101,demo).position());
        assertEquals(1L,bookings.queue(101,other).position());
        db.update("update queue_entry set expires_at=? where performance_id=101 and user_id=?",Store.utc(Instant.now().minusSeconds(1)),demo);
        assertEquals("EXPIRED",bookings.queue(101,demo).state());assertNull(bookings.queue(101,demo).admissionToken());
        assertEquals("ADMITTED",db.queryForObject("select state from queue_entry where performance_id=101 and user_id=?",String.class,demo));
        var reentered=bookings.enter(101,demo);assertTrue(reentered.ticketNumber()>second.ticketNumber());assertEquals(2L,reentered.position());
        worker.expire();assertEquals("ADMITTED",bookings.queue(101,other).state());assertEquals("WAITING",bookings.queue(101,demo).state());
    }
    @Test void readsCompleteWhilePerformanceIsLockedAndDoNotCleanInventory() throws Exception {
        String token=admitted(demo);bookings.enter(101,other);
        var hold=bookings.hold(101,demo,token,new HoldRequest(List.of(1L)));
        db.update("update seat_hold set expires_at=? where id=?",Store.utc(Instant.now().minusSeconds(1)),hold.id());
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var owner=pool.submit(()->tx.executeWithoutResult(status->{
                db.queryForList("select id from performance where id=101 for update");locked.countDown();
                try {if(!release.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("Lock fixture timed out");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
            }));
            try {
                assertTrue(locked.await(5,TimeUnit.SECONDS));
                var reader=pool.submit(()->{
                    assertEquals("WAITING",bookings.queue(101,other).state());
                    assertEquals("AVAILABLE",bookings.seats(101,demo,token).getFirst().status());
                });
                reader.get(3,TimeUnit.SECONDS);
                assertEquals(hold.id(),db.queryForObject("select hold_id from performance_seat where performance_id=101 and seat_id=1",String.class));
                assertEquals("ACTIVE",db.queryForObject("select state from seat_hold where id=?",String.class,hold.id()));
                // Busy performances are skipped rather than blocking worker progress.
                pool.submit(worker::expire).get(3,TimeUnit.SECONDS);
            } finally {release.countDown();}
            owner.get(5,TimeUnit.SECONDS);
        }
        worker.expire();assertNull(db.queryForObject("select hold_id from performance_seat where performance_id=101 and seat_id=1",String.class));
        assertEquals("EXPIRED",db.queryForObject("select state from seat_hold where id=?",String.class,hold.id()));
    }
    @Test void workerExpiresClosedQueuesAndReadsMaskExpiredAdmission() {
        String token=admitted(demo);bookings.enter(101,other);
        var original=db.queryForObject("select closes_at from performance where id=101",java.time.OffsetDateTime.class);
        db.update("update performance set closes_at=? where id=101",Store.utc(Instant.now().minusSeconds(1)));
        try {
            assertEquals("EXPIRED",bookings.queue(101,demo).state());assertEquals("EXPIRED",bookings.queue(101,other).state());
            error("BOOKING_CLOSED",()->bookings.seats(101,demo,token));worker.expire();
            assertEquals(2L,db.queryForObject("select count(*) from queue_entry where performance_id=101 and state='EXPIRED' and token is null",Long.class));
        } finally {db.update("update performance set closes_at=? where id=101",original);}
    }
    @Test void httpPollingAndSeatReadLimitsAreScopedPerUserAndEndpoint() throws Exception {
        String username="poll_"+UUID.randomUUID().toString().replace("-","");auth.signUp(new SignUp(username,"Ticket1234!","조회 제한"));
        var login=auth.login(new Login(username,"Ticket1234!"));long user=Long.parseLong(login.auth().user().id());String access=login.auth().accessToken();
        bookings.enter(101,user);worker.expire();String token=bookings.queue(101,user).admissionToken();
        var first=request("GET","/api/v1/performances/101/queue/me",null,access,Map.of());assertEquals(200,first.statusCode());
        assertEquals("no-store",first.headers().firstValue("cache-control").orElseThrow());
        var repeated=request("GET","/api/v1/performances/101/queue/me",null,access,Map.of());assertEquals(429,repeated.statusCode());assertEquals("RATE_LIMITED",node(repeated).path("code").asString());
        assertTrue(Integer.parseInt(repeated.headers().firstValue("Retry-After").orElseThrow())>=1);
        assertEquals(429,request("GET","/api/v1/performances/000101/queue/me",null,access,Map.of()).statusCode());
        var seats=request("GET","/api/v1/performances/101/seats",null,access,Map.of("X-Admission-Token",token));assertEquals(200,seats.statusCode());assertEquals("2",seats.headers().firstValue("X-Poll-After").orElseThrow());
        assertEquals(429,request("GET","/api/v1/performances/101/seats",null,access,Map.of("X-Admission-Token",token)).statusCode());
        // Rejected reads do not prevent releasing the queue (writes use their own policy).
        assertEquals(204,request("DELETE","/api/v1/performances/101/queue/me",null,access,Map.of()).statusCode());
    }
    @Test void duplicateQueueAdmissionOwnershipAndPromotion() {
        admitted(demo);var first=bookings.queue(101,demo);var repeat=bookings.enter(101,demo);
        assertEquals(first.ticketNumber(),repeat.ticketNumber());assertEquals(first.admissionToken(),repeat.admissionToken());
        var second=bookings.enter(101,other);assertEquals("WAITING",second.state());assertNull(second.estimatedWaitSeconds());assertEquals(0,second.aheadCount());
        error("QUEUE_ADMISSION_REQUIRED",()->bookings.hold(101,other,first.admissionToken(),new HoldRequest(List.of(1L))));
        error("QUEUE_ADMISSION_REQUIRED",()->bookings.hold(102,demo,first.admissionToken(),new HoldRequest(List.of(1L))));
        bookings.leave(101,demo);assertEquals("WAITING",bookings.queue(101,other).state());worker.expire();assertEquals("ADMITTED",bookings.queue(101,other).state());
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
    byte[] posterPng(int width,int height) throws Exception {
        var out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(width,height,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",out);return out.toByteArray();
    }
    String eventRegistrationJson(long section,boolean invalidDate) {
        Instant now=Instant.now();
        return json.writeValueAsString(Map.of("titles",Map.of("ko","관리자 이미지 등록 "+UUID.randomUUID()),"descriptions",Map.of("ko","이미지가 포함된 행사"),"category","CONCERT","featuredRank",0,
            "performances",List.of(Map.of("venueId",10,"startsAt",now.plusSeconds(86400*30).toString(),"opensAt",now.minusSeconds(60).toString(),"closesAt",(invalidDate?now.minusSeconds(120):now.plusSeconds(86400*29)).toString(),"sectionPrices",List.of(Map.of("sectionId",section,"grade","VIP","price",150000),Map.of("sectionId",1000002,"grade","R","price",110000))))));
    }
    HttpResponse<String> registerEvent(String event,byte[] image,String mime,String access) throws Exception {
        String boundary="ticketbuy-"+UUID.randomUUID();var out=new java.io.ByteArrayOutputStream();
        out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"event\"\r\nContent-Type: application/json\r\n\r\n"+event+"\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if(image!=null) {
            out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"image\"; filename=\"../../poster.png\"\r\nContent-Type: "+mime+"\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.write(image);out.write("\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        out.write(("--"+boundary+"--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/admin/events")).header("Content-Type","multipart/form-data; boundary="+boundary);
        if(access!=null)builder.header("Authorization","Bearer "+access);
        return HttpClient.newHttpClient().send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void adminCanRegisterImageEventAndUseExistingBookingFlow() throws Exception {
        String admin=auth.login(new Login("admin","Demo1234!")).auth().accessToken(),user=auth.login(new Login("demo","Demo1234!")).auth().accessToken();
        String body=eventRegistrationJson(1000001,false);byte[] png=posterPng(4,6);
        assertEquals(401,registerEvent(body,png,"image/png",null).statusCode());assertEquals(403,registerEvent(body,png,"image/png",user).statusCode());
        assertEquals(200,request("GET","/api/v1/admin/venues",null,admin,Map.of()).statusCode());assertEquals(403,request("GET","/api/v1/admin/venues",null,user,Map.of()).statusCode());
        var layout=request("GET","/api/v1/admin/venues/10/sections",null,admin,Map.of());assertEquals(200,layout.statusCode());assertEquals(2,node(layout).path("items").size());
        var created=registerEvent(body,png,"image/png",admin);assertEquals(201,created.statusCode());
        var result=node(created);long event=result.path("event").path("id").asLong(),performance=result.path("performances").get(0).path("id").asLong();
        assertTrue(event>=100000);assertTrue(performance>=100000);assertEquals("ON_SALE",result.path("event").path("status").asString());
        String imagePath=URI.create(result.path("event").path("posterUrl").asString()).getPath();
        assertTrue(imagePath.startsWith("/api/v1/event-images/"));assertFalse(imagePath.contains("poster.png"));
        var image=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+imagePath)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,image.statusCode());assertEquals("image/png",image.headers().firstValue("content-type").orElseThrow());assertEquals("nosniff",image.headers().firstValue("x-content-type-options").orElseThrow());
        assertEquals(4,javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(image.body())).getWidth());
        var cached=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+imagePath)).header("If-None-Match",image.headers().firstValue("etag").orElseThrow()).GET().build(),HttpResponse.BodyHandlers.ofByteArray());assertEquals(304,cached.statusCode());
        assertEquals(200,request("GET","/api/v1/events/"+event,null,null,Map.of()).statusCode());
        String admission=admitted(performance,demo);assertEquals(500,bookings.sections(performance,demo,admission,"ko").items().stream().mapToLong(Section::totalSeats).sum());
        var held=bookings.hold(performance,demo,admission,new HoldRequest(List.of(10000001L,10000251L)));assertEquals(260000,held.total());
        assertEquals("CONFIRMED",bookings.reserve(demo,admission,"admin-event-created",new ReservationRequest(held.id())).status());
        posterSeed.run();assertEquals(result.path("event").path("posterUrl").asString(),catalog.event(event,"ko").posterUrl());
        var spec=node(request("GET","/v3/api-docs",null,null,Map.of()));
        assertTrue(spec.path("paths").path("/api/v1/admin/events").path("post").path("requestBody").path("content").has("multipart/form-data"));
    }
    @Test void registrationRejectsInvalidImagesAndPlansWithoutPersistingOrphans() throws Exception {
        String admin=auth.login(new Login("admin","Demo1234!")).auth().accessToken();String body=eventRegistrationJson(1000001,false);byte[] png=posterPng(4,6);
        long images=db.queryForObject("select count(*) from event_image",Long.class),events=db.queryForObject("select count(*) from event",Long.class);
        assertEquals(415,request("POST","/api/v1/admin/events",body,admin,Map.of()).statusCode());
        assertEquals(400,registerEvent(body,null,"image/png",admin).statusCode());assertEquals(400,registerEvent(body,new byte[0],"image/png",admin).statusCode());
        assertEquals(415,registerEvent(body,"<svg/>".getBytes(java.nio.charset.StandardCharsets.UTF_8),"image/svg+xml",admin).statusCode());
        assertEquals(400,registerEvent(body,new byte[]{1,2,3},"image/png",admin).statusCode());assertEquals(415,registerEvent(body,png,"image/jpeg",admin).statusCode());
        assertEquals(400,registerEvent(body,posterPng(6001,1),"image/png",admin).statusCode());
        assertEquals(413,registerEvent(body,new byte[EventImages.MAX_BYTES+1],"image/png",admin).statusCode());
        var schedule=registerEvent(eventRegistrationJson(1000001,true),png,"image/png",admin);assertEquals(400,schedule.statusCode());assertEquals("INVALID_SCHEDULE",node(schedule).path("code").asString());
        var section=registerEvent(eventRegistrationJson(2000001,false),png,"image/png",admin);assertEquals(400,section.statusCode());assertEquals("INVALID_SECTION",node(section).path("code").asString());
        long previous=db.queryForObject("select next_id from id_allocator where name='performance'",Long.class);
        try {
            db.update("update id_allocator set next_id=101 where name='performance'");
            assertEquals(500,registerEvent(body,png,"image/png",admin).statusCode());
        } finally {db.update("update id_allocator set next_id=? where name='performance'",previous);}
        assertEquals(images,db.queryForObject("select count(*) from event_image",Long.class));assertEquals(events,db.queryForObject("select count(*) from event",Long.class));
    }
    @Test void seededEventsHaveReachablePostersAndPosterSeedIsIdempotent() throws Exception {
        for(long event:List.of(1L,2L,3L,4L,5L,10L,20L,30L)) {
            String url=catalog.event(event,"ko").posterUrl();assertNotNull(url);String path=URI.create(url).getPath();assertTrue(path.startsWith("/posters/"));
            var response=request("GET",path,null,null,Map.of());assertEquals(200,response.statusCode());assertTrue(response.body().contains("<svg"));
        }
        posterSeed.run();assertTrue(catalog.event(30,"ko").posterUrl().endsWith("/posters/stadium.svg"));
    }
    @Test void contentManagementRequiresAdminForEveryWriteOperation() throws Exception {
        String user=auth.login(new Login("demo","Demo1234!")).auth().accessToken();
        String admin=auth.login(new Login("admin","Demo1234!")).auth().accessToken();
        String body=json.writeValueAsString(Map.of("category","GUIDE","titles",Map.of("ko","관리자 전용 콘텐츠"),"bodies",Map.of("ko","권한 검증 내용")));
        for(String resource:List.of("faqs","posts")) {
            String collection="/api/v1/admin/"+resource;
            var created=request("POST",collection,body,admin,Map.of());assertEquals(201,created.statusCode());
            long id=node(created).path("id").asLong();String detail=collection+"/"+id;
            for(String method:List.of("POST","PATCH","DELETE")) {
                String path=method.equals("POST")?collection:detail;
                String requestBody=method.equals("DELETE")?null:body;
                var anonymous=request(method,path,requestBody,null,Map.of());
                assertEquals(401,anonymous.statusCode());assertEquals("AUTHENTICATION_REQUIRED",node(anonymous).path("code").asString());
                var forbidden=request(method,path,requestBody,user,Map.of());
                assertEquals(403,forbidden.statusCode());assertEquals("FORBIDDEN",node(forbidden).path("code").asString());assertTrue(node(forbidden).has("traceId"));
            }
            String kind=resource.equals("faqs")?"FAQ":"POST";
            assertEquals(1L,db.queryForObject("select count(*) from content_entry where id=? and kind=?",Long.class,id,kind));
            assertEquals("관리자 전용 콘텐츠",catalog.content(kind,id,"ko").title());
            assertEquals(200,request("GET","/api/v1/"+resource,null,null,Map.of()).statusCode());
            assertEquals(200,request("GET","/api/v1/"+resource,null,user,Map.of()).statusCode());
            assertEquals(200,request("PATCH",detail,body,admin,Map.of()).statusCode());
            assertEquals(204,request("DELETE",detail,null,admin,Map.of()).statusCode());
        }
    }
    @Test void contentServiceRejectsUserEvenWithoutAdminController() {
        var previous=org.springframework.security.core.context.SecurityContextHolder.getContext();
        var context=org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("demo",null,
            List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"))));
        org.springframework.security.core.context.SecurityContextHolder.setContext(context);
        try {
            var req=new ContentRequest(ContentCategory.GUIDE,Map.of("ko","권한 검사"),Map.of("ko","내용"));
            assertThrows(org.springframework.security.access.AccessDeniedException.class,()->catalog.save("FAQ",null,req));
            assertThrows(org.springframework.security.access.AccessDeniedException.class,()->catalog.save("POST",null,req));
            assertThrows(org.springframework.security.access.AccessDeniedException.class,()->catalog.delete("FAQ",-1));
            assertThrows(org.springframework.security.access.AccessDeniedException.class,()->catalog.delete("POST",-1));
        } finally {org.springframework.security.core.context.SecurityContextHolder.setContext(previous);}
    }
    @Test void signupValidatesHashesAndAllowsLoginWithoutGrantingAdmin() throws Exception {
        var docs=node(request("GET","/v3/api-docs",null,null,Map.of()));
        assertTrue(docs.path("paths").path("/api/v1/auth/signup").path("post").path("responses").has("201"));
        String username="signup_"+UUID.randomUUID().toString().replace("-","");
        String password="Ticket1234!";
        String body=json.writeValueAsString(Map.of("username",username,"password",password,"name","  신규 사용자  ","role","ADMIN"));
        var created=request("POST","/api/v1/auth/signup",body,null,Map.of("Origin","http://localhost:5173"));
        assertEquals(201,created.statusCode());assertEquals("신규 사용자",node(created).path("name").asString());
        assertEquals("USER",node(created).path("role").asString());
        assertTrue(node(created).has("id"));assertFalse(node(created).has("password"));assertFalse(node(created).has("accessToken"));
        assertTrue(created.headers().firstValue("set-cookie").isEmpty());
        var stored=db.queryForMap("select * from app_user where username=?",username);
        assertEquals("USER",stored.get("role"));assertNotEquals(password,stored.get("password_hash"));
        assertTrue(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().matches(password,stored.get("password_hash").toString()));
        var duplicate=request("POST","/api/v1/auth/signup",body,null,Map.of());
        assertEquals(409,duplicate.statusCode());assertEquals("USERNAME_ALREADY_EXISTS",node(duplicate).path("code").asString());assertTrue(node(duplicate).has("traceId"));
        var loggedIn=request("POST","/api/v1/auth/login",json.writeValueAsString(Map.of("username",username,"password",password)),null,Map.of());
        assertEquals(200,loggedIn.statusCode());String token=node(loggedIn).path("accessToken").asString();
        assertEquals(node(created).path("id"),node(request("GET","/api/v1/users/me",null,token,Map.of())).path("id"));
        assertEquals(403,request("POST","/api/v1/admin/posts","{}",token,Map.of()).statusCode());
        var invalidBodies=List.of(
            Map.of("username","abc","password",password,"name","사용자"),
            Map.of("username","bad username","password",password,"name","사용자"),
            Map.of("username",username,"password","short1","name","사용자"),
            Map.of("username",username,"password","onlyletters","name","사용자"),
            Map.of("username",username,"password","가나다라마바사123","name","사용자"),
            Map.of("username",username,"password","a1"+"x".repeat(71),"name","사용자"),
            Map.of("username",username,"password",password,"name","   "),
            Map.of("username",username,"password",password,"name","x".repeat(101)),
            Map.of("username",username,"name","사용자"));
        for(var invalid:invalidBodies) {
            var result=request("POST","/api/v1/auth/signup",json.writeValueAsString(invalid),null,Map.of());
            assertEquals(400,result.statusCode());assertEquals("INVALID_REQUEST",node(result).path("code").asString());
        }
    }
    @Test void roleCodesAreReturnedOnLoginRefreshAndMe() throws Exception {
        for(var account:Map.of("demo","USER","admin","ADMIN").entrySet()) {
            var login=request("POST","/api/v1/auth/login",json.writeValueAsString(Map.of("username",account.getKey(),"password","Demo1234!")),null,Map.of());
            assertEquals(200,login.statusCode());assertEquals(account.getValue(),node(login).path("user").path("role").asString());
            String access=node(login).path("accessToken").asString();
            var me=request("GET","/api/v1/users/me",null,access,Map.of());
            assertEquals(200,me.statusCode());assertEquals(account.getValue(),node(me).path("role").asString());
            String cookie=login.headers().firstValue("set-cookie").orElseThrow().split(";",2)[0];
            var refreshed=request("POST","/api/v1/auth/refresh",null,null,Map.of("Cookie",cookie));
            assertEquals(200,refreshed.statusCode());assertEquals(account.getValue(),node(refreshed).path("user").path("role").asString());
        }
    }
    @Test void concurrentSignupCreatesOnlyOneAccount() throws Exception {
        String username="race_"+UUID.randomUUID().toString().replace("-","");
        var req=new SignUp(username,"Ticket1234!","동시 가입");var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<String> call=()->{start.await();try {auth.signUp(req);return "OK";}catch(ApiError e){return e.code;}};
            var a=pool.submit(call);var b=pool.submit(call);start.countDown();
            var results=List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
            assertEquals(1,Collections.frequency(results,"OK"));assertEquals(1,Collections.frequency(results,"USERNAME_ALREADY_EXISTS"));
        }
        assertEquals(1L,db.queryForObject("select count(*) from app_user where username=?",Long.class,username));
    }
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
        var entered=request("POST","/api/v1/performances/101/queue",null,access,Map.of());assertEquals(200,entered.statusCode());assertEquals("WAITING",node(entered).path("state").asString());worker.expire();
        var queue=request("GET","/api/v1/performances/101/queue/me",null,access,Map.of());assertEquals(200,queue.statusCode());String admission=node(queue).path("admissionToken").asString();
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
