-- 관리자 임시 비밀번호 · 마지막 로그인(결정 043 의 5). 더하기만 한다(앞으로만 호환 — 옛 서버가 넣는 행은 기본값을 받는다).
alter table admin_accounts
	-- 서버가 만든 임시 비밀번호(추가 · 재발급)로 정해졌으면 true. 그 관리자가 자기 비밀번호로 바꾸면 false.
	-- 있던 관리자 · 환경 변수로 만든 첫 관리자는 false.
	add column must_change_password boolean not null default false,
	-- 마지막으로 로그인에 성공한 때. 한 번도 없으면 null.
	add column last_login_at timestamptz;
