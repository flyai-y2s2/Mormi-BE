# Deployment

`develop` push → GitHub Actions가 빌드/테스트 → Docker 이미지 빌드 → Amazon ECR push → EC2에서 pull → 컨테이너 교체.

```
GitHub Actions (Ubuntu)
  ├─ ./gradlew clean build         (Testcontainers Postgres, RDS 무관)
  ├─ OIDC → AWS IAM Role assume    (장기 access key 없음)
  ├─ docker build → ECR push       (mormi-backend:<sha>, mormi-backend:develop)
  └─ SSH → EC2
             ├─ aws ecr get-login-password | docker login   (EC2 Instance Role)
             ├─ docker pull <IMAGE_URI>
             ├─ docker rm -f mormi-backend
             └─ docker run -d --env-file /etc/mormi-backend/mormi.env ...
```

- 런타임 시크릿(RDS 크리덴셜 등)은 이미지에 굽지 않는다. `/etc/mormi-backend/mormi.env` 에서 `--env-file` 로 주입.
- CI는 절대 RDS에 붙지 않는다. `SPRING_PROFILES_ACTIVE=test` + Testcontainers Postgres만 사용.

---

## 1. AWS 콘솔에서 준비 (최초 1회)

### 1-1. ECR 리포지토리 생성

- 리전: 서비스 리전 (예: `ap-northeast-2`)
- 이름: `mormi-backend`
- 이미지 태그 immutability: `Mutable` (SHA 태그는 실무상 immutable이지만 `develop` alias를 재작성해야 하므로 mutable로 둔다)
- 스캔: On push (권장)

리전과 계정 ID(12자리)를 기록. 이미지 URI 형식:

```
<ACCOUNT_ID>.dkr.ecr.<REGION>.amazonaws.com/mormi-backend:<TAG>
```

### 1-2. GitHub OIDC provider 등록

IAM → Identity providers → Add provider

- Provider type: **OpenID Connect**
- Provider URL: `https://token.actions.githubusercontent.com`
- Audience: `sts.amazonaws.com`

이미 다른 레포에서 등록해 뒀다면 재사용.

### 1-3. GitHub Actions용 IAM Role (ECR push)

IAM → Roles → Create role
- Trusted entity: **Web identity**
- Identity provider: 방금 만든 GitHub OIDC provider
- Audience: `sts.amazonaws.com`

Trust policy(신뢰 관계)를 다음으로 교체 (레포 슬러그와 브랜치를 정확히 맞춘다):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Federated": "arn:aws:iam::<ACCOUNT_ID>:oidc-provider/token.actions.githubusercontent.com"
      },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": {
          "token.actions.githubusercontent.com:aud": "sts.amazonaws.com"
        },
        "StringLike": {
          "token.actions.githubusercontent.com:sub": "repo:<GITHUB_ORG>/<REPO>:ref:refs/heads/develop"
        }
      }
    }
  ]
}
```

Permission policy (인라인, ECR push 최소권한):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "EcrAuth",
      "Effect": "Allow",
      "Action": "ecr:GetAuthorizationToken",
      "Resource": "*"
    },
    {
      "Sid": "EcrPushPull",
      "Effect": "Allow",
      "Action": [
        "ecr:BatchCheckLayerAvailability",
        "ecr:BatchGetImage",
        "ecr:GetDownloadUrlForLayer",
        "ecr:InitiateLayerUpload",
        "ecr:UploadLayerPart",
        "ecr:CompleteLayerUpload",
        "ecr:PutImage"
      ],
      "Resource": "arn:aws:ecr:<REGION>:<ACCOUNT_ID>:repository/mormi-backend"
    }
  ]
}
```

Role 이름 예: `github-actions-mormi-backend-deploy`
생성 후 **Role ARN** 을 복사해 둔다. → GitHub Secret `AWS_DEPLOY_ROLE_ARN` 에 저장.

### 1-4. EC2 Instance Role (ECR pull)

EC2가 ECR에서 이미지를 pull할 때 사용. Access Key를 EC2에 두지 않기 위함.

IAM → Roles → Create role
- Trusted entity: **AWS service** → **EC2**

