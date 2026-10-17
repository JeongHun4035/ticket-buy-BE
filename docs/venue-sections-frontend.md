# 공연장 규모·층·구역 프론트 전달 사항

## 실행과 실제 테스트 데이터

앱을 재시작하면 Flyway V3가 기존 좌석/예매 ID를 유지하면서 층·구역을 추가한다. V1/V2를 수정하거나 개발 DB를 지울 필요가 없다. V3는 기존 V1의 이름 없는 UNIQUE 제약을 H2/PostgreSQL에서 찾아 변경해야 하므로 Java 마이그레이션(`src/main/java/db/migration/V3__venue_sections.java`)으로 관리한다.

dev 프로필에서 다음 데이터가 DB에 생성된다. 기존 96석 예제 행사/회차도 유지한다. 추가 시드는 기존 사용자 존재 여부와 무관하게 실행되고, 고정 ID를 기준으로 누락된 데이터만 추가한다. 재실행 시 판매 날짜·가격·확보·예매 상태를 초기화하지 않는다. prod가 활성화되면 dev도 함께 지정했더라도 개발 시드는 실행되지 않는다. 배치는 개발용 합성 배치이며 실제 건축 도면이나 실공연장의 좌석도를 의미하지 않는다.

| 행사 ID | 공연장 ID / 이름 | 좌석 수 | 층 수 | 구역 수 | 판매 중 회차 ID | 첫 구역 ID | 첫 좌석 ID |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 10 | 10 / 소극장 500 | 500 | 1 | 2 | 1001, 1002 | 1000001 | 10000001 |
| 20 | 20 / 그랜드홀 5000 | 5,000 | 2 | 20 | 2001, 2002 | 2000001 | 20000001 |
| 30 | 30 / 스타디움 30000 | 30,000 | 3 | 120 | 3001, 3002 | 3000001 | 30000001 |

각 구역은 250석(A~J열, 각 25석)이다. 같은 A열 1번이 다른 구역에도 존재하므로 좌석은 반드시 id로 식별한다. 예: 소극장 1구역 A열 1번=`10000001`, 2구역 A열 1번=`10000251`. 구역을 바꾸거나 새로고침해도 이 ID는 동일하며 같은 공연장 두 회차는 물리 좌석 ID를 공유하지만 재고·가격은 독립적이다. 선택 상태를 저장할 때는 회차 ID와 좌석 ID를 함께 사용한다.

`GET /api/v1/events/10`, `/events/20`, `/events/30`으로 행사/공연장/규모를 확인하고, `/events/{eventId}/performances`로 회차를 확인한다. 판매 기간은 최초 생성 시점 기준이므로 오래된 시드가 마감됐다면 기간을 검토해야 한다. 시드 재실행으로 날짜를 자동 연장하지 않는다.

## 인증과 호출 흐름

기존처럼 로그인 → 회차 대기열 진입 → ADMITTED 확인 → 구역 조회 → 구역 선택 → 해당 구역 좌석 조회 → 확보 → 예매 순서다. 아래 모든 공연장/좌석 GET에는 동일하게 두 헤더가 필요하다.

```http
Authorization: Bearer <accessToken>
X-Admission-Token: <해당 사용자·회차의 admissionToken>
```

| 경로 | 응답 | 용도 |
| --- | --- | --- |
| GET /api/v1/performances/{performanceId}/sections | Sections | 공연장·층·구역 위치와 잔여 수 |
| GET /api/v1/performances/{performanceId}/sections/{sectionId}/seats | Page<Seat> | 선택한 구역의 좌석만 조회 |
| GET /api/v1/performances/{performanceId}/seats | Page<Seat> | 기존 전체 조회 유지. 대형 공연장에는 구역 API 권장 |

`lang=ko`(기본), `en`, `zh-CN` 지원. 번역이 없으면 ko. 확보/예매 응답의 좌석 위치 표시는 현재 기본 언어 ko이며, 식별은 언어 독립 ID를 사용한다.

## 응답 예시

소극장 회차 1001, 확보/예매 전 `GET /performances/1001/sections`의 응답 형태:

```json
{
  "venue": {"id":10,"name":"소극장 500","capacity":500,"mapWidth":1000,"mapHeight":1000},
  "floors": [{"id":"10001","name":"1층","level":1}],
  "items": [
    {"id":"1000001","name":"1구역","floor":"1층","floorId":"10001","totalSeats":250,"availableSeats":250,"x":20,"y":50,"width":150,"height":150,"sortOrder":1},
    {"id":"1000002","name":"2구역","floor":"1층","floorId":"10001","totalSeats":250,"availableSeats":250,"x":200,"y":50,"width":150,"height":150,"sortOrder":2}
  ],
  "total":2,"page":1,"pageSize":2
}
```

구역 x/y/width/height는 `venue.mapWidth × mapHeight` 지도에서 해당 층 구역 사각형의 위치/크기다. 층별로 지도를 전환하며 다른 층의 동일 좌표를 한 평면에 겹쳐 그리지 않는다. sections의 total/pageSize는 **구역 개수**이며, 좌석 합계는 items의 totalSeats 합이다. venue.capacity는 공연장 전체 배치 좌석 수, 각 totalSeats는 해당 회차에 편성된 구역 좌석 수다.

`GET /performances/1001/sections/1000001/seats` 응답 중 첫 좌석만 발췌(실제 items는 250개):

```json
{
  "items": [{
    "id":"10000001","row":"A","number":1,"x":1,"y":1,
    "grade":"VIP","price":150000,"status":"AVAILABLE",
    "sectionId":"1000001","section":"1구역","floor":"1층","floorId":"10001"
  }],
  "total":250,"page":1,"pageSize":250
}
```

