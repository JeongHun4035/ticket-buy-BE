# API v1 계약

관리자 행사 등록은 `POST /api/v1/admin/events`(multipart event JSON + image 파일)이며 공연장/구역 선택과 이미지 저장 정책은 [관리자 행사·이미지 계약](admin-events.md)을 참고한다. dev 행사 포스터 8개와 PUBLIC_BASE_URL 기반 posterUrl을 제공한다.

Base URL `/api/v1`. JSON UTF-8. 시각은 ISO 8601 UTC(Z), 프론트 표시는 `Intl.DateTimeFormat`과 사용자 언어/Asia/Seoul을 사용한다. 금액은 정수 KRW. 페이지는 1부터, pageSize 기본 20/최대 100. 목록 `{items,total,page,pageSize}`. 좌석 배치와 장르는 전체 목록 1페이지 반환. 중첩 seats는 부모 리소스 구성 요소다. 성공 본문·스키마·각 파라미터는 실행 중 `/v3/api-docs`에서도 확인할 수 있다.

공연장·층·구역 기능은 [프론트 전달 문서](venue-sections-frontend.md)에 실제 ID·전체 JSON·좌표 규칙과 함께 정리했다. `GET /performances/{performanceId}/sections`와 `GET /performances/{performanceId}/sections/{sectionId}/seats`를 추가했으며 기존 전체 좌석 조회도 유지한다. 두 API 모두 Bearer + X-Admission-Token 필수다. 구역/전체 좌석은 고정 1페이지이며 pageSize는 실제 반환 개수라 일반 목록의 최대 100 제한과 다르다. 좌석·확보·예매 좌석 항목에 sectionId/section/floor/floorId, 행사에 venueId/venueCapacity, 회차에 venueCapacity를 추가했다. 하단의 기존 응답 예시는 주요 필드 중심이며 최신 전체 예시는 Swagger와 별도 전달 문서를 참고한다.

## 인증 및 헤더

### 회원가입

`POST /api/v1/auth/signup`은 인증 없이 호출한다. 가입 후 별도 로그인하며 토큰/쿠키를 발급하지 않는다.

```json
{"username":"ticket_user","password":"Ticket1234!","name":"티켓 사용자"}
```

성공 201 응답은 `{"id":"4","name":"티켓 사용자","role":"USER"}`이다. username은 영문·숫자·밑줄 4~80자, 대소문자를 구분하고 공백을 허용하지 않는다. password는 영문과 숫자를 모두 포함한 공백 없는 ASCII 8~72자이며 특수문자 사용이 가능하다. BCrypt의 72바이트 한도를 넘는 입력은 거부한다. name은 공백만 입력할 수 없고 제어문자 없이 최대 100자이며 저장 시 앞뒤 공백을 제거한다. 사용자 권한은 서버가 항상 USER로 지정한다.

입력 오류는 400 INVALID_REQUEST, 중복 아이디는 409 USERNAME_ALREADY_EXISTS이다. 동시 가입도 DB UNIQUE 제약으로 한 건만 생성된다. 기존 인증 API와 동일한 Origin/CORS 및 요청 제한(30회/분)을 적용한다. 이메일/휴대폰 인증은 제공하지 않는다. 기존 app_user 스키마를 사용하므로 추가 마이그레이션은 필요 없다.

### 로그인·세션

```http
POST /api/v1/auth/login
Content-Type: application/json
Origin: http://localhost:5173

{"username":"demo","password":"Demo1234!"}
```

200 응답(토큰은 형식 예시):

```json
{"accessToken":"opaque-random-token","tokenType":"Bearer","expiresAt":"2026-10-18T03:15:00Z","user":{"id":"1","name":"예매 사용자","role":"USER"}}
```

동시에 Set-Cookie refresh_token(HttpOnly/SameSite=Strict, prod Secure)를 설정한다. 후속 보호 API는 `Authorization: Bearer <accessToken>` 사용. `POST /auth/refresh`는 쿠키로 갱신(200/401), `POST /auth/logout`은 Bearer 또는 쿠키 세션 폐기(204). `GET /users/me`는 `{id,name,role}`(200/401). 브라우저는 credentials: include. 갱신 시 기존 access token 폐기. 비밀번호·토큰·쿠키는 로그/분석 이벤트에 기록하지 않는다.

