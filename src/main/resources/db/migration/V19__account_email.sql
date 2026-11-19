-- 가입할 때 공급자가 확인한 이메일(결정 024). 공급자가 준 값 그대로. 개발용 계정(DEV)과 이 결정 전에 만든 계정은 비어 있을 수 있다.
alter table accounts add column email text;
-- 처음 온 사람은 가입을 마칠 때까지 가입권에 둔다.
alter table signup_tickets add column email text;
