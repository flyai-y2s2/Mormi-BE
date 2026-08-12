# Mormi Backend

모르미의 일반 학습 백엔드와 AI 대화 백엔드를 함께 관리하는 저장소입니다.

## 서비스 구성

```text
Mormi-BE/
├── src/                    # Spring Boot 일반 학습 백엔드
├── build.gradle
└── ai-dialogue/            # FastAPI + LangGraph AI 대화 백엔드
```

### Spring Boot

학습자, 반복학습 결과, 진행도, 보상 원장, 장소 해금 등 일반 학습 데이터를
담당합니다.

- Java 21
- Spring Boot 4
- Spring MVC, JPA, Security, Validation
- PostgreSQL, Flyway

```bash
./gradlew bootRun
```

기본 상태 확인은 `GET http://localhost:8080/health`입니다.

### AI 대화 백엔드

아이 발화 이해, 발화사다리·힌트사다리, 모르미 대사, 도움 카드, 별노트를
담당합니다. 자세한 실행 방법과 API 계약은
[`ai-dialogue/README.md`](./ai-dialogue/README.md)를 참고하세요.

```bash
cd ai-dialogue
python3.12 -m venv .venv
source .venv/bin/activate
pip install -e '.[dev]'
cp .env.example .env
uvicorn mormi_api.main:app --reload
```

- Swagger UI: `http://localhost:8000/docs`
- 상태 확인: `GET http://localhost:8000/health`
- 프론트 연동 규격: [`ai-dialogue/docs/FRONTEND_INTEGRATION.md`](./ai-dialogue/docs/FRONTEND_INTEGRATION.md)
- OpenAPI: [`ai-dialogue/docs/openapi.json`](./ai-dialogue/docs/openapi.json)

## 책임 경계

```text
브라우저
  → Next.js BFF
      ├── Spring Boot: 학습 기록·진행도·보상
      └── FastAPI: AI 대화 턴·도움 카드·별노트
```

프론트엔드는 아이 응답의 정오, 오개념, 발화사다리 이동, 별노트 귀속을
재판정하지 않고 AI 대화 백엔드의 `TurnContract`를 그대로 렌더링합니다.

## 환경 변수

실제 비밀값은 커밋하지 않습니다. AI 대화 서비스는
`ai-dialogue/.env.example`을 복사해 설정합니다.

운영 환경에서는 다음 값이 필수입니다.

- `MORMI_DATABASE_URL`: PostgreSQL 비동기 접속 주소
- `MORMI_ANTHROPIC_API_KEY`: Claude API 키
- `MORMI_RAW_DATA_ENCRYPTION_KEY`: 대화 원문 암호화 키
- `MORMI_SERVICE_API_KEY`: Next.js BFF와의 서비스 간 인증 키
