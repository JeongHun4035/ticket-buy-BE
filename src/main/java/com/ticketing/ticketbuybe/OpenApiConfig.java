package com.ticketing.ticketbuybe;

import org.springframework.context.annotation.*;
import org.springdoc.core.customizers.OpenApiCustomizer;
import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.*;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.*;

@Configuration
public class OpenApiConfig {
    @Bean OpenAPI api() {
        return new OpenAPI().info(new Info().title("TICKET BUY API").version("v1")
            .description("로그인 → accessToken 복사 → Authorize(Bearer) → /users/me → /events → /events/1/performances → /performances/101/queue → /queue/me → admissionToken 복사 → seats → holds → reservations. 개발 계정 demo / Demo1234!, 관리자 admin / Demo1234!. 날짜 ISO 8601 UTC, 금액 정수 KRW, 페이지 1부터. refresh 쿠키는 같은 브라우저에서 자동 전달됩니다. 영구 저장소 기반 실제 대기열이며 시뮬레이션/입장 우회 API는 제공하지 않습니다."))
            .components(new Components().addSecuritySchemes("bearerAuth",new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").description("로그인 응답 accessToken만 입력")));
    }
    @Bean OpenApiCustomizer examples(tools.jackson.databind.ObjectMapper json) { return api->{
        Map<String,String> samples=Map.ofEntries(
            Map.entry("User", """
                {"id":"1","name":"예매 사용자"}
                """),
            Map.entry("Auth", """
                {"accessToken":"opaque-access-token","tokenType":"Bearer","expiresAt":"2026-10-18T03:15:00Z","user":{"id":"1","name":"예매 사용자"}}
                """),
            Map.entry("Event", """
                {"id":1,"title":"서울 라이브 콘서트","description":"TICKET BUY 개발용 행사입니다.","category":"CONCERT","venue":"서울 아트홀","date":"2026-11-17T03:00:00Z","price":80000,"posterUrl":null,"status":"ON_SALE"}
                """),
            Map.entry("Performance", """
                {"id":101,"eventId":1,"venueId":1,"venue":"서울 아트홀","startsAt":"2026-11-17T03:00:00Z","opensAt":"2026-10-17T03:00:00Z","closesAt":"2026-11-16T03:00:00Z","status":"ON_SALE"}
                """),
            Map.entry("Queue", """
                {"state":"ADMITTED","ticketNumber":1,"aheadCount":0,"estimatedWaitSeconds":null,"admissionToken":"opaque-admission-token","admissionExpiresAt":"2026-10-18T03:10:00Z","pollAfterSeconds":30}
                """),
            Map.entry("Seat", """
                {"id":"1","row":"A","number":1,"x":1,"y":1,"grade":"VIP","price":150000,"status":"AVAILABLE"}
                """),
            Map.entry("Hold", """
                {"id":"hold-uuid","performanceId":101,"expiresAt":"2026-10-18T03:05:00Z","status":"ACTIVE","seats":[{"id":"1","row":"A","number":1,"x":1,"y":1,"grade":"VIP","price":150000,"status":"HELD"}],"total":150000,"currency":"KRW"}
                """),
            Map.entry("Reservation", """
                {"id":"reservation-uuid","performanceId":101,"status":"CONFIRMED","paymentStatus":"NOT_REQUIRED","total":150000,"currency":"KRW","createdAt":"2026-10-18T03:01:00Z","seats":[{"id":"1","row":"A","number":1,"grade":"VIP","price":150000}]}
                """),
            Map.entry("Content", """
                {"id":1,"category":"GUIDE","title":"몇 석까지 예매할 수 있나요?","content":"최대 4석을 5분간 확보할 수 있습니다.","date":"2026-10-18T03:00:00Z"}
                """));
        samples.forEach((name,sample)->{var schema=api.getComponents().getSchemas().get(name);if(schema!=null)schema.setExample(json.readValue(sample,Object.class));});
        api.getComponents().getSchemas().forEach((name,schema)->{
            if(name.startsWith("Page")) {
                String item=name.substring(4);
                if(samples.containsKey(item)) schema.setExample(Map.of("items",List.of(json.readValue(samples.get(item),Object.class)),"total",1,"page",1,"pageSize",20));
            }
        });
        var errorSchema=new ObjectSchema().addProperty("code",new StringSchema()).addProperty("message",new StringSchema())
            .addProperty("details",new ObjectSchema()).addProperty("traceId",new StringSchema());
        api.getComponents().addSchemas("Error",errorSchema);
        api.getPaths().forEach((path,item)->item.readOperations().forEach(op->{
            boolean secured=path.contains("/admin/")||path.contains("/performances/")||path.contains("/holds/")||path.contains("/reservations")||path.endsWith("/users/me");
            if(secured) op.addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
            Map<String,String> codes=new LinkedHashMap<>();
            codes.put("400","INVALID_REQUEST");codes.put("401","AUTHENTICATION_REQUIRED");codes.put("403",path.contains("/admin/")?"FORBIDDEN":"QUEUE_ADMISSION_REQUIRED");
            codes.put("404","NOT_FOUND");codes.put("409","SEAT_CONFLICT");codes.put("429","RATE_LIMITED");codes.put("500","INTERNAL_ERROR");
            codes.forEach((status,code)->{
                var media=new MediaType().schema(new Schema<>().$ref("#/components/schemas/Error"));
                media.addExamples(code,new io.swagger.v3.oas.models.examples.Example().value(Map.of("code",code,"message","요청을 처리할 수 없습니다.","details",Map.of(),"traceId","5285cfe8-3eba-4148-94db-57780d5f689b")));
                if(status.equals("409")) for(String c:List.of("HOLD_EXPIRED","HOLD_NOT_ACTIVE","SEAT_LIMIT_EXCEEDED","IDEMPOTENCY_CONFLICT","BOOKING_NOT_OPEN","BOOKING_CLOSED","CANCELLATION_CLOSED","CONCURRENT_REQUEST"))
                    media.addExamples(c,new io.swagger.v3.oas.models.examples.Example().value(Map.of("code",c,"message","상태를 확인하고 다시 시도하세요.","details",Map.of(),"traceId","5285cfe8-3eba-4148-94db-57780d5f689b")));
                if(status.equals("401")) for(String c:List.of("AUTHENTICATION_FAILED","TOKEN_EXPIRED")) media.addExamples(c,new io.swagger.v3.oas.models.examples.Example().value(Map.of("code",c,"message","다시 로그인하세요.","details",Map.of(),"traceId","5285cfe8-3eba-4148-94db-57780d5f689b")));
                op.getResponses().addApiResponse(status,new ApiResponse().description(code).content(new Content().addMediaType("application/json",media)));
            });
            if(op.getRequestBody()!=null && op.getRequestBody().getContent()!=null) {
                Object example=path.endsWith("/login")?Map.of("username","demo","password","Demo1234!"):
                    path.endsWith("/holds")?Map.of("seatIds",List.of(1,2)):
                    path.equals("/api/v1/reservations")?Map.of("holdId","replace-with-hold-id"):
                    Map.of("category","GUIDE","titles",Map.of("ko","이용 안내","en","Guide","zh-CN","指南"),"bodies",Map.of("ko","내용","en","Content","zh-CN","内容"));
                op.getRequestBody().getContent().values().forEach(m->m.setExample(example));
            }
            if(op.getParameters()!=null) for(var p:op.getParameters()) {
                if(p.getName().equals("X-Admission-Token")) {p.setDescription("ADMITTED 응답의 admissionToken. 본인·해당 회차에만 유효하며 만료 시 재진입 필요.");p.setExample("replace-with-admissionToken");}
                if(p.getName().equals("Idempotency-Key")) {p.setDescription("동일 요청 재시도는 동일 키 사용. 다른 holdId에는 새 키. 1~100자 영문/숫자/_.:-");p.setExample("booking-demo-1");}
                if(p.getName().endsWith("Id")&&p.getIn().equals("path")) {p.setDescription("대상 리소스 식별자");if(p.getName().equals("eventId"))p.setExample(1);if(p.getName().equals("performanceId"))p.setExample(101);}
            }
        }));
    }; }
}
