-- 결정 026: 가입 때 동의한 이용약관 · 개인정보 처리방침의 버전과 그 시각(서버 시각), 푸시 수신 동의 일시(null = 동의 안 함), 프로필 그림 번호.
-- 더하기만 하고 모두 null 허용이다. 이 결정 전에 가입한 계정은 어느 판에 동의했는지 모르므로 채우지 않는다(backfill 없음).
-- 이메일 · 닉네임처럼 계정을 지우면 함께 지워진다(감사 기록은 남는다, 결정 015).
alter table accounts add column terms_version text;
alter table accounts add column terms_agreed_at timestamptz;
alter table accounts add column privacy_version text;
alter table accounts add column privacy_agreed_at timestamptz;
alter table accounts add column push_consented_at timestamptz;
-- 앱의 브랜드 그림 avatar-1~8 중 하나. null = 옛 계정.
alter table accounts add column avatar smallint check (avatar between 1 and 8);
