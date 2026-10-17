# 관리자 행사 등록·이미지 첨부

## API와 등록 범위

| API | 권한 | 용도 |
| --- | --- | --- |
| GET /api/v1/admin/venues?page=1&pageSize=20 | ADMIN Bearer | 공연장 선택, 규모 확인 |
| GET /api/v1/admin/venues/{venueId}/sections | ADMIN Bearer | 대기열 없이 공연장 층·구역·좌석 수 조회 |
| POST /api/v1/admin/events | ADMIN Bearer | 포스터 파일과 행사·회차·구역 가격을 함께 등록 |
| GET /api/v1/event-images/{imageId} | 공개 | 첨부 이미지 바이트(PNG/JPEG) 조회 |

관리 URL과 서비스 양쪽에서 ADMIN을 검사한다. 비로그인 401, 일반 USER 403. 신규 등록에는 대기열 토큰이 필요 없다. 이후 사용자의 좌석 조회/확보에는 기존처럼 회차 대기열 입장이 필요하다. 공연장 자체를 생성·수정하는 API, 행사 수정·삭제·포스터 교체는 이번 범위에 없다.

POST는 `multipart/form-data`, 파트 두 개를 받는다.

- `event`: **Content-Type: application/json**. [실행 가능한 요청 JSON 예시](examples/event-create-request.json).
- `image`: **image/png 또는 image/jpeg** 파일, 필수. 최대 5MiB, 요청 전체 6MiB. 각 변 최대 6000px, 전체 2000만 픽셀. 파일 확장자가 아닌 실제 디코딩 형식도 검사한다. 업로드 SVG/GIF/WebP는 받지 않는다.

행사 제목 titles·설명 descriptions는 ko 필수, en/zh-CN 선택이며 번역 fallback은 기존과 같다. 제목 최대 200자, 설명 최대 20000자. category는 기존 장르 코드, featuredRank는 0~100000(기본 0, 추천 제외)이다.

performances는 1~20개이며 venueId, startsAt/opensAt/closesAt(ISO 8601), sectionPrices를 받는다. `opensAt < closesAt <= startsAt`, startsAt/closesAt은 미래여야 한다. 예시 날짜가 지난 시점에는 날짜를 수정한다. sectionPrices는 구역당 `{sectionId,grade,price}`이며 한 회차에 같은 구역을 중복 지정할 수 없다. grade는 VIP/R/S, price는 정수 KRW 0~100000000이다. 지정한 구역의 **전체 좌석만** 해당 회차 재고로 편성한다. 모든 구역을 지정하면 전체 공연장을 판매하고, 일부만 지정하면 나머지는 그 회차에서 조회·확보할 수 없다. 가격/등급은 회차별로 독립적이다.

DB에서 행사/회차 ID를 할당하므로 클라이언트는 id를 보내지 않는다. 이미지·행사·회차·재고는 하나의 트랜잭션이며 실패 시 함께 rollback한다. 이미지는 재인코딩하여 메타데이터/후행 데이터를 제거하고 원본 파일명을 저장 경로에 사용하지 않는다. 저장 방식은 DB BYTEA이며 별도 로컬 업로드 디렉터리가 없다. 클라우드 DB에서도 동일하게 동작하지만 대량 이미지·CDN 최적화는 객체 저장소 도입 시 별도로 설계한다. 등록 요청 자체는 멱등 API가 아니므로 제출 버튼 중복 클릭을 막고 응답을 기다려야 한다.

## Swagger 및 curl

`/swagger-ui/index.html`에서 admin으로 로그인하고 Authorize를 설정한다. 관리자 공연장/구역 API에서 ID를 선택한 뒤 POST /admin/events의 event 입력에 JSON을 넣고 image 파일을 선택한다. event 파트에 application/json 인코딩을 지정해 두었다. 성공 201 응답은 `{event,performances}`이며 event는 기존 행사 DTO, performances는 생성된 회차 DTO 배열이다. 반환 ID와 posterUrl을 사용한다.

```powershell
curl.exe -X POST http://localhost:8080/api/v1/admin/events `
  -H "Authorization: Bearer <adminAccessToken>" `
  -F "event=@docs/examples/event-create-request.json;type=application/json" `
  -F "image=@poster.png;type=image/png"
