-- 공유 v2(결정 036): 새 카드 넷(내 별명 · 나 탭 영역 · 그날의 하늘 · 다음 큰 변화)을 받게 kind 제약을 넓힌다.
-- 제약을 지우고 넓힌 제약을 다시 건다 — 행은 바뀌지 않는다(옛 네 종류는 그대로 열린다). 번호는 다른 묶음과 합칠 때 다음 빈 번호로 옮긴다.
alter table shares drop constraint shares_kind_check;
alter table shares add constraint shares_kind_check
	check (kind in ('READING', 'SCORE', 'RANKING', 'NEXT_TURN', 'NICKNAME', 'ME_AREA', 'DAY_SKY', 'NEXT_BIG'));
