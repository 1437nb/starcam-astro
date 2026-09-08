import re, os
BASE = r'C:\starcam-bundle\code\app\src\main\java\com\starcam\astro\astro'
star_re = re.compile(r'StarEntry\((\d+),\s*([\d.]+),\s*([-\d.]+),\s*([-\d.]+),\s*"([^"]*)",\s*"([^"]*)"\)')
stars = []
for p in range(1, 9):
    with open(os.path.join(BASE, 'StarCatalogPart%d.kt' % p), encoding='utf-8') as f:
        for m in star_re.finditer(f.read()):
            stars.append({'hip': int(m.group(1)), 'name': m.group(5), 'con': m.group(6)})
idx = {s['hip']: i for i, s in enumerate(stars)}
line_re = re.compile(r'intArrayOf\((\d+),\s*(\d+)\)')
with open(os.path.join(BASE, 'StarCatalogData.kt'), encoding='utf-8') as f:
    lines = [(int(a), int(b)) for a, b in line_re.findall(f.read())]
print('total segments:', len(lines))
crb_hips = [76127, 75695, 76267, 76952, 77512, 78159, 78493]
crb_idx = {idx[h] for h in crb_hips if h in idx}
print('CrB crown star indices present:', sorted(crb_idx))
crb_segs = [(a, b) for a, b in lines if a in crb_idx and b in crb_idx]
print('CrB crown segments:', len(crb_segs))
for a, b in crb_segs:
    print('  %s(%s) -- %s(%s)' % (stars[a]['name'] or stars[a]['hip'], a, stars[b]['name'] or stars[b]['hip'], b))
