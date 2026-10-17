# 구현 검증 결과

`gradlew test bootJar --offline --no-daemon` 성공. Java 21, Spring Boot 4.0.8. 총 7개 테스트(통합 6 + 컨텍스트 1), 실패 0. 보고서는 `build/reports/tests/test/index.html`에 생성된다.

| 검증 | 확인 결과 |
| --- | --- |
| 동시 좌석 확보 | 서로 다른 사용자 2명이 같은 회차/좌석을 동시에 요청, 1명만 성공, 나머지 SEAT_CONFLICT |
| 대기열 | 중복 진입은 동일 순번/토큰, 용량 초과 시 WAITING, 앞선 사용자 퇴장 시 승격 |
| 입장 권한 | 다른 사용자·다른 회차 토큰, 만료 토큰으로 좌석 확보 불가 |
| 확보 | 기존 활성 확보 합산 최대 4석, 만료 확정 거부, 재조회 시 재고 해제 |
| 예매 | 동일 멱등 요청 동시 처리로 같은 예매 ID 반환, DB 예매 1건, 서버 가격 합계 |
| 접근 제어 | 타인 예매·확보 404, 일반 사용자 관리 API 403 |
| 취소 | 예매 CANCELLED 및 좌석 AVAILABLE 복원 |
| HTTP 전체 흐름 | 로그인 → 내 정보 → 행사 → 대기열 → 확보 → 예매 생성 성공 |
| 토큰 수명주기 | 잘못된 비밀번호 401, refresh 회전, 이전 access/refresh 거부, logout 후 접근 거부 |
| API 오류/설정 | 페이지 0 거부, 위조 입장 헤더 거부, 오픈 전/마감 거부, localhost:5173 CORS |
| 콘텐츠 | 관리자 게시글 등록·수정·삭제, 한글 보존, zh-CN 미번역 시 ko fallback |
| Swagger | 실제 HTTP UI 200, OpenAPI 문서에 예매 경로 포함 |

추가로 생성 JAR `build/libs/ticket-buy-be-0.0.1-SNAPSHOT.jar`를 포트 18080과 독립 H2 메모리 DB로 기동했다. Flyway V1 마이그레이션 적용, 개발 시드 로딩, `/swagger-ui/index.html` 200, `/actuator/health` UP을 확인했다. `/v3/api-docs`를 UTF-8 `docs/openapi.json`으로 추출했고 OpenAPI 3.1.0, 29개 operation 및 정상 응답 예시를 확인했다. 검증용 서버는 종료했다. 기본 파일 DB가 이미 사용 중이어서 이를 사용하는 기존 프로세스는 변경하지 않았다.

PostgreSQL/Docker 실행 도구가 없는 환경이므로 PostgreSQL 엔진에서의 실행, 부하·장애 복구, 프론트 실제 화면 연동은 검증하지 않았다. PostgreSQL 재실행 방법은 README에 있다. 프론트 소스가 없어 TypeScript 예시는 프론트 프로젝트의 Vite 환경 타입과 함께 옮겨 타입 검사·화면 연동해야 한다. 실제 PG 결제는 구현·테스트 범위에 없다.
