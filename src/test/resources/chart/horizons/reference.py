# fetch.py 가 받아 둔 JPL Horizons 황경으로 마디·구간의 기준값을 만든다(서버 코드와 따로 짠 계산).
# 정의(앱 저장소 release-1.0.0 F4): 허용 오차 1도 안에 있는 기간, 역행으로 여러 번 지나가면 처음 들어온 때~마지막으로 나간 때,
# 태어난 뒤부터 만 80세 전까지 시작하는 구간(결정 008). 하루 간격 값 사이는 직선으로 잇는다.
import datetime as dt, math, sys
UTC = dt.timezone.utc
MONTHS = {m: i + 1 for i, m in enumerate("Jan Feb Mar Apr May Jun Jul Aug Sep Oct Nov Dec".split())}
# 상승점·중천·버텍스·하우스 경계는 Horizons 에 없다. 이미 쓰는 Swiss Ephemeris 기준값(ChartReference.kt)을 쓴다.
CHARTS = {
    "A": dict(birth="1990-03-15T05:30", asc=125.053774, mc=23.813022, vertex=259.434152,
              cusps=[125.053774, 146.462870, 172.200566, 203.813022, 239.682733, 274.571309,
                     305.053774, 326.462870, 352.200566, 23.813022, 59.682733, 94.571309]),
    "B": dict(birth="1985-11-01T21:15", asc=212.290802, mc=126.535344, vertex=76.626717,
              cusps=[212.290802, 240.958817, 272.752094, 306.535344, 339.260260, 8.074942,
                     32.290802, 60.958817, 92.752094, 126.535344, 159.260260, 188.074942]),
    "C": dict(birth="2000-06-15T03:00"),  # 시각 모름: 그날 한국 정오
}
def rows(path):
    out, on = [], False
    for line in open(path):
        if line.startswith("$$SOE"): on = True; continue
        if line.startswith("$$EOE"): break
        if on:
            p = line.split()
            y, m, d = p[0].split("-"); hh, mm = p[1].split(":")
            out.append((dt.datetime(int(y), MONTHS[m], int(d), int(hh), int(mm), tzinfo=UTC), float(p[2])))
    return out
def sdiff(a, b):
    d = (a - b) % 360.0
    return d - 360.0 if d > 180.0 else d
def unwrap(series):
    out = [series[0][1]]
    for i in range(1, len(series)):
        out.append(out[-1] + sdiff(series[i][1], series[i - 1][1]))
    return out
def at(t0, t1, frac):
    return t0 + (t1 - t0) * frac
def passes(times, U, lo, width):
    """[lo, lo+width] (360 마다 되풀이) 안에 있는 기간. 같은 바퀴(k)의 여러 번은 하나로 묶는다."""
    found = {}
    for i in range(len(U) - 1):
        for k in {math.floor((U[i] - lo) / 360.0), math.floor((U[i + 1] - lo) / 360.0)}:
            f0, f1 = U[i] - lo - 360.0 * k, U[i + 1] - lo - 360.0 * k
            a, b = 0.0, 1.0  # 이 하루 가운데 안에 있는 부분 [a, b]
            if f1 != f0:
                e0, e1 = (0.0 - f0) / (f1 - f0), (width - f0) / (f1 - f0)
                a, b = max(a, min(e0, e1)), min(b, max(e0, e1))
            elif not (0.0 <= f0 <= width):
                continue
            if a > b: continue
            s, e = at(times[i], times[i + 1], a), at(times[i], times[i + 1], b)
            cur = found.get(k)
            found[k] = (s, e) if cur is None else (min(cur[0], s), max(cur[1], e))
    return [found[k] for k in sorted(found)]
def crossings(times, U, level):
    out = []
    for i in range(len(U) - 1):
        if (U[i] - level) * (U[i + 1] - level) < 0 or U[i + 1] == level:
            out.append(at(times[i], times[i + 1], (level - U[i]) / (U[i + 1] - U[i])))
    return out
def fmt(t): return t.strftime("%Y-%m-%dT%H:%MZ")
lines = []
for name, c in CHARTS.items():
    birth = dt.datetime.strptime(c["birth"], "%Y-%m-%dT%H:%M").replace(tzinfo=UTC)
    end = birth.replace(year=birth.year + 80)
    natal = {b: rows(f"data/{name}_natal_{b}.txt")[0][1] for b in ["SUN", "MOON", "VENUS", "SATURN", "URANUS"]}
    daily = {}
    for b in ["JUPITER", "SATURN", "URANUS", "NEPTUNE", "PLUTO"]:
        r = rows(f"data/{name}_daily85_{b}.txt")
        daily[b] = ([t for t, _ in r], unwrap(r))
    def emit(kind, mover, what, target, lo, width):
        times, U = daily[mover]
        for s, e in passes(times, U, lo, width):
            # 태어난 뒤에 시작해 만 80세 전에 시작하는 구간만. 끝은 자르지 않는다. 태어날 때 이미 안에 있던 구간은 뺀다.
            if birth < s < end and s < e: lines.append("\t".join([name, kind, mover, what, target, fmt(s), fmt(e)]))
    # 마디
    for mover, kind, offset in [("SATURN", "SATURN_SQUARE", 270.0), ("SATURN", "SATURN_RETURN", 360.0),
                                ("URANUS", "URANUS_OPPOSITION", 180.0), ("SATURN", "SECOND_SATURN_RETURN", 720.0)]:
        times, U = daily[mover]
        i = max(j for j in range(len(times)) if times[j] <= birth)
        ub = U[i] + (U[i + 1] - U[i]) * ((birth - times[i]) / (times[i + 1] - times[i]))
        level = ub + sdiff(natal[mover], ub % 360.0) + offset
        exact = crossings(times, U, level)
        band = [p for p in passes(times, U, level - 1.0, 2.0) if p[0] <= exact[0] <= p[1]][0]
        lines.append("\t".join([name, "MILESTONE", mover, kind, ",".join(fmt(t) for t in exact), fmt(band[0]), fmt(band[1])]))
    known = "asc" in c
    targets = {"SUN": natal["SUN"], "MOON": natal["MOON"]}
    if known: targets["ASCENDANT"] = c["asc"]
    for mover in ["SATURN", "URANUS", "NEPTUNE", "PLUTO"]:
        for target, lon in targets.items():
            for offset, what in [(0, "CONJUNCTION"), (90, "SQUARE"), (180, "OPPOSITION"), (270, "SQUARE")]:
                emit("CHANGE", mover, what, target, lon + offset - 1.0, 2.0)
    targets["VENUS"] = natal["VENUS"]
    if known: targets["MIDHEAVEN"] = c["mc"]
    for target, lon in targets.items():
        for offset, what in [(0, "CONJUNCTION"), (120, "TRINE"), (240, "TRINE")]:
            emit("OPPORTUNITY", "JUPITER", what, target, lon + offset - 1.0, 2.0)
    if known:
        for house in (5, 7, 11):
            lo = c["cusps"][house - 1]
            emit("OPPORTUNITY", "JUPITER", "HOUSE", f"HOUSE_{house}", lo, (c["cusps"][house % 12] - lo) % 360.0)
        for mover in ["JUPITER", "SATURN", "URANUS", "NEPTUNE", "PLUTO"]:
            emit("FATED", mover, "CONJUNCTION", "VERTEX", c["vertex"] - 1.0, 2.0)
    print(name, {k: round(v, 4) for k, v in natal.items()}, file=sys.stderr)
print("\n".join(lines))