좌석 x/y는 구역 내부의 배치 좌표이며 개요 지도 사각형 x/y와 별개다. 1~25, 1~10 좌표를 프론트 그리드 간격에 맞춰 렌더링한다. 실제 전체 JSON: [500석 구역](examples/sections-500.json), [5,000석 구역](examples/sections-5000.json), [30,000석 구역](examples/sections-30000.json), [선택 구역 좌석 250개](examples/section-seats-500.json), [규모별 행사](examples/venue-events.json). 각 규모의 구역 좌석 응답도 `examples/section-seats-{규모}.json`에 저장했다. 예제 파일은 생성 직후 스냅샷이며 운영 재고로 사용하지 않는다.

행사 목록/상세에는 기존 venue 문자열에 더해 `venueId`, `venueCapacity`가 추가된다. 회차 목록에도 `venueCapacity`가 추가된다. 예: `/events/10`의 venue=`소극장 500`, venueId=10, venueCapacity=500. 행사의 대표 장소는 기존처럼 대표 회차 기준이며, 실제 예매 장소는 선택한 회차를 기준으로 한다.

전체/구역 좌석 및 확보 응답의 seats에 `sectionId`, `section`, `floor`, `floorId`를 추가했다. 예매 상세 seats에도 같은 필드를 추가해 구역이 다른 A열 1번을 구분할 수 있다. 기존 id/row/number/x/y/grade/price/status와 예매·확보 요청 구조는 유지한다. 예매 좌석 항목에는 기존처럼 x/y/status가 없다.

## 여러 구역 함께 확보

```http
POST /api/v1/performances/1001/holds
Authorization: Bearer <accessToken>
X-Admission-Token: <admissionToken>
Content-Type: application/json

{"seatIds":[10000001,10000251]}
```

서버 가격은 각각 VIP 150000원, R 110000원, total=260000원이다. 회차 1002의 같은 좌석은 각각 155000/115000원이며 별도 재고다. 기존 모든 ACTIVE 확보를 합쳐 회차별 최대 4석이며 구역을 바꿔도 제한이 초기화되지 않는다. 확보 id로 `/reservations`를 호출하는 방식과 Idempotency-Key는 동일하다.

## 프론트 수정 사항

1. `AuthUser`와 기존 인증/대기열 흐름은 유지한다. ADMITTED 후 전체 좌석 대신 sections를 먼저 요청한다.
2. 층/구역 선택 UI는 서버의 floorId/sectionId를 사용한다. 데이터가 있으므로 임의 200석 표시 블록으로 대체하지 않는다. 전체 조회 fallback을 사용해도 먼저 sectionId로 묶고, 구역 내부에서만 시각적으로 나눈다.
3. 전체 30,000석을 DOM에 그리지 않고 선택 구역만 표시한다. 구역 내 250석은 필요하면 가상화한다. 구역 전환 시 이전 요청을 취소하고 늦게 도착한 응답이 현재 구역을 덮어쓰지 않게 한다.
4. 선택·확보 좌석은 구역 전환과 별도로 회차 단위에 보관하고 seat.id를 React key로 사용한다. 표시 라벨은 `floor + section + row + number`를 사용한다. 표시 이름으로 동일성을 판단하지 않는다.
5. 구역 잔여 수는 참고 값이다. HELD는 아직 만료되지 않은 확보이며 만료된 확보는 worker 정리 전에도 AVAILABLE로 계산된다. 좌석·잔여 수는 서로 다른 요청 사이에 달라질 수 있다. 최종 hold의 409 SEAT_CONFLICT를 처리하고 재조회한다.
6. 대기열/입장 만료·확보 만료·해제·취소 처리는 유지한다. 다른 회차로 이동하면 선택과 해당 회차 입장 토큰을 분리한다. 금액은 확보/예매 응답의 서버 total을 사용한다.
7. 전체 좌석과 모든 구역 좌석 조회는 사용자·회차당 최소 2초 간격을 공유한다. 구역 개요는 별도 2초 간격이다. 빠른 구역 전환에는 요청 debounce와 429 Retry-After 후 재시도를 적용한다. 실제 간격은 X-Poll-After를 확인한다. 모든 조회는 no-store이다.

호출 타입/함수 예시는 `docs/frontend-example.ts`의 SectionDto, SectionsDto, getSections, getSectionSeats를 참고한다.

## 오류·검증 한계

기존 공통 오류 `{code,message,details,traceId}`를 사용한다. 400 INVALID_REQUEST(잘못된 ID/lang/헤더), 401 인증 실패, 403 QUEUE_ADMISSION_REQUIRED, 404 NOT_FOUND(다른 공연장/회차의 구역 포함), 409 BOOKING_NOT_OPEN/BOOKING_CLOSED, 429 RATE_LIMITED. 확보는 409 SEAT_CONFLICT/SEAT_LIMIT_EXCEEDED/HOLD_EXPIRED 등 기존 계약을 따른다.

구역 집계는 SQL GROUP BY이며 좌석 객체를 전부 반환하거나 로딩하지 않는다. 구역 재고 조회는 `(performance_id,section_id,seat_id)` 인덱스를 사용하도록 조건을 구성했다. 최종 확보·예매는 기존 회차 잠금/조건부 UPDATE/멱등성 제약을 유지하며 복합 FK로 공연장·구역·좌석·회차 연결도 검증한다. 수만 석 데이터 생성과 기능 테스트는 대규모 트래픽 처리량 검증을 의미하지 않는다. PostgreSQL 실제 실행·실행 계획·부하 검증과 다중 서버 전역 요청 제한은 별도 작업이다.
