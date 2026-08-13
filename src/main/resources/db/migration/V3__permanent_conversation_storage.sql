-- 파일럿 참여자는 서비스 이용 전에 보호자·기관의 데이터 수집 동의를 완료한다.
-- 신규·기존 학습자의 질문, 아이 원문 발화, 선택 응답을 AI DB에서 암호화해
-- 영구 저장할 수 있도록 Spring이 항상 같은 정책을 전달한다.
ALTER TABLE learners
    ALTER COLUMN conversation_storage_consent SET DEFAULT TRUE,
    ALTER COLUMN retention_policy SET DEFAULT 'permanent';

UPDATE learners
SET conversation_storage_consent = TRUE,
    retention_policy = 'permanent'
WHERE conversation_storage_consent IS DISTINCT FROM TRUE
   OR retention_policy IS DISTINCT FROM 'permanent';
