-- 공유 카드(결정 012). 공유하는 순간의 공개 글만 따로 저장하고, 공개 조회는 이 표만 읽는다.
-- 근거·본문·출생 정보는 여기에 없다. 계정을 지우면 함께 지워진다.
create table shares (
	-- 공유 주소에 쓰는 무작위 128비트(URL 용 Base64 22자)
	share_id text primary key,
	owner_id uuid not null references accounts (id) on delete cascade,
	kind text not null check (kind in ('READING', 'SCORE', 'RANKING', 'NEXT_TURN')),
	-- 공개 조회가 그대로 돌려주는 JSON. 모양은 docs/architecture.md "공유"
	card jsonb not null,
	-- card 의 SHA-256(16진수). 같은 소유자가 같은 내용을 다시 공유하면 있던 행을 쓴다.
	card_hash text not null,
	created_at timestamptz not null default now(),
	unique (owner_id, card_hash)
);
