# -*- coding: utf-8 -*-
"""nova.astrometry.net BFS 采集 50 张真实定标星空图"""
import json, os, re, subprocess, time, urllib.request, urllib.parse, http.cookiejar

cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
opener.addheaders = [('User-Agent', 'StarCam-validator/1.0'),
                     ('Referer', 'https://nova.astrometry.net/api/login')]
def api(service, args):
    body = urllib.parse.urlencode({'request-json': json.dumps(args)}).encode()
    req = urllib.request.Request('https://nova.astrometry.net/api/' + service, data=body)
    return json.loads(opener.open(req, timeout=60).read().decode())
def getp(url):
    for att in range(4):
        try:
            return opener.open(url, timeout=120).read().decode(errors='ignore')
        except Exception as e:
            if att == 3:
                raise
            time.sleep(6)

session = api('login', {'apikey': 'akpwurwpjzwxmjcz'})['session']
# 导出 cookie 给 curl
os.makedirs('/tmp/nova50/img', exist_ok=True)
with open('/tmp/nova50/cookies.txt', 'w') as f:
    f.write('# Netscape HTTP Cookie File\n')
    for c in cj:
        f.write('\t'.join([c.domain, 'TRUE' if c.domain.startswith('.') else 'FALSE', c.path,
                           'TRUE' if c.secure else 'FALSE', '0', c.name, c.value]) + '\n')
print('session ok', flush=True)

def curl_img(url, out):
    for att in range(3):
        r = subprocess.run(['curl', '-sL', '--max-time', '300', '-C', '-',
                            '--cookie', '/tmp/nova50/cookies.txt',
                            '-H', 'Referer: https://nova.astrometry.net/api/login',
                            '-A', 'StarCam-validator/1.0', '-o', out, url],
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if os.path.exists(out) and os.path.getsize(out) > 50000:
            try:
                from PIL import Image
                im = Image.open(out); im.load()
                return True
            except Exception:
                os.remove(out)
        time.sleep(4)
    return False

seen = set()
queue = []
seed = ['16192984','16192980','16192976','16192962','16192958','16192951','16192948','16192944',
        '16192456','16192454','16192445','16192407','16192403','16192401','16192400','16192396',
        '16192395','16192390','16192385','16192384','16192988','16192994','16192991','16192990',
        '16131449','16155337']
queue = seed[:]
OUT = '/tmp/nova50'
info = {}
if os.path.exists(OUT + '/info.json'):
    info = json.load(open(OUT + '/info.json'))

TARGET = 50
while queue and len(info) < TARGET:
    sid = queue.pop(0)
    if sid in seen: continue
    seen.add(sid)
    if sid in info: continue
    try:
        h = getp('https://nova.astrometry.net/user_images/%s' % sid)
        # 侧栏更多提交（扩池）
        for other in re.findall(r'/user_images/(\d+)', h):
            if other not in seen and other not in queue:
                queue.append(other)
        mj = re.search(r'/annotated_full/(\d+)', h)
        mf = re.search(r'href="/image/(\d+)\?filename=([^"]+)"', h)
        if not mj or not mf: continue
        job, fid = mj.group(1), mf.group(1)
        msz = re.search(r'Size:</td><td>([\d.]+) x ([\d.]+) deg', h)
        msc = re.search(r'Pixel scale:</td><td>([\d.]+) arcsec/pixel', h)
        mrc = re.search(r'ra=([\d.]+)&amp;dec=([-\d.]+)', h)
        if not (msz and msc and mrc): continue
        fovx, fovy = float(msz.group(1)), float(msz.group(2))
        if fovx < 5 or fovx > 100: continue
        outj = '%s/img/%s.jpg' % (OUT, sid)
        ok = True
        if os.path.exists(outj):
            try:
                from PIL import Image
                im2 = Image.open(outj); im2.load()
            except Exception:
                os.remove(outj)
                ok = False
        if ok and not os.path.exists(outj):
            ok = curl_img('https://nova.astrometry.net/image/%s?filename=%s' % (fid, mf.group(2)), outj)
        if not ok:
            print('DL-FAIL', sid, flush=True); continue
        try:
            wc = opener.open('https://nova.astrometry.net/wcs_file/%s' % job, timeout=60).read()
            open('%s/img/%s.wcs' % (OUT, sid), 'wb').write(wc)
        except Exception:
            pass
        info[sid] = {'job': job, 'file': fid, 'fovx': fovx, 'fovy': fovy,
                     'scale': float(msc.group(1)), 'ra0': float(mrc.group(1)), 'dec0': float(mrc.group(2))}
        json.dump(info, open(OUT + '/info.json', 'w'), indent=1)
        print('OK %s fov=%.1fx%.1f scale=%.2f c=(%.1f,%.1f) [%d/%d] q=%d' % (
            sid, fovx, fovy, info[sid]['scale'], info[sid]['ra0'], info[sid]['dec0'],
            len(info), TARGET, len(queue)), flush=True)
    except Exception as e:
        print('ERR', sid, str(e)[:80], flush=True)
        time.sleep(3.0)
    time.sleep(2.0)
print('DONE', len(info), flush=True)
