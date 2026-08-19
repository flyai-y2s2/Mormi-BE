-- 새 숫자·새 표현·생활 맥락 적용 시도를 attempt 단위로 저장한다.
-- transfer_solved 불리언 하나로는 적용 시도 횟수·지원 수준·맥락을 알 수 없다.
-- 시도의 출처를 attempts 한 곳으로 유지하기 위해 새 테이블 대신 컬럼을 넓힌다.

ALTER TABLE attempts
    -- same_form_new_number | new_representation | real_life_context
    ADD COLUMN application_scope VARCHAR(30),
    -- FE 사다리 0(도움 없음)~3(최대 지원). AI 관찰의 L/H 와 척도가 다르므로 변환하지 않는다.
    ADD COLUMN support_level     INTEGER;

-- 오타 값이 들어오면 리포트 분류가 조용히 갈린다. NULL(미수집)은 허용한다.
ALTER TABLE attempts
    ADD CONSTRAINT ck_attempts_application_scope CHECK (
        application_scope IS NULL OR application_scope IN
            ('same_form_new_number', 'new_representation', 'real_life_context')),
    ADD CONSTRAINT ck_attempts_support_level CHECK (
        support_level IS NULL OR support_level BETWEEN 0 AND 3);
