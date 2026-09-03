# 对比 Kotlin 现行 std-阈值 与 MAD-阈值的检出 blob 数
import glob, os, struct
import numpy as np
from scipy import ndimage

def load_gray(p):
    b = open(p, 'rb').read()
    w, h = struct.unpack('<ii', b[:8])
    return np.frombuffer(b[8:], dtype='<f4').reshape(h, w).astype(np.float64)

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
    std = sub.std()
    thrK = max(8.0, 3.5 * std)
    med = np.median(sub)
    mad = np.median(np.abs(sub - med))
    sigM = 1.4826 * mad
    thrM = max(8.0, 3.5 * sigM)
    lab, nlab = ndimage.label(sub > thrK)
    if nlab:
        sizes = ndimage.sum(sub > thrK, lab, range(1, nlab + 1))
        nK = int((sizes >= 2).sum())
    else:
        nK = 0
    lab, nlab = ndimage.label(sub > thrM)
    if nlab:
        sizes = ndimage.sum(sub > thrM, lab, range(1, nlab + 1))
        peaks = ndimage.maximum(sub, lab, range(1, nlab + 1))
        nM = int(((sizes >= 3) & (peaks > 2 * thrM)).sum())
    else:
        nM = 0
    print('%s stdThru=%.0f ->%d blobs | madThr=%.0f ->%d blobs' % (
        os.path.basename(p)[-8:-5], thrK, nK, thrM, nM))
