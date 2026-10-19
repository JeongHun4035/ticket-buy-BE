# OpenJDK 21 · VS Code · VM 실행 가이드

이 프로젝트의 `build.gradle`은 Java toolchain **21**, Spring Boot **4.0.8**을 사용한다. Gradle Wrapper는 `gradle/wrapper/gradle-wrapper.properties`에 지정된 **9.8.1**을 사용한다. 별도 Gradle 설치 대신 저장소의 `gradlew` / `gradlew.bat`를 실행한다. 컴파일에는 `javac`가 포함된 **JDK 21**이 필요하다.

아래 VM 명령어는 **Ubuntu 24.04 LTS / Bash / sudo 사용 가능**을 가정한다. 다른 OS는 패키지 설치 명령을 조정한다. 공개 주소와 비밀번호는 실제 환경 값으로 바꾼다.

## 1. Windows 개발 환경의 JDK 확인

OpenJDK 21 배포판을 설치한 후 새 PowerShell에서 확인한다.

```powershell
java -version
javac -version
$env:JAVA_HOME
Get-Command java,javac | Select-Object Name,Source
```

`java`와 `javac`의 버전이 모두 21인지 확인한다. 다른 버전이 선택되면 실제 설치 경로로 현재 터미널을 설정한다. `JAVA_HOME`은 `bin`이 아닌 JDK 루트 디렉터리다.

```powershell
# 예시 경로: 설치된 JDK 21의 실제 경로로 교체
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat --stop
.\gradlew.bat --version
.\gradlew.bat -q javaToolchains
```

지속 적용하려면 Windows 환경 변수의 `JAVA_HOME`과 `Path`도 수정하고 VS Code와 터미널을 다시 연다. Gradle 출력의 Launcher/Daemon JVM과 toolchain을 확인한다. 사용자 `~/.gradle/gradle.properties`나 IDE의 Gradle 설정에 다른 JDK 경로가 고정되어 있다면 함께 수정한다.

## 2. VS Code 확장 프로그램

Extensions에서 다음 두 팩을 설치한다.

| 확장 프로그램 | ID | 용도 |
| --- | --- | --- |
| Extension Pack for Java | `vscjava.vscode-java-pack` | Java 프로젝트 인식, 자동 완성, 디버깅, 테스트 |
| Spring Boot Extension Pack | `vmware.vscode-boot-dev-pack` | Spring Boot 개발 지원, Dashboard, Initializr |

VS Code의 `code` 명령이 PATH에 등록되어 있으면 터미널에서도 설치할 수 있다.

```bash
code --install-extension vscjava.vscode-java-pack
code --install-extension vmware.vscode-boot-dev-pack
```

명령 팔레트의 **Java: Configure Java Runtime**에서 프로젝트가 Java 21을 사용하는지 확인한다. 확장 프로그램 자체 실행용 JDK와 프로젝트 빌드용 JDK는 별도일 수 있으므로 확장이 요구하는 실행 환경은 유지하고 프로젝트 runtime을 21로 지정한다. 변경 후 **Developer: Reload Window**를 실행하고, 프로젝트 인식이 계속 잘못되면 **Java: Clean Java Language Server Workspace**를 실행한다.

Remote SSH로 VM 폴더를 열어 개발할 때는 확장 화면의 **Install in SSH: 호스트명**을 통해 원격 환경에도 설치하고, VM의 JDK를 확인한다. SSH 터미널에서 빌드하거나 JAR만 실행한다면 VS Code 확장 프로그램 설치는 필요 없다.

## 3. Ubuntu VM에 OpenJDK 21 설치

```bash
sudo apt update
sudo apt install -y openjdk-21-jdk git curl unzip
sudo update-alternatives --config java
sudo update-alternatives --config javac
```

선택 화면이 나오면 두 명령 모두 Java 21 경로를 선택한다. 후보가 하나뿐이면 바로 종료될 수 있다.

```bash
export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
export PATH="$JAVA_HOME/bin:$PATH"
java -version
javac -version
echo "$JAVA_HOME"
```

현재 셸의 설정이다. SSH 재접속 뒤에도 필요하면 검증한 `JAVA_HOME`과 PATH 설정을 `~/.bashrc`에 추가한다. systemd 서비스는 이 파일을 읽지 않으므로 아래 서비스 설정에서 Java 경로를 별도로 지정한다.

## 4. 저장소 받기 · 빌드 · 개발 실행

처음 받을 때:

```bash
git clone https://github.com/JeongHun4035/ticket-buy-BE.git
cd ticket-buy-BE
chmod +x gradlew
./gradlew --stop
./gradlew --version
./gradlew -q javaToolchains
./gradlew test bootJar
```

이미 받은 저장소에서는 작업 내용을 `git status`로 확인한 뒤 프로젝트 루트에서 `git pull --ff-only`로 갱신한다. 최초 빌드에는 Gradle 배포 파일과 의존성을 내려받을 외부 네트워크 연결이 필요하다.

개발용 H2 DB로 실행한다. 개발 계정과 시드가 생성되므로 개발 검증에 사용한다.

```bash
export SPRING_PROFILES_ACTIVE=dev
./gradlew bootRun
```

종료는 `Ctrl+C`. 빌드된 JAR을 실행할 때는 위 프로세스를 종료하고 다음을 사용한다.

```bash
java -jar build/libs/ticket-buy-be-0.0.1-SNAPSHOT.jar
```

별도 SSH 터미널에서 확인한다.

```bash
curl -fsS http://localhost:8080/actuator/health
ss -ltnp | grep ':8080'
```

정상 기동 시 health 응답에 `"status":"UP"`이 표시된다. Swagger는 dev에서 `http://localhost:8080/swagger-ui.html`로 확인한다. 로컬 PC에서 VM의 개발 서버에 접근하려면 PC 터미널에서 포워딩할 수 있다.

```bash
# 사용자와 VM 주소를 교체하고 이 SSH 연결을 유지
ssh -L 8080:127.0.0.1:8080 USER@VM_HOST
```

이후 PC 브라우저에서 `http://localhost:8080/swagger-ui.html`을 연다. 직접 공개 접속을 구성했다면 클라우드 방화벽, VM 방화벽, reverse proxy 설정도 확인한다.

## 5. PostgreSQL 및 운영 프로필

`compose.yaml`은 PostgreSQL만 실행한다. Docker Engine과 Compose 플러그인이 설치된 VM에서 확인하고 실행한다.

```bash
docker --version
docker compose version
read -rsp 'PostgreSQL password: ' DB_PASSWORD
echo
export DB_PASSWORD
docker compose up -d
docker compose ps
docker compose logs --tail=50 postgres
```

이 DB는 호스트의 `127.0.0.1:5432`로 연결하며 볼륨에 데이터를 보관한다. 중지는 `docker compose stop`, 재시작은 `docker compose start`다. `docker compose down -v`는 DB 볼륨을 삭제하므로 실행하지 않는다.

운영 실행 예시에서는 실제 HTTPS 공개 주소와 프론트 Origin을 지정한다. HTTPS 종료용 reverse proxy와 인증서는 별도로 구성되어 있어야 한다.

```bash
export SPRING_PROFILES_ACTIVE=prod
export DB_URL='jdbc:postgresql://localhost:5432/ticketbuy'
export DB_USER='ticketbuy'
# DB_PASSWORD는 위에서 입력한 값 또는 실제 운영 DB 비밀번호
export PUBLIC_BASE_URL='https://api.example.com'
export CORS_ORIGINS='https://tickets.example.com'
java -jar build/libs/ticket-buy-be-0.0.1-SNAPSHOT.jar
```

prod는 개발 시드를 생성하지 않고 Secure 쿠키를 강제하며 Swagger를 기본 비활성화한다. HTTP 공개 주소로는 운영 refresh 쿠키를 정상 사용할 수 없다. 프론트와 API가 같은 공개 주소라면 두 URL에 같은 Origin을 사용한다. CORS 목록에는 경로 없이 프로토콜·호스트·포트를 넣는다. 상세 설정은 [Origin 가이드](server-origin.md)를 참고한다.

Spring은 `.env`를 자동으로 읽지 않는다. Docker Compose가 `.env`를 읽더라도 호스트에서 실행하는 Java에 자동 전달되지 않는다. 환경변수는 프로세스 시작 전에 지정하고 변경 후 재시작한다. 실제 비밀번호가 담긴 파일은 Git에 올리지 않는다.

## 6. SSH 종료 후에도 실행: systemd

앞 절의 빌드와 DB 준비를 완료한 뒤 사용한다. 아래 `YOUR_USER`, `/ABSOLUTE/PATH/ticket-buy-BE`, Java 절대 경로를 실제 값으로 바꾼다. Java 경로는 `readlink -f "$(command -v java)"`로 확인한다. 서비스는 일반 사용자로 실행한다.

