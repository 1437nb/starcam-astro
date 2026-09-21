# 归因诊断：统计每个回归场「画面内」的星表星数（浅域 mag<=4.0 / 深域 mag<=6.5）。
# 目的：区分「素材本身可解」与「星表在这个天区根本没有足够的星」。
# 画面按 TAN 投影的方形视场取星：中心角距 < fov/2*sqrt(2) 即落在四角内。
import json, math, os, re, sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NF = os.path.join(REPO, "testdata", "narrowfield")
PARTS = sorted(
    os.path.join(REPO, "code", "app", "src", "main", "java", "com", "starcam", "astro", "astro", f)
    for f in os.listdir(os.path.join(REPO, "code", "app", "src", "main", "java", "com", "starcam", "astro", "astro"))
    if re.match(r"StarCatalogPart\d+\.kt$", f)
)

ENTRY = re.compile(r"StarEntry\(\s*(-?\d+),\s*([-\d.]+),\s*([-\d.]+),\s*([-\d.]+),")
stars = []
for p in PARTS:
    src = open(p, encoding="utf-8").read()
    for m in ENTRY.finditer(src):
        stars.append((int(m.group(1)), float(m.group(2)), float(m.group(3)), float(m.group(4))))
print(f"catalog: {len(stars)} stars from {len(PARTS)} parts")

def angdist(ra1, dec1, ra2, dec2):
    r1, d1, r2, d2 = map(math.radians, (ra1, dec1, ra2, dec2))
    c = math.sin(d1) * math.sin(d2) + math.cos(d1) * math.cos(d2) * math.cos(r1 - r2)
    return math.degrees(math.acos(max(-1.0, min(1.0, c))))

truth = json.load(open(os.path.join(NF, "truth.json"), encoding="utf-8"))
rows = []
for fid, t in truth.items():
    ra, dec, fov = t["ra"], t["dec"], t["fov"]
    rmax = fov / 2.0 * math.sqrt(2.0)  # 方形视场外接圆
    n40 = n65 = 0
    for _, sra, sdec, mag in stars:
        if mag > 6.5:
            continue
        if angdist(ra, dec, sra, sdec) <= rmax:
            if mag <= 4.0:
                n40 += 1
            n65 += 1
    rows.append((fov, fid, n40, n65))

rows.sort()
print(f"\n{'fov':>5} {'id':<12} {'mag<=4.0':>9} {'mag<=6.5':>9}")
for fov, fid, n40, n65 in rows:
    print(f"{fov:5.1f} {fid:<12} {n40:9d} {n65:9d}")
