#!/usr/bin/env python3
"""
Packs the Java runtimes (8, 17, 21, 25) that are bundled inside the Azux APK.

Source: Android (bionic, aarch64) builds of OpenJDK that the open-source Zalith launcher keeps in its repository
(OpenJDK = GPLv2 + Classpath Exception). Only the runtime archives are downloaded at CI time and pinned to one
commit; none of that launcher's code is used. Each runtime comes as two archives:
  universal.tar.xz   architecture independent files (modules, conf, jars)
  bin-arm64.tar.xz   native libraries and binaries for aarch64
They are merged and re-packed as app/src/main/assets/runtime/jre<N>.tar.gz (the app unpacks .tar.gz itself).

Usage: fetch_runtime.py 8 17 21 25
       fetch_runtime.py --selftest
"""
import glob
import io
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request

COMMIT = "ed8a5c873b25f19d08aef5bdfc1084fd9aff836c"
BASE = ("https://raw.githubusercontent.com/ZalithLauncher/ZalithLauncher/%s/"
        "ZalithLauncher/src/main/assets/components/jre-%%s/%%s.tar.xz" % COMMIT)
PARTS = ("universal", "bin-arm64")
# libjvm.so location per layout: Java 8 keeps libraries in lib/aarch64/, Java 9+ in lib/
LIBJVM = {"8": "lib/aarch64/server/libjvm.so", "17": "lib/server/libjvm.so", "21": "lib/server/libjvm.so", "25": "lib/server/libjvm.so"}


def fetch(url, tries=3):
    last = None
    for _ in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "azux-ci"})
            with urllib.request.urlopen(req, timeout=180) as r:
                return r.read()
        except Exception as e:  # retry
            last = e
    raise RuntimeError("download failed: %s (%s)" % (url, last))


def find_unpack200():
    """Java 8 ships its jars as Pack200 (.jar.pack). Unpack them with the host's Java 8 tool."""
    for cand in [shutil.which("unpack200")] + glob.glob("/usr/lib/jvm/*8*/jre/bin/unpack200") \
            + glob.glob(os.path.join(os.environ.get("JAVA_HOME_8_X64", "/nonexistent"), "jre", "bin", "unpack200")) \
            + glob.glob(os.path.join(os.environ.get("JAVA_HOME_8_X64", "/nonexistent"), "bin", "unpack200")):
        if cand and os.path.isfile(cand):
            return cand
    return None


def unpack_packs(stage):
    packs = [os.path.join(dp, f) for dp, _, fs in os.walk(stage) for f in fs if f.endswith(".pack")]
    if not packs:
        return 0
    tool = find_unpack200()
    if not tool:
        raise RuntimeError("Java 8 needs unpack200 to unpack its .jar.pack files (install openjdk-8-jre-headless)")
    for pk in packs:
        jar = pk[:-len(".pack")]
        subprocess.run([tool, pk, jar], check=True)
        os.remove(pk)
    return len(packs)


def build(major, assets_dir):
    work = tempfile.mkdtemp(prefix="jre%s_" % major)
    stage = os.path.join(work, "jre")
    os.makedirs(stage)
    for part in PARTS:
        url = BASE % (major, part)
        print("[%s] downloading %s" % (major, url.split("/components/")[1]))
        data = fetch(url)
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:xz") as t:
            for m in t.getmembers():       # refuse anything that would escape the folder
                if m.name.startswith("/") or ".." in m.name.split("/"):
                    raise RuntimeError("unsafe path in archive: " + m.name)
            t.extractall(stage)
    n = unpack_packs(stage)
    if n:
        print("[%s] unpacked %d Pack200 jars" % (major, n))
    libjvm = os.path.join(stage, LIBJVM[major])
    if not os.path.isfile(libjvm):
        raise RuntimeError("[%s] %s missing after unpacking" % (major, LIBJVM[major]))
    with open(libjvm, "rb") as f:
        if f.read(4) != b"\x7fELF":
            raise RuntimeError("[%s] libjvm.so is not an ELF file" % major)
    os.makedirs(assets_dir, exist_ok=True)
    out = os.path.join(assets_dir, "jre%s.tar.gz" % major)
    with tarfile.open(out, "w:gz", format=tarfile.GNU_FORMAT, compresslevel=9, dereference=True) as t:
        for dp, dn, fn in os.walk(stage):
            for name in sorted(dn + fn):
                p = os.path.join(dp, name)
                t.add(p, arcname=os.path.relpath(p, stage), recursive=False)
    print("[%s] wrote %s (%d MB, %d MB unpacked)" % (major, out, os.path.getsize(out) // 2**20,
          sum(os.path.getsize(os.path.join(dp, f)) for dp, _, fs in os.walk(stage) for f in fs) // 2**20))
    shutil.rmtree(work, ignore_errors=True)


def selftest():
    work = tempfile.mkdtemp()
    stage = os.path.join(work, "s")
    os.makedirs(os.path.join(stage, "lib", "server"))
    with open(os.path.join(stage, "lib", "server", "libjvm.so"), "wb") as f:
        f.write(b"\x7fELF" + b"\0" * 60)
    os.symlink("server/libjvm.so", os.path.join(stage, "lib", "link.so"))
    out = os.path.join(work, "o.tar.gz")
    with tarfile.open(out, "w:gz", format=tarfile.GNU_FORMAT, dereference=True) as t:
        for dp, dn, fn in os.walk(stage):
            for name in sorted(dn + fn):
                p = os.path.join(dp, name)
                t.add(p, arcname=os.path.relpath(p, stage), recursive=False)
    names = tarfile.open(out).getnames()
    assert "lib/link.so" in names and "lib/server/libjvm.so" in names, names
    assert tarfile.open(out).getmember("lib/link.so").isfile()   # symlink was dereferenced into a real file
    assert (BASE % ("17", "universal")).endswith("jre-17/universal.tar.xz")
    print("selftest ok")


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        selftest()
        sys.exit(0)
    versions = [a for a in sys.argv[1:] if a.isdigit()] or ["8", "17", "21", "25"]
    here = os.path.dirname(os.path.abspath(__file__))
    assets = os.path.join(here, "..", "app", "src", "main", "assets", "runtime")
    failed = 0
    for v in versions:
        try:
            build(v, assets)
        except Exception as e:
            print("!! Java %s failed: %s" % (v, e))
            failed += 1
    sys.exit(1 if failed else 0)
