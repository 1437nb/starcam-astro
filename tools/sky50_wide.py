# -*- coding: utf-8 -*-
"""补 8 张 25-35° 宽场（自研引擎工作区）"""
import json, os, re, subprocess, math
import numpy as np
from PIL import Image
from astropy.wcs import WCS

WIDE = [
    ('wide-pleiades-auriga', 68.0, 33.0, 28.0), ('wide-orion', 84.0, 2.0, 26.0),
    ('wide-cas-per', 40.0, 52.0, 30.0), ('wide-gemini', 105.0, 25.0, 25.0),
    ('wide-uma', 165.0, 55.0, 30.0), ('wide-cygnus', 305.0, 42.0, 28.0),
    ('wide-leo', 155.0, 15.0, 30.0), ('wide-sco-sgr', 265.0, -25.0, 28.0),
]
IMG = '/tmp/sky50/img'; GRAY = '/tmp/sky50/gray'
cats = {}
for line in open('/opt/starcam-build/app/src/main/java/com/starcam/astro/astro/StarCatalogData.kt'):
    m = re.search(r'StarEntry\((\d+),\s*([-\d.]+),\s*([-\d.]+),\s*([-\d.]+),\s*"([^"]*)",\s*"(\w+)"\)', line)
    if m:
        cats[int(m.group(1))] = (float(m.group(2)), float(m.group(3)), float(m.group(4)), m.group(6))
ras = np.array([v[0] for v in cats.values()]); decs = np.array([v[1] for v in cats.values()])
mags = np.array([v[2] for v in cats.values()])

truth = json.load(open('/tmp/sky50/truth.json'))
for name, ra, dec, fov in WIDE:
    jpg = os.path.join(IMG, name + '.jpg')
    if not os.path.exists(jpg + '.ok'):
        px = 1500
        q = ('https://skyview.gsfc.nasa.gov/current/cgi/runquery.pl?Survey=dss2r'
             '&position=%.3f,%.3f&size=%g&pixels=%d&coordinates=J2000&projection=Tan&format=FITS'
             % (ra, dec, fov, px))
        skv = None
        for attempt in range(3):
            html = subprocess.run(['curl', '-sL', '--max-time', '150', q],
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout.decode(errors='ignore')
            m2 = re.search(r'tempspace/fits/([a-z0-9]+)\.jpg', html)
            if m2: skv = m2.group(1); break
            import time; time.sleep(8)
        if not skv:
            print(name, 'NO-LINK', flush=True); continue
        subprocess.run(['curl', '-sL', '--max-time', '500', '-o', jpg,
                        'https://skyview.gsfc.nasa.gov/tempspace/fits/%s.jpg' % skv],
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            im = Image.open(jpg); im.load()
        except Exception as e:
            print(name, 'BAD', e, flush=True); continue
        open(jpg + '.ok', 'w').write('ok')
    im = Image.open(jpg)
    nx, ny = im.size
    scale_deg = fov / nx
    hdr = {'NAXIS': 2, 'NAXIS1': nx, 'NAXIS2': ny, 'CTYPE1': 'RA---TAN', 'CTYPE2': 'DEC--TAN',
           'CRVAL1': ra, 'CRVAL2': dec, 'CRPIX1': nx/2+0.5, 'CRPIX2': ny/2+0.5,
           'CDELT1': -scale_deg, 'CDELT2': scale_deg, 'RADESYS': 'FK5', 'EQUINOX': 2000.0}
    w = WCS(hdr)
    ra0, dec0 = w.all_pix2world(nx/2.0, ny/2.0, 0)
    ra1, dec1 = w.all_pix2world(nx/2.0-1, ny/2.0, 0)
    dra = ((ra0 - ra1 + 180) % 360 - 180)
    scale_as = abs(math.hypot(dra*math.cos(math.radians(dec0)), dec0-dec1)) * 3600
    px2, py2 = w.all_world2pix(ras, decs, 0)
    inb = (px2 >= 0) & (px2 < nx) & (py2 >= 0) & (py2 < ny) & (mags <= 4.6)
    truth[name] = {'ra0': float(ra0), 'dec0': float(dec0), 'scale': float(scale_as),
                   'fovx': float(nx*scale_as/3600), 'fovy': float(ny*scale_as/3600),
                   'cat_stars': int(inb.sum())}
    a = np.asarray(im.convert('L'), dtype=np.float32)
    with open(os.path.join(GRAY, name + '.gray'), 'wb') as fp:
        fp.write(np.int32(nx).tobytes()); fp.write(np.int32(ny).tobytes())
        fp.write(a.astype('<f4').tobytes())
    print('WIDE', name, 'catstars=%d' % inb.sum(), flush=True)
json.dump(truth, open('/tmp/sky50/truth.json', 'w'), indent=1)
print('TOTAL', len(truth))
