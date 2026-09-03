# 把 12 张真机照片中"能识别出来"的 8 张压缩为 APK assets（离线演示用）
# 规格：2200px 长边 JPEG q85（与 App decodeSampledBitmap(2200) 对齐，约 4MB 总量）
import os
from PIL import Image

SRC = r'C:\starcam-bundle\testdata\photos12'
DST = r'C:\starcam-bundle\code\app\src\main\assets\offline_photos'
# 回归台（JVM 8 真解）验证名单：4974/4984/4998/5040/5049/5057/5068/5076
IDS = ['4974', '4984', '4998', '5040', '5049', '5057', '5068', '5076']
LONG_EDGE = 2200
QUALITY = 85

os.makedirs(DST, exist_ok=True)
total = 0
for i in IDS:
    p = os.path.join(SRC, f'178767019{i}.jpg')
    im = Image.open(p).convert('RGB')
    w, h = im.size
    scale = LONG_EDGE / max(w, h)
    if scale < 1:
        im = im.resize((round(w * scale), round(h * scale)), Image.LANCZOS)
    out = os.path.join(DST, f'photo{i}.jpg')
    im.save(out, 'JPEG', quality=QUALITY)
    sz = os.path.getsize(out)
    total += sz
    print(f'{out}  {im.size}  {sz // 1024} KB')
print(f'TOTAL {total // 1024} KB')
