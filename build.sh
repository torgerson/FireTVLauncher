#!/bin/bash
# Builds out/TorgLauncher.apk without Gradle or Android Studio.
# Needs: JDK 17, Android SDK build-tools 34.0.0 and platform android-28 (see README).
set -euo pipefail
cd "$(dirname "$0")"

# Override any of these with environment variables if your setup differs.
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/${BUILD_TOOLS:-34.0.0}"
JAR="$SDK/platforms/android-28/android.jar"
OUT=out

for f in "$BT/aapt2" "$BT/d8" "$JAR"; do
  [ -e "$f" ] || { echo "Missing $f — install the Android SDK pieces listed in README.md"; exit 1; }
done
rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/classes"

# App icon + Fire TV banner are committed. To regenerate them from your own wallpaper,
# delete app/res/drawable/*.png and run again (macOS `sips` required).
WP=wallpaper/torg-shop-wallpaper.png
if [ ! -f app/res/drawable/banner.png ] && [ -f "$WP" ]; then
  mkdir -p app/res/drawable
  sips -s format png --cropOffset 120 200 -c 480 853 "$WP" --out "$OUT/banner_src.png" >/dev/null
  sips -z 180 320 "$OUT/banner_src.png" --out app/res/drawable/banner.png >/dev/null
  sips -s format png --cropOffset 140 230 -c 460 460 "$WP" --out "$OUT/icon_src.png" >/dev/null
  sips -z 192 192 "$OUT/icon_src.png" --out app/res/drawable/icon.png >/dev/null
fi

"$BT/aapt2" compile --dir app/res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/unsigned.apk" -I "$JAR" --manifest app/AndroidManifest.xml \
  --java "$OUT/gen" "$OUT/res.zip" --min-sdk-version 22 --target-sdk-version 28
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 -bootclasspath "$JAR" -classpath "$JAR:$BT/core-lambda-stubs.jar" \
  -d "$OUT/classes" $(find app/src "$OUT/gen" -name '*.java')
"$BT/d8" --min-api 22 --lib "$JAR" --output "$OUT" $(find "$OUT/classes" -name '*.class')
(cd "$OUT" && zip -q -j unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

# Signing key: created once in keystore/ (git-ignored). Keep it — updates must be signed with the same key.
mkdir -p keystore
if [ ! -f keystore/torg.jks ]; then
  PASS=$(openssl rand -hex 16)
  echo "$PASS" > keystore/password.txt
  keytool -genkeypair -keystore keystore/torg.jks -storepass "$PASS" -keypass "$PASS" -alias torg \
    -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=FireTV Launcher" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks keystore/torg.jks --ks-pass file:keystore/password.txt --ks-key-alias torg \
  --out "$OUT/TorgLauncher.apk" "$OUT/aligned.apk"
ls -l "$OUT/TorgLauncher.apk"
