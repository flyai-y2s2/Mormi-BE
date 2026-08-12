# Mormi Backend

모르미의 회원, 일반 학습, 진행도와 서비스 데이터를 관리하는 Spring Boot 백엔드입니다.

## 서비스 구성

```text
Mormi-BE/
├── src/                    # Spring Boot 일반 학습 백엔드
└── build.gradle
```

### Spring Boot

학습자, 반복학습 결과, 진행도, 보상 원장, 장소 해금 등 일반 학습 데이터를
담당합니다.

현재 저장소의 Spring Boot 구현 범위는 `/health` 초기 골격이며, 위 일반 학습
기능은 팀 백엔드가 순차적으로 추가할 예정입니다.

- Java 21
- Spring Boot 4
- Spring MVC, JPA, Security, Validation
- PostgreSQL, Flyway

```bash
./gradlew bootRun
```

기본 상태 확인은 `GET http://localhost:8080/health`입니다.

### AI 대화 서비스

아이 발화 이해, 발화사다리·힌트사다리, 모르미 대사, 도움 카드, 별노트 후보 생성은 별도 [`Mormi-AI`](https://github.com/flyai-y2s2/Mormi-AI) 저장소에서 관리합니다.

## 책임 경계

```text
브라우저
  → Next.js BFF
      ├── Spring Boot: 학습 기록·진행도·보상
      └── Mormi-AI: AI 대화 턴·도움 카드·별노트 후보
```

프론트엔드는 아이 응답의 정오, 오개념, 발화사다리 이동, 별노트 귀속을
재판정하지 않고 AI 대화 백엔드의 `TurnContract`를 그대로 렌더링합니다.

Spring 백엔드는 회원·인증, 반복학습 원본, 전체 진도, 보상·장소 해금과 장기 서비스 데이터의 최종 원장을 담당합니다. AI 모델 키와 AI 프롬프트는 이 저장소에 두지 않습니다.
