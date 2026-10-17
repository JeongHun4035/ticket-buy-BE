package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.sql.*;
import java.util.*;

/** Preserves existing seat IDs and discovers the unnamed V1 unique constraint on both supported databases. */
public class V3__venue_sections extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        Connection c=context.getConnection();
        try(Statement sql=c.createStatement()) {
            sql.execute("alter table venue add column map_width int not null default 1000");
            sql.execute("alter table venue add column map_height int not null default 1000");
            sql.execute("""
                create table venue_floor (
                  id bigint primary key, venue_id bigint not null references venue(id), names text not null,
                  level int not null, unique(venue_id,level), unique(id,venue_id))
                """);
            sql.execute("""
                create table venue_section (
                  id bigint primary key, venue_id bigint not null references venue(id), floor_id bigint not null,
                  names text not null, x int not null, y int not null, width int not null check(width>0),
                  height int not null check(height>0), sort_order int not null,
                  foreign key(floor_id,venue_id) references venue_floor(id,venue_id), unique(id,venue_id))
                """);
            sql.execute("insert into venue_floor(id,venue_id,names,level) select id*1000+1,id,'{\"ko\":\"1층\",\"en\":\"Floor 1\",\"zh-CN\":\"1层\"}',1 from venue");
            sql.execute("insert into venue_section(id,venue_id,floor_id,names,x,y,width,height,sort_order) select id*100000+1,id,id*1000+1,'{\"ko\":\"기본 구역\",\"en\":\"Main section\",\"zh-CN\":\"主区\"}',100,100,800,800,1 from venue");
            sql.execute("alter table venue_seat add column section_id bigint");
            sql.execute("update venue_seat set section_id=venue_id*100000+1");
            sql.execute("alter table venue_seat alter column section_id set not null");
            Map<String,Set<String>> constraints=new HashMap<>();
            try(ResultSet rs=sql.executeQuery("""
                select t.constraint_name,k.column_name from information_schema.table_constraints t
                join information_schema.key_column_usage k on k.constraint_catalog=t.constraint_catalog
                  and k.constraint_schema=t.constraint_schema and k.constraint_name=t.constraint_name and k.table_name=t.table_name
                where t.table_schema=current_schema() and t.table_name='venue_seat' and t.constraint_type='UNIQUE'
                """)) {
                while(rs.next()) constraints.computeIfAbsent(rs.getString(1),key->new HashSet<>()).add(rs.getString(2));
            }
            var old=constraints.entrySet().stream().filter(e->e.getValue().equals(Set.of("venue_id","row_label","seat_number"))).findFirst()
                .orElseThrow(()->new IllegalStateException("V1 venue seat uniqueness constraint not found"));
            sql.execute("alter table venue_seat drop constraint \""+old.getKey().replace("\"","\"\"")+"\"");
            sql.execute("alter table venue_seat add constraint seat_section_position unique(section_id,row_label,seat_number)");
            sql.execute("alter table venue_seat add constraint seat_section_venue unique(id,section_id,venue_id)");
            sql.execute("alter table venue_seat add constraint seat_section_fk foreign key(section_id,venue_id) references venue_section(id,venue_id)");
            sql.execute("alter table performance add constraint performance_venue unique(id,venue_id)");
            sql.execute("alter table performance_seat add column section_id bigint");
            sql.execute("alter table performance_seat add column venue_id bigint");
            sql.execute("update performance_seat set section_id=(select s.section_id from venue_seat s where s.id=seat_id), venue_id=(select p.venue_id from performance p where p.id=performance_id)");
            sql.execute("alter table performance_seat alter column section_id set not null");
            sql.execute("alter table performance_seat alter column venue_id set not null");
            sql.execute("alter table performance_seat add constraint inventory_seat_venue foreign key(seat_id,section_id,venue_id) references venue_seat(id,section_id,venue_id)");
            sql.execute("alter table performance_seat add constraint inventory_performance_venue foreign key(performance_id,venue_id) references performance(id,venue_id)");
            sql.execute("create index inventory_section on performance_seat(performance_id,section_id,seat_id)");
            sql.execute("create index venue_seat_venue on venue_seat(venue_id)");
            sql.execute("create index section_floor on venue_section(venue_id,floor_id,sort_order)");
        }
    }
}
