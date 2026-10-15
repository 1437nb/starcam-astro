# 从 SkyView 拉「视场闸门扫描」素材（§0.103 深域准入 45° 阈值）。
# 用途：把一条**生产决策**（>45° 宽场跳过深域重试）第一次用真实素材钉住 ——
# 现有真值台里没有一个样本恰好跨这条线。
#   用法: python tools/fetch_gate_sweep.py <输出目录> [并发数]
# 之后用 tools/convert_narrowfield.py <输出目录> 转 .gray + 写 truth.json。
import os, sys, urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

OUT = sys.argv[1] if len(sys.argv) > 1 else "testdata/gate-sweep"
JOBS = int(sys.argv[2]) if len(sys.argv) > 2 else 6
os.makedirs(OUT, exist_ok=True)

# (id, ra, dec, size_deg, pixels, 备注)
FIELDS = [
    ("gate35", 200.000,  30.000, 35.0, 1600, "35° 银道面外（闸门内，深域允许；SkyView 能承受的上限附近）"),
    ("gate40", 210.000,  25.000, 40.0, 1600, "40° 室女/后发方向（闸门内）"),
    ("ctrl10", 120.000,  30.000, 10.0, 1200, "10° 对照（能力边界，深域允许）"),
]

BASE = "https://skyview.gsfc.nasa.gov/cgi-bin/images"

def fetch(item):
    fid, ra, dec, size, px, note = item
    fits = os.path.join(OUT, fid + ".fits")
    if os.path.exists(fits) and os.path.getsize(fits) > 10000:
        return (fid, "cached", os.path.getsize(fits))
    url = (f"{BASE}?Survey=DSS&Position={ra:.5f}+{dec:.5f}"
           f"&Size={size}&Pixels={px}&Return=FITS")
    try:
        with urllib.request.urlopen(url, timeout=600) as r:
            data = r.read()
        if len(data) < 2880:
            return (fid, f"too small ({len(data)}B)", 0)
        with open(fits, "wb") as f:
            f.write(data)
        return (fid, "ok", len(data))
    except Exception as e:
        return (fid, f"FAIL {e}", 0)

with ThreadPoolExecutor(max_workers=JOBS) as ex:
    futs = {ex.submit(fetch, it): it[0] for it in FIELDS}
    for i, fut in enumerate(as_completed(futs), 1):
        fid, status, n = fut.result()
        print("[%d/%d] %-8s %-12s %.0f KB" % (i, len(FIELDS), fid, status, n/1024), flush=True)

ok = sum(1 for it in FIELDS if os.path.exists(os.path.join(OUT, it[0] + ".fits")))
print("\n%d/%d FITS in %s" % (ok, len(FIELDS), OUT))
