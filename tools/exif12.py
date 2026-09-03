# 读取 photos12 EXIF：焦距/35mm 等效焦距 → 每张照片的实际视场
import glob, os
from PIL import Image
from PIL.ExifTags import TAGS

files = sorted(glob.glob(r'C:\starcam-bundle\testdata\photos12\*.jpg'))
print('found', len(files), 'jpgs')
for p in files:
    im = Image.open(p)
    ex = im.getexif()
    d = {TAGS.get(k, k): v for k, v in ex.items()}
    focal = d.get('FocalLength')
    f35 = d.get('FocalLengthIn35mmFilm')
    model = d.get('Model', '')
    datetime = d.get('DateTime', '')
    # 视场估算：对角 43.3mm（35mm 全幅），sensor 尺寸按 35mm 等效换算
    fov = None
    if f35:
        import math
        diag = 2 * math.degrees(math.atan(21.6 / f35))
        fov = round(diag, 1)
    print(os.path.basename(p)[-8:], im.size, 'focal=%s f35=%s diagFov=%s' % (focal, f35, fov), model, datetime)
