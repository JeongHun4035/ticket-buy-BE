package com.ticketing.ticketbuybe;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import static com.ticketing.ticketbuybe.Store.*;
import static com.ticketing.ticketbuybe.Contracts.*;

@Service
@PreAuthorize("hasRole('ADMIN')")
public class EventAdminService {
    final Store s;final CatalogService catalog;final ObjectMapper json;final EventImages images;
    public EventAdminService(Store s,CatalogService catalog,ObjectMapper json,EventImages images){this.s=s;this.catalog=catalog;this.json=json;this.images=images;}
    long next(String name) {
        long id=num(s.one("select next_id from id_allocator where name=? for update",name),"next_id");
        s.db.update("update id_allocator set next_id=? where name=?",id+1,name);return id;
    }
    @Transactional
    public EventCreated create(EventCreate req,MultipartFile image) {
        if(!req.titles().containsKey("ko")||!req.descriptions().containsKey("ko")) throw new ApiError(400,"INVALID_REQUEST","제목과 설명에 기본 언어 ko가 필요합니다.");
        for(var p:req.performances()) {
            if(!p.opensAt().isBefore(p.closesAt())||p.closesAt().isAfter(p.startsAt())||!p.startsAt().isAfter(Instant.now())||!p.closesAt().isAfter(Instant.now()))
                throw new ApiError(400,"INVALID_SCHEDULE","opensAt < closesAt <= startsAt, 미래 시작·마감 시각이어야 합니다.");
            s.one("select id from venue where id=?",p.venueId());
            Set<Long> sections=new HashSet<>();
            for(var plan:p.sectionPrices()) {
                if(!sections.add(plan.sectionId())) throw new ApiError(400,"DUPLICATE_SECTION","한 회차에 같은 구역을 중복 지정할 수 없습니다.");
                if(s.count("select count(*) from venue_section sec where sec.id=? and sec.venue_id=? and exists(select 1 from venue_seat vs where vs.section_id=sec.id)",plan.sectionId(),p.venueId())==0)
                    throw new ApiError(400,"INVALID_SECTION","좌석이 있는 해당 공연장의 구역을 지정하세요.");
            }
        }
        var validated=images.validate(image);long event=next("event");String poster=images.save(validated);
        s.db.update("insert into event(id,titles,descriptions,category,poster_url,featured_rank) values(?,?,?,?,?,?)",event,json.writeValueAsString(req.titles()),json.writeValueAsString(req.descriptions()),req.category().name(),poster,req.featuredRank());
        for(var p:req.performances()) {
            long performance=next("performance");
            s.db.update("insert into performance(id,event_id,venue_id,starts_at,opens_at,closes_at) values(?,?,?,?,?,?)",performance,event,p.venueId(),utc(p.startsAt()),utc(p.opensAt()),utc(p.closesAt()));
            for(var plan:p.sectionPrices()) s.db.update("insert into performance_seat(performance_id,seat_id,section_id,venue_id,grade,price) select ?,vs.id,vs.section_id,vs.venue_id,?,? from venue_seat vs where vs.venue_id=? and vs.section_id=?",performance,plan.grade().name(),plan.price(),p.venueId(),plan.sectionId());
        }
        return new EventCreated(catalog.event(event,"ko"),catalog.performances(event,1,100,"ko").items());
    }
    @Transactional(readOnly=true)
    public Page<VenueInfo> venues(int page,int size) {
        var rows=s.db.queryForList("select v.*,(select count(*) from venue_seat vs where vs.venue_id=v.id) capacity from venue v order by id limit ? offset ?",size,(page-1L)*size);
        var items=rows.stream().map(v->new VenueInfo(num(v,"id"),catalog.localized(v.get("names"),"ko"),num(v,"capacity"),(int)num(v,"map_width"),(int)num(v,"map_height"))).toList();
        return new Page<>(items,s.count("select count(*) from venue"),page,size);
    }
    @Transactional(readOnly=true)
    public VenueLayout layout(long venue) {
        var v=s.one("select * from venue where id=?",venue);
        var info=new VenueInfo(venue,catalog.localized(v.get("names"),"ko"),s.count("select count(*) from venue_seat where venue_id=?",venue),(int)num(v,"map_width"),(int)num(v,"map_height"));
        var floors=s.db.queryForList("select * from venue_floor where venue_id=? order by level,id",venue).stream().map(f->new FloorInfo(f.get("id").toString(),catalog.localized(f.get("names"),"ko"),(int)num(f,"level"))).toList();
        var items=s.db.queryForList("select sec.*,f.names floor_names,(select count(*) from venue_seat vs where vs.section_id=sec.id) total_seats from venue_section sec join venue_floor f on f.id=sec.floor_id where sec.venue_id=? order by f.level,sec.sort_order,sec.id",venue).stream()
            .map(r->new VenueSection(r.get("id").toString(),catalog.localized(r.get("names"),"ko"),catalog.localized(r.get("floor_names"),"ko"),r.get("floor_id").toString(),num(r,"total_seats"),(int)num(r,"x"),(int)num(r,"y"),(int)num(r,"width"),(int)num(r,"height"),(int)num(r,"sort_order"))).toList();
        return new VenueLayout(info,floors,items,items.size(),1,items.size());
    }
}
