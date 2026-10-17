# TICKET BUY Backend

기존 Java 21 / Spring Boot 4 / Gradle 프로젝트를 확장했다. 회원가입·인증, 행사·회차, PostgreSQL 기반 대기열, 좌석 확보, 결제 없는 예매 확정, 고객센터·FAQ·게시판과 관리자 CRUD를 제공한다.

## 로컬 실행

Java 21이 필요하다. 프로젝트 루트 PowerShell에서:

```powershell
.\gradlew.bat bootRun
```

기본 프로필은 dev이며 H2 파일 DB(`data/ticketbuy`)를 생성한다. 최초 실행 때 개발 계정과 행사 5개, 공연장, A~H × 12석 배치, 행사별 2회차, FAQ·게시글을 트랜잭션으로 생성한다. 1~4번 행사는 판매 중, 5번은 오픈 예정이다. 시드 날짜는 최초 실행 시점 기준이며 재시작으로 변경하지 않는다. 시드 리셋은 앱을 종료한 후 개발용 data 디렉터리를 별도로 백업/제거하여 수행한다.

추가로 500석 소극장(행사 10, 회차 1001/1002), 5,000석 그랜드홀(행사 20, 회차 2001/2002), 30,000석 스타디움(행사 30, 회차 3001/3002)을 생성한다. 각 구역 250석이며 공연장별로 2/20/120개 구역이다. 추가 시드는 기존 dev DB에도 고정 ID로 누락 항목만 생성하고 기존 확보·예매를 유지한다. prod에서는 실행되지 않는다. [프론트 전달 문서와 실제 JSON 예시](docs/venue-sections-frontend.md)를 참고한다.

개발 행사 8개에는 외부 네트워크 없이 표시되는 포스터 SVG를 연결한다. 관리자는 `POST /api/v1/admin/events`에 JSON event와 PNG/JPEG image를 multipart로 첨부해 행사·회차·좌석 재고를 등록할 수 있다. [관리자 행사 등록/이미지 API 및 프론트 예시](docs/admin-events.md)를 참고한다. `PUBLIC_BASE_URL`은 이미지 URL의 공개 API 주소이며 로컬 기본값은 http://localhost:8080, prod에서는 필수다.

H2 파일 DB는 한 프로세스에서 사용한다. `Database may be already in use` 오류가 나면 같은 DB를 사용하는 기존 실행을 확인하거나 별도 DB_URL을 지정한다. 여러 인스턴스 검증에는 PostgreSQL을 사용한다.

| 용도 | 주소/계정 |
| --- | --- |
| API | http://localhost:8080/api/v1 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |
| OpenAPI YAML | http://localhost:8080/v3/api-docs.yaml |
| 상태 확인 | http://localhost:8080/actuator/health |
| 사용자 | demo / Demo1234! (다른 사용자: other) |
| 관리자 | admin / Demo1234! |

환경 예시는 [.env.example](.env.example)에 있다. Spring은 `.env`를 자동 로딩하지 않는다. PowerShell `$env:변수='값'`으로 설정하거나 IDE 환경변수를 사용한다. Gradle 캐시 위치가 쓰기 불가능한 환경에서는 `$env:GRADLE_USER_HOME="$PWD/.gradle-user"`를 설정한다.

## PostgreSQL 개발 실행

Docker가 설치된 환경에서 전용 개발 DB를 준비한다.

```powershell
$env:DB_PASSWORD='replace-with-local-password'
docker compose up -d
$env:DB_URL='jdbc:postgresql://localhost:5432/ticketbuy'
$env:DB_USER='ticketbuy'
$env:SPRING_PROFILES_ACTIVE='dev'
.\gradlew.bat bootRun
```

