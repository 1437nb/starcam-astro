# FITS → .gray（与 Photo12RegressionTest / gray12_convert.py 同格式），
# 并把 FITS WCS 的真值写成 truth.json。
# 用法: python convert_narrowfield.py <目录>
import json, os, sys
import numpy as np

OUT = sys.argv[1] if len(sys.argv) > 1 else "/c/starword/testdata/narrowfield"

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

with open(os.path.join(OUT, "truth.json"), "w", encoding="utf-8") as f:
    json.dump(truth, f, ensure_ascii=False, indent=1)
print(f"\ntruth: {len(truth)} fields")
