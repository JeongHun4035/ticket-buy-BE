package com.ticketing.ticketbuybe;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import static com.ticketing.ticketbuybe.Store.*;
import static com.ticketing.ticketbuybe.Contracts.*;

@Service
public class CatalogService {
    final Store s; final ObjectMapper json;
    public CatalogService(Store s,ObjectMapper json) { this.s=s; this.json=json; }
    String localized(Object value,String lang) {
        var node=json.readTree(value.toString());
        return node.path(lang).asString(node.path("ko").asString());
    }
    String status(Map<String,Object> p) {
        Instant now=Instant.now();
        return now.isBefore(instant(p,"opens_at"))?"UPCOMING":now.isBefore(instant(p,"closes_at"))?"ON_SALE":"CLOSED";
    }
    Event event(Map<String,Object> e,String lang) {
        long event=num(e,"id");
        var performances=s.db.queryForList("select p.*,v.names from performance p join venue v on v.id=p.venue_id where p.event_id=? order by starts_at",event);
        var available=performances.stream().filter(p->status(p).equals("ON_SALE")).findFirst();
        var next=available.orElseGet(()->performances.stream().filter(p->status(p).equals("UPCOMING")).findFirst().orElse(performances.isEmpty()?null:performances.getLast()));
        Long price=s.db.queryForObject("select min(price) from performance_seat ps join performance p on p.id=ps.performance_id where p.event_id=?",Long.class,event);
        return new Event(event,localized(e.get("titles"),lang),localized(e.get("descriptions"),lang),Category.valueOf(e.get("category").toString()),
            next==null?null:localized(next.get("names"),lang),next==null?null:instant(next,"starts_at"),price,(String)e.get("poster_url"),next==null?"CLOSED":status(next));
    }
    public Page<Event> events(String q,Category category,Sort sort,int page,int size,String lang,boolean featured) {
        // Localized text is selected before filtering; this keeps search semantics consistent across DB engines.
        var all=s.db.queryForList("select * from event order by featured_rank desc,id").stream()
            .filter(e->!featured||num(e,"featured_rank")>0).map(e->event(e,lang))
            .filter(e->category==null||category==e.category())
            .filter(e->q==null||e.title().toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))||(e.venue()!=null&&e.venue().toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))))
            .toList();
        var sorted=new ArrayList<>(all);
        if(sort==Sort.DATE_ASC) sorted.sort(Comparator.comparing(Event::date,Comparator.nullsLast(Comparator.naturalOrder())).thenComparingLong(Event::id));
        if(sort==Sort.PRICE_ASC) sorted.sort(Comparator.comparing(Event::price,Comparator.nullsLast(Comparator.naturalOrder())).thenComparingLong(Event::id));
        return new Page<>(sorted.stream().skip((page-1L)*size).limit(size).toList(),all.size(),page,size);
    }
    public Event event(long id,String lang) { return event(s.one("select * from event where id=?",id),lang); }
    public Page<Performance> performances(long event,int page,int size,String lang) {
        s.one("select id from event where id=?",event);
        var rows=s.db.queryForList("select p.*,v.names from performance p join venue v on p.venue_id=v.id where p.event_id=? order by p.starts_at,p.id limit ? offset ?",event,size,(page-1L)*size);
        return new Page<>(rows.stream().map(p->new Performance(num(p,"id"),event,num(p,"venue_id"),localized(p.get("names"),lang),instant(p,"starts_at"),instant(p,"opens_at"),instant(p,"closes_at"),status(p))).toList(),s.count("select count(*) from performance where event_id=?",event),page,size);
    }
    Content content(Map<String,Object> row,String lang) {
        return new Content(num(row,"id"),row.get("category").toString(),localized(row.get("titles"),lang),localized(row.get("bodies"),lang),instant(row,"created_at"));
    }
    public Content content(String kind,long id,String lang) { return content(s.one("select * from content_entry where kind=? and id=?",kind,id),lang); }
    public Page<Content> contents(String kind,String q,int page,int size,String lang) {
        var all=s.db.queryForList("select * from content_entry where kind=? order by created_at desc,id desc",kind).stream().map(r->content(r,lang))
            .filter(r->q==null||r.title().toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))||r.content().toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))).toList();
        return new Page<>(all.stream().skip((page-1L)*size).limit(size).toList(),all.size(),page,size);
    }
    @Transactional
    public Content save(String kind,Long id,ContentRequest request) {
        if(!request.titles().containsKey("ko")||!request.bodies().containsKey("ko")) throw new ApiError(400,"INVALID_REQUEST","기본 언어 ko는 필수입니다.");
        String titles=json.writeValueAsString(request.titles()),bodies=json.writeValueAsString(request.bodies());
        if(id==null) {
            var key=new org.springframework.jdbc.support.GeneratedKeyHolder();
            s.db.update(connection->{var ps=connection.prepareStatement("insert into content_entry(kind,category,titles,bodies,created_at) values(?,?,?,?,?)",new String[]{"id"});
                ps.setString(1,kind);ps.setString(2,request.category().name());ps.setString(3,titles);ps.setString(4,bodies);ps.setObject(5,utc(Instant.now()));return ps;},key);
            id=Objects.requireNonNull(key.getKey()).longValue();
        } else if(s.db.update("update content_entry set category=?,titles=?,bodies=? where id=? and kind=?",request.category().name(),titles,bodies,id,kind)!=1)
            throw new ApiError(404,"NOT_FOUND","리소스를 찾을 수 없습니다.");
        return content(kind,id,"ko");
    }
    public void delete(String kind,long id) {
        if(s.db.update("delete from content_entry where id=? and kind=?",id,kind)!=1) throw new ApiError(404,"NOT_FOUND","리소스를 찾을 수 없습니다.");
    }
}
