# -*- coding: utf-8 -*-
import re, math, json
import numpy as np
from PIL import Image, ImageDraw

cats = {}
for line in open('/opt/starcam-build/app/src/main/java/com/starcam/astro/astro/StarCatalogData.kt'):
    m = re.search(r'StarEntry\((\d+),\s*([-\d.]+),\s*([-\d.]+),\s*([-\d.]+),\s*"([^"]*)",\s*"(\w+)"\)', line)
    if m:
        cats[int(m.group(1))] = (float(m.group(2)), float(m.group(3)), float(m.group(4)), m.group(6))
hips = list(cats.keys())
ras = np.array([cats[h][0] for h in hips])
decs = np.array([cats[h][1] for h in hips])
mags = np.array([cats[h][2] for h in hips])
cons = np.array([cats[h][3] for h in hips])

D = math.pi/180
def gnomonic(ra0, dec0, ra, dec):
    a0, d0 = ra0*D, dec0*D
    a, d = ra*D, dec*D
    cosc = np.sin(d0)*np.sin(d) + np.cos(d0)*np.cos(d)*np.cos(a-a0)
    denom = np.where(cosc > 1e-6, cosc, -1.0)
    xi = np.cos(d)*np.sin(a-a0)/denom
    eta = (np.cos(d0)*np.sin(d) - np.sin(d0)*np.cos(d)*np.cos(a-a0))/denom
    xi = np.where(cosc > 1e-6, xi/D, np.nan)
    eta = np.where(cosc > 1e-6, eta/D, np.nan)
    return xi, eta

sizes = {'1787670194963': (4000,1846), '1787670194974': (4000,1846),
         '1787670194984': (3000,4000), '1787670194998': (4000,3000),
         '1787670195031': (4000,3000), '1787670195040': (4000,3000),
         '1787670195049': (4000,3000), '1787670195057': (4000,3000),
         '1787670195068': (4000,3000), '1787670195076': (4000,3000),
         '1787670195087': (3000,4000), '1787670195092': (3000,4000)}
stars = json.load(open('/tmp/vision/stars.json'))

def overlay(name, ra0, dec0, pixscale, label):
    W, H = sizes[name]
    fovx, fovy = W*pixscale/3600.0, H*pixscale/3600.0
    img = Image.open('/tmp/vision/chart-'+name+'.jpg').convert('RGB')
    cw, chh = img.size
    dx = ImageDraw.Draw(img)
    k = 1200.0/max(W, H)
    photo = np.array([(x*k, y*k) for x, y, fl in stars[name+'.jpg']])
    xi, eta = gnomonic(ra0, dec0, ras, decs)
    vis = ~np.isnan(xi) & (mags <= 5.5)
    sx, sy = cw/fovx, chh/fovy   # 度→像素
    cx, cy = cw/2, chh/2
    best = None
    for parity in (1, -1):
        u0, v0 = xi*parity, eta
        for th in range(0, 360, 2):
            t = th*D
            c, s = math.cos(t), math.sin(t)
            px = cx + (c*u0 - s*v0)*sx
            py = cy - (s*u0 + c*v0)*sy
            score = 0
            for j in range(len(photo)):
                d2 = (px[vis]-photo[j,0])**2 + (py[vis]-photo[j,1])**2
                if d2.min() < (0.012*max(cw,chh))**2: score += 1
            if best is None or score > best[0]:
                best = (score, parity, th, px.copy(), py.copy())
    score, parity, th, px, py = best
    sel = vis
    for i in np.where(sel)[0]:
        r = max(2.0, 6.5-mags[i])
        dx.ellipse([px[i]-r, py[i]-r, px[i]+r, py[i]+r], outline=(255,80,80), width=2)
    # 星座标签：帧内可见 mag<4.6 的星 ≥3 颗的星座，质心标注
    counts = {}
    for i in np.where(sel & (mags<4.6))[0]:
        counts.setdefault(cons[i], []).append(i)
    labels = []
    for con, idx in counts.items():
        if len(idx) >= 3:
            lx, ly = px[idx].mean(), py[idx].mean()
            labels.append((con, lx, ly, len(idx)))
    for con, lx, ly, n in sorted(labels, key=lambda z:-z[3]):
        dx.text((lx+8, ly-8), con, fill=(80,255,120))
    dx.text((10, 10), '%s center=(%.1f,%.1f) fov=(%.1f,%.1f)deg best: %d/%d pairs parity=%+d rot=%ddeg'
            % (label, ra0, dec0, fovx, fovy, score, len(photo), parity, th), fill=(255,220,0))
    img.save('/tmp/vision/ov-'+name+'.jpg', quality=92)
    print(name, 'match=%d/%d parity=%+d rot=%d cons=%s' % (score, len(photo), parity, th,
          ','.join(c for c,_,_,n in sorted(labels,key=lambda z:-z[3]) if True)))

overlay('1787670194963', 73.39, 19.08, 0.2, '4963')
overlay('1787670194974', 81.96, 32.06, 69.06, '4974')
overlay('1787670194984', 60.04, 25.26, 20.0, '4984')
overlay('1787670195031', 13.41, 70.98, 67.98, '5031')
overlay('1787670195040', 68.74, -14.26, 68.93, '5040')
overlay('1787670195068', 57.31, 26.68, 64.7, '5068')
overlay('1787670195076', 55.25, 25.98, 65.1, '5076')
overlay('1787670195087', 351.23, 24.32, 6.6, '5087')
overlay('1787670195092', 351.26, 24.32, 6.6, '5092')
