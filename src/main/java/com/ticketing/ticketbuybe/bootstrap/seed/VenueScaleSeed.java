package com.ticketing.ticketbuybe.bootstrap.seed;

import com.ticketing.ticketbuybe.infrastructure.persistence.Store;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import static com.ticketing.ticketbuybe.infrastructure.persistence.Store.*;

/** Stable IDs, independent of legacy seed/users. Existing dates, prices, holds and reservations are never reset. */
@Component @Profile("dev & !prod") @Order(20)
public class VenueScaleSeed implements CommandLineRunner {
    final Store s; final ObjectMapper json;
    public VenueScaleSeed(Store s,ObjectMapper json){this.s=s;this.json=json;}
    String tr(String ko,String en,String zh){return json.writeValueAsString(Map.of("ko",ko,"en",en,"zh-CN",zh));}
    @Transactional
    public void run(String... args) {
        seed(10,500,1,"소극장 500","Studio 500","小剧场 500","소극장 연극 축제","Studio Theater Festival","小剧场戏剧节","THEATER");
        seed(20,5000,2,"그랜드홀 5000","Grand Hall 5000","大剧院 5000","그랜드홀 뮤지컬","Grand Hall Musical","大剧院音乐剧","MUSICAL");
        seed(30,30000,3,"스타디움 30000","Stadium 30000","体育场 30000","스타디움 라이브 콘서트","Stadium Live Concert","体育场演唱会","CONCERT");
    }
    void seed(long venue,int capacity,int floors,String ko,String en,String zh,String titleKo,String titleEn,String titleZh,String category) {
        if(s.count("select count(*) from venue where id=?",venue)==0) s.jdbc().update("insert into venue(id,names) values(?,?)",venue,tr(ko,en,zh));
        int sectionCount=capacity/250,perFloor=sectionCount/floors;
        for(int f=1;f<=floors;f++) if(s.count("select count(*) from venue_floor where id=?",venue*1000+f)==0)
            s.jdbc().update("insert into venue_floor(id,venue_id,names,level) values(?,?,?,?)",venue*1000+f,venue,tr(f+"층","Floor "+f,f+"层"),f);
        for(int section=1;section<=sectionCount;section++) {
            long id=venue*100000+section;int floor=(section-1)/perFloor+1,index=(section-1)%perFloor;
            int columns=venue==30?8:5,step=venue==30?120:180;
            if(s.count("select count(*) from venue_section where id=?",id)==0)
                s.jdbc().update("insert into venue_section(id,venue_id,floor_id,names,x,y,width,height,sort_order) values(?,?,?,?,?,?,?,?,?)",
                    id,venue,venue*1000+floor,tr(section+"구역","Section "+section,section+"区"),20+(index%columns)*step,50+(index/columns)*180,venue==30?100:150,150,section);
        }
        if(s.count("select count(*) from venue_seat where venue_id=?",venue)!=capacity) {
            Set<Long> existing=new HashSet<>(s.jdbc().queryForList("select id from venue_seat where venue_id=?",Long.class,venue));
            List<Object[]> seats=new ArrayList<>();
            for(int i=0;i<capacity;i++) {
                long id=venue*1000000+i+1;if(existing.contains(id))continue;
                int inSection=i%250;
                seats.add(new Object[]{id,venue,venue*100000+i/250+1,String.valueOf((char)('A'+inSection/25)),inSection%25+1,inSection%25+1,inSection/25+1});
            }
            for(int start=0;start<seats.size();start+=1000) s.jdbc().batchUpdate("insert into venue_seat(id,venue_id,section_id,row_label,seat_number,x,y) values(?,?,?,?,?,?,?)",seats.subList(start,Math.min(start+1000,seats.size())));
        }
        if(s.count("select count(*) from event where id=?",venue)==0) s.jdbc().update("insert into event(id,titles,descriptions,category,featured_rank) values(?,?,?,?,?)",venue,tr(titleKo,titleEn,titleZh),tr("구역별 좌석 예매 개발 데이터","Section booking development data","分区购票开发数据"),category,20+venue/10);
        for(int k=1;k<=2;k++) {
            long performance=venue*100+k;
            if(s.count("select count(*) from performance where id=?",performance)==0) {
                Instant now=Instant.now();
                s.jdbc().update("insert into performance(id,event_id,venue_id,starts_at,opens_at,closes_at) values(?,?,?,?,?,?)",performance,venue,venue,utc(now.plus(Duration.ofDays(30+k))),utc(now.minus(Duration.ofDays(1))),utc(now.plus(Duration.ofDays(29+k))));
            }
            s.jdbc().update("""
                insert into performance_seat(performance_id,seat_id,section_id,venue_id,grade,price)
                select ?,vs.id,vs.section_id,vs.venue_id,
                  case mod(sec.sort_order-1,3) when 0 then 'VIP' when 1 then 'R' else 'S' end,
                  (case mod(sec.sort_order-1,3) when 0 then 150000 when 1 then 110000 else 80000 end)+?
                from venue_seat vs join venue_section sec on sec.id=vs.section_id
                where vs.venue_id=? and not exists(select 1 from performance_seat ps where ps.performance_id=? and ps.seat_id=vs.id)
                """,performance,(k-1)*5000,venue,performance);
        }
    }
}
