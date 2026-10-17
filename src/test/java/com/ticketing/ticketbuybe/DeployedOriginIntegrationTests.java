package com.ticketing.ticketbuybe;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.public-base-url=http://34.64.146.19","app.origins=http://localhost:5173,http://localhost:8080"})
class DeployedOriginIntegrationTests {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    HttpResponse<String> post(String path,Map<String,String> body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path))
            .header("Origin","http://34.64.146.19").header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void publicServerOriginCanSignUpAndLoginAndPreflightWhileOthersAreRejected() throws Exception {
        String username="origin_"+UUID.randomUUID().toString().replace("-","");
        var signup=post("/api/v1/auth/signup",Map.of("username",username,"password","Ticket1234!","name","서버 가입"));
        assertEquals(201,signup.statusCode());assertEquals("http://34.64.146.19",signup.headers().firstValue("access-control-allow-origin").orElseThrow());
        for(String account:List.of("demo","admin",username)) {
            var login=post("/api/v1/auth/login",Map.of("username",account,"password",account.equals(username)?"Ticket1234!":"Demo1234!"));
            assertEquals(200,login.statusCode());assertTrue(json.readTree(login.body()).has("accessToken"));
        }
        for(String origin:List.of("http://34.64.146.19","https://evil.example")) {
            var preflight=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/auth/signup"))
                .header("Origin",origin).header("Access-Control-Request-Method","POST").header("Access-Control-Request-Headers","content-type")
                .method("OPTIONS",HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(origin.equals("http://34.64.146.19")?200:403,preflight.statusCode());
        }
    }
}
