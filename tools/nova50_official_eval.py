# -*- coding: utf-8 -*-
"""官方引擎评测（修正：scale=hypot(CD1_1,CD1_2)）"""
import json, math, os
from astropy.wcs import WCS
from astropy.io import fits
import warnings; warnings.filterwarnings('ignore')
truth = json.load(open('/tmp/nova50/truth.json'))
def angsep(ra1, dec1, ra2, dec2):
    r = math.pi/180
    s = math.sin(dec1*r)*math.sin(dec2*r)+math.cos(dec1*r)*math.cos(dec2*r)*math.cos((ra1-ra2)*r)
    return math.degrees(math.acos(max(-1,min(1,s))))
ok = fp = fail = 0
for sid, t in sorted(truth.items()):
    wf = '/tmp/nova50/solve/%s/%s.wcs' % (sid, sid)
    if not os.path.exists(wf):
        print(sid, 'FAILED'); fail += 1; continue
    h = fits.getheader(wf); w = WCS(h)
    nx, ny = h['IMAGEW'], h['IMAGEH']
    cra, cdec = w.all_pix2world(nx/2.0, ny/2.0, 0)
    sep = angsep(float(cra), float(cdec), t['ra0'], t['dec0'])
    sc = math.hypot(h['CD1_1'], h['CD1_2']) * 3600
    fr = sc / t['scale']
    good = sep < 0.5 and 0.9 <= fr <= 1.1
    if good: ok += 1
    else: fp += 1
    print('%-12s %-6s sep=%.3f° scale=%+.0f%%' % (sid, 'OK' if good else 'WRONG', sep, (fr-1)*100))
print('=== 官方引擎: 正确 %d/%d ===' % (ok, ok+fp))
