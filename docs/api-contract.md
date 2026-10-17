# API v1 계약

Base URL `/api/v1`. JSON UTF-8. 시각은 ISO 8601 UTC(Z), 프론트 표시는 `Intl.DateTimeFormat`과 사용자 언어/Asia/Seoul을 사용한다. 금액은 정수 KRW. 페이지는 1부터, pageSize 기본 20/최대 100. 목록 `{items,total,page,pageSize}`. 좌석 배치와 장르는 전체 목록 1페이지 반환. 중첩 seats는 부모 리소스 구성 요소다. 성공 본문·스키마·각 파라미터는 실행 중 `/v3/api-docs`에서도 확인할 수 있다.

## 인증 및 헤더

```http
POST /api/v1/auth/login
Content-Type: application/json
Origin: http://localhost:5173

{"username":"demo","password":"Demo1234!"}
```

200 응답(토큰은 형식 예시):

```json
{"accessToken":"opaque-random-token","tokenType":"Bearer","expiresAt":"2026-10-18T03:15:00Z","user":{"id":"1","name":"예매 사용자"}}
```

동시에 Set-Cookie refresh_token(HttpOnly/SameSite=Strict, prod Secure)를 설정한다. 후속 보호 API는 `Authorization: Bearer <accessToken>` 사용. `POST /auth/refresh`는 쿠키로 갱신(200/401), `POST /auth/logout`은 Bearer 또는 쿠키 세션 폐기(204). `GET /users/me`는 `{id,name}`(200/401). 브라우저는 credentials: include. 갱신 시 기존 access token 폐기. 비밀번호·토큰·쿠키는 로그/분석 이벤트에 기록하지 않는다.

## 행사

| 메서드/경로 | 입력·정렬 | 인증 |
| --- | --- | --- |
| GET /events | q(행사명·장소 부분 검색), category, sort, page, pageSize, lang | 공개 |
| GET /events/featured | page, pageSize, lang, 추천순 | 공개 |
| GET /events/{eventId} | 양수 eventId, lang | 공개 |
| GET /events/{eventId}/performances | page, pageSize, lang, 시작일시순 | 공개 |
| GET /categories | 코드·3개 언어 표시명 | 공개 |

sort: RECOMMENDED(추천순위 내림차순/id순), DATE_ASC(대표 회차 시작일시/id순), PRICE_ASC(최저가격/id순). category: CONCERT/MUSICAL/THEATER/EXHIBITION/CLASSICAL. lang: ko(기본)/en/zh-CN. 없는 번역은 ko.

`GET /events?q=서울&category=CONCERT&sort=PRICE_ASC&page=1&pageSize=12&lang=ko`:

```json
{"items":[{"id":1,"title":"서울 라이브 콘서트","description":"TICKET BUY 개발용 행사입니다.","category":"CONCERT","venue":"서울 아트홀","date":"2026-11-17T03:00:00Z","price":80000,"posterUrl":null,"status":"ON_SALE"}],"total":1,"page":1,"pageSize":12}
```

판매 상태: UPCOMING(오픈 전), ON_SALE(opensAt 이상 closesAt 미만), CLOSED(마감). 종료된 행사도 조회 가능. 오픈 예정/마감은 예매 버튼을 비활성화한다. 날짜는 시드 생성 시점에 따라 달라진다.

`GET /events/1/performances`:

```json
{"items":[{"id":101,"eventId":1,"venueId":1,"venue":"서울 아트홀","startsAt":"2026-11-17T03:00:00Z","opensAt":"2026-10-17T03:00:00Z","closesAt":"2026-11-16T03:00:00Z","status":"ON_SALE"}],"total":2,"page":1,"pageSize":20}
```

예매 진입은 eventId가 아니라 **선택한 performanceId**를 사용한다. 위 예시는 items 중 일부만 제시했다.

## 대기열

모두 Bearer 필요. POST /performances/{performanceId}/queue 진입(200), GET 같은 경로 /me로 상태 조회(200), DELETE /me 취소(204). 예: `/performances/101/queue/me`.

```json
{"state":"WAITING","ticketNumber":51,"aheadCount":0,"estimatedWaitSeconds":null,"admissionToken":null,"admissionExpiresAt":null,"pollAfterSeconds":3}
```

```json
{"state":"ADMITTED","ticketNumber":1,"aheadCount":0,"estimatedWaitSeconds":null,"admissionToken":"opaque-admission-token","admissionExpiresAt":"2026-10-18T03:10:00Z","pollAfterSeconds":30}
```