```

등록 후 응답 예시의 주요 필드:

```json
{
  "event": {
    "id":100000,"title":"새로운 라이브 콘서트","category":"CONCERT",
    "venue":"소극장 500","venueId":10,"venueCapacity":500,
    "posterUrl":"http://localhost:8080/api/v1/event-images/발급된-이미지-UUID",
    "status":"ON_SALE","price":110000
  },
  "performances":[{"id":100000,"eventId":100000,"venueId":10,"venue":"소극장 500","venueCapacity":500,"status":"ON_SALE"}]
}
```

위는 주요 필드 발췌이며 실제 응답에는 설명·포스터·날짜와 회차 판매 기간도 포함된다. ID는 예시이며 실제 발급 결과를 사용한다. 등록 후 `/events`, `/events/{id}`, `/events/{id}/performances`로 조회 가능하다. 실제 실행 응답은 [등록 응답](examples/event-created.json)에 저장한다.

## 프론트 연결

`docs/frontend-example.ts`에 EventCreateDto, registerEvent, getAdminVenues, getAdminVenueSections를 추가했다. `user.role === 'ADMIN'`에만 행사 등록 메뉴를 표시하고 서버도 별도로 권한을 검사한다.

FormData의 event는 `new Blob([JSON.stringify(payload)], {type:'application/json'})`, image는 선택한 File을 append한다. **요청 전체의 Content-Type을 JSON이나 multipart 문자열로 직접 설정하지 않는다.** 브라우저가 boundary를 붙이도록 한다. 기존 api helper도 FormData에는 application/json을 강제로 설정하지 않도록 수정했다.

행사 목록/상세의 `posterUrl`을 `<img src={event.posterUrl}>`로 사용한다. URL을 VITE_API_BASE_URL 뒤에 다시 붙이지 않는다. `PUBLIC_BASE_URL` 기본값은 http://localhost:8080이며 배포 시 브라우저에서 접근 가능한 API 공개 주소로 설정한다. prod에서는 필수다. DB에는 상대 이미지 경로를 보관하므로 호스트 변경 시 DB 경로를 일괄 수정하지 않아도 된다. 이미지 GET에는 Bearer가 필요 없으며 캐시/ETag/nosniff를 제공한다.

## 기존 개발 행사 포스터

기존 행사 1/2/3/4/5/10/20/30에 각각 포스터 SVG 8개를 포함했다(`src/main/resources/static/posters`). 외부 이미지 사이트나 네트워크에 의존하지 않는다. dev 전용 후속 시드가 poster_url이 없는 기존 행사만 채우며, 사용자 지정 포스터를 덮어쓰지 않는다. prod에서는 개발 데이터 보충을 실행하지 않는다. 번들 SVG는 개발용으로 신뢰된 정적 자산이며 업로드 SVG를 허용한다는 의미가 아니다.

예: 콘서트 `/posters/concert.svg`, 소극장 `/posters/studio.svg`, 그랜드홀 `/posters/grand-hall.svg`, 스타디움 `/posters/stadium.svg`. API는 PUBLIC_BASE_URL이 붙은 URL을 반환한다.

## 오류·실행

공통 `{code,message,details,traceId}` 형식. 400 INVALID_REQUEST/INVALID_SCHEDULE/INVALID_SECTION/DUPLICATE_SECTION/IMAGE_REQUIRED/INVALID_IMAGE/IMAGE_DIMENSIONS_EXCEEDED, 413 IMAGE_TOO_LARGE, 415 UNSUPPORTED_IMAGE_TYPE, 401/403 인증·권한 오류, 404 없는 공연장/이미지다. 누락된 multipart 파트는 400 INVALID_REQUEST다.

요청 전체를 application/json으로 보내면 415 UNSUPPORTED_MEDIA_TYPE이다. 전체는 multipart/form-data, event 파트만 application/json을 사용한다.

앱 재시작 시 V4 마이그레이션이 ID 할당 테이블과 이미지 테이블을 만든다. 기존 V1~V3 파일은 수정하지 않는다. 기존 행사/회차 ID와 예매 데이터는 유지한다. 이미지 URL의 실제 호스트와 보관 데이터 백업 정책을 배포 환경에 맞게 설정한다. PG 연동과 결제 없는 예매 정책은 변경하지 않았다.
