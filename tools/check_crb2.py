import re, os
BASE = r'C:\starcam-bundle\code\app\src\main\java\com\starcam\astro\astro'
star_re = re.compile(r'StarEntry\((\d+),\s*([\d.]+),\s*([-\d.]+),\s*([-\d.]+),\s*"([^"]*)",\s*"([^"]*)"\)')
stars = []
for p in range(1, 9):
    with open(os.path.join(BASE, 'StarCatalogPart%d.kt' % p), encoding='utf-8') as f:
        for m in star_re.finditer(f.read()):
            stars.append({'hip': int(m.group(1)), 'name': m.group(5), 'con': m.group(6), 'mag': float(m.group(4))})
idx = {s['hip']: i for i, s in enumerate(stars)}
line_re = re.compile(r'intArrayOf\((\d+),\s*(\d+)\)')
with open(os.path.join(BASE, 'StarCatalogData.kt'), encoding='utf-8') as f:
    lines = [(int(a), int(b)) for a, b in line_re.findall(f.read())]

# 北冕座冠形 7 星 HIP（Stellarium fab）
crb = [76127, 75695, 76267, 76952, 77512, 78159, 78493]
# 用 fab 顺序追踪链：找每条 fab 相邻对的映射
fab = [76127, 75695, 76267, 76952, 77512, 78159, 78493]
# 通过坐标找增补星的索引：直接查 6 条 fab 线段是否都在生成的 lines 里
# 生成 lines 是无序对，故只需确认这些 HIP 对（经映射）都在
# 先找 77512/78159/78493 的坐标匹配索引：查 hips_stellarium.csv + 最近星
import csv
hip_pos = {}
with open(r'C:\dev\hips_stellarium.csv', encoding='utf-8') as f:
    for row in csv.DictReader(f):
        hip_pos[int(row['HIP'])] = (float(row['RAICRS']), float(row['DEICRS']))
def nearest(ra, dec):
    best, bd = None, 99
    for s in stars:
        dra = abs(s['ra']-ra); 
        if dra > 180: dra = 360-dra
        d = max(abs(s['dec']-dec), dra)
        if d < bd: bd, best = d, s
    return best, bd
def chain_idx(h):
    if h in idx: return idx[h]
    if h in hip_pos:
        s, d = nearest(*hip_pos[h])
        return idx[s['hip']] if d < 0.02 else None
    return None
chain = [chain_idx(h) for h in crb]
print('crown chain indices:', chain)
pairs = list(zip(chain, chain[1:]))
allpairs = {tuple(sorted(p)) for p in lines}
ok = all(tuple(sorted((a, b))) in allpairs for a, b in pairs if a is not None and b is not None)
print('crown segments all present:', ok, '(', len(pairs), 'links )')
for (a, b) in pairs:
    present = tuple(sorted((a, b))) in allpairs
    print('  %s(%d) -- %s(%d)  %s' % (
        stars[a]['name'] or ('HR' + str(120000 - a)) if a >= 120000 else stars[a]['name'], a,
        stars[b]['name'] or ('HR' + str(120000 - b)) if b >= 120000 else stars[b]['name'], b,
        'OK' if present else 'MISSING'))
