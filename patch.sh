#!/usr/bin/env bash
# Adds the Azux launcher UI on top of the PojavLauncher engine (LGPL-3.0).
set -euo pipefail
SRC="${1:-src}"
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$SRC/app_pojavlauncher"
MAIN="$APP/src/main"

python3 - "$MAIN" "$APP" "$HERE" <<'PY'
import re, sys, shutil
main, app, here = sys.argv[1:4]

def edit(path, fn):
    s = open(path, encoding="utf-8").read()
    s2 = fn(s)
    if s2 == s:
        sys.exit("PATCH FAILED (no change): " + path)
    open(path, "w", encoding="utf-8").write(s2)

# 1) Azux activity
shutil.copy(f"{here}/overlay/net/kdt/pojavlaunch/AzuxActivity.java", f"{main}/java/net/kdt/pojavlaunch/AzuxActivity.java")

# 2) Entry point: storage check -> Azux screen instead of the stock launcher screen
edit(f"{main}/java/net/kdt/pojavlaunch/TestStorageActivity.java",
     lambda s: s.replace("new Intent(this, LauncherActivity.class)", "new Intent(this, AzuxActivity.class)", 1))

# 3) Manifest: register the activity
decl = '''<activity
            android:name=".AzuxActivity"
            android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden|keyboard|navigation|uiMode"
            android:exported="false"
            android:label="Azux Launcher"
            android:screenOrientation="sensorLandscape"
            android:windowSoftInputMode="adjustResize" />
        '''
edit(f"{main}/AndroidManifest.xml",
     lambda s: re.sub(r'(<activity\s+android:name="\.LauncherActivity")', lambda m: decl + m.group(1), s, count=1))

# 4) Names
def names(s):
    s = re.sub(r'(<string name="app_name">)[^<]*(</string>)', r'\1Azux Launcher\2', s, count=1)
    s = re.sub(r'(<string name="app_short_name">)[^<]*(</string>)', r'\1Azux\2', s, count=1)
    return s
edit(f"{main}/res/values/strings.xml", names)

# 5) arm64 only (faster build, smaller APK)
edit(f"{app}/build.gradle",
     lambda s: s.replace("multiDexEnabled true //important", "multiDexEnabled true //important\n        ndk { abiFilters 'arm64-v8a' }", 1))

# 6) Icon
shutil.copy(f"{here}/branding/azux_foreground.xml", f"{main}/res/drawable/azux_foreground.xml")
open(f"{main}/res/drawable/azux_icon_bg.xml", "w").write('''<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="#080808"/>
</shape>
''')
for n in ("ic_launcher", "ic_launcher_round"):
    open(f"{main}/res/mipmap-anydpi-v26/{n}.xml", "w").write('''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/azux_icon_bg"/>
    <foreground android:drawable="@drawable/azux_foreground"/>
</adaptive-icon>
''')
# 7) Version label (non-fatal)
import re as _re
_g = f"{app}/build.gradle"
_t = open(_g, encoding="utf-8").read()
_t2 = _re.sub(r'versionName\s+"[^"]*"', 'versionName "azux-v6"', _t, count=1)
if _t2 != _t:
    open(_g, "w", encoding="utf-8").write(_t2)
print("Azux patch applied.")
PY
