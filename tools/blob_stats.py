# 星点 blob 尺寸统计：判别照片是广角还是变焦
# 同一部手机同晚拍的话：变焦 N 倍 → 星点 FWHM 约 N 倍、可检出星数骤减
import glob, os, struct
import numpy as np
from scipy import ndimage

def load_gray(p):
    b = open(p, 'rb').read()
    w, h = struct.unpack('<ii', b[:8])
    a = np.frombuffer(b[8:], dtype='<f4').reshape(h, w).astype(np.float64)
    return a

def box_blur(a, r):
    n = 2 * r + 1
    ii = np.cumsum(np.cumsum(a, axis=0), axis=1)
    ii = np.pad(ii, ((1, 0), (1, 0)))
    H, W = a.shape
    y0 = np.clip(np.arange(H)[:, None] - r, 0, H)
    y1 = np.clip(np.arange(H)[:, None] + r + 1, 0, H)
    x0 = np.clip(np.arange(W)[None, :] - r, 0, W)
    x1 = np.clip(np.arange(W)[None, :] + r + 1, 0, W)
    s = ii[y1, x1] - ii[y0, x1] - ii[y1, x0] + ii[y0, x0]
    return s / (n * n)

for p in sorted(glob.glob(r'C:\starcam-bundle\testdata\gray12\*.gray')):
    a = load_gray(p)
    H, W = a.shape
    r = max(3, min(W, H) // 80)
    sub = a - box_blur(a, r)
    med = np.median(sub)
    mad = np.median(np.abs(sub - med))
    sigma = 1.4826 * mad
    thr = max(8.0, 3.5 * sigma)
    mask = sub > thr
    lab, n = ndimage.label(mask)
    if n:
        sizes = ndimage.sum(mask, lab, range(1, n + 1))
        peaks = ndimage.maximum(sub, lab, range(1, n + 1))
        good = (sizes >= 3) & (peaks > 2 * thr)
        ns = int(good.sum())
        medsz = float(np.median(sizes[good])) if ns else 0
        medpk = float(np.median(peaks[good])) if ns else 0
    else:
        ns, medsz, medpk = 0, 0, 0
    print('%s %dx%d sigma=%.1f thr=%.0f blobs:%d medSize=%.1f medPeak=%.0f' % (
        os.path.basename(p)[-8:-5], W, H, sigma, thr, ns, medsz, medpk))