Permission policy (인라인, ECR pull 최소권한):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "EcrAuth",
      "Effect": "Allow",
      "Action": "ecr:GetAuthorizationToken",
      "Resource": "*"
    },
    {
      "Sid": "EcrPull",
      "Effect": "Allow",
      "Action": [
        "ecr:BatchCheckLayerAvailability",
        "ecr:BatchGetImage",
        "ecr:GetDownloadUrlForLayer"
      ],
      "Resource": "arn:aws:ecr:<REGION>:<ACCOUNT_ID>:repository/mormi-backend"
    }
  ]
}
```

Role 이름 예: `ec2-mormi-backend-ecr-pull`
EC2 인스턴스 → Actions → Security → Modify IAM role → 이 role 부착.

---

## 2. GitHub에 등록해야 할 값

Repo → Settings → Secrets and variables → Actions

### Secrets

| 이름                    | 값                                                                              |
|-------------------------|---------------------------------------------------------------------------------|
| `AWS_DEPLOY_ROLE_ARN`   | 위 1-3 에서 만든 role ARN (`arn:aws:iam::<ACCOUNT_ID>:role/github-actions-...`) |
| `EC2_HOST`              | EC2 퍼블릭 DNS 또는 IP                                                          |
| `EC2_USER`              | 배포용 SSH 유저 (기본 `ubuntu`)                                                 |
| `EC2_SSH_KEY`           | 해당 유저의 SSH 프라이빗 키 (`-----BEGIN OPENSSH PRIVATE KEY-----` 포함)         |

### Variables (Repository variables 탭)

| 이름              | 값                                    |
|-------------------|---------------------------------------|
| `AWS_REGION`      | `ap-northeast-2` 등 실제 리전         |
| `ECR_REPOSITORY`  | `mormi-backend`                       |

> `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` 는 등록하지 않는다. OIDC 만 사용.

---

## 3. EC2 최초 1회 세팅

EC2에 SSH로 로그인 (`ubuntu` 유저).

### 3-1. Docker 설치

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl gnupg
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | \
  sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg
echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
   https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io

sudo usermod -aG docker ubuntu   # 재로그인 후 sudo 없이 docker 사용 가능
sudo systemctl enable --now docker
```

재로그인 후 확인:

```bash
docker version
docker ps
```

### 3-2. AWS CLI v2 설치 (ECR 로그인용)

```bash
sudo apt-get install -y unzip
curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o /tmp/awscliv2.zip
unzip -q /tmp/awscliv2.zip -d /tmp
sudo /tmp/aws/install
aws --version
```

Instance Role이 부착돼 있으면 `aws sts get-caller-identity` 로 확인 가능.

### 3-3. 환경변수 파일 (RDS 크리덴셜)

기존 파일이 있으면 그대로 재사용. 없으면 새로 생성:

```bash
sudo mkdir -p /etc/mormi-backend
sudo tee /etc/mormi-backend/mormi.env >/dev/null <<'EOF'
SPRING_PROFILES_ACTIVE=dev
DB_HOST=<RDS 엔드포인트>
DB_PORT=5432
DB_NAME=mormi
DB_USERNAME=<계정>
DB_PASSWORD=<비밀번호>
MORMI_DIALOGUE_BASE_URL=http://<Mormi-AI-내부주소>:8000
MORMI_DIALOGUE_SERVICE_KEY=<AI의 MORMI_SERVICE_API_KEY와 같은 값>
MORMI_DIALOGUE_READ_TIMEOUT_SECONDS=45
EOF
```

Docker 전환에 맞춰 소유권/권한 조정 (SSH 유저가 `--env-file` 를 읽어야 함):

```bash
sudo chown root:ubuntu /etc/mormi-backend/mormi.env
sudo chmod 640 /etc/mormi-backend/mormi.env
```

이 파일은 GitHub Secrets에 넣지 않는다. EC2 위에서만 존재.

`MORMI_DIALOGUE_BASE_URL`과 `MORMI_DIALOGUE_SERVICE_KEY`가 없으면 일반 학습 API는 실행되지만 가르치기·카페 AI 대화를 시작할 수 없습니다. 서비스 키를 FE 또는 Vercel의 `NEXT_PUBLIC_*` 환경변수에 넣지 마세요.

### 3-4. 8080 포트 방화벽

Security Group 인바운드에 8080/TCP 를 원하는 소스(ALB SG 혹은 특정 CIDR) 로 허용.

