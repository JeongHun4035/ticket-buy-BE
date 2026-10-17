package com.ticketing.ticketbuybe;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import static com.ticketing.ticketbuybe.Store.utc;

@Configuration @EnableScheduling
class Scheduling {}

@Component
public class ExpiryWorker {
    final Store s; final BookingService bookings; final TransactionTemplate tx;
    public ExpiryWorker(Store s,BookingService bookings,TransactionTemplate tx){this.s=s;this.bookings=bookings;this.tx=tx;}
    @Scheduled(fixedDelayString="${app.cleanup-ms:10000}")
    public void expire() {
        var rows=s.db.queryForList("select distinct performance_id from seat_hold where state='ACTIVE' and expires_at<=? union select distinct performance_id from queue_entry where state='ADMITTED' and expires_at<=?",utc(Instant.now()),utc(Instant.now()));
        for(var row:rows) tx.executeWithoutResult(status->{long performance=Store.num(row,"performance_id");s.lockPerformance(performance);bookings.maintain(performance);});
        s.db.update("delete from auth_session where refresh_expires<=?",utc(Instant.now()));
    }
}
