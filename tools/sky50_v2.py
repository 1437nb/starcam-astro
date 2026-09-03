# -*- coding: utf-8 -*-
"""SkyView DSS2: 并行下载 jpg 快视图 + 解析构造 WCS 真值 -> .gray + truth.json"""
import json, os, re, subprocess, sys, math
from concurrent.futures import ThreadPoolExecutor
import numpy as np
from PIL import Image

OUT = '/tmp/sky50'
IMG = os.path.join(OUT, 'img'); os.makedirs(IMG, exist_ok=True)
manifest = json.load(open(os.path.join(OUT, 'manifest.json')))

def curl(url, out, timeout=280):
    subprocess.run(['curl', '-sL', '--max-time', str(timeout), '-o', out, url],
                   stdout=subprocess.PIPE, stderr=subprocess.PIPE)

def query_link(j):
    px = int(j['fov'] * 150)
    q = ('https://skyview.gsfc.nasa.gov/current/cgi/runquery.pl?Survey=dss2r'
         '&position=%.3f,%.3f&size=%g&pixels=%d&coordinates=J2000&projection=Tan&format=FITS'
         % (j['ra'], j['dec'], j['fov'], px))
    for attempt in range(3):
        html = subprocess.run(['curl', '-sL', '--max-time', '120', q],
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout.decode(errors='ignore')
        m = re.search(r'tempspace/fits/([a-z0-9]+)\.jpg', html)
        if m: return m.group(1), px
        import time; time.sleep(8)
    return None, px

def process(j):
    id_ = j['id']
    jpg_out = os.path.join(IMG, id_ + '.jpg')
    if not os.path.exists(jpg_out + '.ok'):
        skv, px = query_link(j)
        if skv is None:
            return id_, 'NO-LINK'
        curl('https://skyview.gsfc.nasa.gov/tempspace/fits/%s.jpg' % skv, jpg_out)
        try:
            im = Image.open(jpg_out); im.load()
            if im.size[0] != px:
                return id_, 'SIZE-MISMATCH %s vs %d' % (im.size, px)
        except Exception as e:
            return id_, 'BAD-JPG ' + str(e)
        open(jpg_out + '.ok', 'w').write('ok')
        return id_, 'OK %dx%d' % (px, px)
    return id_, 'SKIP'

with ThreadPoolExecutor(4) as ex:
    for id_, st in ex.map(process, manifest):
        print(id_, st, flush=True)

# 构造 WCS 真值 + 转 .gray
import sys
sys.path.insert(0, '/opt/starcam-build')
cats = {}
for line in open('/opt/starcam-build/app/src/main/java/com/starcam/astro/astro/StarCatalogData.kt'):
    m = re.search(r'StarEntry\((\d+),\s*([-\d.]+),\s*([-\d.]+),\s*([-\d.]+),\s*"([^"]*)",\s*"(\w+)"\)', line)
    if m:
        cats[int(m.group(1))] = (float(m.group(2)), float(m.group(3)), float(m.group(4)), m.group(6))
hips = list(cats.keys())
ras = np.array([cats[h][0] for h in hips]); decs = np.array([cats[h][1] for h in hips])
mags = np.array([cats[h][2] for h in hips])

from astropy.wcs import WCS
truth = {}
gray_dir = os.path.join(OUT, 'gray'); os.makedirs(gray_dir, exist_ok=True)
for j in manifest:
    jpg = os.path.join(IMG, j['id'] + '.jpg')
    if not os.path.exists(jpg + '.ok'): continue
    im = Image.open(jpg)
    nx, ny = im.size
    px = nx
    scale_deg = j['fov'] / px
    hdr = {'NAXIS': 2, 'NAXIS1': nx, 'NAXIS2': ny,
           'CTYPE1': 'RA---TAN', 'CTYPE2': 'DEC--TAN',
           'CRVAL1': j['ra'], 'CRVAL2': j['dec'],
           'CRPIX1': px/2 + 0.5, 'CRPIX2': px/2 + 0.5,
           'CDELT1': -scale_deg, 'CDELT2': scale_deg,
           'RADESYS': 'FK5', 'EQUINOX': 2000.0}
    w = WCS(hdr)
    ra0, dec0 = w.all_pix2world(nx/2.0, ny/2.0, 0)
    ra1, dec1 = w.all_pix2world(nx/2.0 - 1, ny/2.0, 0)
    dra = ((ra0 - ra1 + 180) % 360 - 180)
    scale_as = abs(math.hypot(dra*math.cos(math.radians(dec0)), dec0 - dec1)) * 3600
    px2, py2 = w.all_world2pix(ras, decs, 0)
    inb = (px2 >= 0) & (px2 < nx) & (py2 >= 0) & (py2 < ny) & (mags <= 4.6)
    truth[j['id']] = {'ra0': float(ra0), 'dec0': float(dec0), 'scale': float(scale_as),
                      'fovx': float(nx*scale_as/3600), 'fovy': float(ny*scale_as/3600),
                      'cat_stars': int(inb.sum())}
    g = im.convert('L')
    a = np.asarray(g, dtype=np.float32)
    with open(os.path.join(gray_dir, j['id'] + '.gray'), 'wb') as fp:
        fp.write(np.int32(nx).tobytes()); fp.write(np.int32(ny).tobytes())
        fp.write(a.astype('<f4').tobytes())
    print('TRUTH', j['id'], 'catstars=%d' % inb.sum(), flush=True)
json.dump(truth, open(os.path.join(OUT, 'truth.json'), 'w'), indent=1)
print('TOTAL-TRUTH', len(truth))
