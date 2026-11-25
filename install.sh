#!/bin/bash
# Installs (or reinstalls) the launcher on a Fire TV over network ADB.
#   ./install.sh <tv-ip>[:5555] [wallpaper-image] [config.json]
# ADB debugging must be on (Settings → My Fire TV → Developer Options) and you must
# press "Allow" on the TV the first time this computer connects.
set -euo pipefail
cd "$(dirname "$0")"
[ $# -ge 1 ] || { echo "usage: $0 <tv-ip>[:5555] [wallpaper-image] [config.json]"; exit 1; }
TV="$1"; [[ "$TV" == *:* ]] || TV="$TV:5555"
WALLPAPER="${2:-wallpaper/torg-shop-wallpaper.png}"
CONFIG="${3:-config.json}"
PKG=com.torgcrafted.launcher
export ANDROID_SERIAL="$TV"

adb connect "$TV" >/dev/null
adb install -r out/TorgLauncher.apk
adb shell pm grant $PKG android.permission.READ_EXTERNAL_STORAGE
# Lets the app keep its Home-button service switched on (Fire OS clears it when the app is updated).
adb shell pm grant $PKG android.permission.WRITE_SECURE_SETTINGS
adb shell mkdir -p /sdcard/Pictures /sdcard/Android/data/$PKG/files
WP_NAME=$(basename "$WALLPAPER")
adb push "$WALLPAPER" "/sdcard/Pictures/$WP_NAME"
# Point the config at the wallpaper that was just pushed.
sed "s#\"wallpaper\": *\"[^\"]*\"#\"wallpaper\": \"/sdcard/Pictures/$WP_NAME\"#" "$CONFIG" > out/config.push.json
adb push out/config.push.json /sdcard/Android/data/$PKG/files/config.json
adb shell settings put secure enabled_accessibility_services $PKG/$PKG.HomeWatchService
adb shell settings put secure accessibility_enabled 1
adb shell am force-stop $PKG
adb shell am start -n $PKG/.HomeActivity
echo
echo "Installed. To copy the layout you arranged on the TV back to this computer:"
echo "  adb -s $TV pull /sdcard/Android/data/$PKG/files/config.json config.json"