```bash
sudo install -m 600 /dev/null /etc/ticket-buy-be.env
sudoedit /etc/ticket-buy-be.env
```

환경 파일 내용(`export` 없이 작성):

```dotenv
SPRING_PROFILES_ACTIVE=prod
DB_URL=jdbc:postgresql://localhost:5432/ticketbuy
DB_USER=ticketbuy
DB_PASSWORD=replace-with-real-password
PUBLIC_BASE_URL=https://api.example.com
CORS_ORIGINS=https://tickets.example.com
```

`sudoedit /etc/systemd/system/ticket-buy-be.service`로 다음 내용을 저장한다. `JAVA_21_ABSOLUTE_PATH`는 Java 실행 파일의 절대 경로로 교체한다.

```ini
[Unit]
Description=Ticket Buy Backend
Wants=network-online.target
After=network-online.target

[Service]
User=YOUR_USER
WorkingDirectory=/ABSOLUTE/PATH/ticket-buy-BE
EnvironmentFile=/etc/ticket-buy-be.env
ExecStart=JAVA_21_ABSOLUTE_PATH -jar /ABSOLUTE/PATH/ticket-buy-BE/build/libs/ticket-buy-be-0.0.1-SNAPSHOT.jar
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now ticket-buy-be
sudo systemctl status ticket-buy-be --no-pager
sudo journalctl -u ticket-buy-be -n 100 --no-pager
curl -fsS http://localhost:8080/actuator/health
```

실시간 로그는 `sudo journalctl -u ticket-buy-be -f`, 종료는 `sudo systemctl stop ticket-buy-be`다. DB도 VM 재부팅 후 자동 시작되도록 별도 설정해야 한다. 현재 compose 파일에는 재시작 정책이 없다.

소스 갱신 시에는 프로젝트 루트에서 변경 내용을 확인하고 빌드 성공 후 서비스를 재시작한다.

```bash
git status
git pull --ff-only
./gradlew test bootJar
sudo systemctl restart ticket-buy-be
sudo journalctl -u ticket-buy-be -n 100 --no-pager
curl -fsS http://localhost:8080/actuator/health
```

환경 파일만 수정했다면 서비스 재시작, unit 파일을 수정했다면 `daemon-reload` 후 재시작한다.

## 7. 자주 발생하는 오류

| 증상 | 확인 및 조치 |
| --- | --- |
| Java 버전 오류 / `UnsupportedClassVersionError` | `java -version`, `javac -version`, Wrapper `--version` 확인. 프로젝트 JDK 21과 실행 JVM을 맞추고 Gradle daemon을 중지한 뒤 재실행 |
| toolchain 21을 찾지 못함 | JDK 21 설치 및 `JAVA_HOME` 확인. `./gradlew -q javaToolchains`로 탐지 결과 확인 |
| `Permission denied: ./gradlew` | 저장소 루트에서 `chmod +x gradlew` |
| Gradle 캐시 쓰기 권한 오류 | `export GRADLE_USER_HOME="$PWD/.gradle-user"` 후 다시 실행. Gradle을 sudo로 실행하지 않음 |
| `Port 8080 was already in use` | `ss -ltnp`로 기존 프로세스 확인. systemd와 bootRun을 동시에 실행하지 않음 |
| H2 `Database may be already in use` | 같은 `data/ticketbuy`를 사용하는 기존 앱 종료. 다중 인스턴스에는 PostgreSQL 사용 |
| DB 연결 실패 | `docker compose ps`, DB 로그, DB_URL/DB_USER/DB_PASSWORD 확인 |
| `ORIGIN_NOT_ALLOWED` | 실제 브라우저 Origin을 CORS_ORIGINS에 추가하고 백엔드 재시작 |
| VM 안에서는 되지만 외부 접속 실패 | SSH 포워딩 또는 공개 방화벽·reverse proxy·포트 설정 확인 |

이 문서는 저장소 설정과 공식 도구 문서를 기준으로 작성했다. 실제 VM에서 설치·기동을 수행한 검증 결과는 아니다.

## 참고

- [VS Code Java 확장 프로그램 안내](https://code.visualstudio.com/docs/java/extensions)
- [Spring Boot Extension Pack](https://marketplace.visualstudio.com/items?itemName=vmware.vscode-boot-dev-pack)
- [Gradle Java 호환성](https://docs.gradle.org/current/userguide/compatibility.html)
- [Gradle 설치 및 Wrapper 안내](https://docs.gradle.org/current/userguide/installation.html)
