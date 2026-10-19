package com.ticketing.ticketbuybe.bootstrap.seed;

import com.ticketing.ticketbuybe.domain.auth.AuthService;
import com.ticketing.ticketbuybe.global.dto.Contracts;
import com.ticketing.ticketbuybe.infrastructure.persistence.Store;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import static com.ticketing.ticketbuybe.infrastructure.persistence.Store.*;

/** Never loaded under prod. Seed dates are relative to first boot, not refreshed on restart. */
@Component @Profile("dev & !prod") @org.springframework.core.annotation.Order(10)
public class DevSeed implements CommandLineRunner {
    final Store s; final AuthService auth; final ObjectMapper json;
    public DevSeed(Store s,AuthService auth,ObjectMapper json){this.s=s;this.auth=auth;this.json=json;}
    String tr(String ko,String en,String zh){return json.writeValueAsString(Map.of("ko",ko,"en",en,"zh-CN",zh));}
    @Transactional
    public void run(String... args) {
        if(s.count("select count(*) from app_user")>0) return;
        for(String name:List.of("demo","other","admin")) s.jdbc().update("insert into app_user(username,password_hash,name,role) values(?,?,?,?)",name,auth.hashPassword("Demo1234!"),name.equals("demo")?"예매 사용자":name,name.equals("admin")?"ADMIN":"USER");
        s.jdbc().update("insert into venue(id,names) values(1,?)",tr("서울 아트홀","Seoul Art Hall","首尔艺术厅"));
        s.jdbc().update("insert into venue_floor(id,venue_id,names,level) values(1001,1,?,1)",tr("1층","Floor 1","1层"));
        s.jdbc().update("insert into venue_section(id,venue_id,floor_id,names,x,y,width,height,sort_order) values(100001,1,1001,?,100,100,800,800,1)",tr("기본 구역","Main section","主区"));
        for(int row=0;row<8;row++) for(int n=1;n<=12;n++) s.jdbc().update("insert into venue_seat(id,venue_id,section_id,row_label,seat_number,x,y) values(?,1,100001,?,?,?,?)",row*12+n,String.valueOf((char)('A'+row)),n,n,row+1);
        String[] ko={"서울 라이브 콘서트","별빛 뮤지컬","우리의 연극","빛의 전시","가을 클래식"};
        String[] en={"Seoul Live Concert","Starlight Musical","Our Theater","Light Exhibition","Autumn Classics"};
        String[] zh={"首尔现场演唱会","星光音乐剧","我们的戏剧","光之展览","秋日古典音乐"};
        for(int e=1;e<=5;e++) {
            s.jdbc().update("insert into event(id,titles,descriptions,category,poster_url,featured_rank) values(?,?,?,?,?,?)",e,tr(ko[e-1],en[e-1],zh[e-1]),tr("TICKET BUY 개발용 행사입니다.","TICKET BUY development event.","TICKET BUY 开发活动。"),Contracts.Category.values()[e-1].name(),null,6-e);
            for(int k=0;k<2;k++) {
                long p=e*100+k+1;Instant now=Instant.now();
                s.jdbc().update("insert into performance(id,event_id,venue_id,starts_at,opens_at,closes_at) values(?,?,1,?,?,?)",p,e,utc(now.plus(Duration.ofDays(30+k))),utc(now.plus(Duration.ofDays(e==5?10:-1))),utc(now.plus(Duration.ofDays(29+k))));
                for(int row=0;row<8;row++) for(int n=1;n<=12;n++) s.jdbc().update("insert into performance_seat(performance_id,seat_id,section_id,venue_id,grade,price) values(?,?,100001,1,?,?)",p,row*12+n,row<2?"VIP":row<5?"R":"S",row<2?150000:row<5?110000:80000);
            }
        }
        s.jdbc().update("insert into content_entry(kind,category,titles,bodies,created_at) values('FAQ','GUIDE',?,?,?)",tr("몇 석까지 예매할 수 있나요?","How many seats can I hold?","最多可以选择几个座位？"),tr("회차별 임시 확보는 최대 4석이며 5분간 유지됩니다.","Up to four active held seats per performance, held for five minutes.","每场最多暂留四个座位，保留五分钟。"),utc(Instant.now()));
        s.jdbc().update("insert into content_entry(kind,category,titles,bodies,created_at) values('POST','NOTICE',?,?,?)",tr("TICKET BUY 오픈 안내","Welcome to TICKET BUY","欢迎使用 TICKET BUY"),tr("개발 환경에서는 실제 결제가 발생하지 않습니다.","No real payment is collected in this development service.","开发环境不会收取实际付款。"),utc(Instant.now()));
    }
}
