-- 가입권(결정 021): 처음 온 사람이 로그인하면 계정 대신 30분짜리 1회용 가입권을 받는다.
-- 출생 정보와 함께 가입할 때 만 14세 이상이면 계정을 만들고 지운다. 만 14세 미만이면 바로 지운다.
create table signup_tickets (
	-- 가입권의 SHA-256(16진수). 원문은 저장하지 않는다.
	token_hash text primary key,
	provider text not null,
	provider_user_id text not null,
	expires_at timestamptz not null
);
