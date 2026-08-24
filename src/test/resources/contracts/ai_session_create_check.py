"""BE가 보내는 놀이동산 대화 본문을 Mormi-AI 의 실제 SessionCreate 스키마에 그대로 넣어 본다.

BE 테스트가 규칙을 베껴 적으면 AI 쪽이 계약을 바꿨을 때 BE 는 초록불인 채로 배포된다.
그래서 여기서는 규칙을 다시 쓰지 않고 pydantic 모델을 그대로 불러 검증만 시킨다.

stdin  : {"accept": [본문, ...], "reject": [본문, ...]}
stdout : {"failures": ["...", ...]}
"""

import json
import sys

from mormi_api.schemas import SessionCreate

payload = json.load(sys.stdin)
failures: list[str] = []

for index, body in enumerate(payload.get("accept", [])):
    try:
        SessionCreate.model_validate(body)
    except Exception as error:  # pydantic ValidationError 포함
        failures.append(f"accept[{index}] {body.get('scenario_id')}: {error}")

# 거절 표본은 이 테스트가 실제로 무언가를 잡아내는지 보여 주는 대조군이다.
for index, body in enumerate(payload.get("reject", [])):
    try:
        SessionCreate.model_validate(body)
    except Exception:
        continue
    failures.append(
        f"reject[{index}] {body.get('scenario_id')}: 거절했어야 하는 본문을 스키마가 통과시켰다"
    )

json.dump({"failures": failures}, sys.stdout, ensure_ascii=False)
