# 并行从 SkyView 拉窄场/中场 DSS 图。下载与转换分离：
#   1) 本脚本只并行下载 FITS（-u 保证进度可见）
#   2) convert_narrowfield.py 再统一转 .gray + 写 truth.json
# 用法: python fetch_narrowfield.py <输出目录> [并发数]
import os, sys, urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

OUT = sys.argv[1] if len(sys.argv) > 1 else "/c/starword/testdata/narrowfield"
JOBS = int(sys.argv[2]) if len(sys.argv) > 2 else 8
os.makedirs(OUT, exist_ok=True)

# (id, ra, dec, size_deg, pixels, 备注)
FIELDS = [
    ("m51-1d",   202.469,  47.195, 1.0, 1200, "M51 涡状星系 1°"),
    ("m51-5d",   202.469,  47.195, 5.0, 1200, "M51 5°"),
    ("m42-1d",    83.822,  -5.391, 1.0, 1200, "M42 猎户大星云 1°（延展天体+密星）"),
    ("m31-1d",    10.685,  41.269, 1.0, 1200, "M31 仙女座大星系 1°"),
    ("m13-05d",  250.422,  36.460, 0.5, 1000, "M13 球状星团 0.5°（极端密）"),
    ("m45-1d",    56.750,  24.117, 1.0, 1200, "M45 昴星团 1°（密星团）"),
    ("hdf-05d",  189.218,  62.215, 0.5, 1000, "哈勃深场 0.5°（极端稀疏）"),
    ("polaris",   37.954,  89.264, 2.0, 1200, "北极星附近 2°（高纬）"),
    ("seam0",      0.000,   0.000, 3.0, 1200, "赤经 0° 天顶 3°（RA 接缝）"),
    ("seam350",  350.000,  60.000,10.0, 1200, "RA 350° +60° 10°（接缝+中场）"),
    ("cygnus",  312.000,  40.000, 5.0, 1200, "天鹅座银河 5°（密）"),
    ("sgr",     275.000, -25.000, 5.0, 1200, "人马座 5°（银心方向密）"),
    ("lmc",      80.894, -69.756, 5.0, 1200, "大麦哲伦云 5°（南天+密）"),
    ("m8182-1d", 148.969,  69.080, 1.0, 1200, "M81/M82 1°"),
    ("vega",    279.234,  38.784, 2.0, 1200, "织女一 2°（亮星+衍射星芒）"),
    ("void",    186.500,  55.000, 2.0, 1200, "高银纬空场 2°（极稀疏）"),
    ("equator10", 30.000,  0.000,10.0, 1200, "赤道 10°（中场）"),
    ("mid30",    120.000, 30.000,30.0, 1200, "30° 视场（中场）"),
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
        with urllib.request.urlopen(url, timeout=300) as r:
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
    done = 0
    for fut in as_completed(futs):
        fid, status, n = fut.result()
        done += 1
        print(f"[{done}/{len(FIELDS)}] {fid:12s} {status:12s} {n/1024:.0f} KB", flush=True)

ok = sum(1 for it in FIELDS if os.path.exists(os.path.join(OUT, it[0] + ".fits")))
print(f"\n{ok}/{len(FIELDS)} FITS in {OUT}", flush=True)
