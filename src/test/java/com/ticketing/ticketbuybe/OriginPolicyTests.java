package com.ticketing.ticketbuybe;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OriginPolicyTests {
    @Test void trustsConfiguredPublicOriginAndNormalizesExplicitOrigins() {
        var policy=new OriginPolicy(" http://localhost:5173/ , https://FRONT.example.com:443/ ","http://34.64.146.19/backend");
        assertTrue(policy.allows("http://34.64.146.19"));assertTrue(policy.allows("http://34.64.146.19:80"));
        assertTrue(policy.allows("http://localhost:5173"));assertTrue(policy.allows("https://front.example.com"));
        assertEquals(3,policy.allowedOrigins().size());
    }
    @Test void rejectsUntrustedDomainsPortsAndMalformedOrigins() {
        var policy=new OriginPolicy("http://localhost:5173","http://34.64.146.19");
        for(String origin:new String[]{"null","*","https://34.64.146.19","http://34.64.146.19:5173","http://34.64.146.19.evil.test","http://34.64.146.19@evil.test","http://34.64.146.19/path","http://34.64.146.19?x=1"}) assertFalse(policy.allows(origin),origin);
        assertFalse(policy.allows(null));
    }
    @Test void invalidServerConfigurationFailsRatherThanAllowingAllOrigins() {
        assertThrows(IllegalArgumentException.class,()->new OriginPolicy("*","http://localhost:8080"));
        assertThrows(IllegalArgumentException.class,()->new OriginPolicy("https://front.example.com/path","http://localhost:8080"));
        assertThrows(IllegalArgumentException.class,()->new OriginPolicy("http://localhost:5173","javascript:alert(1)"));
    }
}
