# -*- coding: utf-8 -*-
"""官方引擎解 vs 真值"""
import json, math, glob, os
from astropy.io import fits
truth = json.load(open('/tmp/sky50/truth.json'))
def angsep(ra1, dec1, ra2, dec2):
    r = math.pi/180
    s = math.sin(dec1*r)*math.sin(dec2*r) + math.cos(dec1*r)*math.cos(dec2*r)*math.cos((ra1-ra2)*r)
    return math.acos(max(-1,min(1,s)))/r
ok = fp = err = 0
seps = []
rows = []
for k, t in sorted(truth.items()):
    wfile = '/tmp/sky50/solve/%s/%s.wcs' % (k, k)
    if not os.path.exists(wfile):
        rows.append((k, 'NO-WCS', '')); err += 1; continue
    h = fits.getheader(wfile)
    s = abs(h['CD1_1'])*3600 if 'CD1_1' in h else abs(h['CDELT1'])*3600
    sep = angsep(h['CRVAL1'], h['CRVAL2'], t['ra0'], t['dec0'])
    fr = s / t['scale']
    correct = sep < max(2.0, 0.25*t['fovx']) and 0.7 <= fr <= 1.4
    if correct:
        ok += 1; seps.append(sep)
        rows.append((k, 'OK', 'sep=%.2f° scale%+.0f%%' % (sep, (fr-1)*100)))
    else:
        fp += 1
        rows.append((k, 'WRONG', 'sep=%.2f° scale%+.0f%%' % (sep, (fr-1)*100)))
n = ok + fp
print('=== 官方引擎 × %d 张定标图（带视场先验）===' % n)
print('正确: %d (%.0f%%)  解错天区: %d  无解: %d' % (ok, 100.0*ok/max(n,1), fp, err))
if seps:
    seps.sort()
    print('中心定位误差: 中位 %.2f° P90 %.2f° 最大 %.2f°' % (seps[len(seps)//2], seps[int(len(seps)*0.9)], seps[-1]))
for r in rows: print('%-24s %-8s %s' % r)
