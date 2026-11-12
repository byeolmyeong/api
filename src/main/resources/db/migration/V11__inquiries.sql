-- 문의(설정의 "문의하기"). 관리 화면·메일 발송은 아직 없어 소유자가 이 테이블을 직접 읽는다. 결정 013.
-- 계정을 지우면 함께 지워진다. 기기 정보·주소(IP)는 두지 않는다.
create table inquiries (
	id uuid primary key,
	owner_id uuid not null references accounts (id) on delete cascade,
	-- 종류: ACCOUNT · READING · SYNASTRY · BUG · OTHER (서버 코드가 검증한다)
	category text not null,
	-- 문의 글. 앞뒤 공백을 뗀 10~2000자
	body text not null,
	-- 답장 받을 이메일. 남기지 않았으면 null
	reply_email text,
	created_at timestamptz not null default now()
);

create index inquiries_owner_id on inquiries (owner_id);
