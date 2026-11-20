-- 웹 클라이언트가 받은 구글 ID 토큰의 jti(결정 047). 만료까지 한 번만 받는다. 더하기만 한다.
-- 서버 여러 대가 같은 표로 선점한다(결정 046). 끝난 줄은 날마다 지운다. 토큰 원문 · 계정은 두지 않는다.
create table used_id_tokens (
	jti text primary key,
	expires_at timestamptz not null
);

create index used_id_tokens_expires_at on used_id_tokens (expires_at);
