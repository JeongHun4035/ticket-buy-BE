# 프로젝트 디렉터리 구조

기능별 패키지를 기준으로 인증, 예매, 카탈로그 코드를 분리한다. 공통 API 계약과 예외, 보안 설정, DB 접근, 개발용 초기 데이터는 별도 패키지로 관리한다.

## 전체 구조

```text
ticket-buy-BE/
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   ├── com/ticketing/ticketbuybe/
│   │   │   │   ├── TicketBuyBeApplication.java
│   │   │   │   ├── domain/
│   │   │   │   │   ├── auth/          # 회원가입·로그인·세션
│   │   │   │   │   ├── booking/       # 대기열·좌석·확보·예매·만료 작업
│   │   │   │   │   └── catalog/       # 행사·공연장·이미지·FAQ·게시판
│   │   │   │   ├── global/
│   │   │   │   │   ├── config/        # OpenAPI·스케줄링 설정
│   │   │   │   │   ├── dto/           # 공통 요청·응답 계약
│   │   │   │   │   ├── error/         # 예외와 HTTP 오류 응답
│   │   │   │   │   └── security/      # 인증 필터·CORS·조회 제한
│   │   │   │   ├── infrastructure/
│   │   │   │   │   └── persistence/   # 공통 JDBC 접근
│   │   │   │   └── bootstrap/
│   │   │   │       └── seed/          # dev 전용 초기 데이터
│   │   │   └── db/migration/          # Flyway Java 마이그레이션
│   │   └── resources/
│   │       ├── application.properties
│   │       ├── application-prod.properties
│   │       ├── db/migration/          # Flyway SQL 마이그레이션
│   │       └── static/posters/        # 개발용 포스터
│   └── test/
│       ├── java/com/ticketing/ticketbuybe/
│       │   ├── TicketBuyBeApplicationTests.java
│       │   ├── domain/booking/        # 예매·좌석·관련 HTTP 통합 검증
│       │   ├── global/security/       # Origin 정책 단위 검증
│       │   ├── infrastructure/persistence/ # 마이그레이션·시드 검증
│       │   └── integration/           # 배포 Origin HTTP 통합 검증
│       └── resources/application.properties
├── docs/                             # API·운영·구조·검증 문서
│   └── examples/                     # JSON 요청·응답 예시
├── gradle/wrapper/                   # Gradle Wrapper
├── build.gradle
├── settings.gradle
├── gradlew
├── gradlew.bat
├── compose.yaml                      # 개발용 PostgreSQL
├── .env.example
└── README.md
```

`build/`, `.gradle/`, `.gradle-user/`, `data/`는 실행·빌드 시 생성되는 로컬 디렉터리이며 `.gitignore`로 제외한다. 배포할 JAR은 `bootJar`로 생성되는 `build/libs/ticket-buy-be-0.0.1-SNAPSHOT.jar`를 사용한다.

## 패키지별 책임

| 패키지 | 주요 클래스 | 책임 |
| --- | --- | --- |
| `domain.auth` | `AuthController`, `AuthService` | 회원가입, 로그인, 인증 토큰, refresh, 로그아웃 |
| `domain.booking` | `BookingController`, `BookingService`, `ExpiryWorker` | 입장 한도, 대기순서, 좌석 재고, 확보, 예매, 만료 |
| `domain.catalog` | `CatalogController`, `CatalogService`, `AdminController` | 행사 조회와 FAQ·게시글 관리 |
| `domain.catalog` | `EventAdminController`, `EventAdminService`, `EventImages`, `EventImageController` | 행사·회차·재고 등록과 이미지 저장·조회 |
| `global.config` | `OpenApiConfig`, `Scheduling` | API 문서와 스케줄링 활성화 |
| `global.dto` | `Contracts` | 기존 요청·응답 record와 enum |
| `global.error` | `ApiError`, `Errors` | 도메인 오류, 공통 예외 처리 |
| `global.security` | `SecurityConfig`, `OriginPolicy`, `ReadRequestLimiter` | 인증·인가, Origin 정책, 요청 제한 |
| `infrastructure.persistence` | `Store` | JDBC 조회, 회차 잠금, SQL 결과 변환 |
| `bootstrap.seed` | `DevSeed`, `VenueScaleSeed`, `DevPosterSeed` | dev 환경의 계정·행사·좌석·포스터 생성 |

## 코드 추가 기준

- 기능을 수정할 때는 해당 `domain` 패키지에서 컨트롤러와 서비스를 함께 찾는다. HTTP 입력·응답 처리는 컨트롤러, 업무 규칙과 트랜잭션은 서비스에 둔다.
- 여러 기능에서 실제로 공유하는 설정·계약만 `global`에 둔다. 현재 `Contracts`는 기존 API 계약을 모아 둔 구조를 유지하며, 계약 분리는 별도 리팩터링으로 진행할 수 있다.
- DB 접근은 기존 JDBC 구현을 유지한다. 기능이 커지면 도메인별 repository로 SQL을 분리한다. 현재 구조가 JPA entity/repository 계층을 구현한 것은 아니다.
- 개발 시드는 `bootstrap.seed`에 추가하고 기존 dev 전용 프로필 조건을 유지한다.
- 테스트는 대상 패키지와 같은 경로에 추가한다. 여러 기능을 함께 검증하는 시나리오는 `integration`에 둔다. 기존 `BookingIntegrationTests`에는 인증·행사 관련 회귀 시나리오도 포함되어 있다.
- Flyway Java 마이그레이션은 기본 탐색 위치인 `db.migration`에 둔다. 적용된 SQL/Java 마이그레이션의 버전과 내용을 바꾸지 않는다.
- 애플리케이션 시작 클래스는 최상위 `com.ticketing.ticketbuybe`에 둬 하위 패키지의 Spring 컴포넌트를 자동 탐색한다.

## 검증 명령

이전 패키지의 컴파일 결과가 남지 않도록 구조 변경 후에는 clean 빌드로 검증한다.

```powershell
.\gradlew.bat clean test bootJar
```

```bash
./gradlew clean test bootJar
```
