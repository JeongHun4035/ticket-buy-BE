package com.ticketing.ticketbuybe.infrastructure.persistence;

import com.ticketing.ticketbuybe.bootstrap.seed.DevPosterSeed;
import com.ticketing.ticketbuybe.bootstrap.seed.DevSeed;
import com.ticketing.ticketbuybe.bootstrap.seed.VenueScaleSeed;

import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class VenueMigrationTests {
    @Test void productionNeverLoadsDevelopmentSeedsEvenWithDevAlsoEnabled() {
        try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("dev","prod");
            context.register(DevSeed.class,VenueScaleSeed.class,DevPosterSeed.class);context.refresh();
            assertTrue(context.getBeansOfType(DevSeed.class).isEmpty());assertTrue(context.getBeansOfType(VenueScaleSeed.class).isEmpty());assertTrue(context.getBeansOfType(DevPosterSeed.class).isEmpty());
        }
    }
    @Test void upgradingV2PreservesSeatIdsAndReservationsAndAllowsRepeatedRowNumbers() {
        String url="jdbc:h2:mem:upgrade_"+java.util.UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try(var datasource=new com.zaxxer.hikari.HikariDataSource()) {
        datasource.setJdbcUrl(url);datasource.setUsername("sa");datasource.setPassword("");datasource.setMaximumPoolSize(2);datasource.setMinimumIdle(1);
        Flyway.configure().dataSource(datasource).target("2").load().migrate();
        var db=new JdbcTemplate(datasource);
        db.update("insert into app_user(id,username,password_hash,name,role) values(1,'legacy','unused','기존 사용자','USER')");
        db.update("insert into venue(id,names) values(1,'{\"ko\":\"기존 공연장\"}')");
        db.update("insert into venue_seat(id,venue_id,row_label,seat_number,x,y) values(1,1,'A',1,1,1)");
        db.update("insert into event(id,titles,descriptions,category) values(1,'{\"ko\":\"공연\"}','{\"ko\":\"설명\"}','CONCERT')");
        Instant now=Instant.now();
        db.update("insert into performance(id,event_id,venue_id,starts_at,opens_at,closes_at) values(101,1,1,?,?,?)",Store.utc(now.plusSeconds(7200)),Store.utc(now.minusSeconds(7200)),Store.utc(now.plusSeconds(3600)));
        db.update("insert into seat_hold(id,performance_id,user_id,expires_at,state) values('old-hold',101,1,?,'CONSUMED')",Store.utc(now.plusSeconds(300)));
        db.update("insert into reservation(id,user_id,performance_id,hold_id,idempotency_key,status,total,created_at) values('old-reservation',1,101,'old-hold','old-key','CONFIRMED',150000,?)",Store.utc(now));
        db.update("insert into reservation_item(reservation_id,seat_id,grade,price) values('old-reservation',1,'VIP',150000)");
        db.update("insert into performance_seat(performance_id,seat_id,grade,price,reservation_id) values(101,1,'VIP',150000,'old-reservation')");
        Flyway.configure().dataSource(datasource).load().migrate();
        assertEquals(100001L,db.queryForObject("select section_id from venue_seat where id=1",Long.class));
        assertEquals("old-reservation",db.queryForObject("select reservation_id from performance_seat where performance_id=101 and seat_id=1",String.class));
        assertEquals(1L,db.queryForObject("select seat_id from reservation_item where reservation_id='old-reservation'",Long.class));
        db.update("insert into venue_section(id,venue_id,floor_id,names,x,y,width,height,sort_order) values(100002,1,1001,'{\"ko\":\"새 구역\"}',0,0,100,100,2)");
        db.update("insert into venue_seat(id,venue_id,section_id,row_label,seat_number,x,y) values(2,1,100002,'A',1,1,1)");
        assertEquals(2L,db.queryForObject("select count(*) from venue_seat where row_label='A' and seat_number=1",Long.class));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->db.update("insert into venue_seat(id,venue_id,section_id,row_label,seat_number,x,y) values(3,1,100002,'A',1,1,1)"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->db.update("insert into performance_seat(performance_id,seat_id,section_id,venue_id,grade,price) values(101,2,100001,1,'VIP',1)"));
        }
    }
}
