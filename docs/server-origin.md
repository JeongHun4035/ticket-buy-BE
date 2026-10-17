# 실서버 로그인·회원가입 Origin 설정

`ORIGIN_NOT_ALLOWED`는 비밀번호 검사 전에 브라우저 Origin이 서버 허용 목록에 없어서 발생한다. demo/admin 같은 계정 이름과 관계없다. Request URL은 백엔드 주소이며 Origin은 **프론트 페이지의 프로토콜+호스트+포트**다. Referrer Policy의 `strict-origin-when-cross-origin`은 Origin 값이 아니다.

## 현재 서버 주소가 http://34.64.146.19인 경우

프론트도 브라우저에서 `http://34.64.146.19`로 접속한다면 백엔드 프로세스/컨테이너에 아래 환경변수를 적용하고 재시작한다.

```dotenv
PUBLIC_BASE_URL=http://34.64.146.19
CORS_ORIGINS=http://34.64.146.19,http://localhost:5173
```

백엔드 내부 포트가 8080이어도 브라우저가 포트 80의 reverse proxy로 접속하면 공개 Origin은 `http://34.64.146.19`다. `:8080`을 허용한 것만으로 공개 주소가 허용되지 않는다. 브라우저 프론트가 `http://34.64.146.19:5173`이라면 CORS_ORIGINS에 그 주소도 정확히 추가한다. 다른 도메인/HTTPS도 서로 다른 Origin이다. `/api/v1` 같은 경로는 CORS_ORIGINS에 넣지 않는다.

Spring은 `.env` 파일을 자동 로딩하지 않는다. 실제 Java 프로세스의 환경변수 또는 실행 인자에 전달해야 한다. 예를 들어 Linux 셸에서 직접 실행한다면:

```bash
export PUBLIC_BASE_URL=http://34.64.146.19
export CORS_ORIGINS=http://34.64.146.19,http://localhost:5173
java -jar build/libs/ticket-buy-be-0.0.1-SNAPSHOT.jar
```

기존 프로필/DB/기타 실행 설정은 함께 유지한다. systemd 서비스는 서비스의 Environment/EnvironmentFile, Docker는 컨테이너 environment/env_file에 지정하고 프로세스/컨테이너를 재시작한다. 셸에 export만 한 뒤 이미 실행 중인 서비스에는 반영되지 않는다. PUBLIC_BASE_URL은 포스터 이미지 URL에도 사용한다. HTTPS로 전환하면 두 설정의 주소도 실제 HTTPS 주소로 맞춘다. prod의 Secure refresh 쿠키 정책은 유지하므로 운영 로그인 갱신에는 HTTPS가 필요하다.

## 코드 변경과 확인

CORS 필터와 인증 API Origin 검사는 이제 동일한 OriginPolicy를 사용한다. 명시한 CORS_ORIGINS에 더해 설정된 PUBLIC_BASE_URL의 Origin도 신뢰한다. 따라서 같은 공개 주소에서 서비스되는 프론트/Swagger를 허용 목록 차이로 차단하지 않는다. 기본 포트(HTTP 80, HTTPS 443), 설정의 끝 슬래시/공백을 정리한다. 와일드카드/자격 증명/임의 경로를 포함한 잘못된 CORS 설정은 기동 시 거부한다. 요청 Host/X-Forwarded-*를 근거로 임의 도메인을 자동 신뢰하지 않는다.

기본 localhost 설정만으로 임의 공개 IP를 허용하지 않는다. 위 서버 설정은 여전히 필요하다. 새 JAR을 배포한 후 실제 환경변수도 적용해야 한다. 이 저장소에서 코드를 수정하는 것만으로 원격 실행 서버의 환경변수가 바뀌지는 않는다.

브라우저 개발자 도구 Network에서 다음을 확인한다.

1. 로그인/회원가입 요청의 Request Headers → Origin이 CORS_ORIGINS 또는 PUBLIC_BASE_URL의 Origin과 같은지 확인한다.
2. OPTIONS가 발생하면 성공하고 Access-Control-Allow-Origin이 해당 Origin인지 확인한다.
3. 올바른 입력이면 signup 201, demo/admin login 200인지 확인한다. 잘못된 비밀번호의 401은 Origin 검사를 통과한 뒤의 인증 오류다.

테스트는 외부 서버에 접속하지 않고 로컬 실제 HTTP 서버에 `Origin: http://34.64.146.19`를 보내 신규 가입, demo/admin 로그인과 preflight 허용을 검증한다. 악성 도메인/다른 포트/다른 프로토콜은 계속 거부한다.
