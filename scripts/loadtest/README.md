# 대화 부하 테스트 (k6 + LLM 스텁)

목적: "동시 N명이 가르치기 대화를 할 때 어디가 먼저 막히는가"를 **돈 안 쓰고** 숫자로 잡는다.
FE→BE→RDS→AI 경로는 전부 실제로 타고, Anthropic 호출만 지연 스텁(`Mormi-AI/scripts/llm_stub.py`)이 대신한다.

코드에서 예측한 첫 병목: `DialogueService` 가 `@Transactional` 안에서 AI 를 호출해
Hikari 기본 풀 **10개**를 AI 응답 시간 동안 붙잡는다. 그래서 스텁의 `/stats` 에서
`max_in_flight` 가 10 언저리에서 멈추는지가 첫 관찰 포인트다.

## 0. 준비

- 파일럿 참여자가 아직 없는 시간에 한다 (운영 스택을 그대로 쓴다).
- BE EC2 는 RAM 908MB·스왑 0 (2026-08-25 확인). 테스트 중 `free -m`, `docker stats mormi-backend`, `dmesg -T | grep -i kill` 도 같이 본다 — 커넥션 풀보다 OOM 이 먼저일 수 있다.
- 노트북: `brew install k6`
- AI EC2에서 실제 지연·토큰 평균을 뽑아 스텁 지연값으로 쓴다.

```bash
docker logs mormi-ai 2>&1 | grep 'llm_call.*status=ok' \
  | sed -E 's/.*stage=([a-z_]+).*duration_ms=([0-9]+).*input_tokens=([0-9]+) output_tokens=([0-9]+).*/\1 \2 \3 \4/' \
  | awk '{n[$1]++; d[$1]+=$2; i[$1]+=$3; o[$1]+=$4} END {for (s in n) printf "%-12s calls=%d avg_ms=%d avg_in=%d avg_out=%d\n", s, n[s], d[s]/n[s], i[s]/n[s], o[s]/n[s]}'
```

## 1. 스텁 띄우기 (AI EC2)

이미지에 아직 없는 파일이므로 마운트해서 쓴다. 다음 배포 이후엔 `-v` 없이도 된다.

```bash
scp Mormi-AI/scripts/llm_stub.py ec2-user@<AI EC2>:/opt/mormi/llm_stub.py

# AI EC2
IMAGE=$(docker inspect --format '{{.Config.Image}}' mormi-ai)
docker rm -f mormi-llm-stub 2>/dev/null || true
docker run -d --name mormi-llm-stub --network mormi-services \
  -v /opt/mormi/llm_stub.py:/app/scripts/llm_stub.py:ro \
  "$IMAGE" python scripts/llm_stub.py --port 9000 \
    --classifier-ms 3000 --speaker-ms 1500 --bridge-ms 800   # ← 0단계 평균으로 교체

# mormi-ai 컨테이너에서 이름으로 닿는지
docker exec mormi-ai python -c "import urllib.request;print(urllib.request.urlopen('http://mormi-llm-stub:9000/health').read().decode())"
```

## 2. AI 컨테이너를 스텁으로 향하게

`--env-file` 은 `docker run` 시점에만 읽히므로 컨테이너를 **다시 만들어야** 한다.

```bash
echo 'ANTHROPIC_BASE_URL=http://mormi-llm-stub:9000' | sudo tee -a /etc/mormi-ai/mormi.env
```

그다음 둘 중 하나:
- GitHub Actions → Mormi-AI `CI/CD` 워크플로 **Run workflow** (develop). 배포가 env 파일로 컨테이너를 다시 만든다.
- 또는 AI EC2에서 `deploy.yml` 의 `docker run -d --name mormi-ai ...` 블록을 그대로 실행
  (`"${IMAGE_URI}"` 자리에 위 `$IMAGE`).

확인:

```bash
docker exec mormi-ai env | grep ANTHROPIC_BASE_URL
curl -s http://localhost:9000/stats   # 아직 0
```

`ANTHROPIC_API_KEY` 는 그대로 둔다(비어 있으면 앱이 클라이언트를 안 만든다). 스텁은 키를 보지 않는다.

## 3. 실행 (노트북)

```bash
cd Mormi-BE
BE_URL=http://<BE 공인IP>:8080 VUS=20 DURATION=10m RUN_TAG=$(date +%m%d%H%M) \
  k6 run --summary-export=scripts/loadtest/summary-$(date +%m%d%H%M).json \
  scripts/loadtest/dialogue-turns.js
```

- `RAMP_S=0`(기본)이면 20명이 **동시에** 온보딩·가르치기를 시작한다 — 교실에서 다 같이 "시작"을 누르는 상황.
- `RAMP_S=60` 으로 하면 1분에 걸쳐 늘어난다 — 정상 상태 처리량을 볼 때.
- 한계를 찾을 땐 `VUS=20 → 30 → 50` 으로 올린다. 스텁이라 비용은 같다.

## 4. 보는 것

| 어디서 | 무엇 | 뜻 |
|---|---|---|
| k6 요약 | `mormi_turn_ms` p95, `mormi_turn_failed`, `mormi_5xx` | 아이가 체감하는 지연·실패 |
| 스텁 `GET /stats` | `max_in_flight.classifier` | **10에서 멈추면 BE 풀이 상한** (AI·스텁은 더 받을 수 있는데 BE가 못 보냄) |
| BE 로그 | `Connection is not available, request timed out` | Hikari 대기 30초 초과 → 500 |
| BE 로그 | `dialogue_upstream_error` | AI 응답이 45초 read-timeout 을 넘음 |

계산: 처리 가능 턴/초 ≈ 10 ÷ (classifier + speaker 지연 초). 요구 턴/초 ≈ VUS ÷ TURN_INTERVAL_S.

## 5. 되돌리기·정리

```bash
# AI EC2
sudo sed -i '/^ANTHROPIC_BASE_URL=/d' /etc/mormi-ai/mormi.env
docker rm -f mormi-llm-stub
# 컨테이너 재생성 (2단계와 같은 방법) 후
docker exec mormi-ai env | grep -c ANTHROPIC_BASE_URL   # 0 이어야 함
```

테스트 학습자는 `research_code` 가 `lt-<RUN_TAG>-<VU>`, `login_id` 가 `lt<RUN_TAG>v<VU>` 로 남는다. 파일럿 데이터와 섞이지 않게
`scripts/reset-*.sql` 을 참고해서 지운다(연관 테이블 FK 순서 확인 후).
