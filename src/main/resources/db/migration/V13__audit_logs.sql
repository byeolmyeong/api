-- 감사 기록(결정 015). 관리자와 사용자의 주요 행동을 "누가 무엇을 언제" 만 남긴다. 넣기만 한다(고치거나 지우는 코드가 없다).
-- accounts·admin_accounts 를 가리키는 외래 키가 없다: 계정을 지워도 기록은 남는다. 그래서 개인적인 것은 어느 칸에도 넣지 않는다
-- (출생 정보·이름·이메일·메모·문의·FAQ 의 글·비밀번호·토큰·주소 없음).

create table audit_logs (
	id uuid primary key,
	occurred_at timestamptz not null default now(),
	actor_type text not null check (actor_type in ('ADMIN', 'USER', 'SYSTEM')),
	-- 관리자 계정 id 또는 사용자 계정 id 의 글자. SYSTEM 은 null. 응답에는 내보내지 않는다.
	actor_id text,
	-- 관리자는 그때의 로그인 아이디, 사용자는 ownerRef(계정 id 해시의 앞 12자). SYSTEM 은 null
	actor_label text,
	-- 행동 코드(web.AuditAction). 행동이 늘 때 마이그레이션이 필요 없게 목록으로 묶지 않는다.
	action text not null,
	-- 대상의 종류와 id. 없으면 null
	target_type text,
	target_id text,
	outcome text not null check (outcome in ('SUCCESS', 'FAILURE')),
	-- 코드 값만 담는 작은 JSON(예 {"status":"DONE"}). 없으면 null
	detail jsonb
);

-- 목록은 새것부터 본다
create index audit_logs_occurred_at on audit_logs (occurred_at desc, id desc);
create index audit_logs_actor_label on audit_logs (actor_label);
