# JPL Horizons 에서 지구 중심 겉보기 황경(날짜 기준 황도, QUANTITIES=31 의 ObsEcLon)을 받아 data/ 에 둔다.
# 요청에 들어가는 것은 테스트용 가짜 출생 시각(UTC)뿐이다. 받은 원본(data/)은 커밋하지 않는다(약 60MB).
# 실행: python3 fetch.py && python3 reference.py > ../timeline-horizons-reference.tsv (머리말 주석은 손으로 붙였다)
import datetime as dt
import os
import subprocess
import sys
import time
import urllib.parse

API = "https://ssd.jpl.nasa.gov/api/horizons.api"
BODIES = {"SUN": "10", "MOON": "301", "VENUS": "299", "JUPITER": "599", "SATURN": "699", "URANUS": "799", "NEPTUNE": "899", "PLUTO": "999"}
CHARTS = {"A": "1990-03-15 05:30", "B": "1985-11-01 21:15", "C": "2000-06-15 03:00"}


def get(cmd, start, stop, step):
    q = {"format": "text", "COMMAND": f"'{cmd}'", "OBJ_DATA": "'NO'", "MAKE_EPHEM": "'YES'", "EPHEM_TYPE": "'OBSERVER'",
         "CENTER": "'500@399'", "START_TIME": f"'{start}'", "STOP_TIME": f"'{stop}'", "STEP_SIZE": f"'{step}'", "QUANTITIES": "'31'"}
    url = API + "?" + urllib.parse.urlencode(q)
    for _ in range(4):
        # curl 을 쓴다: 시스템 인증서 저장소로 TLS 를 검증한다.
        text = subprocess.run(["curl", "-sS", "-m", "240", url], capture_output=True, text=True).stdout
        if "$$EOE" in text:
            return text
        print("retry", cmd, file=sys.stderr)
        time.sleep(5)
    raise SystemExit("failed " + cmd)


def fetch(path, cmd, start, stop, step):
    if os.path.exists(path) and "$$EOE" in open(path).read():
        return
    text = get(cmd, start, stop, step)
    open(path, "w").write(text)
    time.sleep(0.5)


os.makedirs("data", exist_ok=True)
for name, birth in CHARTS.items():
    b = dt.datetime.strptime(birth, "%Y-%m-%d %H:%M")
    for body, cmd in BODIES.items():
        fetch(f"data/{name}_natal_{body}.txt", cmd, birth, (b + dt.timedelta(minutes=1)).strftime("%Y-%m-%d %H:%M"), "1m")
    first = (b.date() - dt.timedelta(days=1)).isoformat()
    # 만 80세 전에 시작한 구간이 끝날 때까지 보려고 85년 치를 받는다.
    last = (dt.date(b.year + 85, b.month, b.day) + dt.timedelta(days=3)).isoformat()
    for body in ["JUPITER", "SATURN", "URANUS", "NEPTUNE", "PLUTO"]:
        fetch(f"data/{name}_daily85_{body}.txt", BODIES[body], first, last, "1d")
