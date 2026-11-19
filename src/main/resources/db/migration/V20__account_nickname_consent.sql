-- 결정 025: 가입 때 받는 닉네임(필수, 옛 계정은 null)과 마케팅 수신 동의 일시(선택, null = 동의 안 함).
-- 결정 021 의 "이름은 받지 않는다"(V2 주석)를 대체한다. 닉네임은 로그·감사 기록에 넣지 않는다. 계정을 지우면 함께 지워진다.
alter table accounts add column nickname text;
alter table accounts add column marketing_consented_at timestamptz;
-- 닉네임은 겹치면 안 된다(소유자 2026-11-19). 영문 대소문자는 같은 이름으로 본다. 옛 계정의 null 은 겹쳐도 된다.
create unique index accounts_nickname_lower_key on accounts (lower(nickname));
