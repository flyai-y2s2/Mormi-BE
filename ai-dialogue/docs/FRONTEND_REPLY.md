# 프론트엔드 회신에 대한 백엔드 확정 답변

2026-08-12 전달받은 「모르미 AI 대화 백엔드 연동 — 프론트엔드 회신 및 합의 요청」의
구현 전 확인 항목에 대한 답변입니다.

1. **`learner_id`**: 양의 정수로 확정했습니다.
2. **URL 변수명**: 모든 AI 대화 경로에서 `{conversation_id}`로 통일했습니다.
3. **시작 응답**: 시작과 후속 응답 모두 `{ conversation_id, turn }` 계약을 반환합니다.
4. **선택지**: `{ id, label, image_url?, disabled? }` 배열로 반환합니다.
5. **시각 자료**: [`VISUAL_CONTRACTS.md`](./VISUAL_CONTRACTS.md)에 타입별 필드와 예시를 제공합니다.
6. **완료 결과**: `status=completed`일 때 `completion.outcome`과 `teach_reward_eligible`을 반환합니다.
7. **409**: `detail`에 `conversation_id`, 최신 `turn_id`, `state_version`을 포함합니다.
8. **멱등 보존**: 동일 `response_id`의 최초 결과를 기본 30일 보존합니다.
9. **반복 결과 조회**: AI DB에 저장된 ID는 `practice_result_id`만으로 조회합니다. 분리 서비스 MVP에서 조회할 수 없으면 `practice_summary`를 함께 보내야 합니다.
10. **원문 동의**: 일반 학습/온보딩 계층이 동의를 수집하고 BFF가 스냅샷을 전달합니다. AI 백엔드는 `conversation_storage_consent`와 `retention_policy`를 강제합니다.
11. **별노트**: 대화 중 생성·중복 방지는 AI 대화 DB가 담당합니다. 일반 학습 백엔드는 추후 `note_id`로 동기화하거나 AI 조회 API를 사용합니다.
12. **주소·인증**: 로컬은 `http://localhost:8000`, 헤더는 `X-Mormi-Service-Key`입니다. 스테이징 주소는 배포 후 환경 변수로 전달합니다.

## 추가 합의

- 아이 이름은 AI 대화 시작 요청과 LLM 프롬프트에 넣지 않습니다.
- 따라서 현재 AI 백엔드의 귀속 라벨은 “아이가 알려줌/아이와 같이 공부함”입니다.
- 프론트가 보유한 이름을 귀속 UI에 붙일 경우에도 별노트 본문은 수정하지 않습니다.
- 추후 일반 학습 백엔드의 내부 프로필 조회가 연결되면 서버 간 조회로 이름 라벨을 완성할 수 있습니다.
- 모르미 질문은 화면 복구를 위해 항상 암호화 저장합니다.
- 아이 원문은 동의가 있는 대화에서만 암호화 저장하며, 동의가 없으면 구조화 결과만 저장합니다.
