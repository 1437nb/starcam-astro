# -*- coding: utf-8 -*-
"""解析自研引擎输出，对真值算成功率/误差"""
import json, math, re, sys

truth = json.load(open('/tmp/sky50/truth.json'))
def angsep(ra1, dec1, ra2, dec2):
    r = math.pi/180
    s = math.sin(dec1*r)*math.sin(dec2*r) + math.cos(dec1*r)*math.cos(dec2*r)*math.cos((ra1-ra2)*r)
    return math.acos(max(-1, min(1, s)))/r

results = {}
for line in open(sys.argv[1]):
    m = re.match(r'PHOTO (\S+) .*?(SOLVED ra=([\d.]+) dec=([-\d.]+) inlier=(\d+) scale=([\d.]+)|UNSOLVED|ERROR.*)', line)
    if m:
        fid = m.group(1).replace('.gray','')
        if m.group(2).startswith('SOLVED'):
            results[fid] = {'solved': True, 'ra': float(m.group(3)), 'dec': float(m.group(4)),
                            'inlier': int(m.group(5)), 'scale': float(m.group(6))}
        else:
            results[fid] = {'solved': False}

n = ok = fp = unsolved = 0
seps = []
rows = []
for fid, t in sorted(truth.items()):
    if fid not in results: continue
    n += 1
    r = results[fid]
    if not r['solved']:
        unsolved += 1
        rows.append((fid, t['cat_stars'], t['fovx'], 'UNSOLVED', ''))
        continue
    sep = angsep(r['ra'], r['dec'], t['ra0'], t['dec0'])
    fov_ratio = (r['scale'] / t['scale']) if t['scale'] else 0
    correct = sep < max(3.0, 0.35 * t['fovx']) and 0.5 <= fov_ratio <= 2.0
    if correct:
        ok += 1; seps.append(sep)
        rows.append((fid, t['cat_stars'], t['fovx'], 'OK', 'sep=%.2fdeg fovx%.0f' % (sep, fov_ratio*100)))
    else:
        fp += 1
        rows.append((fid, t['cat_stars'], t['fovx'], 'FALSE-POS', 'sep=%.2fdeg fovx%.0f' % (sep, fov_ratio*100)))

print('=== 自研引擎 × %d 张 SkyView 真实定标图 ===' % n)
print('正确解出: %d (%.0f%%)  假阳性: %d  未解出: %d' % (ok, 100.0*ok/max(n,1), fp, unsolved))
if seps:
    seps.sort()
    print('定位误差: 中位 %.2f° P90 %.2f° 最大 %.2f°' % (seps[len(seps)//2], seps[int(len(seps)*0.9)], seps[-1]))
for row in rows:
    print('%-22s cat%2d  fov%4.1f°  %-10s %s' % row)
