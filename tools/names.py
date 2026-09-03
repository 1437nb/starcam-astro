# -*- coding: utf-8 -*-
import re, math
import numpy as np

cats = {}
for line in open('/opt/starcam-build/app/src/main/java/com/starcam/astro/astro/StarCatalogData.kt'):
    m = re.search(r'StarEntry\((\d+),\s*([-\d.]+),\s*([-\d.]+),\s*([-\d.]+),\s*"([^"]*)",\s*"(\w+)"\)', line)
    if m:
        cats[int(m.group(1))] = (float(m.group(2)), float(m.group(3)), float(m.group(4)), m.group(5), m.group(6))
hips = list(cats.keys())
ras = np.array([cats[h][0] for h in hips])
decs = np.array([cats[h][1] for h in hips])
mags = np.array([cats[h][2] for h in hips])
names = [cats[h][3] for h in hips]
cons = np.array([cats[h][4] for h in hips])
D = math.pi/180

def angdist(ra1, dec1, ra2, dec2):
    s = math.sin(dec1*D)*math.sin(dec2*D) + math.cos(dec1*D)*math.cos(dec2*D)*math.cos((ra1-ra2)*D)
    return math.acos(max(-1,min(1,s)))/D

photos = [
 ('1787670194963', 73.39, 19.08, 0.2, 'official'),
 ('1787670194974', 81.96, 32.06, 76.7, 'local'),
 ('1787670194984', 60.04, 25.26, 16.6, 'official'),
 ('1787670194998', 60.03, 25.26, 72.3, 'official-batch3'),
 ('1787670195031', 13.41, 70.98, 75.5, 'local'),
 ('1787670195040', 68.74, -14.26, 76.6, 'local'),
 ('1787670195049', 70.50, -20.88, 70.7, 'official-batch3'),
 ('1787670195057', None, None, None, 'unsolved'),
 ('1787670195068', 57.31, 26.68, 71.9, 'official'),
 ('1787670195076', 55.25, 25.98, 72.3, 'official'),
 ('1787670195087', 351.23, 24.32, 5.5, 'official'),
 ('1787670195092', 351.26, 24.32, 5.5, 'official'),
]
for name, ra0, dec0, fov, src in photos:
    print('=== %s (RA=%s Dec=%s FOV=%s %s)' % (name, ra0, dec0, fov, src))
    if ra0 is None: continue
    # 中心半径内（fov/2 + 边角余量），按星等排
    rmax = fov/2*1.3 + 2
    idx = [i for i in range(len(hips)) if mags[i] <= 4.0 and angdist(ra0, dec0, ras[i], decs[i]) <= rmax]
    idx.sort(key=lambda i: mags[i])
    named = [(names[i], cons[i], mags[i], angdist(ra0, dec0, ras[i], decs[i])) for i in idx if names[i]]
    top = [(cons[i], mags[i]) for i in idx[:8]]
    # 星座统计（中心 fov/4 内 mag<=3.5）
    inner = [cons[i] for i in range(len(hips)) if mags[i] <= 3.5 and angdist(ra0, dec0, ras[i], decs[i]) <= fov/4 + 1]
    from collections import Counter
    cc = Counter(inner).most_common(6)
    print('  中心区星座分布(mag<=3.5):', cc)
    print('  帧内最亮(前8):', ['%s%.0f' % (c, m) for c, m in top])
    if named:
        print('  具名亮星:', ', '.join('%s(%s %.1f等)' % (n, c, m) for n, c, m, d in named[:10]))
