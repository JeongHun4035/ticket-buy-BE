package com.ticketing.ticketbuybe.global.security;

import com.ticketing.ticketbuybe.global.config.Scheduling;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.util.concurrent.ConcurrentHashMap;

/** Per-process guard, keyed by authenticated user and performance endpoint, never by client input role. */
@Component
public class ReadRequestLimiter {
    private static final java.util.regex.Pattern READ_PATH=java.util.regex.Pattern.compile("/api/v1/performances/([^/]+)/(queue/me|seats|sections(?:/([^/]+)/seats)?)");
    private final ConcurrentHashMap<String,Long> nextAllowed=new ConcurrentHashMap<>();
    private final long queueMillis,seatMillis;
    private final int maxKeys;
    public ReadRequestLimiter(@Value("${app.queue-poll-seconds:3}") int queueSeconds,
        @Value("${app.seat-read-min-seconds:2}") int seatSeconds,
        @Value("${app.read-limit-max-keys:100000}") int maxKeys) {
        if(queueSeconds<1||seatSeconds<1||maxKeys<1) throw new IllegalArgumentException("Invalid polling settings");
        this.queueMillis=queueSeconds*1000L;this.seatMillis=seatSeconds*1000L;this.maxKeys=maxKeys;
    }
    int intervalSeconds(String path) {return (int)((path.endsWith("/queue/me")?queueMillis:seatMillis)/1000);}
    String canonicalPath(String path) {
        var match=READ_PATH.matcher(path);
        if(!match.matches()) return null;
        try {
            long performance=Long.parseLong(match.group(1));
            String kind=match.group(2);
            if(match.group(3)!=null){if(Long.parseLong(match.group(3))<=0)return null;kind="seats";}
            return performance>0?"/api/v1/performances/"+performance+"/"+kind:null;
        } catch(NumberFormatException e) {return null;}
    }
    /** Returns seconds until retry, or zero when allowed. */
    long retryAfter(long user,String path) {
        long now=System.currentTimeMillis(),interval=intervalSeconds(path)*1000L;
        String key=user+":"+path;
        if(nextAllowed.size()>=maxKeys) {
            nextAllowed.entrySet().removeIf(e->e.getValue()<=now);
            if(nextAllowed.size()>=maxKeys&&!nextAllowed.containsKey(key)) return Math.max(1,interval/1000);
        }
        var wait=new java.util.concurrent.atomic.AtomicLong();
        nextAllowed.compute(key,(k,until)->{
            if(until!=null&&until>now) {wait.set((until-now+999)/1000);return until;}
            return now+interval;
        });
        return wait.get();
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=60000)
    public void cleanup() {
        long now=System.currentTimeMillis();nextAllowed.entrySet().removeIf(e->e.getValue()<=now);
    }
}
