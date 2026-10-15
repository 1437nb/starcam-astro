#!/usr/bin/env python3
# 生成 RAW 支持的真机验证素材（结构真实的合成容器）。
#
# 为什么用合成容器：仓库里没有真实相机 RAW（体积 + 版权），联网抓样本又常被挡
# （raw.pixls.us 403；exiftool 的样本是裁剪过的元数据文件，不含预览图）。
# 但 RawPreview 的解码路径**不依赖厂商差异**的部分（TIFF IFD 走查 / SubIFD /
# 条带拼接 / 兜底 SOI…EOI 扫描）都可以用"结构相同的容器 + 真实 JPEG 预览"完整覆盖 ——
# 容器结构与真机文件一致（TIFF 头、IFD 条目、RATIONAL 等），预览用的是仓库自带的
# 公开演示照片（可解出、无隐私）。
#
# 不做的事：不模拟厂商私有布局（MakerNote 里的预览指针、CR3 的 ISO-BMFF、
# HEIF 压缩预览）—— 那些只能拿真机 RAW 验，见 README。
#
# 产出（写到 <输出目录>）：
#   probe-ifd0-preview.dng     小端 TIFF，IFD0 的 JPEGInterchangeFormat 指预览（CR2/ARW/NEF 常见）
#   probe-subifd-preview.cr2   大端 TIFF，预览在 SubIFD 里（部分机型）
#   probe-strip-preview.nef    小端 TIFF，Compression=7 + StripOffsets/ByteCounts（条带式）
#   probe-fallback-scan.rw2    非 TIFF：裸 JPEG 改名（走兜底扫描路径）
#   probe-no-preview.orf       合法 TIFF 但没有任何预览线索（期望：干净失败，不崩）
import struct, sys, os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir)
PREVIEW = os.path.join(ROOT, "code/app/src/main/assets/offline_photos/photo5031.jpg")

def ifd(entries, data_start, le=True):
    """entries: [(tag, type, count, payload_bytes | inline_int)] → (ifd_bytes, data_bytes)"""
    fmt = "<" if le else ">"
    head = struct.pack(fmt + "H", len(entries))
    body, data = b"", b""
    for tag, typ, count, payload in entries:
        if isinstance(payload, int):
            val = struct.pack(fmt + "I", payload)
        elif len(payload) <= 4:
            val = payload + b"\x00" * (4 - len(payload))
        else:
            off = data_start + len(data)
            val = struct.pack(fmt + "I", off)
            data += payload
        body += struct.pack(fmt + "HHI", tag, typ, count) + val
    return head + body + struct.pack(fmt + "I", 0), data

def tiff(preview, mode):
    """按 mode 组装容器；返回完整文件字节。"""
    le = mode != "subifd"           # subifd 用大端，其余小端（覆盖两种字节序）
    fmt = "<" if le else ">"
    hdr_size, w, h = 8, 1200, 800
    if mode == "ifd0":
        ifd0_size = 2 + 4 * 12 + 4
        prev_off = hdr_size + ifd0_size
        e = [(0x0100, 4, 1, w), (0x0101, 4, 1, h),
             (0x0201, 4, 1, prev_off), (0x0202, 4, 1, len(preview))]
        ifd0, _ = ifd(e, 0, le)
        return (b"II" if le else b"MM") + struct.pack(fmt + "H", 42) + struct.pack(fmt + "I", 8) + ifd0 + preview
    if mode == "subifd":
        ifd0_size = 2 + 3 * 12 + 4
        sub_off = hdr_size + ifd0_size
        sub_size = 2 + 3 * 12 + 4
        prev_off = sub_off + sub_size
        ifd0, _ = ifd([(0x0100, 4, 1, w), (0x0101, 4, 1, h), (0x014A, 4, 1, sub_off)], 0, le)
        sub, _ = ifd([(0x0100, 4, 1, w), (0x0101, 4, 1, h),
                      (0x0201, 4, 1, prev_off)], 0, le)
        # SubIFD 内部指针按**绝对偏移**修正：上面的 ifd() 只写相对值，这里重写第三项
        sub = sub[:2 + 2 * 12 + 8] + struct.pack(fmt + "I", prev_off) + sub[2 + 3 * 12:]
        return (b"II" if le else b"MM") + struct.pack(fmt + "H", 42) + struct.pack(fmt + "I", 8) + ifd0 + sub + preview
    if mode == "strip":
        ifd0_size = 2 + 5 * 12 + 4
        prev_off = hdr_size + ifd0_size
        e = [(0x0100, 4, 1, w), (0x0101, 4, 1, h), (0x0103, 3, 1, 7),  # Compression=JPEG
             (0x0111, 4, 1, prev_off), (0x0117, 4, 1, len(preview))]
        ifd0, _ = ifd(e, 0, le)
        return (b"II" if le else b"MM") + struct.pack(fmt + "H", 42) + struct.pack(fmt + "I", 8) + ifd0 + preview
    if mode == "none":     # 合法 TIFF、无任何预览线索 → 期望干净失败
        ifd0, _ = ifd([(0x0100, 4, 1, w), (0x0101, 4, 1, h)], 0, le)
        return (b"II" if le else b"MM") + struct.pack(fmt + "H", 42) + struct.pack(fmt + "I", 8) + ifd0
    raise ValueError(mode)

def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(os.path.abspath(__file__)), os.pardir, "..", "verify-v1576", "raw-test")
    os.makedirs(out, exist_ok=True)
    preview = open(PREVIEW, "rb").read()
    jobs = [
        ("probe-ifd0-preview.dng", "ifd0"),
        ("probe-subifd-preview.cr2", "subifd"),
        ("probe-strip-preview.nef", "strip"),
        ("probe-fallback-scan.rw2", "fallback"),
        ("probe-no-preview.orf", "none"),
    ]
    for name, mode in jobs:
        data = preview if mode == "fallback" else tiff(preview, mode)
        p = os.path.join(out, name)
        open(p, "wb").write(data)
        print("  %-26s %7d bytes  (%s)" % (name, len(data), mode))

if __name__ == "__main__":
    main()
