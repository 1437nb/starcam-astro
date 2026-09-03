from PIL import Image, ImageDraw, ImageFilter
import numpy as np
import glob, os, json

def label_runs(mask):
    """行程编码连通域：返回 [(cx, cy, flux_coords...)] 所需的组件像素聚合。
    mask: 2D bool。yield 每个组件的 (ys_list, xs_list) —— 用并查集合并行行程。"""
    H, W = mask.shape
    parent = {}
    def find(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a
    def union(a, b):
        ra, rb = find(a), find(b)
        if ra != rb: parent[rb] = ra
    run_id = 0
    runs = []  # (row, x0, x1, id)
    prev_row_runs = []
    for y in range(H):
        row = mask[y]
        if not row.any():
            prev_row_runs = []
            continue
        xs = np.flatnonzero(row)
        # 切分连续段
        breaks = np.flatnonzero(np.diff(xs) > 1)
        starts = np.concatenate(([0], breaks + 1))
        ends = np.concatenate((breaks, [len(xs) - 1]))
        cur = []
        for s, e in zip(starts, ends):
            x0, x1 = int(xs[s]), int(xs[e])
            rid = run_id; run_id += 1
            parent[rid] = rid
            runs.append((y, x0, x1, rid))
            cur.append((x0, x1, rid))
        # 与上一行行程合并（8 邻域：区间扩展1）
        for px0, px1, prid in prev_row_runs:
            merged = False
            for cx0, cx1, crid in cur:
                if px0 - 1 <= cx1 and cx1 >= px0 - 1 and cx0 <= px1 + 1 and px0 <= cx1 + 1:
                    union(prid, crid)
        prev_row_runs = cur
    # 聚合
    comp = {}
    for y, x0, x1, rid in runs:
        root = find(rid)
        comp.setdefault(root, []).append((y, x0, x1))
    return comp

os.chdir('/tmp/wide-test/photos')
results = {}
for f in sorted(glob.glob('*.jpg')):
    im = Image.open(f).convert('L').filter(ImageFilter.MedianFilter(3))
    W, H = im.size
    a = np.asarray(im).astype(np.float32)
    bg = np.asarray(im.filter(ImageFilter.GaussianBlur(radius=45))).astype(np.float32)
    sub = a - bg
    med = np.median(sub)
    mad = np.median(np.abs(sub - med)) * 1.4826
    sigma = max(mad, 1.0)
    thr = max(8.0, 5.0 * sigma)
    mask = sub > thr
    comp = label_runs(mask)
    stars = []
    for segs in comp.values():
        size = sum(x1 - x0 + 1 for _, x0, x1 in segs)
        if size < 2 or size > 800: continue
        flux = 0.0; sw = 0.0; sx = 0.0; sy = 0.0
        for y, x0, x1 in segs:
            vals = sub[y, x0:x1+1]
            fl = float(vals.sum())
            flux += fl
            xs = np.arange(x0, x1+1, dtype=np.float64)
            sx += float((vals * xs).sum())
            sy += fl * y
        if flux <= 0: continue
        stars.append((sx/flux, sy/flux, flux))
    stars.sort(key=lambda s: -s[2])
    top = stars[:80]
    results[f] = [(round(x,1), round(y,1), round(fl,1)) for x,y,fl in top]
    scale = 1200.0 / max(W, H)
    cw, ch = max(1,int(W*scale)), max(1,int(H*scale))
    canvas = Image.new('L', (cw, ch), 0)
    d = ImageDraw.Draw(canvas)
    if top:
        maxf = top[0][2]
        for x, y, flux in top:
            r = max(1.5, 6.0 * (flux / maxf) ** 0.5)
            xi, yi = x*scale, y*scale
            d.ellipse([xi-r, yi-r, xi+r, yi+r], fill=255)
    canvas.save('/tmp/vision/chart-' + f, quality=92)
    print(f, 'W=%d H=%d sig=%.2f thr=%.1f comps=%d stars=%d' % (W, H, sigma, thr, len(comp), len(top)))
json.dump(results, open('/tmp/vision/stars.json','w'))
