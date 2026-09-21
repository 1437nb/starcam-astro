# 并行从 SkyView 拉 10°~20° 中场图（§0.76）。下载与转换分离：
#   1) 本脚本只并行下载 FITS
#   2) convert_narrowfield.py 再统一转 .gray + 写 truth.json
#
# 为什么单独一批：v1.5.63 把深域兜底的支持下界做到 10°，但回归里
# 10°~20° 区间原先只有 equator10 / seam350 两张，样本太薄，无法判断
# 这个区间到底是"稳"还是"碰巧过"。这里按天区分布、星密度、赤纬、
# RA 接缝四个维度铺开。
#
# 用法: python fetch_midfield.py [输出目录] [并发数]
import os, sys, urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

# 默认目录由本脚本位置推导（tools/ 的上一级即仓库根）。不要写死
# "/c/starword/..." —— Windows 原生 Python 会把它解析成 C:\c\starword\...，
# 素材会静默落到另一棵目录树上。
_HERE = os.path.dirname(os.path.abspath(__file__))
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(_HERE, os.pardir, "testdata", "narrowfield")
JOBS = int(sys.argv[2]) if len(sys.argv) > 2 else 8
os.makedirs(OUT, exist_ok=True)

# (id, ra, dec, size_deg, pixels, 备注)
FIELDS = [
    # --- 北天可辨认图案 ---
    ("cas15",      35.000,  57.000, 15.0, 1200, "仙后座 W 15°（北天图案+双星团）"),
    ("m31m33-14",  17.000,  35.000, 14.0, 1200, "M31+M33 双星系 14°"),
    ("leo15",     155.000,  20.000, 15.0, 1200, "狮子座镰刀 15°"),
    # --- 赤道/南天 ---
    ("orion15",    83.000,  -3.000, 15.0, 1200, "猎户座宽场 15°（参宿四→参宿三）"),
    ("sco18",     253.000, -35.000, 18.0, 1200, "天蝎座 18°"),
    ("sgr15",     280.000, -27.000, 15.0, 1200, "人马座茶壶 15°（银心方向）"),
    ("crux10",    186.000, -60.000, 10.0, 1200, "南十字座 10°（南天）"),
    # --- 夏季大三角 ---
    ("summer20",  296.000,  31.000, 20.0, 1200, "夏季大三角 20°（织女-牛郎-天津四）"),
    ("cyg15",     305.000,  40.000, 15.0, 1200, "天鹅座银河 15°（密场）"),
    # --- 稀疏场压力测试 ---
    ("void15",    200.000,  62.000, 15.0, 1200, "高银纬空场 15°（极稀疏）"),
    # --- 坐标边缘 ---
    ("seam0-15",    0.500,  20.000, 15.0, 1200, "RA 0° 接缝 15°"),
    ("npole12",   150.000,  85.000, 12.0, 1200, "北天极 12°（高赤纬 cos 压缩）"),
    ("spole12",    20.000, -85.000, 12.0, 1200, "南天极 12°（高赤纬 cos 压缩）"),
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
