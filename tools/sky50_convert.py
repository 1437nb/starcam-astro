# -*- coding: utf-8 -*-
"""FITS -> .gray + 真值清单（WCS 中心/视场 + 星表投影）"""
import json, os, re
import numpy as np
from astropy.io import fits
from astropy.wcs import WCS

cats = {}
for line in open('/opt/starcam-build/app/src/main/java/com/starcam/astro/astro/StarCatalogData.kt'):
    m = re.search(r'StarEntry\((\d+),\s*([-\d.]+),\s*([-\d.]+),\s*([-\d.]+),\s*"([^"]*)",\s*"(\w+)"\)', line)
    if m:
        cats[int(m.group(1))] = (float(m.group(2)), float(m.group(3)), float(m.group(4)), m.group(6))
hips = list(cats.keys())
ras = np.array([cats[h][0] for h in hips]); decs = np.array([cats[h][1] for h in hips])
mags = np.array([cats[h][2] for h in hips])

manifest = json.load(open('/tmp/sky50/manifest.json'))
gray_dir = '/tmp/sky50/gray'; os.makedirs(gray_dir, exist_ok=True)
truth = {}
for j in manifest:
    p = '/tmp/sky50/fits/%s.fits' % j['id']
    if not os.path.exists(p + '.ok'): continue
    try:
        with fits.open(p) as hdul:
            data = hdul[0].data.astype(np.float32)
            w = WCS(hdul[0].header)
        ny, nx = data.shape
        # 真值：中心像素 -> 天球；视场
        ra0, dec0 = w.all_pix2world(nx/2.0, ny/2.0, 0)
        scale = abs(w.all_pix2world(nx/2, ny/2, 0)[0] - w.all_pix2world(nx/2-1, ny/2, 0)[0]) * 3600
        if scale > 180:  # 经度环绕
            scale = abs(((w.all_pix2world(nx/2, ny/2, 0)[0] - w.all_pix2world(nx/2-1, ny/2, 0)[0]) + 180) % 360 - 180) * 3600
        fovx, fovy = nx*scale/3600, ny*scale/3600
        # 星表投影（帧内）
        px, py = w.all_world2pix(ras, decs, 0)
        inb = (px >= 0) & (px < nx) & (py >= 0) & (py < ny) & (mags <= 4.6)
        truth[j['id']] = {'ra0': float(ra0), 'dec0': float(dec0), 'scale': float(scale),
                          'fovx': float(fovx), 'fovy': float(fovy),
                          'cat_stars': int(inb.sum())}
        out = os.path.join(gray_dir, j['id'] + '.gray')
        with open(out, 'wb') as fp:
            fp.write(np.int32(nx).tobytes()); fp.write(np.int32(ny).tobytes())
            fp.write(data.astype('<f4').tobytes())
        print(j['id'], '%dx%d' % (nx, ny), 'center=(%.2f,%.2f) fov=(%.1f,%.1f) catstars=%d' % (ra0, dec0, fovx, fovy, inb.sum()), flush=True)
    except Exception as e:
        print(j['id'], 'ERR', e, flush=True)
json.dump(truth, open('/tmp/sky50/truth.json', 'w'), indent=1)
print('converted:', len(truth))
