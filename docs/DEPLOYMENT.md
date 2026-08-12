# Deployment

`develop` 브랜치에 push → GitHub Actions가 빌드/테스트 후 EC2로 JAR을 배포하고 systemd 서비스를 재시작한다.

```
GitHub Actions (Ubuntu)
  ├─ ./gradlew clean build   (Testcontainers Postgres, RDS 무관)
  └─ 성공 시 SCP → EC2:/tmp/app.jar
             SSH  → sudo install → /opt/mormi-backend/app.jar
                     sudo systemctl restart mormi-backend
```

---

## 1. GitHub Secrets

Repo → Settings → Secrets and variables → Actions 에 세 개 등록.

| 이름           | 값                                                                        |
|----------------|---------------------------------------------------------------------------|
| `EC2_HOST`     | EC2 퍼블릭 DNS 또는 IP (예: `ec2-13-xxx-xxx-xxx.compute.amazonaws.com`)     |
| `EC2_USER`     | 배포용 SSH 유저 (기본 `ubuntu`)                                            |
| `EC2_SSH_KEY`  | `EC2_USER`로 접속 가능한 프라이빗 키 (`-----BEGIN OPENSSH PRIVATE KEY-----` 포함, 개행 유지) |

키는 배포 전용으로 하나 새로 만드는 것을 권장. `ssh-keygen -t ed25519 -C deploy@mormi` 후 공개키만 EC2 `~ubuntu/.ssh/authorized_keys` 에 추가.

---

## 2. EC2 최초 1회 세팅

EC2에 SSH로 로그인한 뒤 순서대로 실행.

### 2-1. 자바 21 설치

```bash
sudo apt-get update
sudo apt-get install -y openjdk-21-jre-headless
java -version   # 21.x 확인
```

### 2-2. 앱 실행 유저 & 디렉토리

```bash
# 서비스 실행 전용 유저 (로그인 불가)
sudo useradd --system --shell /usr/sbin/nologin --home-dir /opt/mormi-backend mormi

# JAR 배치 디렉토리
sudo mkdir -p /opt/mormi-backend
sudo chown mormi:mormi /opt/mormi-backend
sudo chmod 750 /opt/mormi-backend

# 환경변수 디렉토리
sudo mkdir -p /etc/mormi-backend
sudo chown root:mormi /etc/mormi-backend
sudo chmod 750 /etc/mormi-backend
```

### 2-3. 환경변수 파일 (RDS 크리덴셜)

```bash
sudo tee /etc/mormi-backend/mormi.env >/dev/null <<'EOF'
SPRING_PROFILES_ACTIVE=dev
DB_HOST=<RDS 엔드포인트>
DB_PORT=5432
DB_NAME=mormi
DB_USERNAME=<계정>
DB_PASSWORD=<비밀번호>
EOF

sudo chown root:mormi /etc/mormi-backend/mormi.env
sudo chmod 640 /etc/mormi-backend/mormi.env
```

파일 자체는 root만 쓸 수 있고 `mormi` 그룹만 읽을 수 있다. GitHub Secrets에 넣지 않는다.

### 2-4. systemd unit 등록

레포에 있는 `deploy/mormi-backend.service` 를 EC2로 복사한 뒤:

```bash
sudo cp deploy/mormi-backend.service /etc/systemd/system/mormi-backend.service
sudo systemctl daemon-reload
sudo systemctl enable mormi-backend
```

### 2-5. 배포 유저에 systemctl 권한 부여 (NOPASSWD)

Actions가 SSH로 `sudo systemctl restart` 를 무비번으로 실행해야 함. 최소 권한만 준다.

```bash
sudo tee /etc/sudoers.d/mormi-backend-deploy >/dev/null <<'EOF'
ubuntu ALL=(root) NOPASSWD: /usr/bin/install -o mormi -g mormi -m 640 /tmp/app.jar /opt/mormi-backend/app.jar, /usr/bin/systemctl restart mormi-backend, /usr/bin/systemctl status mormi-backend
EOF
sudo chmod 440 /etc/sudoers.d/mormi-backend-deploy
sudo visudo -c   # 문법 검증
```

> `EC2_USER`를 `ubuntu` 이외로 쓴다면 위 파일의 유저명도 함께 바꿀 것.

### 2-6. 첫 배포 (수동)

이후는 develop push로 자동화되지만, 최초 1회는 다음 중 하나로 시작:
- GitHub Actions 탭 → `CI/CD (develop → EC2)` → `Run workflow` (수동 트리거)
- 또는 develop에 아무 커밋 push

배포 성공 후:

```bash
sudo systemctl status mormi-backend
sudo journalctl -u mormi-backend -f
```

---

## 3. 운영 체크리스트

| 상황                | 명령                                                    |
|---------------------|---------------------------------------------------------|
| 서비스 상태 확인    | `sudo systemctl status mormi-backend`                   |
| 실시간 로그         | `sudo journalctl -u mormi-backend -f`                   |
| 최근 로그 100줄     | `sudo journalctl -u mormi-backend -n 100 --no-pager`    |
| 수동 재시작        | `sudo systemctl restart mormi-backend`                  |
| 수동 정지          | `sudo systemctl stop mormi-backend`                     |
| RDS 크리덴셜 변경   | `/etc/mormi-backend/mormi.env` 수정 후 `restart`         |

---

## 4. 테스트가 RDS 없이 동작하는 이유

- `BackendApplicationTests` 는 `@Testcontainers` + `@ServiceConnection` + `postgres:16-alpine` 을 사용한다. Spring Boot가 Testcontainer의 datasource를 자동으로 주입하므로 `DB_HOST` 등 env 변수 없이도 컨텍스트가 로드된다.
- `build.gradle` 의 `test` 태스크에서 `SPRING_PROFILES_ACTIVE=test` 를 강제 세팅하므로, 로컬/CI 어디서 실행하든 `application-test.yml` 이 로드되고 `application-local.yml` (localhost postgres 참조) 이나 `application-dev.yml` (RDS 참조) 은 로드되지 않는다.
- Flyway 마이그레이션은 Testcontainer Postgres에 그대로 적용되고, `ddl-auto=validate` 가 스키마 정합성까지 검증한다.

---

## 5. 참고: 관련 파일

- `.github/workflows/deploy.yml` — CI/CD 워크플로우
- `deploy/mormi-backend.service` — systemd unit 원본
- `build.gradle` — `bootJar { archiveFileName = 'app.jar' }` 로 산출물 경로 고정
