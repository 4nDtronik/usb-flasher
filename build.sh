#!/usr/bin/env bash
# Build USB Flasher tanpa Gradle/Android Studio (Ubuntu/Debian).
# Prasyarat: sudo apt install openjdk-21-jdk-headless aapt apksigner dalvik-exchange libandroid-23-java zipalign
set -euo pipefail
cd "$(dirname "$0")"

ANDROID_JAR=/usr/lib/android-sdk/platforms/android-23/android.jar
DX=/usr/lib/android-sdk/build-tools/debian/dx
OUT=build
APK=USB-Flasher.apk
KS=debug.keystore

rm -rf "$OUT" && mkdir -p "$OUT"

# 1. Kompilasi Java -> .class (target Java 8)
javac --release 8 -cp "$ANDROID_JAR" -d "$OUT" src/id/flasher/usb/*.java

# 2. .class -> classes.dex
"$DX" --dex --output="$OUT/classes.dex" "$OUT"

# 3. Resource + manifest -> APK mentah, lalu tambahkan dex
aapt package -f -M AndroidManifest.xml -S res -I "$ANDROID_JAR" -F "$OUT/unsigned.apk"
(cd "$OUT" && aapt add unsigned.apk classes.dex)

# 4. Align
zipalign -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

# 5. Tanda tangan (buat kunci debug jika belum ada)
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias key -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=USB Flasher"
fi
rm -f "$APK"
apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --out "$APK" "$OUT/aligned.apk"
rm -f "$APK.idsig"
apksigner verify "$APK" && echo "OK: $APK"
