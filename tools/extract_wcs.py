from astropy.io import fits
import glob, os
os.chdir('/tmp/wide-test/photos')
for f in sorted(glob.glob('*.wcs')):
    base = f[:-4]
    try:
        h = fits.getheader(f)
        cd11 = abs(h.get('CD1_1', 0))
        scale = cd11 * 3600
        w = h.get('IMAGEW', 0); hh = h.get('IMAGEH', 0)
        fov_w = w * scale / 3600
        print(f"{base}: RA={h['CRVAL1']:.2f} Dec={h['CRVAL2']:.2f} FOV={fov_w:.1f}deg scale={scale:.1f}\"/px")
    except Exception as e:
        print(f"{base}: ERR {e}")
