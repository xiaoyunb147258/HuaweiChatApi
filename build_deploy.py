#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""上传项目 → 等编译 → 下 APK（纯标准库）"""
import os, sys, json, time, base64, urllib.request, urllib.error, zipfile, subprocess

TOKEN = os.environ.get("GH_TOKEN", "")
OWNER = "xiaoyunb147258"
REPO = "HuaweiChatApi"
PROJECT_DIR = "/tmp/hw2"
OUT_DIR = "/var/minis/attachments"
SKIP = {'.git', 'build', '.gradle', '.idea', 'run.sh', 'dl.py', 'build.log'}

API = "https://api.github.com"
HDR = {"Authorization": f"Bearer {TOKEN}", "Accept": "application/vnd.github+json",
       "User-Agent": "auto-build"}


def req(method, url, body=None, raw=False, timeout=60):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(url, data=data, method=method, headers=HDR)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            c = resp.read()
            return resp.status, (c if raw else (json.loads(c) if c else {}))
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:400]
    except Exception as e:
        return -1, str(e)[:200]


def log(m):
    print(m, flush=True)


log("① token / 仓库")
st, d = req("GET", f"{API}/user")
if st != 200:
    log(f"   ❌ {d}"); sys.exit(1)
log(f"   ✅ {d.get('login')}")
req("POST", f"{API}/user/repos", {"name": REPO, "private": False, "auto_init": False})

log("② 上传文件（API，增量）")
CAPI = f"{API}/repos/{OWNER}/{REPO}/contents/"
files = []
for dp, dns, fns in os.walk(PROJECT_DIR):
    dns[:] = [x for x in dns if x not in SKIP]
    for fn in fns:
        rel = os.path.relpath(os.path.join(dp, fn), PROJECT_DIR).replace(os.sep, "/")
        if not rel.startswith(".git") and rel not in SKIP:
            files.append(rel)

log(f"   共 {len(files)} 个文件")
ok = 0
for i, rel in enumerate(sorted(files), 1):
    url = CAPI + rel
    sha = None
    st, d = req("GET", url)
    if st == 200 and isinstance(d, dict):
        sha = d.get("sha")
    with open(os.path.join(PROJECT_DIR, rel), "rb") as f:
        content = base64.b64encode(f.read()).decode()
    body = {"message": f"up {rel}", "content": content}
    if sha:
        body["sha"] = sha
    st, d = req("PUT", url, body)
    if st in (200, 201):
        ok += 1
        log(f"   [{i}/{len(files)}] ✅ {rel}")
    else:
        log(f"   [{i}/{len(files)}] ❌ {rel} {d}")
    time.sleep(0.35)
log(f"   上传 {ok}/{len(files)}")

log("③ 等编译（最多 20 分钟）")
rid = None
for i in range(40):
    time.sleep(30)
    st, d = req("GET", f"{API}/repos/{OWNER}/{REPO}/actions/runs?per_page=1")
    runs = d.get("workflow_runs", []) if isinstance(d, dict) else []
    if not runs:
        log("   ⏳ 未触发"); continue
    r = runs[0]
    log(f"   ⏳ [{i}] {r['status']} / {r.get('conclusion')}")
    if r["status"] == "completed":
        rid = r["id"]
        if r.get("conclusion") == "success":
            log("   ✅ 编译成功")
        else:
            log(f"   ❌ 失败 {r.get('conclusion')}"); sys.exit(2)
        break
if not rid:
    log("   ❌ 超时"); sys.exit(3)

log("④ 下载 APK")
st, d = req("GET", f"{API}/repos/{OWNER}/{REPO}/actions/runs/{rid}/artifacts")
arts = d.get("artifacts", []) if isinstance(d, dict) else []
if not arts:
    log("   ❌ 无产物"); sys.exit(4)
tgt = arts[0]
zp = "/tmp/_apk.zip"
p = subprocess.run(["curl", "-sSL", "-o", zp,
                    "-H", f"Authorization: Bearer {TOKEN}",
                    "-H", "Accept: application/vnd.github+json",
                    tgt["archive_download_url"]], capture_output=True, text=True)
if not os.path.exists(zp) or os.path.getsize(zp) < 5000:
    log(f"   ❌ 下载失败 {p.stderr[:200]}"); sys.exit(5)
os.makedirs(OUT_DIR, exist_ok=True)
with zipfile.ZipFile(zp) as z:
    log(f"   包内 {z.namelist()}")
    z.extractall("/tmp/_apkx")
os.remove(zp)
apk = None
for dp, _, fns in os.walk("/tmp/_apkx"):
    for f in fns:
        if f.endswith(".apk"):
            apk = os.path.join(dp, f)
if not apk:
    log("   ❌ 未找到 apk"); sys.exit(6)
dst = os.path.join(OUT_DIR, "MaDaoGateway-debug.apk")
os.replace(apk, dst)
log("")
log("=" * 46)
log(f"🎉 {dst}")
log(f"   大小 {os.path.getsize(dst)}")
log("=" * 46)