ticketNumber는 회차별 누적 발급 순번, aheadCount는 앞선 WAITING 인원(입장자는 제외). 첫 대기자는 앞선 대기자 0명이어도 입장자 용량이 차면 대기한다. 예상 시간 추정 근거가 없어 null. 진행률은 임의 계산하지 않는다. WAITING → ADMITTED → EXPIRED, 또는 취소 시 CANCELLED. 동일 사용자 활성 재진입은 같은 상태. EXPIRED/CANCELLED 재진입은 새 순번. 토큰은 재조회로 연장되지 않는다. 입장 권한은 해당 사용자·회차에만 유효하다. 미진입 상태 조회는 404. 판매 시작 전/마감 진입은 409.

## 좌석·확보

GET /performances/{performanceId}/seats: Bearer + `X-Admission-Token` 필수. 서버가 판매 기간과 권한을 재검증한다.

```json
{"items":[{"id":"1","row":"A","number":1,"x":1,"y":1,"grade":"VIP","price":150000,"status":"AVAILABLE"}],"total":96,"page":1,"pageSize":96}
```

실제 응답은 전체 96석이며 위는 한 좌석만 제시했다. grade VIP/R/S. status AVAILABLE/HELD/RESERVED. 배치는 row/number 및 x/y 좌표로 렌더링한다. 무대는 배치 상단에 표시한다. 임의 개수·등급 배열을 프론트에 고정하지 않는다.

```http
POST /api/v1/performances/101/holds
Authorization: Bearer <accessToken>
X-Admission-Token: <admissionToken>
Content-Type: application/json

{"seatIds":[1,2]}
```

201:

```json
{"id":"generated-hold-uuid","performanceId":101,"expiresAt":"2026-10-18T03:05:00Z","status":"ACTIVE","seats":[{"id":"1","row":"A","number":1,"x":1,"y":1,"grade":"VIP","price":150000,"status":"HELD"},{"id":"2","row":"A","number":2,"x":2,"y":1,"grade":"VIP","price":150000,"status":"HELD"}],"total":300000,"currency":"KRW"}
```

요청 seatIds는 양수 정수·중복 불가·1~4석. 사용자 기존 ACTIVE 확보 포함 회차별 최대 4석. 충돌은 409, 부분 확보 없음. 409 SEAT_CONFLICT 시 재고를 다시 조회하고 재선택한다. 5분 만료 기본, 해제는 DELETE /holds/{holdId}(204, Bearer). 타인 확보는 404. 확보 상태 내부 코드 ACTIVE/EXPIRED/RELEASED/CONSUMED. 다른 사용자의 holdId나 소유자 정보는 좌석 API에서 노출하지 않는다.

## 예매

```http
POST /api/v1/reservations
Authorization: Bearer <accessToken>
X-Admission-Token: <admissionToken>
Idempotency-Key: booking-unique-action-1
Content-Type: application/json

{"holdId":"generated-hold-uuid"}
```

201:

```json
{"id":"generated-reservation-uuid","performanceId":101,"status":"CONFIRMED","paymentStatus":"NOT_REQUIRED","total":300000,"currency":"KRW","createdAt":"2026-10-18T03:01:00Z","seats":[{"id":"1","row":"A","number":1,"grade":"VIP","price":150000},{"id":"2","row":"A","number":2,"grade":"VIP","price":150000}]}
```

서버 가격만 사용한다. 입장/확보 만료/소유권/재고를 검증한다. 생성 성공 후 네트워크 실패 시 **같은 키와 holdId**로 재시도한다. 같은 키는 항상 기존 예매 반환(201), 다른 holdId 재사용은 409. 새 키로 소비한 hold 재사용도 409. 인증은 필요하지만 이미 성공한 멱등 요청 반환에는 유효 입장 권한을 다시 요구하지 않는다. 결제 없이 CONFIRMED이며 NOT_REQUIRED를 결제 완료라고 표시하지 않는다.

- GET /reservations/me?page=1&pageSize=20: 본인 예매, 최신순, Page<Reservation>.
- GET /reservations/{reservationId}: 본인 상세. 타인은 404.
- POST /reservations/{reservationId}/cancel: 공연 시작 전 취소(200), 반복 호출은 동일 CANCELLED 응답. 좌석 재판매, 결제 상태 NOT_REQUIRED 유지. 시작 이후 409 CANCELLATION_CLOSED.

## 고객센터·콘텐츠

GET /support/info: serviceName/phone/email/timezone/message. 미설정 연락처는 null. GET /faqs, /posts: q(제목/내용 부분 검색), page/pageSize/lang, 최신순 Page<Content>. GET /posts/{postId}?lang=ko: Content.