Flyway가 V1 스키마, V2 대기열/만료 인덱스, V3 층·구역 모델을 순서대로 적용한다. V3는 `src/main/java/db/migration/V3__venue_sections.java`이며 기존 좌석 ID와 예매를 보존하고 구역 기본값을 backfill한다. 이미 적용된 마이그레이션은 수정하지 않고 다음 버전을 추가한다. JPA 자동 DDL은 비활성이다. 운영은 `SPRING_PROFILES_ACTIVE=prod`, PostgreSQL DB_URL/DB_USER/DB_PASSWORD와 실제 CORS_ORIGINS를 반드시 설정한다. dev 시드가 로딩되지 않으며 Secure 쿠키가 강제되고 Swagger는 기본 비활성이다. 운영 DB에 개발 시드/계정을 복사하지 않는다. 운영 계정·행사/배치 등록 도구는 별도 범위다.

V4는 관리자 행사 등록용 ID 할당과 이미지 저장 테이블을 추가한다. 행사 등록은 API로 지원하며 이미지 바이너리는 DB에 보관한다. PUBLIC_BASE_URL도 운영 필수 설정이다.

## 테스트와 빌드

대기 상태·좌석 GET은 회차 행 잠금/만료 UPDATE 없이 조회한다. 최초 진입은 WAITING이며 기본 1초 주기 작업자가 회차당 활성 입장자 최대 `QUEUE_CAPACITY` 내에서 순서대로 승격한다. 작업자 1회/회차의 승격량은 `QUEUE_ADMIT_BATCH_SIZE`(기본 20)로 제한한다. `QUEUE_POLL_SECONDS`(기본 3), `SEAT_READ_MIN_SECONDS`(기본 2)보다 빠른 사용자·회차별 조회는 429/Retry-After를 받는다. `QUEUE_WORKER_INTERVAL_MS`(기본 1000)로 작업 주기를 설정한다. 이 요청 제한은 인스턴스별이며 전역 트래픽 제한은 게이트웨이/공유 저장소 구성이 필요하다. 좌석 조회는 스냅샷이고 최종 확보·확정은 DB 잠금 안에서 재검증한다.

```powershell
.\gradlew.bat test
.\gradlew.bat bootJar
java -jar build/libs/ticket-buy-be-0.0.1-SNAPSHOT.jar
```

테스트는 전용 H2 메모리 DB에서 실제 HTTP 서버를 띄워 실행한다. 결과: `build/reports/tests/test/index.html`. 좌석 동시 확보 1건만 성공, 대기열 중복 진입/승격, 사용자·회차 입장 토큰 검증, 입장/확보 만료, 합산 4석 제한, 예매 동시 재시도, 타인 접근 차단, 취소·재고 해제, 로그인/refresh 회전/logout, CORS, 관리자 CRUD, UTF-8·번역 fallback, 판매 기간, 입력 오류, Swagger를 검증한다.

PostgreSQL 재검증은 **데이터를 지워도 되는 별도 테스트 DB**로 수행한다. 테스트가 예매·확보·대기열 데이터를 삭제하므로 개발/운영 DB를 지정하면 안 된다.

```powershell
$env:TEST_DB_URL='jdbc:postgresql://localhost:5432/ticketbuy_test'
$env:TEST_DB_USER='ticketbuy'
$env:TEST_DB_PASSWORD='replace-with-test-password'
.\gradlew.bat test --rerun-tasks
```

현재 작업 환경에는 Docker/psql이 없어 PostgreSQL 엔진의 실제 실행·부하 테스트는 수행하지 않았다. H2 결과만으로 PostgreSQL의 처리량/운영 성능을 보장하지 않는다.

## Swagger 테스트 순서

