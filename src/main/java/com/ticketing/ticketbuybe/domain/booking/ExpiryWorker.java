package com.ticketing.ticketbuybe.domain.booking;

import com.ticketing.ticketbuybe.infrastructure.persistence.Store;

import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import static com.ticketing.ticketbuybe.infrastructure.persistence.Store.utc;
import org.springframework.beans.factory.annotation.Value;


@Component
public class ExpiryWorker {
    final Store s; final BookingService bookings; final TransactionTemplate tx;
    final int capacity;
    public ExpiryWorker(Store s,BookingService bookings,TransactionTemplate tx,@Value("${app.queue-capacity}") int capacity){this.s=s;this.bookings=bookings;this.tx=tx;this.capacity=capacity;}
    @Scheduled(fixedDelayString="${app.cleanup-ms:1000}",initialDelayString="${app.cleanup-initial-delay-ms:1000}")
    public void expire() {
        var now=utc(Instant.now());
        var rows=s.jdbc().queryForList("""
            select p.id from performance p where
            exists (select 1 from seat_hold h where h.performance_id=p.id and h.state='ACTIVE' and h.expires_at<=?)
            or exists (select 1 from queue_entry q where q.performance_id=p.id and q.state='ADMITTED' and q.expires_at<=?)
            or (p.closes_at<=? and exists (select 1 from queue_entry q where q.performance_id=p.id and q.state in ('WAITING','ADMITTED')))
            or (p.opens_at<=? and p.closes_at>? and exists (select 1 from queue_entry q where q.performance_id=p.id and q.state='WAITING')
                and (select count(*) from queue_entry q where q.performance_id=p.id and q.state='ADMITTED' and q.expires_at>?)<?)
            order by p.id
            """,now,now,now,now,now,now,capacity);
        for(var row:rows) {
            long performance=Store.num(row,"id");
            try {
                tx.executeWithoutResult(status->{
                    // Another worker or booking transaction owns the row: retry on a later tick.
                    if(!s.jdbc().queryForList("select id from performance where id=? for update skip locked",performance).isEmpty()) bookings.maintain(performance);
                });
            } catch(org.springframework.dao.DataAccessException e) {
                org.slf4j.LoggerFactory.getLogger(ExpiryWorker.class).warn("Queue maintenance failed for performance {} ({})",performance,e.getClass().getSimpleName());
            }
        }
    }
    @Scheduled(fixedDelayString="${app.session-cleanup-ms:60000}",initialDelayString="${app.cleanup-initial-delay-ms:1000}")
    public void expireSessions() {
        s.jdbc().update("delete from auth_session where refresh_expires<=?",utc(Instant.now()));
    }
}