권한 코드는 `USER`(일반 사용자), `ADMIN`(관리자)이다. 로그인·갱신 응답의 user.role과 회원가입·내 정보 응답의 role에 포함된다. 프론트는 `user.role === 'ADMIN'`으로 콘텐츠 관리 메뉴를 표시한다. 클라이언트에서 이 값을 바꾸더라도 서버는 DB의 권한을 검사한다. 회원가입 요청으로 권한을 지정하거나 변경할 수 없다. 기존 DB role 컬럼과 코드가 동일하므로 스키마 마이그레이션은 필요 없다.

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
{"state":"WAITING","ticketNumber":51,"aheadCount":0,"position":1,"estimatedWaitSeconds":null,"admissionToken":null,"admissionExpiresAt":null,"pollAfterSeconds":3}
```

```json
{"state":"ADMITTED","ticketNumber":1,"aheadCount":0,"position":null,"estimatedWaitSeconds":null,"admissionToken":"opaque-admission-token","admissionExpiresAt":"2026-10-18T03:10:00Z","pollAfterSeconds":30}
```

ticketNumber는 회차별 누적 발급 순번, aheadCount는 앞선 WAITING 인원(입장자는 제외). position은 WAITING일 때 aheadCount+1, 그 외 null이다. UI에는 position을 '현재 대기순서', ticketNumber를 '발급 번호'로 구분해 표시한다. 유효 입장자 수가 QUEUE_CAPACITY(기본 50) 미만이고 앞선 WAITING이 없으면 즉시 ADMITTED와 admissionToken을 반환한다. 그 외 WAITING이며 작업자가 기본 1초 주기로 용량 내에서 FIFO 승격한다. 프론트는 ADMITTED이면 대기 화면 없이 좌석 선택으로 이동하고 WAITING일 때만 position을 표시한다. ticketNumber는 즉시 입장에도 유지되는 누적 발급 번호다. GET은 DB 잠금·갱신 없이 상태를 조회하며 승격을 수행하지 않는다. 첫 대기자는 앞선 대기자 0명이어도 입장자 용량이 차면 대기한다. 예상 시간 추정 근거가 없어 null. 진행률은 임의 계산하지 않는다. WAITING → ADMITTED → EXPIRED, 또는 취소 시 CANCELLED. 동일 사용자 활성 재진입은 같은 상태. EXPIRED/CANCELLED 재진입은 새 순번. 토큰은 재조회로 연장되지 않는다. 입장 권한은 해당 사용자·회차에만 유효하다. 미진입 상태 조회는 404. 판매 시작 전/마감 진입은 409.

GET queue/me는 사용자·회차별 최소 조회 간격 기본 3초, GET seats는 기본 2초다. 두 경로의 간격 제한은 독립이며 다른 사용자와 공유하지 않는다. 응답은 Cache-Control: no-store, X-Poll-After(초)를 제공한다. 빠른 반복 요청은 429 RATE_LIMITED 및 Retry-After(남은 초)를 반환한다. 프론트는 이 시간 이후 약간의 양수 jitter를 더해 재요청하고, 이전 요청이 완료되기 전에 중복 폴링하지 않는다. 제한은 현재 프로세스 단위이며 다중 인스턴스 전역 제한은 게이트웨이가 필요하다.

## 좌석·확보

GET /performances/{performanceId}/seats: Bearer + `X-Admission-Token` 필수. 서버가 판매 기간과 권한을 재검증한다.

이 조회는 performance 행을 잠그거나 만료 정리를 수행하지 않는다. worker가 아직 DB 참조를 정리하지 않았더라도 확보 만료 시각이 지났으면 AVAILABLE로 표시한다. 조회 결과는 스냅샷이며 좌석 확보 성공을 보장하지 않는다. 확보·예매 API는 트랜잭션에서 재고와 권한을 다시 검증하므로 조회 후 충돌은 409로 처리한다.

```json
{"items":[{"id":"1","row":"A","number":1,"x":1,"y":1,"grade":"VIP","price":150000,"status":"AVAILABLE"}],"total":96,"page":1,"pageSize":96,"availableSeats":96}
```

전체 및 구역 좌석 응답은 기존 items/total/page/pageSize에 availableSeats를 추가한다. total은 조회 범위의 전체 좌석 수, availableSeats는 같은 items에서 AVAILABLE인 잔여좌석 수다. 전체 좌석 API는 회차 전체, 구역 좌석 API는 해당 구역만 집계한다. 유효 HELD와 RESERVED는 제외하며 만료된 확보는 포함한다. 조회 시점 참고 값으로 최종 확보를 보장하지 않는다.

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

URL 및 저장/삭제 서비스 양쪽에서 ADMIN 권한을 검사한다. 비로그인 사용자는 401 AUTHENTICATION_REQUIRED, 일반 사용자는 403 FORBIDDEN이며 데이터가 변경되지 않는다. 공개 GET /faqs, /posts, /posts/{postId}는 관리자 여부와 관계없이 조회할 수 있다. 프론트 관리 메뉴 표시와 별개로 서버가 접근을 차단한다.

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
| 409 | USERNAME_ALREADY_EXISTS | 다른 아이디로 가입 |
| 409 | CANCELLATION_CLOSED | 공연 시작 후 취소 불가 |
| 409 | CONCURRENT_REQUEST | 잠시 후 안전한 재시도 |
| 429 | RATE_LIMITED | Retry-After(초)만큼 대기 |
| 500 | INTERNAL_ERROR | traceId로 문의, 내부 정보 노출 없음 |

## 기존 프론트 교체

`docs/frontend-example.ts`를 참고한다. VITE_API_BASE_URL=http://localhost:8080/api/v1 설정. 목록은 response.items로 바꾸고 total/page/pageSize를 페이지 UI에 반영한다. genre/status 표시문자 대신 코드로 분기한다. art는 제거하고 posterUrl 또는 로컬 fallback 이미지 사용. date와 price는 null 가능하여 빈 값 처리하고 실제 예매는 회차를 선택해야 한다. CLOSED 표시를 기존 두 상태 union에 추가한다.

AuthUser는 {id,name,role}로 확장한다(role: 'USER' | 'ADMIN'). FAQ question←title, answer←content. 게시글 category는 번역 코드, date는 ISO. 좌석 reserved←status !== AVAILABLE, 현재 내 확보는 별도의 selected 상태로 표시한다. 홀드 결과 서버 total을 최종 금액으로 사용하고 expiresAt 기준 타이머를 표시한다. 선택만 한 좌석은 확보된 것이 아니며 확보 POST 성공 후만 HELD로 표시한다.

30초 대기 시뮬레이션/바로 입장 버튼은 제거한다. 응답 pollAfterSeconds 주기로 직렬 폴링하고 ADMITTED일 때만 좌석 화면으로 이동한다. estimatedWaitSeconds=null이면 '계산 중' 또는 '예상 시간 제공 불가' 표시. 진행률은 고정 30000 기준으로 계산하지 않는다. 컴포넌트 해제 시 타이머/요청을 취소한다. 보유한 입장 토큰·hold id·멱등 키는 해당 동작 동안 유지하되 로그나 URL에 넣지 않는다. 오류가 나면 좌석/대기열을 서버에서 다시 조회한다.
