package com.ticketing.ticketbuybe.bootstrap.seed;

import com.ticketing.ticketbuybe.infrastructure.persistence.Store;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;

@Component @Profile("dev & !prod") @Order(30)
public class DevPosterSeed implements CommandLineRunner {
    final Store s;
    public DevPosterSeed(Store s){this.s=s;}
    @Transactional public void run(String... args) {
        Map.of(1,"concert",2,"musical",3,"theater",4,"exhibition",5,"classical",10,"studio",20,"grand-hall",30,"stadium")
            .forEach((id,name)->s.jdbc().update("update event set poster_url=? where id=? and poster_url is null","/posters/"+name+".svg",id));
    }
}
