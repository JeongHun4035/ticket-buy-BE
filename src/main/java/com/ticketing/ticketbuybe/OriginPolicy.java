package com.ticketing.ticketbuybe;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.net.URI;
import java.util.*;

/** Trust only server configuration, never Host/X-Forwarded-* supplied by the request. */
@Component
public class OriginPolicy {
    private final List<String> origins;
    public OriginPolicy(@Value("${app.origins}") String configured,
        @Value("${app.public-base-url:http://localhost:8080}") String publicBase) {
        var allowed=new LinkedHashSet<String>();
        for(String entry:configured.split(",")) if(!entry.isBlank()) allowed.add(normalize(entry.trim(),false));
        // Same-origin Swagger and reverse-proxied frontend must pass the auth Origin check too.
        allowed.add(normalize(publicBase.trim(),true));
        origins=List.copyOf(allowed);
    }
    public List<String> allowedOrigins(){return origins;}
    public boolean allows(String origin) {
        if(origin==null)return false;
        try {return origins.contains(normalize(origin,false));}
        catch(IllegalArgumentException e){return false;}
    }
    private static String normalize(String value,boolean baseUrl) {
        URI uri;
        try {uri=URI.create(value);}catch(IllegalArgumentException e){throw new IllegalArgumentException("Invalid CORS_ORIGINS/PUBLIC_BASE_URL origin");}
        String scheme=uri.getScheme(),host=uri.getHost(),path=uri.getRawPath();
        if(scheme==null||host==null||!Set.of("http","https").contains(scheme.toLowerCase(Locale.ROOT))||
            uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||
            (!baseUrl&&path!=null&&!path.isEmpty()&&!path.equals("/")))
            throw new IllegalArgumentException("CORS_ORIGINS must contain exact HTTP(S) origins without paths or wildcards");
        scheme=scheme.toLowerCase(Locale.ROOT);host=host.toLowerCase(Locale.ROOT);
        int port=uri.getPort();
        if(port>65535)throw new IllegalArgumentException("Invalid origin port");
        boolean defaultPort=port==-1||(scheme.equals("http")&&port==80)||(scheme.equals("https")&&port==443);
        return scheme+"://"+host+(defaultPort?"":":"+port);
    }
}
