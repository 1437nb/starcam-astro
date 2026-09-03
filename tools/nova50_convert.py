# -*- coding: utf-8 -*-
"""nova50: jpg + wcs -> .gray + truth.json（SIP 安全版）"""
import json, os, re, math
import numpy as np
from PIL import Image
from astropy.io import fits
from astropy.wcs import WCS
import warnings
warnings.filterwarnings('ignore')

cats = {}
for line in open('/opt/starcam-build/app/src/main/java/com/starcam/astro/astro/StarCatalogData.kt'):
    m = re.search(r'StarEntry\((\d+),\s*([-\d.]+),\s*([-\d.]+),\s*([-\d.]+),\s*"([^"]*)",\s*"(\w+)"\)', line)
    if m:
        cats[int(m.group(1))] = (float(m.group(2)), float(m.group(3)), float(m.group(4)), m.group(6))
ras = np.array([v[0] for v in cats.values()]); decs = np.array([v[1] for v in cats.values()])
mags = np.array([v[2] for v in cats.values()])

info = json.load(open('/tmp/nova50/info.json'))
gray = '/tmp/nova50/gray'; os.makedirs(gray, exist_ok=True)
truth = {}
for sid, inf in sorted(info.items()):
    jp = '/tmp/nova50/img/%s.jpg' % sid
    wp = '/tmp/nova50/img/%s.wcs' % sid
    if not (os.path.exists(jp) and os.path.exists(wp)): continue
    try:
        im = Image.open(jp); im.load()
        nx, ny = im.size
        h = fits.getheader(wp)
        w = WCS(h)
        # 前向投影（SIP 正向稳定）：中心 + 比例尺
        ra0, dec0 = w.all_pix2world(nx/2.0, ny/2.0, 0)
        ra1, dec1 = w.all_pix2world(nx/2.0-1, ny/2.0, 0)
        dra = ((float(ra0)-float(ra1)+180) % 360 - 180)
        scale = abs(math.hypot(dra*math.cos(math.radians(float(dec0))), float(dec0)-float(dec1))) * 3600
        fovx, fovy = nx*scale/3600, ny*scale/3600
        # 与页面标定交叉验证
        page_sep = math.hypot((float(ra0)-inf['ra0'])*math.cos(math.radians(float(dec0))), float(dec0)-inf['dec0'])
        # 星表投影：先试 SIP 反演（放宽），失败回退线性
        try:
            px2, py2 = w.all_world2pix(ras, decs, 0, tolerance=1e-3, maxiter=60)
        except Exception:
            px2, py2 = w.wcs_world2pix(ras, decs, 0)
        inb = (px2 >= -8) & (px2 < nx+8) & (py2 >= -8) & (py2 < ny+8) & (mags <= 4.6)
        truth[sid] = {'ra0': float(ra0), 'dec0': float(dec0), 'scale': float(scale),
                      'fovx': float(fovx), 'fovy': float(fovy), 'cat_stars': int(inb.sum()),
                      'page_sep': round(page_sep, 3)}
        a = np.asarray(im.convert('L'), dtype=np.float32)
        with open(os.path.join(gray, sid + '.gray'), 'wb') as fp:
            fp.write(np.int32(nx).tobytes()); fp.write(np.int32(ny).tobytes())
            fp.write(a.astype('<f4').tobytes())
        print(sid, '%dx%d fov=(%.1f,%.1f) cat=%d pageSep=%.2f°' % (nx, ny, fovx, fovy, inb.sum(), page_sep), flush=True)
    except Exception as e:
        print(sid, 'ERR', str(e)[:80], flush=True)
json.dump(truth, open('/tmp/nova50/truth.json', 'w'), indent=1)
print('TOTAL-TRUTH', len(truth))