### 3-5. (선택) 기존 systemd/JAR 잔재 정리

Docker 방식으로 완전히 전환했다면:

```bash
sudo systemctl disable --now mormi-backend 2>/dev/null || true
sudo rm -f /etc/systemd/system/mormi-backend.service
sudo systemctl daemon-reload
sudo rm -f /opt/mormi-backend/app.jar
sudo rm -f /etc/sudoers.d/mormi-backend-deploy
```

---

## 4. 첫 배포

- GitHub Actions 탭 → `CI/CD (develop → ECR → EC2)` → `Run workflow`
- 또는 develop 에 커밋 push

성공 시 EC2 에서:

```bash
docker ps                    # mormi-backend 가 Up 상태
docker logs -f mormi-backend # Spring Boot 부팅 로그
curl -i http://localhost:8080/
```

기대 로그:
- `The following 1 profile is active: "dev"`
- `HikariPool-1 - Added connection ...` (RDS)
- `Tomcat started on port 8080 (http)`
- `Started BackendApplication in ...`

---

## 5. 운영 명령

| 상황                     | 명령                                                          |
|--------------------------|---------------------------------------------------------------|
| 컨테이너 상태           | `docker ps -a --filter name=mormi-backend`                    |
| 실시간 로그             | `docker logs -f mormi-backend`                                |
| 최근 로그 200줄         | `docker logs --tail=200 mormi-backend`                        |
| 수동 재시작             | `docker restart mormi-backend`                                |
| 수동 정지               | `docker stop mormi-backend`                                   |
| RDS 크리덴셜 변경       | `/etc/mormi-backend/mormi.env` 수정 후 `docker restart mormi-backend` |
| 현재 실행 중인 이미지   | `docker inspect --format '{{.Config.Image}}' mormi-backend`   |

---

## 6. Rollback

이전 성공한 SHA로 되돌린다. 이미지는 ECR 에 `mormi-backend:<sha>` 로 남아있다.

EC2 에서:

```bash
REGISTRY=<ACCOUNT_ID>.dkr.ecr.<REGION>.amazonaws.com
IMAGE=$REGISTRY/mormi-backend:<이전_커밋_SHA>

aws ecr get-login-password --region <REGION> \
  | docker login --username AWS --password-stdin $REGISTRY

docker pull $IMAGE
docker rm -f mormi-backend
docker run -d \
  --name mormi-backend \
  --restart unless-stopped \
  --env-file /etc/mormi-backend/mormi.env \
  -p 8080:8080 \
  --log-opt max-size=10m --log-opt max-file=5 \
  $IMAGE

docker logs -f mormi-backend
```

이후 `develop` 브랜치에서 hotfix 커밋 → 정상 파이프라인 복귀.

이전 SHA 목록은 AWS Console → ECR → `mormi-backend` 에서 확인 가능.

---

## 7. 테스트가 RDS 없이 동작하는 이유

- `BackendApplicationTests` 는 `@Testcontainers` + `@ServiceConnection` + `postgres:16-alpine` 을 사용한다. Spring Boot 가 Testcontainer의 datasource를 자동으로 주입하므로 `DB_HOST` 등 env 변수 없이도 컨텍스트가 로드된다.
- `build.gradle` 의 `test` 태스크에서 `SPRING_PROFILES_ACTIVE=test` 를 강제 세팅하므로, 로컬/CI 어디서 실행하든 `application-test.yml` 이 로드되고 `application-dev.yml` (RDS 참조) 은 로드되지 않는다.
- Flyway 마이그레이션은 Testcontainer Postgres 에 그대로 적용되고, `ddl-auto=validate` 가 스키마 정합성까지 검증한다.

---

## 8. 관련 파일

- `Dockerfile` — 런타임 이미지 정의 (Java 21 JRE Alpine, non-root, 8080 expose)
- `.dockerignore` — build context 최소화 (`build/libs/app.jar` 만 포함)
- `.github/workflows/deploy.yml` — CI/CD 워크플로우
- `build.gradle` — `bootJar { archiveFileName = 'app.jar' }` 로 산출물 경로 고정
- `/etc/mormi-backend/mormi.env` (EC2) — RDS 크리덴셜 및 런타임 env, `--env-file` 로 주입
