package com.ticketing.ticketbuybe;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import java.time.*;
import java.util.*;
import com.ticketing.ticketbuybe.Contracts.Queue;
import static com.ticketing.ticketbuybe.Store.*;
import static com.ticketing.ticketbuybe.Contracts.*;

/** All queue/inventory mutations lock the performance first. This ordering is shared by every path. */
@Service
public class BookingService {
    final Store s;
    final int capacity,admissionSeconds,holdSeconds;
    public BookingService(Store s,@Value("${app.queue-capacity}") int capacity,
        @Value("${app.admission-seconds}") int admissionSeconds,@Value("${app.hold-seconds}") int holdSeconds) {
        if(capacity<1||admissionSeconds<1||holdSeconds<1) throw new IllegalArgumentException("Invalid booking settings");
        this.s=s; this.capacity=capacity; this.admissionSeconds=admissionSeconds; this.holdSeconds=holdSeconds;
    }
    void sales(Map<String,Object> p) {
        Instant now=Instant.now();
        if(now.isBefore(instant(p,"opens_at"))) throw new ApiError(409,"BOOKING_NOT_OPEN","예매 오픈 전입니다.");
        if(!now.isBefore(instant(p,"closes_at"))) throw new ApiError(409,"BOOKING_CLOSED","예매가 마감되었습니다.");
    }
    void maintain(long performance) {
        var now=utc(Instant.now());
        s.db.update("update performance_seat set hold_id=null where performance_id=? and hold_id in (select id from seat_hold where expires_at<=? and state='ACTIVE')",performance,now);
        s.db.update("update seat_hold set state='EXPIRED' where performance_id=? and state='ACTIVE' and expires_at<=?",performance,now);
        s.db.update("update queue_entry set state='EXPIRED',token=null where performance_id=? and state='ADMITTED' and expires_at<=?",performance,now);
        var p=s.one("select * from performance where id=?",performance);
        if(!Instant.now().isBefore(instant(p,"closes_at"))) {
            s.db.update("update queue_entry set state='EXPIRED',token=null where performance_id=? and state in ('WAITING','ADMITTED')",performance);
            return;
        }
        if(Instant.now().isBefore(instant(p,"opens_at"))) return;
        int free=capacity-(int)s.count("select count(*) from queue_entry where performance_id=? and state='ADMITTED'",performance);
        var waiters=s.db.queryForList("select id from queue_entry where performance_id=? and state='WAITING' order by ticket limit ?",performance,Math.max(0,free));
        for(var row:waiters) s.db.update("update queue_entry set state='ADMITTED',token=?,expires_at=? where id=?",AuthService.token(),utc(Instant.now().plusSeconds(admissionSeconds)),row.get("id"));
    }
    @Transactional
    public Queue enter(long performance,long user) {
        var p=s.lockPerformance(performance); sales(p); maintain(performance);
        var rows=s.db.queryForList("select * from queue_entry where performance_id=? and user_id=?",performance,user);
        if(rows.isEmpty()||Set.of("EXPIRED","CANCELLED").contains(rows.getFirst().get("state"))) {
            long ticket=num(p,"next_ticket")+1;
            s.db.update("update performance set next_ticket=? where id=?",ticket,performance);
            if(rows.isEmpty()) s.db.update("insert into queue_entry(id,performance_id,user_id,ticket,state) values(?,?,?,?,'WAITING')",id(),performance,user,ticket);
            else s.db.update("update queue_entry set ticket=?,state='WAITING',token=null,expires_at=null where performance_id=? and user_id=?",ticket,performance,user);
            maintain(performance);
        }
        return queueView(performance,user);
    }
    @Transactional
    public Queue queue(long performance,long user) {
        s.lockPerformance(performance); maintain(performance); return queueView(performance,user);
    }
    Queue queueView(long performance,long user) {
        var q=s.one("select * from queue_entry where performance_id=? and user_id=?",performance,user);
        long ahead="WAITING".equals(q.get("state"))?s.count("select count(*) from queue_entry where performance_id=? and state='WAITING' and ticket<?",performance,num(q,"ticket")):0;
        boolean admitted="ADMITTED".equals(q.get("state"));
        return new Queue(q.get("state").toString(),num(q,"ticket"),ahead,null,admitted?q.get("token").toString():null,
            admitted?instant(q,"expires_at"):null,admitted?30:3);
    }
    @Transactional
    public void leave(long performance,long user) {
        s.lockPerformance(performance);
        s.db.update("update queue_entry set state='CANCELLED',token=null where performance_id=? and user_id=?",performance,user);
        var holds=s.db.queryForList("select id from seat_hold where performance_id=? and user_id=? and state='ACTIVE'",performance,user);
        for(var h:holds) releaseInternal(h.get("id").toString());
        maintain(performance);
    }
    void admission(long performance,long user,String token) {
        if(token==null||s.count("select count(*) from queue_entry where performance_id=? and user_id=? and state='ADMITTED' and token=? and expires_at>?",performance,user,token,utc(Instant.now()))==0)
            throw new ApiError(403,"QUEUE_ADMISSION_REQUIRED","유효한 대기열 입장 권한이 필요합니다.");
    }
    @Transactional
    public List<Seat> seats(long performance,long user,String token) {
        var p=s.lockPerformance(performance); sales(p); maintain(performance); admission(performance,user,token);
        return seatList(performance,null);
    }
    List<Seat> seatList(long performance,String hold) {
        String sql="select ps.*,vs.row_label,vs.seat_number,vs.x,vs.y from performance_seat ps join venue_seat vs on ps.seat_id=vs.id where ps.performance_id=?";
        List<Map<String,Object>> rows=hold==null?s.db.queryForList(sql+" order by vs.row_label,vs.seat_number",performance):s.db.queryForList(sql+" and ps.hold_id=? order by vs.row_label,vs.seat_number",performance,hold);
        return rows.stream().map(r->new Seat(r.get("seat_id").toString(),r.get("row_label").toString(),(int)num(r,"seat_number"),(int)num(r,"x"),(int)num(r,"y"),r.get("grade").toString(),num(r,"price"),r.get("reservation_id")!=null?"RESERVED":r.get("hold_id")!=null?"HELD":"AVAILABLE")).toList();
    }
    @Transactional
    public Hold hold(long performance,long user,String token,HoldRequest req) {
        var p=s.lockPerformance(performance); sales(p); maintain(performance); admission(performance,user,token);
        if(req.seatIds().size()>4||req.seatIds().isEmpty()||new HashSet<>(req.seatIds()).size()!=req.seatIds().size())
            throw new ApiError(400,"INVALID_REQUEST","중복 없이 최대 4석을 선택하세요.");
        long held=s.count("select count(*) from performance_seat ps join seat_hold h on ps.hold_id=h.id where ps.performance_id=? and h.user_id=? and h.state='ACTIVE'",performance,user);
        if(held+req.seatIds().size()>4) throw new ApiError(409,"SEAT_LIMIT_EXCEEDED","확보 중인 좌석을 포함해 최대 4석입니다.");
        String hold=id(); Instant expires=Instant.now().plusSeconds(holdSeconds);
        s.db.update("insert into seat_hold(id,performance_id,user_id,expires_at,state) values(?,?,?,?,'ACTIVE')",hold,performance,user,utc(expires));
        for(long seat:req.seatIds().stream().sorted().toList()) {
            if(s.db.update("update performance_seat set hold_id=? where performance_id=? and seat_id=? and hold_id is null and reservation_id is null",hold,performance,seat)!=1)
                throw new ApiError(409,"SEAT_CONFLICT","다른 사용자가 선택했거나 존재하지 않는 좌석입니다.");
        }
        var seats=seatList(performance,hold);
        return new Hold(hold,performance,expires,"ACTIVE",seats,seats.stream().mapToLong(Seat::price).sum(),"KRW");
    }
    @Transactional
    public void release(String hold,long user) {
        var h=s.one("select * from seat_hold where id=? and user_id=?",hold,user);
        long performance=num(h,"performance_id"); s.lockPerformance(performance); maintain(performance);
        releaseInternal(hold);
    }
    void releaseInternal(String hold) {
        s.db.update("update performance_seat set hold_id=null where hold_id=?",hold);
        s.db.update("update seat_hold set state='RELEASED' where id=? and state='ACTIVE'",hold);
    }
    @Transactional
    public Reservation reserve(long user,String token,String key,ReservationRequest req) {
        if(key==null||!key.matches("[A-Za-z0-9_.:-]{1,100}")) throw new ApiError(400,"INVALID_REQUEST","Idempotency-Key가 필요합니다.");
        // User lock serializes idempotency keys across different performances for the same user.
        s.one("select id from app_user where id=? for update",user);
        var previous=s.db.queryForList("select * from reservation where user_id=? and idempotency_key=?",user,key);
        if(!previous.isEmpty()) {
            if(!req.holdId().equals(previous.getFirst().get("hold_id"))) throw new ApiError(409,"IDEMPOTENCY_CONFLICT","다른 요청에 사용된 키입니다.");
            return reservation(previous.getFirst().get("id").toString(),user);
        }
        var h=s.one("select * from seat_hold where id=? and user_id=?",req.holdId(),user);
        long performance=num(h,"performance_id"); var p=s.lockPerformance(performance); sales(p); maintain(performance);
        admission(performance,user,token);
        h=s.one("select * from seat_hold where id=?",req.holdId());
        if(!instant(h,"expires_at").isAfter(Instant.now())||"EXPIRED".equals(h.get("state"))) throw new ApiError(409,"HOLD_EXPIRED","좌석 확보가 만료되었습니다.");
        if(!"ACTIVE".equals(h.get("state"))) throw new ApiError(409,"HOLD_NOT_ACTIVE","사용할 수 없는 좌석 확보입니다.");
        var seats=seatList(performance,req.holdId());
        if(seats.isEmpty()) throw new ApiError(409,"SEAT_CONFLICT","확보된 좌석이 없습니다.");
        String reservation=id(); long total=seats.stream().mapToLong(Seat::price).sum();
        s.db.update("insert into reservation(id,user_id,performance_id,hold_id,idempotency_key,status,total,created_at) values(?,?,?,?,?,'CONFIRMED',?,?)",reservation,user,performance,req.holdId(),key,total,utc(Instant.now()));
        for(var seat:seats) s.db.update("insert into reservation_item(reservation_id,seat_id,grade,price) values(?,?,?,?)",reservation,Long.parseLong(seat.id()),seat.grade(),seat.price());
        s.db.update("update performance_seat set hold_id=null,reservation_id=? where hold_id=?",reservation,req.holdId());
        s.db.update("update seat_hold set state='CONSUMED' where id=?",req.holdId());
        return reservation(reservation,user);
    }
    public Reservation reservation(String id,long user) {
        var r=s.one("select * from reservation where id=? and user_id=?",id,user);
        var items=s.db.queryForList("select ri.*,vs.row_label,vs.seat_number from reservation_item ri join venue_seat vs on vs.id=ri.seat_id where ri.reservation_id=? order by vs.row_label,vs.seat_number",id)
            .stream().map(i->new ReservationSeat(i.get("seat_id").toString(),i.get("row_label").toString(),(int)num(i,"seat_number"),i.get("grade").toString(),num(i,"price"))).toList();
        return new Reservation(id,num(r,"performance_id"),r.get("status").toString(),r.get("payment_status").toString(),num(r,"total"),"KRW",instant(r,"created_at"),items);
    }
    public Page<Reservation> reservations(long user,int page,int size) {
        var rows=s.db.queryForList("select id from reservation where user_id=? order by created_at desc,id limit ? offset ?",user,size,(page-1L)*size);
        return new Page<>(rows.stream().map(r->reservation(r.get("id").toString(),user)).toList(),s.count("select count(*) from reservation where user_id=?",user),page,size);
    }
    @Transactional
    public Reservation cancel(String id,long user) {
        var r=s.one("select * from reservation where id=? and user_id=?",id,user);
        var p=s.lockPerformance(num(r,"performance_id"));
        if(!Instant.now().isBefore(instant(p,"starts_at"))) throw new ApiError(409,"CANCELLATION_CLOSED","공연 시작 후에는 취소할 수 없습니다.");
        s.db.update("update reservation set status='CANCELLED' where id=?",id);
        s.db.update("update performance_seat set reservation_id=null where reservation_id=?",id);
        return reservation(id,user);
    }
}