1. `POST /auth/login`의 예시 demo 계정으로 실행한다. 응답 accessToken을 복사해 상단 **Authorize**에 토큰 값만 입력한다(`Bearer ` 접두사는 UI가 붙인다).
2. `GET /users/me`, `GET /events`, `GET /events/1/performances`를 호출한다.
3. 판매 중 회차 101에 `POST /performances/101/queue`를 호출한다. 최초 WAITING이며 별도 작업자가 순번대로 입장시킨다. `/queue/me`를 pollAfterSeconds 주기로 조회한다(기본 3초). position은 현재 대기순서, ticketNumber는 누적 발급 순번이다. 빈 개발 환경에서도 작업자 tick 후 입장한다.
4. ADMITTED 응답의 admissionToken을 복사한다. `/performances/101/seats`의 X-Admission-Token에 입력한다.
5. `/performances/101/holds`에 같은 헤더와 `{"seatIds":[1,2]}`를 전달한다. 반환 hold id와 expiresAt을 확인한다.
6. `/reservations`에 같은 입장 헤더, `Idempotency-Key: booking-demo-1`, `{"holdId":"반환된 id"}`를 전달한다.
7. 같은 요청을 반복하면 같은 예매를 받는다. `/reservations/me`, 상세, 취소를 확인한다.
8. 다른 계정/회차/만료 토큰과 이미 확보된 좌석을 사용해 오류 예시를 확인한다. 사용하지 않는 확보는 DELETE holds로 해제한다.

로그인·refresh는 브라우저 쿠키를 사용한다. refresh 후에는 새 accessToken으로 Swagger Authorize도 갱신한다. 쿠키 값은 JavaScript로 읽을 수 없다. 로그아웃으로 세션을 폐기한다. FAQ/게시글 관리는 admin으로 로그인한 Bearer가 필요하다.

## 연동 및 범위

콘텐츠 관리(FAQ·게시글 등록/수정/삭제)는 ADMIN 전용이다. `/api/v1/admin/**`의 URL 권한 검사와 `CatalogService.save/delete`의 메서드 권한 검사를 함께 적용한다. 비로그인 요청은 401, 일반 사용자 요청은 403이다. FAQ·게시글 조회는 누구나 가능하며 회원가입으로 관리자 권한을 얻을 수 없다. 프론트에서 관리 메뉴를 숨겨도 서버가 별도로 권한을 검사한다.

회원가입·내 정보 응답은 `{id,name,role}`, 로그인·인증 갱신 응답의 user도 같은 구조다. 권한 코드는 `USER`(일반 사용자), `ADMIN`(관리자)이며 프론트는 `user.role === 'ADMIN'`으로 관리 메뉴 표시 여부를 판단한다. 권한은 서버가 결정하고 회원가입은 항상 USER로 생성한다.

- [API 계약·오류 코드·요청/응답](docs/api-contract.md)
- [실행 서버에서 추출한 OpenAPI 3.1 JSON](docs/openapi.json)
- [React/TypeScript 교체 예시](docs/frontend-example.ts)
- [공연장 규모·구역 API 및 프론트 전달 사항](docs/venue-sections-frontend.md)
- [관리자 행사 등록·포스터 첨부](docs/admin-events.md)
- [데이터 모델·인증·대기열·잠금·결제 정책](docs/architecture.md)
- [실행·검증 결과와 제한](docs/verification.md)

요청한 API 경로, 회원가입, 이미지 첨부 행사 등록, 마이그레이션, 개발 시드, Swagger, 동시성/권한 검증을 구현했다. 회원가입은 `POST /api/v1/auth/signup`에 username/password/name을 전달하고 이후 별도 로그인한다. 상세 제약은 API 계약에 있다. 프론트 소스는 이 저장소에 없어 실제 프론트 수정은 포함하지 않고 매핑과 호출 예시를 제공한다. PG/가상 결제, 이메일·휴대폰 인증/계정 복구, 행사 수정·삭제와 공연장 운영 CRUD, 외부 객체 저장소/CDN, 대규모 검색 최적화·부하 검증·분산 요청 제한은 미구현이다. 현재 예매 확정은 **결제 없는 CONFIRMED / NOT_REQUIRED**이며 실제 결제를 뜻하지 않는다.

Springdoc 버전은 [공식 설치 문서](https://springdoc.org/getting-started.html), Flyway starter는 [Spring Boot 공식 의존성 문서](https://docs.spring.io/spring-boot/4.0/reference/using/build-systems.html)를 기준으로 구성했다.
