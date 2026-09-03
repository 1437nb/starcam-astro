# photos12 jpg → .gray（2200px 长边，对齐 APP decodeSampledBitmap(2200) 管线）
# 用法: python gray12_convert.py <jpg目录> <输出目录>
import os, sys
import numpy as np
from PIL import Image

LONG_EDGE = 2200  # APP 端 ImageUtils.decodeSampledBitmap(path, 2200) 的目标长边

src_dir, dst_dir = sys.argv[1], sys.argv[2]
os.makedirs(dst_dir, exist_ok=True)
for name in sorted(os.listdir(src_dir)):
    if not name.lower().endswith('.jpg'):
        continue
    im = Image.open(os.path.join(src_dir, name)).convert('L')
    w, h = im.size
    scale = LONG_EDGE / max(w, h)
    if scale < 1.0:
        im = im.resize((round(w * scale), round(h * scale)), Image.LANCZOS)
    w, h = im.size
    a = np.asarray(im, dtype='<f4')
    out = os.path.join(dst_dir, name.rsplit('.', 1)[0] + '.gray')
    with open(out, 'wb') as f:
        f.write(np.int32(w).tobytes())
        f.write(np.int32(h).tobytes())
        f.write(a.tobytes())
    print(out, w, 'x', h)
