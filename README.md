# Mormi Backend

모르미의 인증, 반복 학습, 카페 진행, 보상과 **AI 대화 소유권**을 관리하는 Spring Boot 백엔드입니다.

## 운영 아키텍처

```text
브라우저
  → Next.js /api/be 프록시
    → Spring BE (Bearer 학습자 인증·DB·진행·보상)
      → FastAPI Mormi-AI (서비스 키·TurnContract)
```

브라우저는 `learner_id`, 대화 저장 동의, AI 서비스 키를 보내지 않습니다. Spring BE가 Bearer 토큰으로 학습자를 확인하고 DB의 동의 정책을 붙인 뒤 AI를 호출합니다. AI가 발급한 `conversation_id`는 `dialogue_conversations`에 학습 세션 또는 카페 방문과 함께 저장하므로 다른 아이의 대화 조회·응답·보상 위조를 막습니다.

## 주요 기능

- 학습자 생성·복구와 Bearer 토큰 인증
- 집 반복 문제의 정답·오답, 구조화 오개념, 보상 원장 저장
- 마지막 반복 기록 이후 `POST /v1/learning-sessions/{id}/teaching` 한 번으로 반복 결과 집계 + AI 가르치기 시작
- AI `TurnContract` 전체를 변경 없이 FE에 전달
- 카페 네 단계 판정과 단계별 AI 대화 생성, 최초 문제 맥락의 새로고침 복구
- AI가 도움 경로까지 완료하면 `completion.verified_facts`만 사용해 카페 단계 기록을
  동기화하고 `stage_progress`를 FE에 반환
- AI 완료 대화만 가르치기 보상으로 인정하며 세션당 한 번만 지급
- 자유 발화 원문은 일반 학습 DB에 저장하지 않음

아이 원문은 Spring DB의 `attempts.answer_meta`나 `cafe_visit_stages.payload`에 넣지 않습니다. 원문은 대화 응답 순간 AI로만 전달되고, Mormi-AI가 학습자의 동의 정책에 따라 암호화 저장하거나 즉시 폐기합니다.
AI가 생성한 문장도 단계 정답으로 신뢰하지 않으며, Mormi-AI의 결정형
오케스트레이터가 검증한 `completion.verified_facts`만 사용합니다.

## 실행

요구사항: Java 21, PostgreSQL 16.

```bash
SPRING_PROFILES_ACTIVE=dev \
DB_HOST=localhost DB_PORT=5432 DB_NAME=mormi \
DB_USERNAME=mormi DB_PASSWORD=mormi \
MORMI_DIALOGUE_BASE_URL=http://localhost:8000 \
MORMI_DIALOGUE_SERVICE_KEY=change-me \
./gradlew bootRun
```

상태 확인: `GET /health`, `GET /actuator/health`.

## 환경 변수

| 이름 | 설명 |
|---|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` | PostgreSQL 접속 정보 |
| `CORS_ALLOWED_ORIGINS` | 허용할 FE 오리진 목록 |
| `MORMI_DIALOGUE_BASE_URL` | 배포된 Mormi-AI FastAPI 주소 |
| `MORMI_DIALOGUE_SERVICE_KEY` | AI의 `MORMI_SERVICE_API_KEY`와 같은 값 |

운영에서는 두 대화 환경 변수가 모두 필요합니다. 키는 FE/Vercel 공개 환경변수에 넣지 않습니다.

## 테스트

```bash
./gradlew test
```

통합 테스트는 Testcontainers PostgreSQL을 사용합니다. 자세한 요청·응답은 [docs/API_LIST.md](docs/API_LIST.md), 배포 방법은 [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)를 참고하세요.

AI 교육 로직, 발화사다리·힌트사다리, 도움카드와 별노트 생성은 별도 [Mormi-AI](https://github.com/flyai-y2s2/Mormi-AI) 저장소에서 관리합니다.
