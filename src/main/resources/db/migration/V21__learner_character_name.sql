-- 아이가 온보딩에서 직접 지어 준 캐릭터 이름. 아이 1명당 1개이고 이력이 필요 없어
-- 별도 테이블 대신 display_name 옆의 컬럼으로 둔다.
-- NULL 은 '아직 안 지음' 이고, FE 가 이름 짓기 화면을 띄우는 신호로 쓴다.
-- 서버는 기본값 '모르미' 를 채워 넣지 않는다. 그 폴백은 화면의 몫이다.
ALTER TABLE learners
    ADD COLUMN character_name VARCHAR(12);
