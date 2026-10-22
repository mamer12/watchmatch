#!/usr/bin/env bash
# Builds WatchMePlus.apk (package io.watchmeplus): the same code and resources as WatchMatch under another id.
set -euo pipefail
cd "$(dirname "$0")"

die() { echo "Error: $*" >&2; exit 1; }

./build.sh >/dev/null

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  for d in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" \
           /opt/homebrew/share/android-commandlinetools \
           /usr/local/share/android-commandlinetools /usr/lib/android-sdk; do
    if [ -d "$d/build-tools" ]; then SDK="$d"; break; fi
  done
fi
[ -n "$SDK" ] && [ -d "$SDK/build-tools" ] || die "Android SDK not found. Set ANDROID_HOME."
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -n 1)"
AJ=$(ls "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -n 1 || true)
[ -n "$AJ" ] || die "no android.jar. Run: sdkmanager \"platforms;android-34\""

# First link only produces the resource id map; the second reuses it so ids match the compiled dex.
"$BT/aapt2" link -o out/wmp-ids.apk -I "$AJ" --manifest app/AndroidManifest.xml out/res.zip \
  --min-sdk-version 28 --target-sdk-version 33 --emit-ids out/ids.txt
"$BT/aapt2" link -o out/wmp.apk -I "$AJ" --manifest app/watchmeplus/AndroidManifest.xml out/res.zip \
  --stable-ids out/ids.txt --rename-manifest-package io.watchmeplus \
  --min-sdk-version 28 --target-sdk-version 33 -A app/assets -0 ttf
zip -qj out/wmp.apk out/classes.dex
"$BT/zipalign" -f -p 4 out/wmp.apk out/wmp-aligned.apk
"$BT/apksigner" sign --ks watchmatch.keystore --ks-pass pass:watchmatch --key-pass pass:watchmatch \
  --out WatchMePlus.apk out/wmp-aligned.apk 2> >(grep -Ev "^(WARNING:|$)" >&2)
echo "Built WatchMePlus.apk ($(du -h WatchMePlus.apk | cut -f1)). Install: adb install -r WatchMePlus.apk"
