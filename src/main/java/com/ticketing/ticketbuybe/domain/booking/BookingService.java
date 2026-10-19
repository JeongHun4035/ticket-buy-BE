package com.ticketing.ticketbuybe.domain.booking;

import com.ticketing.ticketbuybe.global.error.ApiError;
import com.ticketing.ticketbuybe.domain.auth.AuthService;
import com.ticketing.ticketbuybe.domain.catalog.CatalogService;
import com.ticketing.ticketbuybe.infrastructure.persistence.Store;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import java.time.*;
import java.util.*;
import com.ticketing.ticketbuybe.global.dto.Contracts.Queue;
import static com.ticketing.ticketbuybe.infrastructure.persistence.Store.*;
import static com.ticketing.ticketbuybe.global.dto.Contracts.*;

/** All queue/inventory mutations lock the performance first. This ordering is shared by every path. */
@Service
public class BookingService {
    final Store s;
    final CatalogService catalog;
    final int capacity,admissionSeconds,holdSeconds,admitBatch,pollSeconds;
    public BookingService(Store s,CatalogService catalog,@Value("${app.queue-capacity}") int capacity,
        @Value("${app.admission-seconds}") int admissionSeconds,@Value("${app.hold-seconds}") int holdSeconds,
        @Value("${app.queue-admit-batch-size:20}") int admitBatch,@Value("${app.queue-poll-seconds:3}") int pollSeconds) {
        if(capacity<1||admissionSeconds<1||holdSeconds<1||admitBatch<1||pollSeconds<1) throw new IllegalArgumentException("Invalid booking settings");
        this.s=s;this.catalog=catalog; this.capacity=capacity; this.admissionSeconds=admissionSeconds; this.holdSeconds=holdSeconds;this.admitBatch=admitBatch;this.pollSeconds=pollSeconds;
    }
    void sales(Map<String,Object> p) {
        Instant now=Instant.now();
        if(now.isBefore(instant(p,"opens_at"))) throw new ApiError(409,"BOOKING_NOT_OPEN","예매 오픈 전입니다.");
        if(!now.isBefore(instant(p,"closes_at"))) throw new ApiError(409,"BOOKING_CLOSED","예매가 마감되었습니다.");
    }
    void expireHolds(long performance) {
        var now=utc(Instant.now());
        s.jdbc().update("update performance_seat set hold_id=null where performance_id=? and hold_id in (select id from seat_hold where expires_at<=? and state='ACTIVE')",performance,now);
        s.jdbc().update("update seat_hold set state='EXPIRED' where performance_id=? and state='ACTIVE' and expires_at<=?",performance,now);
    }
    /** Called only by the worker, inside a transaction holding the performance lock. */
    void maintain(long performance) {
        expireHolds(performance);
        var now=utc(Instant.now());
        s.jdbc().update("update queue_entry set state='EXPIRED',token=null where performance_id=? and state='ADMITTED' and expires_at<=?",performance,now);
        var p=s.one("select * from performance where id=?",performance);
        if(!Instant.now().isBefore(instant(p,"closes_at"))) {
            s.jdbc().update("update queue_entry set state='EXPIRED',token=null where performance_id=? and state in ('WAITING','ADMITTED')",performance);
            return;
        }
        if(Instant.now().isBefore(instant(p,"opens_at"))) return;
        int free=capacity-(int)s.count("select count(*) from queue_entry where performance_id=? and state='ADMITTED'",performance);
        var waiters=s.jdbc().queryForList("select id from queue_entry where performance_id=? and state='WAITING' order by ticket limit ?",performance,Math.min(admitBatch,Math.max(0,free)));
        for(var row:waiters) s.jdbc().update("update queue_entry set state='ADMITTED',token=?,expires_at=? where id=?",AuthService.token(),utc(Instant.now().plusSeconds(admissionSeconds)),row.get("id"));
    }
    @Transactional
    public Queue enter(long performance,long user) {
        var p=s.lockPerformance(performance); sales(p);
        var rows=s.jdbc().queryForList("select * from queue_entry where performance_id=? and user_id=?",performance,user);
        boolean expired=!rows.isEmpty()&&"ADMITTED".equals(rows.getFirst().get("state"))&&!instant(rows.getFirst(),"expires_at").isAfter(Instant.now());
        if(rows.isEmpty()||expired||Set.of("EXPIRED","CANCELLED").contains(rows.getFirst().get("state"))) {
            long ticket=num(p,"next_ticket")+1;
            s.jdbc().update("update performance set next_ticket=? where id=?",ticket,performance);
            if(rows.isEmpty()) s.jdbc().update("insert into queue_entry(id,performance_id,user_id,ticket,state) values(?,?,?,?,'WAITING')",id(),performance,user,ticket);
            else s.jdbc().update("update queue_entry set ticket=?,state='WAITING',token=null,expires_at=null where performance_id=? and user_id=?",ticket,performance,user);
            // The performance lock serializes admission across requests and workers.
            // Existing waiters keep priority, including when an expired user re-enters.
            var now=utc(Instant.now());
            boolean hasEarlierWaiters=s.count("select count(*) from queue_entry where performance_id=? and state='WAITING' and ticket<?",performance,ticket)>0;
            long active=s.count("select count(*) from queue_entry where performance_id=? and state='ADMITTED' and expires_at>?",performance,now);
            if(!hasEarlierWaiters&&active<capacity) {
                s.jdbc().update("update queue_entry set state='ADMITTED',token=?,expires_at=? where performance_id=? and user_id=?",
                    AuthService.token(),utc(Instant.now().plusSeconds(admissionSeconds)),performance,user);
            }
        }
        return queueView(performance,user);
    }
    @Transactional(readOnly=true)
    public Queue queue(long performance,long user) {
        return queueView(performance,user);
    }
    Queue queueView(long performance,long user) {
        // One statement gives a consistent state/rank snapshot without a row lock or writes.
        var q=s.one("select q.*,p.closes_at,(select count(*) from queue_entry ahead where ahead.performance_id=q.performance_id and ahead.state='WAITING' and ahead.ticket<q.ticket) ahead_count from queue_entry q join performance p on p.id=q.performance_id where q.performance_id=? and q.user_id=?",performance,user);
        String state=q.get("state").toString(); Instant now=Instant.now();
        if(("ADMITTED".equals(state)&&!instant(q,"expires_at").isAfter(now))||
            (Set.of("WAITING","ADMITTED").contains(state)&&!instant(q,"closes_at").isAfter(now))) state="EXPIRED";
        boolean waiting="WAITING".equals(state),admitted="ADMITTED".equals(state);
        long ahead=waiting?num(q,"ahead_count"):0;
        return new Queue(state,num(q,"ticket"),ahead,waiting?ahead+1:null,null,admitted?q.get("token").toString():null,
            admitted?instant(q,"expires_at"):null,admitted?30:pollSeconds);
    }
    @Transactional
    public void leave(long performance,long user) {
        s.lockPerformance(performance);
        s.jdbc().update("update queue_entry set state='CANCELLED',token=null where performance_id=? and user_id=?",performance,user);
        var holds=s.jdbc().queryForList("select id from seat_hold where performance_id=? and user_id=? and state='ACTIVE'",performance,user);
        for(var h:holds) releaseInternal(h.get("id").toString());
    }
    void admission(long performance,long user,String token) {
        if(token==null||s.count("select count(*) from queue_entry where performance_id=? and user_id=? and state='ADMITTED' and token=? and expires_at>?",performance,user,token,utc(Instant.now()))==0)
            throw new ApiError(403,"QUEUE_ADMISSION_REQUIRED","유효한 대기열 입장 권한이 필요합니다.");
    }
    @Transactional(readOnly=true)
    public List<Seat> seats(long performance,long user,String token) {
        return seats(performance,user,token,"ko");
    }
    @Transactional(readOnly=true)
    public List<Seat> seats(long performance,long user,String token,String lang) {
        var p=s.one("select * from performance where id=?",performance); sales(p); admission(performance,user,token);
        return seatList(performance,null,null,lang);
    }
    List<Seat> seatList(long performance,String hold) {
        return seatList(performance,hold,null,"ko");
    }
    List<Seat> seatList(long performance,String hold,Long section,String lang) {
        String sql="select ps.*,vs.row_label,vs.seat_number,vs.x,vs.y,sec.names section_names,f.names floor_names,f.id floor_id,h.state hold_state,h.expires_at hold_expires_at from performance_seat ps join venue_seat vs on ps.seat_id=vs.id join venue_section sec on sec.id=ps.section_id join venue_floor f on f.id=sec.floor_id left join seat_hold h on h.id=ps.hold_id where ps.performance_id=?";
        List<Object> args=new ArrayList<>();args.add(performance);
        if(hold!=null){sql+=" and ps.hold_id=?";args.add(hold);}
        if(section!=null){sql+=" and ps.section_id=?";args.add(section);}
        var rows=s.jdbc().queryForList(sql+" order by f.level,sec.sort_order,vs.row_label,vs.seat_number",args.toArray());
        Instant now=Instant.now();
        return rows.stream().map(r->new Seat(r.get("seat_id").toString(),r.get("row_label").toString(),(int)num(r,"seat_number"),(int)num(r,"x"),(int)num(r,"y"),r.get("grade").toString(),num(r,"price"),r.get("reservation_id")!=null?"RESERVED":r.get("hold_id")!=null&&"ACTIVE".equals(r.get("hold_state"))&&instant(r,"hold_expires_at").isAfter(now)?"HELD":"AVAILABLE",r.get("section_id").toString(),catalog.localized(r.get("section_names"),lang),catalog.localized(r.get("floor_names"),lang),r.get("floor_id").toString())).toList();
    }
    @Transactional(readOnly=true)
    public List<Seat> sectionSeats(long performance,long section,long user,String token,String lang) {
        var p=s.one("select * from performance where id=?",performance);sales(p);admission(performance,user,token);
        s.one("select sec.id from venue_section sec where sec.id=? and sec.venue_id=? and exists(select 1 from performance_seat ps where ps.performance_id=? and ps.section_id=sec.id)",section,num(p,"venue_id"),performance);
        return seatList(performance,null,section,lang);
    }
    @Transactional(readOnly=true)
    public Sections sections(long performance,long user,String token,String lang) {
        var p=s.one("select * from performance where id=?",performance);sales(p);admission(performance,user,token);
        long venueId=num(p,"venue_id");var v=s.one("select * from venue where id=?",venueId);
        var venue=new VenueInfo(venueId,catalog.localized(v.get("names"),lang),s.count("select count(*) from venue_seat where venue_id=?",venueId),(int)num(v,"map_width"),(int)num(v,"map_height"));
        var floors=s.jdbc().queryForList("select * from venue_floor where venue_id=? order by level,id",venueId).stream()
            .map(f->new FloorInfo(f.get("id").toString(),catalog.localized(f.get("names"),lang),(int)num(f,"level"))).toList();
        // Aggregate scalar counts in the DB; do not materialize seat rows for an overview.
        var rows=s.jdbc().queryForList("""
            select sec.id,sec.names,sec.floor_id,sec.x,sec.y,sec.width,sec.height,sec.sort_order,f.names floor_names,f.level,
              count(ps.seat_id) total_seats,
              sum(case when ps.reservation_id is null and (h.id is null or h.state<>'ACTIVE' or h.expires_at<=?) then 1 else 0 end) available_seats
            from performance_seat ps join venue_section sec on sec.id=ps.section_id join venue_floor f on f.id=sec.floor_id
              left join seat_hold h on h.id=ps.hold_id
            where ps.performance_id=? and sec.venue_id=?
            group by sec.id,sec.names,sec.floor_id,sec.x,sec.y,sec.width,sec.height,sec.sort_order,f.names,f.level
            order by f.level,sec.sort_order,sec.id
            """,utc(Instant.now()),performance,venueId);
        var items=rows.stream().map(r->new Section(r.get("id").toString(),catalog.localized(r.get("names"),lang),catalog.localized(r.get("floor_names"),lang),r.get("floor_id").toString(),num(r,"total_seats"),num(r,"available_seats"),(int)num(r,"x"),(int)num(r,"y"),(int)num(r,"width"),(int)num(r,"height"),(int)num(r,"sort_order"))).toList();
        return new Sections(venue,floors,items,items.size(),1,items.size());
    }
    @Transactional
    public Hold hold(long performance,long user,String token,HoldRequest req) {
        var p=s.lockPerformance(performance); sales(p); expireHolds(performance); admission(performance,user,token);
        if(req.seatIds().size()>4||req.seatIds().isEmpty()||new HashSet<>(req.seatIds()).size()!=req.seatIds().size())
            throw new ApiError(400,"INVALID_REQUEST","중복 없이 최대 4석을 선택하세요.");
        long held=s.count("select count(*) from performance_seat ps join seat_hold h on ps.hold_id=h.id where ps.performance_id=? and h.user_id=? and h.state='ACTIVE'",performance,user);
        if(held+req.seatIds().size()>4) throw new ApiError(409,"SEAT_LIMIT_EXCEEDED","확보 중인 좌석을 포함해 최대 4석입니다.");
        String hold=id(); Instant expires=Instant.now().plusSeconds(holdSeconds);
        s.jdbc().update("insert into seat_hold(id,performance_id,user_id,expires_at,state) values(?,?,?,?,'ACTIVE')",hold,performance,user,utc(expires));
        for(long seat:req.seatIds().stream().sorted().toList()) {
            if(s.jdbc().update("update performance_seat set hold_id=? where performance_id=? and seat_id=? and hold_id is null and reservation_id is null",hold,performance,seat)!=1)
                throw new ApiError(409,"SEAT_CONFLICT","다른 사용자가 선택했거나 존재하지 않는 좌석입니다.");
        }
        var seats=seatList(performance,hold);
        return new Hold(hold,performance,expires,"ACTIVE",seats,seats.stream().mapToLong(Seat::price).sum(),"KRW");
    }
    @Transactional
    public void release(String hold,long user) {
        var h=s.one("select * from seat_hold where id=? and user_id=?",hold,user);
        long performance=num(h,"performance_id"); s.lockPerformance(performance); expireHolds(performance);
        releaseInternal(hold);
    }
    void releaseInternal(String hold) {
        s.jdbc().update("update performance_seat set hold_id=null where hold_id=?",hold);
        s.jdbc().update("update seat_hold set state='RELEASED' where id=? and state='ACTIVE'",hold);
    }
    @Transactional
    public Reservation reserve(long user,String token,String key,ReservationRequest req) {
        if(key==null||!key.matches("[A-Za-z0-9_.:-]{1,100}")) throw new ApiError(400,"INVALID_REQUEST","Idempotency-Key가 필요합니다.");
        // User lock serializes idempotency keys across different performances for the same user.
        s.one("select id from app_user where id=? for update",user);
        var previous=s.jdbc().queryForList("select * from reservation where user_id=? and idempotency_key=?",user,key);
        if(!previous.isEmpty()) {
            if(!req.holdId().equals(previous.getFirst().get("hold_id"))) throw new ApiError(409,"IDEMPOTENCY_CONFLICT","다른 요청에 사용된 키입니다.");
            return reservation(previous.getFirst().get("id").toString(),user);
        }
        var h=s.one("select * from seat_hold where id=? and user_id=?",req.holdId(),user);
        long performance=num(h,"performance_id"); var p=s.lockPerformance(performance); sales(p); expireHolds(performance);
        admission(performance,user,token);
        h=s.one("select * from seat_hold where id=?",req.holdId());
        if(!instant(h,"expires_at").isAfter(Instant.now())||"EXPIRED".equals(h.get("state"))) throw new ApiError(409,"HOLD_EXPIRED","좌석 확보가 만료되었습니다.");
        if(!"ACTIVE".equals(h.get("state"))) throw new ApiError(409,"HOLD_NOT_ACTIVE","사용할 수 없는 좌석 확보입니다.");
        var seats=seatList(performance,req.holdId());
        if(seats.isEmpty()) throw new ApiError(409,"SEAT_CONFLICT","확보된 좌석이 없습니다.");
        String reservation=id(); long total=seats.stream().mapToLong(Seat::price).sum();
        s.jdbc().update("insert into reservation(id,user_id,performance_id,hold_id,idempotency_key,status,total,created_at) values(?,?,?,?,?,'CONFIRMED',?,?)",reservation,user,performance,req.holdId(),key,total,utc(Instant.now()));
        for(var seat:seats) s.jdbc().update("insert into reservation_item(reservation_id,seat_id,grade,price) values(?,?,?,?)",reservation,Long.parseLong(seat.id()),seat.grade(),seat.price());
        s.jdbc().update("update performance_seat set hold_id=null,reservation_id=? where hold_id=?",reservation,req.holdId());
        s.jdbc().update("update seat_hold set state='CONSUMED' where id=?",req.holdId());
        return reservation(reservation,user);
    }
    public Reservation reservation(String id,long user) {
        var r=s.one("select * from reservation where id=? and user_id=?",id,user);
        var items=s.jdbc().queryForList("select ri.*,vs.row_label,vs.seat_number,vs.section_id,sec.names section_names,f.id floor_id,f.names floor_names from reservation_item ri join venue_seat vs on vs.id=ri.seat_id join venue_section sec on sec.id=vs.section_id join venue_floor f on f.id=sec.floor_id where ri.reservation_id=? order by f.level,sec.sort_order,vs.row_label,vs.seat_number",id)
            .stream().map(i->new ReservationSeat(i.get("seat_id").toString(),i.get("row_label").toString(),(int)num(i,"seat_number"),i.get("grade").toString(),num(i,"price"),i.get("section_id").toString(),catalog.localized(i.get("section_names"),"ko"),catalog.localized(i.get("floor_names"),"ko"),i.get("floor_id").toString())).toList();
        return new Reservation(id,num(r,"performance_id"),r.get("status").toString(),r.get("payment_status").toString(),num(r,"total"),"KRW",instant(r,"created_at"),items);
    }
    public Page<Reservation> reservations(long user,int page,int size) {
        var rows=s.jdbc().queryForList("select id from reservation where user_id=? order by created_at desc,id limit ? offset ?",user,size,(page-1L)*size);
        return new Page<>(rows.stream().map(r->reservation(r.get("id").toString(),user)).toList(),s.count("select count(*) from reservation where user_id=?",user),page,size);
    }
    @Transactional
    public Reservation cancel(String id,long user) {
        var r=s.one("select * from reservation where id=? and user_id=?",id,user);
        var p=s.lockPerformance(num(r,"performance_id"));
        if(!Instant.now().isBefore(instant(p,"starts_at"))) throw new ApiError(409,"CANCELLATION_CLOSED","공연 시작 후에는 취소할 수 없습니다.");
        s.jdbc().update("update reservation set status='CANCELLED' where id=?",id);
        s.jdbc().update("update performance_seat set reservation_id=null where reservation_id=?",id);
        return reservation(id,user);
    }
}