```json
{"id":1,"category":"GUIDE","title":"몇 석까지 예매할 수 있나요?","content":"회차별 임시 확보는 최대 4석이며 5분간 유지됩니다.","date":"2026-10-18T03:00:00Z"}
```

관리 경로는 /admin/faqs, /admin/posts. POST 등록(201), PATCH /{id} 수정(200, 맵 전체 교체), DELETE /{id} 삭제(204). 모두 ADMIN Bearer 필요. 요청:

```json
{"category":"GUIDE","titles":{"ko":"이용 안내","en":"Guide","zh-CN":"指南"},"bodies":{"ko":"내용","en":"Content","zh-CN":"内容"}}
```

category NOTICE/GUIDE/UPDATE. titles는 200자, bodies 20000자 제한. ko 필수이며 번역은 선택. 모든 내용은 plain text로 렌더링한다.

## 공통 오류

```json
{"code":"SEAT_CONFLICT","message":"다른 사용자가 선택했거나 존재하지 않는 좌석입니다.","details":{},"traceId":"5285cfe8-3eba-4148-94db-57780d5f689b"}
```

traceId는 X-Trace-Id 응답 헤더에도 있다. 클라이언트 분기는 message 번역 대신 code로 한다. 현재 details는 확장용 빈 객체다.

| HTTP | code | 처리 |
| --- | --- | --- |
| 400 | INVALID_REQUEST | 필드·enum·페이지·헤더 확인 |
| 401 | AUTHENTICATION_REQUIRED / AUTHENTICATION_FAILED / TOKEN_EXPIRED | refresh 또는 재로그인 |
| 403 | FORBIDDEN / ORIGIN_NOT_ALLOWED | 관리자 권한/허용 origin 확인 |
| 403 | QUEUE_ADMISSION_REQUIRED | 대기열 상태 조회·재진입 |
| 404 | NOT_FOUND | 없는 리소스/타인 리소스/미진입 대기열 |
| 409 | SEAT_CONFLICT | 좌석 재조회·재선택 |
| 409 | SEAT_LIMIT_EXCEEDED | 기존 확보 해제 후 선택 |
| 409 | HOLD_EXPIRED / HOLD_NOT_ACTIVE | 좌석 다시 확보 |
| 409 | BOOKING_NOT_OPEN / BOOKING_CLOSED | 판매 기간 확인 |
| 409 | IDEMPOTENCY_CONFLICT | 새 동작에 새 키 사용 |
| 409 | CANCELLATION_CLOSED | 공연 시작 후 취소 불가 |
| 409 | CONCURRENT_REQUEST | 잠시 후 안전한 재시도 |
| 429 | RATE_LIMITED | Retry-After(초)만큼 대기 |
| 500 | INTERNAL_ERROR | traceId로 문의, 내부 정보 노출 없음 |

## 기존 프론트 교체

`docs/frontend-example.ts`를 참고한다. VITE_API_BASE_URL=http://localhost:8080/api/v1 설정. 목록은 response.items로 바꾸고 total/page/pageSize를 페이지 UI에 반영한다. genre/status 표시문자 대신 코드로 분기한다. art는 제거하고 posterUrl 또는 로컬 fallback 이미지 사용. date와 price는 null 가능하여 빈 값 처리하고 실제 예매는 회차를 선택해야 한다. CLOSED 표시를 기존 두 상태 union에 추가한다.

AuthUser는 그대로 {id,name}. FAQ question←title, answer←content. 게시글 category는 번역 코드, date는 ISO. 좌석 reserved←status !== AVAILABLE, 현재 내 확보는 별도의 selected 상태로 표시한다. 홀드 결과 서버 total을 최종 금액으로 사용하고 expiresAt 기준 타이머를 표시한다. 선택만 한 좌석은 확보된 것이 아니며 확보 POST 성공 후만 HELD로 표시한다.

30초 대기 시뮬레이션/바로 입장 버튼은 제거한다. 응답 pollAfterSeconds 주기로 직렬 폴링하고 ADMITTED일 때만 좌석 화면으로 이동한다. estimatedWaitSeconds=null이면 '계산 중' 또는 '예상 시간 제공 불가' 표시. 진행률은 고정 30000 기준으로 계산하지 않는다. 컴포넌트 해제 시 타이머/요청을 취소한다. 보유한 입장 토큰·hold id·멱등 키는 해당 동작 동안 유지하되 로그나 URL에 넣지 않는다. 오류가 나면 좌석/대기열을 서버에서 다시 조회한다.
