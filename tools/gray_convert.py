# jpg → .gray（int32 w,h + float32 数组，AutoTest/测试管线输入格式）
import sys, numpy as np
from PIL import Image
src, dst = sys.argv[1], sys.argv[2]
im = Image.open(src).convert('L')
w, h = im.size
a = np.asarray(im, dtype='<f4')
with open(dst, 'wb') as f:
    f.write(np.int32(w).tobytes()); f.write(np.int32(h).tobytes()); f.write(a.tobytes())
print(dst, w, 'x', h)
