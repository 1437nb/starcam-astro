#!/usr/bin/env python3
# 生成 §0.105/§0.109 的 EXIF 探针素材（测试夹具 + 真机验证用）：
# 在公开演示图 photo4963.jpg 上注入 GPS EXIF。
#
# 为什么夹具必须**真跑一遍 android 的 EXIF 库**才算数：GPSTimeStamp 在标准 EXIF 里是
# RATIONAL 三连（12/1,5/1,9/1），而 androidx ExifInterface（1.3.7）读出来是**格式化后的
# "12:05:09"** —— 解析器若只认前者，时分秒会被静默当 0（epoch 变当天零点，月亮标注可偏 ~7°）。
# 这个坑正是 §0.109 修掉的，夹具与断言都按"库的真实返回形态"设计。
_ = None  # 生成后请用 ExifPriorsGpsTimeRealFileTest 断言（CI 自动跑）
#   a) GPS 时间 12:05:09（分/秒单数位 → v1.5.75 解析失败），无 DateTimeOriginal
#   b) 对照组：GPS 时间 14:30:15（全两位）+ DateTimeOriginal（真实相机写法）
# 木星在 2024-11 位于 RA 79.2° / Dec +22.3°，离 4963 视场中心（78.7/20.85）仅 1.5°，
# 因此"日月行星标注是否出现"是肉眼可辨的判据（详见 README）。
import struct, sys, os

SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir,
                   "code/app/src/main/assets/offline_photos/photo4963.jpg")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir,
                   "code/app/src/test/resources/exif-probe")

def rational(n, d): return struct.pack("<II", n, d)

def ifd(entries, data_start):
    """entries: list of (tag, type, count, payload_bytes_or_inline_int). 返回 (ifd_bytes, data_bytes, next_free)"""
    # 布局：count(2) + n*12 + next(4)，数据区紧随其后
    head = struct.pack("<H", len(entries))
    body = b""
    data = b""
    for tag, typ, count, payload in entries:
        if isinstance(payload, int):
            val = struct.pack("<I", payload)
        elif len(payload) <= 4:
            val = payload + b"\x00" * (4 - len(payload))
        else:
            off = data_start + len(data)
            val = struct.pack("<I", off)
            data += payload
        body += struct.pack("<HHI", tag, typ, count) + val
    total_here = 2 + len(entries) * 12 + 4
    return head + body + struct.pack("<I", 0), data, total_here

def build_exif(gps_time, date_stamp, with_datetime_original):
    # 先算各 IFD 的偏移：TIFF 头 8 字节 → IFD0 → (ExifIFD) → GPS IFD → 数据区
    DATE = "2024:11:01" if date_stamp is None else date_stamp
    # 先构造 GPS IFD 的条目（含指向数据区的偏移，稍后回填）
    gps_entries_raw = [
        (0x0000, 1, 4, bytes([2, 3, 0, 0])),                    # GPSVersionID
        (0x0001, 2, 2, b"N\x00"),                               # GPSLatitudeRef
        (0x0003, 2, 2, b"E\x00"),                               # GPSLongitudeRef
        (0x0007, 5, 3, rational(0,0)),                          # GPSTimeStamp（占位，回填）
        (0x001D, 2, 11, DATE.encode() + b"\x00"),               # GPSDateStamp
    ]
    # 纬度/经度：39°54'0" N / 116°24'0" E（北京）
    lat = rational(39,1)+rational(54,1)+rational(0,1)
    lon = rational(116,1)+rational(24,1)+rational(0,1)
    gps_entries_raw += [(0x0002, 5, 3, lat), (0x0004, 5, 3, lon)]
    gps_entries_raw.sort(key=lambda e: e[0])
    # 回填 GPS 时间（RATIONAL 三连）
    hh, mm, ss = gps_time
    gps_entries_raw = [(t, ty, c, (rational(hh,1)+rational(mm,1)+rational(ss,1)
                                   if (t == 0x0007) else p)) for (t, ty, c, p) in gps_entries_raw]

    # 计算偏移骨架
    tiff_hdr = 8
    ifd0_n = 2 + (3 if with_datetime_original else 2) * 12 + 4
    ifd0_size = 2 + (3 if with_datetime_original else 2) * 12 + 4
    gps_size = 2 + len(gps_entries_raw) * 12 + 4
    exififd_size = 2 + 1 * 12 + 4 if with_datetime_original else 0
    ifd0_off = tiff_hdr
    exififd_off = ifd0_off + ifd0_size if with_datetime_original else 0
    gps_off = ifd0_off + ifd0_size + exififd_size
    data_off = gps_off + gps_size

    # GPS IFD
    gps_ifd, gps_data, _ = ifd(gps_entries_raw, data_off)
    data_off_after_gps = data_off + len(gps_data)

    # ExifIFD（DateTimeOriginal）
    dto = b"2024:11:05 22:30:15\x00"
    if with_datetime_original:
        exif_ifd, exif_data, _ = ifd([(0x9003, 2, len(dto), dto)], data_off_after_gps)
        data_off_after_exif = data_off_after_gps + len(exif_data)
    else:
        exif_ifd, exif_data, data_off_after_exif = b"", b"", data_off_after_gps

    # IFD0
    ifd0_raw = [
        (0x0112, 3, 1, 1),                          # Orientation = 1
        (0x8825, 4, 1, gps_off),                    # GPSInfoIFDPointer
    ]
    if with_datetime_original:
        ifd0_raw.append((0x8769, 4, 1, exififd_off))  # ExifIFDPointer
    ifd0_raw.sort(key=lambda e: e[0])
    ifd0_bytes, ifd0_data, _ = ifd(ifd0_raw, data_off_after_exif)

    tiff = b"II" + struct.pack("<H", 42) + struct.pack("<I", ifd0_off) + ifd0_data_and_ifds(ifd0_bytes, exif_ifd, gps_ifd) + gps_data + exif_data
    assert tiff_hdr == 8
    return b"Exif\x00\x00" + tiff

def ifd0_data_and_ifds(ifd0_bytes, exif_ifd, gps_ifd):
    # IFD 段按 [IFD0][ExifIFD][GPS IFD] 排列；数据段紧随其后（GPS 数据在前、Exif 数据在后），
    # 与 build_exif 里写入的偏移一致。IFD0 自身无 >4 字节载荷，故其数据段为空。
    return ifd0_bytes + exif_ifd + gps_ifd + b""

def inject(src_jpg, out_jpg, exif_payload):
    raw = open(src_jpg, "rb").read()
    assert raw[0:2] == b"\xff\xd8", "不是 JPEG"
    app1 = b"\xff\xe1" + struct.pack(">H", len(exif_payload) + 2) + exif_payload
    out = raw[0:2] + app1 + raw[2:]
    open(out_jpg, "wb").write(out)
    return out

# a) 探针：单数位 GPS 时间、无 DateTimeOriginal
inject(SRC, os.path.join(OUT, "105-a-single-digit-gpstime.jpg"),
       build_exif((12, 5, 9), "2024:11:01", with_datetime_original=False))
# b) 对照：两位 GPS 时间 + DateTimeOriginal
inject(SRC, os.path.join(OUT, "105-b-two-digit-gpstime.jpg"),
       build_exif((14, 30, 15), "2024:11:05", with_datetime_original=True))
print("生成完成")
for f in ("105-a-single-digit-gpstime.jpg", "105-b-two-digit-gpstime.jpg"):
    p = os.path.join(OUT, f)
    print(" ", f, os.path.getsize(p), "bytes")
