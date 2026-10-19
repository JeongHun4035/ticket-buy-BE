# TICKET BUY Backend

행사 조회부터 대기열 입장, 좌석 확보, 예매 확정까지 제공하는 티켓 예매 백엔드입니다.

**Java 21 · Spring Boot 4 · Gradle · PostgreSQL / H2**

> 현재 예매는 **결제 없이 확정**됩니다. `CONFIRMED / NOT_REQUIRED`는 실제 결제 완료를 의미하지 않습니다.

## 목차

- [시작하기](#시작하기)
- [접속 주소와 개발 계정](#접속-주소와-개발-계정)
- [환경변수와 운영 설정](#환경변수와-운영-설정)
- [PostgreSQL 개발 실행](#postgresql-개발-실행)
- [개발 데이터](#개발-데이터)
- [테스트와 빌드](#테스트와-빌드)
- [Swagger 테스트 순서](#swagger-테스트-순서)
- [대기열과 좌석 처리](#대기열과-좌석-처리)
- [인증과 구현 범위](#인증과-구현-범위)
- [관련 문서](#관련-문서)

## 시작하기

### 준비 사항

| 항목 | 설정 |
| --- | --- |
| JDK | **OpenJDK 21** — `java`, `javac` 모두 21인지 확인 |
| 빌드 도구 | 저장소의 Gradle Wrapper 사용 |
| VS Code | **Extension Pack for Java**, **Spring Boot Extension Pack** 설치 |
| 기본 개발 DB | H2 파일 DB — 별도 설치 불필요 |
| PostgreSQL 개발 DB | Docker 및 Compose 필요 |

JDK 버전이 다르면 `JAVA_HOME`, `PATH`, IDE의 프로젝트 JDK를 확인합니다. VS Code 확장 프로그램은 개발 도구이며 VM에서 JAR만 실행할 때는 필요하지 않습니다.

**설치·버전 설정·Remote SSH·Ubuntu VM 명령어는 [개발 환경 및 VM 실행 가이드](docs/setup-and-vm.md)를 참고하세요.**

### 로컬 실행

프로젝트 루트의 PowerShell에서 실행합니다.

```powershell
java -version
javac -version
.\gradlew.bat bootRun
```

기본 프로필은 `dev`입니다. 첫 실행 시 `data/ticketbuy`에 H2 DB와 개발 데이터를 생성합니다. 실행 후 [Swagger UI](http://localhost:8080/swagger-ui.html)에서 API를 확인할 수 있습니다. 종료는 `Ctrl+C`입니다.

Linux VM에서는 다음 명령을 사용합니다.

```bash
chmod +x gradlew
./gradlew bootRun
```

## 접속 주소와 개발 계정

### 로컬 주소

| 용도 | 주소 |
| --- | --- |
| API | http://localhost:8080/api/v1 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |
| OpenAPI YAML | http://localhost:8080/v3/api-docs.yaml |
| 상태 확인 | http://localhost:8080/actuator/health |

### 개발 계정

| 권한 | 아이디 | 비밀번호 |
| --- | --- | --- |
| 사용자 | `demo` | `Demo1234!` |
| 관리자 | `admin` | `Demo1234!` |

다른 사용자 접근 검증용으로 `other` 계정도 제공합니다. 개발 계정과 시드는 `prod`에서 생성하지 않습니다.

## 환경변수와 운영 설정

환경 예시는 [.env.example](.env.example)에 있습니다. **Spring은 `.env` 파일을 자동으로 읽지 않습니다.** PowerShell 또는 IDE 실행 환경에 직접 설정합니다.

```powershell
$env:SPRING_PROFILES_ACTIVE='dev'
$env:PUBLIC_BASE_URL='http://localhost:8080'
$env:CORS_ORIGINS='http://localhost:5173,http://localhost:8080'
.\gradlew.bat bootRun
```

| 변수 | 용도 | 운영 설정 |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | 실행 프로필 | `prod` |
| `DB_URL` | JDBC 연결 주소 | PostgreSQL 주소 필수 |
| `DB_USER`, `DB_PASSWORD` | DB 인증 정보 | 필수 |
| `PUBLIC_BASE_URL` | 이미지 URL에 사용하는 공개 API 주소 | 실제 공개 주소 필수 |
| `CORS_ORIGINS` | 브라우저 프론트 Origin 목록, 쉼표로 구분 | 실제 프론트 주소 필수 |
| `API_DOCS_ENABLED` | 운영 Swagger / OpenAPI 활성화 | 기본 `false` |

운영에서는 Secure 쿠키가 강제되므로 **HTTPS**를 사용합니다. 개발 시드와 계정을 운영 DB에 복사하지 않습니다.

로그인·가입 시 `ORIGIN_NOT_ALLOWED`가 발생하면 [공개 서버 Origin 설정](docs/server-origin.md)을 확인하세요. `CORS_ORIGINS`에는 경로 없이 프로토콜·호스트·포트를 지정합니다. `PUBLIC_BASE_URL`의 Origin도 같은 정책으로 신뢰합니다. 환경변수 변경 후에는 백엔드를 재시작해야 합니다.

Gradle 캐시 경로에 쓰기 권한이 없다면 다음을 설정한 뒤 다시 실행합니다.

```powershell
$env:GRADLE_USER_HOME="$PWD/.gradle-user"
```

## PostgreSQL 개발 실행

Docker가 설치된 환경에서 개발용 DB를 준비합니다. Compose는 PostgreSQL을 실행하며 백엔드는 별도로 실행합니다.

```powershell
$env:DB_PASSWORD='replace-with-local-password'
docker compose up -d

$env:DB_URL='jdbc:postgresql://localhost:5432/ticketbuy'
$env:DB_USER='ticketbuy'
$env:SPRING_PROFILES_ACTIVE='dev'
.\gradlew.bat bootRun
```

H2 파일 DB는 한 프로세스에서 사용합니다. `Database may be already in use`가 발생하면 기존 실행을 확인하거나 별도 `DB_URL`을 지정하세요. 여러 인스턴스 검증에는 PostgreSQL을 사용합니다.

### DB 마이그레이션

Flyway가 다음 마이그레이션을 순서대로 적용합니다. JPA 자동 DDL은 비활성화되어 있습니다.

| 버전 | 내용 |
| --- | --- |
| V1 | 기본 스키마 |
| V2 | 대기열·만료 인덱스 |
| V3 | 층·구역 모델 추가, 기존 좌석 ID·예매 보존 및 구역 기본값 보완 |
| V4 | 관리자 행사 등록용 ID 할당·이미지 저장 테이블 |

V3 구현은 `src/main/java/db/migration/V3__venue_sections.java`에 있습니다. **이미 적용한 마이그레이션은 수정하지 않고 다음 버전을 추가합니다.** 이미지 바이너리는 DB에 저장합니다.

## 개발 데이터

`dev` 프로필에서 개발 계정, 행사, 공연장, 좌석, FAQ와 게시글을 생성합니다.

| 데이터 | 구성 |
| --- | --- |
| 기본 행사 5개 | A~H × 12석, 행사별 2회차. 행사 1~4는 판매 중, 행사 5는 오픈 예정 |
| 500석 소극장 | 행사 `10`, 회차 `1001` / `1002`, 2개 구역 |
| 5,000석 그랜드홀 | 행사 `20`, 회차 `2001` / `2002`, 20개 구역 |
| 30,000석 스타디움 | 행사 `30`, 회차 `3001` / `3002`, 120개 구역 |

추가 공연장은 구역마다 250석입니다. 기존 dev DB에도 고정 ID로 누락 항목만 추가하며 기존 확보·예매를 유지합니다. 개발 행사 8개에는 외부 네트워크 없이 표시되는 SVG 포스터가 연결됩니다.

기본 시드는 트랜잭션으로 생성하며 날짜는 최초 실행 시점을 기준으로 합니다. 재시작해도 날짜는 바뀌지 않습니다. 초기화가 필요하면 앱을 종료하고 개발용 `data` 디렉터리를 백업한 뒤 제거합니다.

구역별 응답 예시는 [공연장·좌석 연동 문서](docs/venue-sections-frontend.md)를 참고하세요.

## 테스트와 빌드

### 기본 테스트 및 JAR 실행

프로젝트 루트의 PowerShell에서 실행합니다.

```powershell
.\gradlew.bat test
.\gradlew.bat bootJar
java -jar build/libs/ticket-buy-be-0.0.1-SNAPSHOT.jar
```

테스트는 전용 H2 메모리 DB에서 실제 HTTP 서버를 띄워 실행합니다. 결과는 `build/reports/tests/test/index.html`에서 확인합니다.

| 검증 영역 | 주요 항목 |
| --- | --- |
| 대기열·좌석 | 중복 진입·승격, 입장 토큰, 입장·확보 만료, 동시 좌석 확보 1건 성공, 합산 4석 제한 |
| 예매 | 동시 재시도, 타인 접근 차단, 취소·재고 해제 |
| 인증·권한 | 로그인, refresh 회전, 로그아웃, CORS, 관리자 CRUD |
| API | UTF-8·번역 fallback, 판매 기간, 입력 오류, Swagger |

### PostgreSQL 테스트

> **데이터를 지워도 되는 별도 테스트 DB를 먼저 준비하세요.** 테스트가 예매·확보·대기열 데이터를 삭제하므로 개발·운영 DB를 지정하면 안 됩니다.

```powershell
$env:TEST_DB_URL='jdbc:postgresql://localhost:5432/ticketbuy_test'
$env:TEST_DB_USER='ticketbuy'
$env:TEST_DB_PASSWORD='replace-with-test-password'
.\gradlew.bat test --rerun-tasks
```

기존 검증 환경에는 Docker/psql이 없어 PostgreSQL 엔진의 실제 실행·부하 테스트는 수행하지 않았습니다. H2 결과만으로 PostgreSQL 처리량이나 운영 성능을 보장하지 않습니다. 상세 결과는 [검증 문서](docs/verification.md)를 참고하세요.

## Swagger 테스트 순서

아래 경로의 공통 접두사는 `/api/v1`입니다. 개발 시드의 판매 중 회차 `101`을 기준으로 합니다.

1. **로그인** — `POST /auth/login`에 `demo` 계정 정보를 전달하고 응답의 `accessToken`을 복사합니다.
2. **인증 설정** — 상단 **Authorize**에 토큰 값만 입력합니다. `Bearer ` 접두사는 UI가 추가합니다.
3. **행사 조회** — `GET /users/me`, `GET /events`, `GET /events/1/performances`를 호출합니다.
4. **대기열 진입** — `POST /performances/101/queue`를 호출합니다. 용량에 여유가 있으면 `ADMITTED`와 입장 토큰을 즉시 받습니다. `WAITING`이면 다음 단계에서 상태를 조회합니다.
5. **입장 확인** — `GET /performances/101/queue/me`를 `pollAfterSeconds` 주기(기본 3초)로 조회합니다. `ADMITTED` 응답의 `admissionToken`을 복사합니다.
6. **좌석 조회** — `GET /performances/101/seats`의 `X-Admission-Token` 헤더에 입장 토큰을 입력합니다.
7. **좌석 확보** — `POST /performances/101/holds`에 같은 입장 헤더와 `{"seatIds":[1,2]}`를 전달합니다. 반환 `id`와 `expiresAt`을 확인합니다.
8. **예매 확정** — `POST /reservations`에 같은 입장 헤더, `Idempotency-Key: booking-demo-1`, `{"holdId":"반환된 id"}`를 전달합니다.
9. **결과 확인** — 같은 확정 요청을 반복해 같은 예매를 받는지 확인합니다. `/reservations/me`, 예매 상세·취소를 확인합니다.

즉시 `ADMITTED` 응답을 받으면 대기 화면 없이 좌석 조회로 이동합니다. `position`은 현재 대기순서, `ticketNumber`는 누적 발급 순번입니다. 다른 계정·회차·만료 토큰이나 이미 확보된 좌석으로 오류 응답도 확인하세요. 사용하지 않는 확보는 DELETE holds로 해제합니다.

인증 갱신에는 브라우저 쿠키를 사용합니다. refresh 후에는 새 `accessToken`으로 Swagger Authorize도 갱신합니다. 쿠키 값은 JavaScript로 읽을 수 없으며 로그아웃하면 세션을 폐기합니다. FAQ·게시글 관리는 `admin` 계정의 Bearer 토큰이 필요합니다.

## 대기열과 좌석 처리

회차별 유효 입장자가 `QUEUE_CAPACITY`(기본 50명) 미만이고 앞선 대기자가 없으면 즉시 `ADMITTED`로 입장합니다. 용량이 차거나 앞선 대기자가 있으면 `WAITING`으로 진입한 뒤 작업자가 순서대로 입장시킵니다. 트래픽 기준은 초당 요청 수나 CPU가 아니라 유효 입장 토큰 수입니다. 입장 권한은 기본 600초이며 만료 또는 대기열 퇴장 시 자리가 생깁니다. 예매 확정만으로는 입장 권한이 해제되지 않습니다. 대기 상태·좌석 GET은 회차 행 잠금이나 만료 UPDATE 없이 조회합니다. 좌석 조회는 스냅샷이며 최종 확보·확정은 DB 잠금 안에서 다시 검증합니다.

| 환경변수 | 기본값 | 의미 |
| --- | --- | --- |
| `QUEUE_CAPACITY` | `50` | 회차당 최대 활성 입장자 수 |
| `QUEUE_ADMIT_BATCH_SIZE` | `20` | 작업자 1회·회차당 최대 승격 인원 |
| `QUEUE_WORKER_INTERVAL_MS` | `1000` | 작업 주기(ms) |
| `QUEUE_POLL_SECONDS` | `3` | 사용자·회차별 대기 상태 최소 조회 간격(초) |
| `SEAT_READ_MIN_SECONDS` | `2` | 사용자·회차별 좌석 최소 조회 간격(초) |

조회 간격보다 빠른 요청에는 `429`와 `Retry-After`를 반환합니다. 요청 제한은 인스턴스별로 적용되며 전역 제한에는 게이트웨이 또는 공유 저장소 구성이 필요합니다.

## 인증과 구현 범위

### 인증·권한

- 회원가입: `POST /api/v1/auth/signup`에 `username`, `password`, `name`을 전달하고 이후 별도로 로그인합니다.
- 회원가입·내 정보 응답은 `{id,name,role}`이며 로그인·인증 갱신 응답의 `user`도 같은 구조입니다.
- 권한은 `USER`와 `ADMIN`입니다. 회원가입으로 생성되는 계정은 항상 `USER`입니다.
- FAQ·게시글 조회는 공개되며 등록·수정·삭제는 `ADMIN` 전용입니다.
- 관리 API는 비로그인 요청에 `401`, 일반 사용자 요청에 `403`을 반환합니다.

`/api/v1/admin/**` URL 검사와 `CatalogService.save/delete` 메서드 권한 검사를 함께 적용합니다. 프론트는 `user.role === 'ADMIN'`으로 관리 메뉴를 표시할 수 있으며 서버에서도 권한을 검사합니다.

### 기능 범위

| 구분 | 내용 |
| --- | --- |
| 구현 | 회원가입·인증, 행사·회차 조회, PostgreSQL 기반 대기열, 좌석 확보, 결제 없는 예매 확정·취소 |
| 구현 | 고객센터·FAQ·게시판 및 관리자 CRUD, 행사·회차·좌석 재고 등록, 포스터 이미지 첨부 |
| 구현 | Flyway 마이그레이션, 개발 시드, Swagger, 동시성·권한 검증 |
| 미구현 | PG·가상 결제, 이메일·휴대폰 인증 및 계정 복구 |
| 미구현 | 행사 수정·삭제, 공연장 운영 CRUD, 운영 계정·배치 등록 도구 |
| 미구현 | 외부 객체 저장소·CDN, 대규모 검색 최적화·부하 검증·분산 요청 제한 |

관리자 행사 등록은 `POST /api/v1/admin/events`에 JSON `event`와 PNG/JPEG `image`를 multipart로 전달합니다. 자세한 요청은 [관리자 행사 API 문서](docs/admin-events.md)를 참고하세요.

프론트 소스는 이 저장소에 포함되어 있지 않습니다. 연동용 매핑과 호출 예시를 별도 문서로 제공합니다.

## 관련 문서

| 문서 | 내용 |
| --- | --- |
| [프로젝트 구조](docs/project-structure.md) | 도메인별 패키지, 공통 코드, 테스트 배치 기준 |
| [개발 환경 및 VM 실행](docs/setup-and-vm.md) | OpenJDK 21, VS Code 확장, Ubuntu 설치·빌드·systemd 명령어 |
| [공개 서버 Origin 설정](docs/server-origin.md) | CORS, 공개 URL, 로그인·가입 오류 해결 |
| [API 계약](docs/api-contract.md) | 요청·응답, 오류 코드, 입력 제약 |
| [OpenAPI JSON](docs/openapi.json) | 실행 서버에서 추출한 OpenAPI 3.1 명세 |
| [React / TypeScript 예시](docs/frontend-example.ts) | 프론트 연동 코드 |
| [공연장·좌석 연동](docs/venue-sections-frontend.md) | 공연장 규모, 구역 API, 실제 JSON 예시 |
| [관리자 행사 API](docs/admin-events.md) | 행사 등록, 포스터 첨부, 프론트 예시 |
| [아키텍처](docs/architecture.md) | 데이터 모델, 인증, 대기열, 잠금, 결제 정책 |
| [검증 결과](docs/verification.md) | 실행·검증 결과와 제한 |

의존성 구성 참고: [Springdoc 공식 설치 문서](https://springdoc.org/getting-started.html), [Spring Boot 공식 의존성 문서](https://docs.spring.io/spring-boot/4.0/reference/using/build-systems.html).
