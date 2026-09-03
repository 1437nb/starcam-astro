# -*- coding: utf-8 -*-
import re, math
import numpy as np
from PIL import Image, ImageDraw

# 1) 解析星表
cats = {}
for line in open('/opt/starcam-build/app/src/main/java/com/starcam/astro/astro/StarCatalogData.kt'):
    m = re.search(r'StarEntry\((\d+),\s*([-\d.]+),\s*([-\d.]+),\s*([-\d.]+),\s*"([^"]*)",\s*"(\w+)"\)', line)
    if m:
        hip, ra, dec, mag = int(m.group(1)), float(m.group(2)), float(m.group(3)), float(m.group(4))
        con = m.group(6)
        cats[hip] = (ra, dec, mag, con)

D = math.pi/180
def gnomonic(ra0, dec0, ras, decs):
    a0, d0 = ra0*D, dec0*D
    a, d = np.asarray(ras)*D, np.asarray(decs)*D
    cosc = np.sin(d0)*np.sin(d) + np.cos(d0)*np.cos(d)*np.cos(a-a0)
    denom = np.where(cosc > 1e-9, cosc, -1)  # 背面/边缘 → NaN
    xi = np.cos(d)*np.sin(a-a0)/denom
    eta = (np.cos(d0)*np.sin(d) - np.sin(d0)*np.cos(d)*np.cos(a-a0))/denom
    return xi/D, eta/D  # 度；xi=东向，eta=北向

def render(name, ra0, dec0, fov_x, fov_y, W=900, extra_rot=0.0):
    hip_list = list(cats.keys())
    ras = [cats[h][0] for h in hip_list]
    decs = [cats[h][1] for h in hip_list]
    xi, eta = gnomonic(ra0, dec0, ras, decs)
    half_x, half_y = fov_x/2, fov_y/2
    img = Image.new('RGB', (W, int(W*fov_y/fov_x)), (0,0,0))
    d = ImageDraw.Draw(img)
    w, h = img.size
    # 标准天图方向：北在上、东在左 → 屏幕 x = -xi, y = -eta
    px = (w/2) - (xi/half_x)*(w/2-20)
    py = (h/2) - (eta/half_y)*(h/2-20)
    mags = [cats[hh][2] for hh in hip_list]
    for i in range(len(hip_list)):
        x, y, mg = px[i], py[i], mags[i]
        if not (0 <= x < w and 0 <= y < h) or math.isnan(x): continue
        if mg > 5.0: continue
        r = max(1.2, 5.2 - mg) 
        d.ellipse([x-r, y-r, x+r, y+r], fill=(255,255,255))
    img.save('/tmp/vision/finder-'+name+'.jpg', quality=92)
    return img

def side_by_side(name, photo_chart, finder_img):
    p = Image.open(photo_chart).convert('RGB')
    f = finder_img
    H = 800
    p = p.resize((int(p.width*H/p.height), H))
    f = f.resize((int(f.width*H/f.height), H))
    canvas = Image.new('RGB', (p.width+f.width+30, H+40), (20,20,20))
    canvas.paste(p, (10, 30)); canvas.paste(f, (p.width+20, 30))
    d = ImageDraw.Draw(canvas)
    d.text((15, 5), 'PHOTO (rotated/zoomed unknown)', fill=(255,200,0))
    d.text((p.width+25, 5), 'CATALOG (N up, E left)', fill=(0,255,120))
    canvas.save('/tmp/vision/cmp-'+name+'.jpg', quality=90)

jobs = [
    # name, ra0, dec0, fovx_deg, fovy_deg
    ('4963-official', 73.39, 19.08, 0.29, 0.20),
    ('4984-official', 60.04, 25.26, 12.4, 16.6),
    ('5068-official', 57.31, 26.68, 53.9, 71.9),
    ('5076-official', 55.25, 25.98, 54.2, 72.3),
    ('5087-official', 351.23, 24.32, 4.1, 5.5),
    ('5092-official', 351.26, 24.32, 4.1, 5.5),
    ('4974-local', 81.96, 32.06, 57.5, 76.7),
    ('5031-local', 13.41, 70.98, 56.6, 75.5),
    ('5040-local', 68.74, -14.26, 57.5, 76.6),
]
for name, ra, dec, fx, fy in jobs:
    img = render(name, ra, dec, fx, fy)
    photo = '/tmp/vision/chart-178767019%s.jpg' % name.split('-')[0]
    side_by_side(name, photo, img)
    print('done', name)
