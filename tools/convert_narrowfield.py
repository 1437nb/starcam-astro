# FITS → .gray（与 Photo12RegressionTest / gray12_convert.py 同格式），
# 并把 FITS WCS 的真值写成 truth.json。
# 用法: python convert_narrowfield.py <目录>
import json, os, sys
import numpy as np

# 默认目录由本脚本位置推导（tools/ 的上一级即仓库根）。不要写死
# "/c/starword/..." —— Windows 原生 Python 会把它解析成 C:\c\starword\...，
# 素材会静默落到另一棵目录树上。
_HERE = os.path.dirname(os.path.abspath(__file__))
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(_HERE, os.pardir, "testdata", "narrowfield")

NOTES = {
    "m51-1d": "M51 涡状星系 1°", "m51-5d": "M51 5°",
    "m42-1d": "M42 猎户大星云 1°（延展天体+密星）",
    "m31-1d": "M31 仙女座大星系 1°",
    "m13-05d": "M13 球状星团 0.5°（极端密）",
    "m45-1d": "M45 昴星团 1°（密星团）",
    "hdf-05d": "哈勃深场 0.5°（极端稀疏）",
    "polaris": "北极星附近 2°（高纬）",
    "seam0": "赤经 0° 天顶 3°（RA 接缝）",
    "seam350": "RA 350° +60° 10°（接缝+中场）",
    "cygnus": "天鹅座银河 5°（密）",
    "sgr": "人马座 5°（银心方向密）",
    "lmc": "大麦哲伦云 5°（南天+密）",
    "m8182-1d": "M81/M82 1°",
    "vega": "织女一 2°（亮星+衍射星芒）",
    "void": "高银纬空场 2°（极稀疏）",
    "equator10": "赤道 10°（中场）",
    "mid30": "30° 视场（中场）",
    # §0.76 中场批（10°~20°）
    "cas15": "仙后座 W 15°（北天图案+双星团）",
    "m31m33-14": "M31+M33 双星系 14°",
    "leo15": "狮子座镰刀 15°",
    "orion15": "猎户座宽场 15°（参宿四→参宿三）",
    "sco18": "天蝎座 18°",
    "sgr15": "人马座茶壶 15°（银心方向）",
    "crux10": "南十字座 10°（南天）",
    "summer20": "夏季大三角 20°（织女-牛郎-天津四）",
    "cyg15": "天鹅座银河 15°（密场）",
    "void15": "高银纬空场 15°（极稀疏）",
    "seam0-15": "RA 0° 接缝 15°",
    "npole12": "北天极 12°（高赤纬 cos 压缩）",
    "spole12": "南天极 12°（高赤纬 cos 压缩）",
}

# 没有本地 FITS 的真值条目：这些图是早前单独拉的，FITS 已删除省空间，
# 但 WCS 真值必须留着，否则回归会静默漏掉这一场。
EXTRA_TRUTH = {
    "apod3xcheck": {
        "ra": 130.274, "dec": -43.878, "fov": 8.5, "px": 30.6,
        "w": 1000, "h": 1000, "bright": 10252,
        "note": "apod3 解算天区的 DSS 交叉验证",
    },
}

def read_header(path):
    hdr, nblk = {}, 0
    with open(path, "rb") as f:
        while True:
            b = f.read(2880)
            if not b:
                break
            nblk += 1
            cards = [b[i:i+80].decode("ascii", "replace") for i in range(0, len(b), 80)]
            end = False
            for c in cards:
                k = c[:8].strip()
                if k == "END":
                    end = True
                    break
                if c[8:10] == "= ":
                    v = c[10:]
                    if "/" in v:
                        v = v.split("/")[0]
                    hdr[k] = v.strip().strip("'").strip()
            if end:
                break
    return hdr, nblk * 2880

truth = {}
for name in sorted(os.listdir(OUT)):
    if not name.endswith(".fits"):
        continue
    fid = name[:-5]
    fits = os.path.join(OUT, name)
    h, off = read_header(fits)
    w, hh = int(h["NAXIS1"]), int(h["NAXIS2"])
    pxdeg = abs(float(h.get("CDELT1") or h.get("CDELT2") or 0))
    with open(fits, "rb") as f:
        f.seek(off)
        raw = f.read(w * hh * 4)
    a = np.frombuffer(raw, dtype=">f4").astype(np.float32).reshape(hh, w)
    # FITS 行序自下而上，图片/Android Bitmap 自上而下 —— 翻转后才与
    # gray12_convert.py（JPEG 源）产出的 .gray 方向一致。
    a = a[::-1, :]
    a = np.nan_to_num(a, nan=0.0, posinf=0.0, neginf=0.0)
    lo, hi = np.percentile(a, 1.0), np.percentile(a, 99.5)
    if hi > lo:
        a = np.clip((a - lo) * (255.0 / (hi - lo)), 0, 255)
    gray = os.path.join(OUT, fid + ".gray")
    with open(gray, "wb") as f:
        f.write(np.int32(w).tobytes())
        f.write(np.int32(hh).tobytes())
        f.write(a.astype("<f4").tobytes())
    # 亮点计数（>200）粗估可用星数
    bright = int((a > 200).sum())
    truth[fid] = {
        "ra": float(h["CRVAL1"]), "dec": float(h["CRVAL2"]),
        "fov": round(pxdeg * max(w, hh), 3), "px": round(pxdeg * 3600.0, 3),
        "w": w, "h": hh, "bright": bright, "note": NOTES.get(fid, fid),
    }
    print(f"OK {fid:12s} {w}x{hh} fov={truth[fid]['fov']:6.2f}° px={truth[fid]['px']:6.2f}\" "
          f"bright={bright:5d} | {truth[fid]['note']}")

truth.update(EXTRA_TRUTH)
with open(os.path.join(OUT, "truth.json"), "w", encoding="utf-8") as f:
    json.dump(truth, f, ensure_ascii=False, indent=1)
print(f"\ntruth: {len(truth)} fields")
