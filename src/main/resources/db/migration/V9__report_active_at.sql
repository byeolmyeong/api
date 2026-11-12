-- 리포트가 "멈췄는지" 를 자리를 잡은 때(started_at)가 아니라 살아 있다는 표시(active_at)로 가린다.
-- 생성을 맡은 서버가 살아 있는 동안은 줄에서 기다리는 것까지 이 값을 주기적으로 다시 적는다(byeolmyeong.report.heartbeat-seconds).
-- WRITING 인 채 이 값이 2분 넘게 끊기면 멈춘 것으로 본다(서버가 쓰는 도중 꺼진 경우). started_at 은 그 생성의 표식으로만 남는다.
-- 이미 WRITING 인 행은 지금을 받는다: 맡은 서버가 없으면 2분 뒤 멈춘 것으로 보인다.
alter table reports add column active_at timestamptz not null default now();
alter table synastry_reports add column active_at timestamptz not null default now();
