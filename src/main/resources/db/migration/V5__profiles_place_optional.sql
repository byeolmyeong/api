-- 태어난 시각을 모르면 태어난 곳을 받지도 저장하지도 않는다(결정 007, 앱 결정 020 의 2번).
-- 곳은 상승점·하우스 계산에만 쓰이고, 시각을 모르면 그것들을 계산하지 않는다.

alter table profiles alter column place_id drop not null;

-- 이미 저장된 시각 모름 행의 곳을 비운다. 시각 모름의 차트 해시에는 위경도가 들어가지 않으므로 저장된 글은 그대로 찾아진다.
update profiles set place_id = null where birth_time is null;

-- 시각이 있으면 곳도 있고, 시각이 없으면 곳도 없다.
alter table profiles add constraint profiles_place_only_with_time check ((birth_time is null) = (place_id is null));
