# -*- coding: utf-8 -*-
"""星座连线生成器（§0.51）——以 Stellarium 西方（modern）星文化的
constellationship.fab 为准重新生成 constellationLines。

数据源：
- Stellarium v23.4 skycultures/modern/constellationship.fab（GPLv2+，与项目一致）
  格式：`<缩写> <线段数> <HIP 端点对...>`，每两个 HIP 一条线段。
- 星表端点：919 颗真 HIP 亮星直接命中；其余按坐标匹配星表中的 BSC5 增补星
  （hip = 120000+HR）；匹配不到的（星等 >6.5 等）所在线段跳过。
- 缺失 HIP 的 J2000 坐标/星等经 VizieR TAP（I/239/hip_main）在线查询，
  缓存到 hips_stellarium.csv（离线可复用）。

用法: python gen_lines_stellarium.py
"""
import csv
import os
import re
import sys
import urllib.parse
import urllib.request

BASE = r'C:\starcam-bundle\code\app\src\main\java\com\starcam\astro\astro'
CATALOG_FILE = os.path.join(BASE, 'StarCatalogData.kt')
FAB_FILE = r'C:\dev\constellationship.fab'
HIPS_CACHE = r'C:\dev\hips_stellarium.csv'
TAP = 'https://tapvizier.cds.unistra.fr/TAPVizieR/tap/sync'
MATCH_DEG = 0.02  # 坐标匹配容差（度，~72″）
MAG_TOL = 1.5      # 星等一致性容差

# ---------- 1. 解析现有星表（hip → 下标/坐标/星等） ----------
star_re = re.compile(r'StarEntry\((\d+),\s*([\d.]+),\s*([-\d.]+),\s*([-\d.]+),')
stars = []  # dict: hip, ra, dec, mag
for p in range(1, 9):
    with open(os.path.join(BASE, 'StarCatalogPart%d.kt' % p), encoding='utf-8') as f:
        for m in star_re.finditer(f.read()):
            stars.append({
                'hip': int(m.group(1)),
                'ra': float(m.group(2)),
                'dec': float(m.group(3)),
                'mag': float(m.group(4)),
            })
idx_by_hip = {s['hip']: i for i, s in enumerate(stars)}
print('catalog stars:', len(stars))

# ---------- 2. 解析 Stellarium 连线 ----------
segments = []          # (abbr, hipA, hipB)
consts = []
with open(FAB_FILE, encoding='utf-8') as f:
    for ln in f:
        ln = ln.strip()
        if not ln or ln.startswith('#'):
            continue
        parts = ln.split()
        abbr = parts[0]
        n = int(parts[1])
        nums = [int(x) for x in parts[2:2 + n * 2]]
        consts.append(abbr)
        for i in range(0, len(nums), 2):
            a, b = nums[i], nums[i + 1]
            if a != b:
                segments.append((abbr, a, b))
print('constellations:', len(consts), 'raw segments:', len(segments))

# ---------- 3. 缺失 HIP → 在线查坐标（缓存） ----------
missing = sorted({h for _, a, b in segments for h in (a, b)} - set(idx_by_hip))
print('missing HIPs:', len(missing))

hip_pos = {}  # hip -> (ra, dec, mag)
if os.path.exists(HIPS_CACHE):
    with open(HIPS_CACHE, encoding='utf-8') as f:
        for row in csv.DictReader(f):
            try:
                hip_pos[int(row['HIP'])] = (float(row['RAICRS']), float(row['DEICRS']), float(row['Vmag']))
            except (KeyError, ValueError):
                pass
print('cached hips:', len(hip_pos))

to_fetch = [h for h in missing if h not in hip_pos]
if to_fetch:
    print('fetching', len(to_fetch), 'from VizieR TAP...')
    q = ('SELECT HIP,RAICRS,DEICRS,Vmag FROM "I/239/hip_main" WHERE HIP IN (%s)' %
         ','.join(str(h) for h in to_fetch))
    url = TAP + '?' + urllib.parse.urlencode({
        'REQUEST': 'doQuery', 'LANG': 'ADQL', 'FORMAT': 'csv', 'QUERY': q})
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'StarCam-tool/1.0'})
        raw = urllib.request.urlopen(req, timeout=120).read().decode('utf-8', 'replace')
        with open(HIPS_CACHE, 'w', encoding='utf-8') as f:
            f.write(raw)
        for row in csv.DictReader(raw.splitlines()):
            try:
                hip_pos[int(row['HIP'])] = (float(row['RAICRS']), float(row['DEICRS']), float(row['Vmag']))
            except (KeyError, ValueError):
                pass
        print('fetched, total hips:', len(hip_pos))
    except Exception as e:
        print('TAP FAILED:', e)

# ---------- 4. 端点 → 星表下标（缺失 HIP 按坐标匹配 BSC5 增补星） ----------
def nearest_index(ra, dec, mag):
    best = None
    best_d = MATCH_DEG
    for s in stars:
        dra = abs(s['ra'] - ra)
        if dra > 180.0:
            dra = 360.0 - dra
        d = max(abs(s['dec'] - dec), dra)  # 保守近似，够用
        if d < best_d:
            best_d = d
            best = s
    if best is None:
        return None
    if abs(best['mag'] - mag) > MAG_TOL:
        return None
    return idx_by_hip[best['hip']]

index_by_hip = dict(idx_by_hip)
unmatched = []
for h in missing:
    if h in hip_pos:
        ra, dec, mag = hip_pos[h]
        ix = nearest_index(ra, dec, mag)
        if ix is not None:
            index_by_hip[h] = ix
            continue
    unmatched.append(h)
print('unmatched HIPs (segments skipped):', len(unmatched), unmatched[:20])

# ---------- 5. 生成新连线（去重：反向重复/自环） ----------
new_segments = []  # (abbr, ia, ib)
skipped = 0
for abbr, ha, hb in segments:
    ia = index_by_hip.get(ha)
    ib = index_by_hip.get(hb)
    if ia is None or ib is None:
        skipped += 1
        continue
    if ia == ib:
        continue
    new_segments.append((abbr, ia, ib))

seen = set()
dedup = []
for abbr, ia, ib in new_segments:
    key = (min(ia, ib), max(ia, ib))
    if key in seen:
        continue
    seen.add(key)
    dedup.append((abbr, ia, ib))
new_segments = dedup
print('kept segments:', len(new_segments), 'skipped:', skipped)

# 统计每个星座线段数
from collections import Counter
cnt = Counter(a for a, _, _ in new_segments)
for abbr in sorted(cnt):
    if cnt[abbr] < 3:
        print('  sparse constellation %s: %d segments' % (abbr, cnt[abbr]))

# ---------- 6. 重写 StarCatalogData.kt 的 constellationLines 块 ----------
with open(CATALOG_FILE, encoding='utf-8') as f:
    src = f.read()

block_re = re.compile(
    r'val constellationLines: List<IntArray> = listOf\((.*?)\)\n', re.S)
body = '\n'.join('        intArrayOf(%d, %d),' % (ia, ib) for _, ia, ib in new_segments)
replacement = 'val constellationLines: List<IntArray> = listOf(\n%s\n    )\n' % body
new_src, nsub = block_re.subn(replacement, src, count=1)
assert nsub == 1, 'constellationLines block not found!'
with open(CATALOG_FILE, 'w', encoding='utf-8') as f:
    f.write(new_src)
print('rewrote constellationLines:', len(new_segments), 'segments in', CATALOG_FILE)
