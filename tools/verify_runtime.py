#!/usr/bin/env python3
"""
Checks the bundled Java runtimes (app/src/main/assets/runtime/jre<N>.tar.gz) without a phone:
every native file must be an aarch64 ELF built for Android (bionic), the core libraries and the files Minecraft
needs (modules, certificates, time zones, security config) must be there, and the version must match.

Usage: verify_runtime.py [8 17 21 25]      exit code 1 if anything is wrong
"""
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "app", "src", "main", "assets", "runtime")
# libc++_shared.so is not inside the runtimes: the app ships it (app/src/main/jniLibs/arm64-v8a, copied from the NDK in CI)
ANDROID_SYSTEM = {"libc++_shared.so", "libc.so", "libm.so", "libdl.so", "liblog.so", "libz.so", "libandroid.so", "libstdc++.so",
                  "libEGL.so", "libGLESv2.so", "libGLESv3.so", "libOpenSLES.so", "libjnigraphics.so"}
CORE_LIBS = ["libjvm.so", "libjava.so", "libjli.so", "libzip.so", "libnio.so", "libnet.so", "libverify.so",
             "libawt.so", "libawt_headless.so", "libfontmanager.so", "libfreetype.so"]


def e_machine(path):
    with open(path, "rb") as f:
        h = f.read(20)
    if h[:4] != b"\x7fELF":
        return None
    return int.from_bytes(h[18:20], "little"), h[4]          # machine, class (2 = 64-bit)


def needed(path):
    out = subprocess.run(["readelf", "-d", path], capture_output=True, text=True).stdout
    return re.findall(r"\(NEEDED\)\s+Shared library: \[(.+?)\]", out)


def check(major, archive):
    problems = []
    info = []
    tmp = tempfile.mkdtemp(prefix="verify%s_" % major)
    try:
        with tarfile.open(archive) as t:
            t.extractall(tmp)
        files = [os.path.join(dp, f) for dp, _, fs in os.walk(tmp) for f in fs]
        so = [f for f in files if f.endswith(".so")]
        libs = {os.path.basename(f): f for f in so}
        # 1) every .so is a 64-bit aarch64 ELF; no glibc dependency; every dependency is inside the runtime or Android
        internal = set(libs)
        for f in so:
            em = e_machine(f)
            rel = os.path.relpath(f, tmp)
            if em is None:
                problems.append("not an ELF: " + rel)
                continue
            if em != (183, 2):
                problems.append("not aarch64-64bit: %s (machine %s)" % (rel, em))
            for n in needed(f):
                if n in ("libc.so.6", "libm.so.6", "libpthread.so.0", "libdl.so.2") or n.startswith("ld-linux"):
                    problems.append("glibc dependency %s in %s" % (n, rel))
                elif n not in internal and n not in ANDROID_SYSTEM:
                    problems.append("missing dependency %s needed by %s" % (n, rel))
        info.append("%d native libraries" % len(so))
        # 2) core libraries
        for c in CORE_LIBS:
            if c not in libs:
                problems.append("core library missing: " + c)
        if "libjvm.so" in libs:
            rel = os.path.relpath(libs["libjvm.so"], tmp)
            want = "lib/aarch64/server/libjvm.so" if major == 8 else "lib/server/libjvm.so"
            if rel != want:
                problems.append("libjvm.so at %s, engine expects %s" % (rel, want))
            info.append("libjvm %d MB" % (os.path.getsize(libs["libjvm.so"]) // 2**20))
        # 3) files Minecraft needs
        must = ["lib/security/cacerts", "release"]
        must += ["lib/rt.jar", "lib/tzdb.dat", "lib/security/java.security"] if major == 8 \
            else ["lib/modules", "lib/tzdb.dat", "conf/security/java.security"]
        for m in must:
            if not os.path.isfile(os.path.join(tmp, m)):
                problems.append("file missing: " + m)
        if os.path.isfile(os.path.join(tmp, "lib/security/cacerts")):
            info.append("cacerts %d KB" % (os.path.getsize(os.path.join(tmp, "lib/security/cacerts")) // 1024))
        # 3b) Java 8: the Pack200 jars must have been unpacked into real, readable jars
        if major == 8:
            for j in ["lib/rt.jar", "lib/jsse.jar", "lib/jce.jar", "lib/charsets.jar", "lib/resources.jar", "lib/ext/sunjce_provider.jar"]:
                jp = os.path.join(tmp, j)
                try:
                    with zipfile.ZipFile(jp) as z:
                        names = z.namelist()
                        if j == "lib/rt.jar" and ("java/lang/Object.class" not in names or len(names) < 15000):
                            problems.append("rt.jar is incomplete (%d entries)" % len(names))
                except Exception as e:
                    problems.append("%s is not a valid jar: %s" % (j, e))
            if any(f.endswith(".pack") for f in files):
                problems.append("still contains .pack files")
        # 3c) Java 9+: list the module image with the JDK's own tool, when it is installed
        elif shutil.which("jimage"):
            r = subprocess.run(["jimage", "list", os.path.join(tmp, "lib/modules")], capture_output=True, text=True)
            if "java/lang/Object.class" not in r.stdout or r.stdout.count("Module:") < 20:
                problems.append("lib/modules does not look like a complete module image")
            else:
                info.append("%d modules" % r.stdout.count("Module:"))
        # 4) version
        rel = os.path.join(tmp, "release")
        if os.path.isfile(rel):
            txt = open(rel, errors="replace").read()
            m = re.search(r'JAVA_VERSION="([^"]+)"', txt)
            ver = m.group(1) if m else "?"
            first = ver.split(".")[1] if ver.startswith("1.") else ver.split(".")[0].split("-")[0]
            info.append("version " + ver)
            if first != str(major):
                problems.append("release says Java %s but this is jre%d" % (ver, major))
        # 5) no dangling/odd entries
        for f in files:
            if os.path.islink(f) and not os.path.exists(f):
                problems.append("dangling link " + os.path.relpath(f, tmp))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    return info, problems


def main():
    versions = [int(a) for a in sys.argv[1:] if a.isdigit()] or [8, 17, 21, 25]
    bad = 0
    cxx = os.path.join(HERE, "..", "app", "src", "main", "jniLibs", "arm64-v8a", "libc++_shared.so")
    if os.path.isfile(cxx) and e_machine(cxx) == (183, 2):
        print("libc++_shared.so  OK  [aarch64, shipped with the app]")
    else:
        print("libc++_shared.so  MISSING or wrong architecture (needed by Java 25)")
        bad += 1
    for v in versions:
        archive = os.path.join(ASSETS, "jre%d.tar.gz" % v)
        if not os.path.isfile(archive):
            print("Java %-2d  MISSING  (%s)" % (v, archive))
            bad += 1
            continue
        info, problems = check(v, archive)
        size = os.path.getsize(archive) // 2**20
        print("Java %-2d  %s  [%d MB archive; %s]" % (v, "OK" if not problems else "PROBLEMS", size, "; ".join(info)))
        for p in problems:
            print("          - " + p)
        bad += 1 if problems else 0
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
