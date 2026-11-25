# Torg Launcher — design notes

A minimal, sideloaded home screen for the shop Fire TV (Insignia NS-55DF710NA21, Fire OS 7.7 / Android 9).

## What it does
1. **Full-screen wallpaper.** Any image you choose (default: the shop photo). Shown edge to edge.
2. **Apps are off by default.** Nothing appears until you switch it on. Initial set: YouTube TV, YouTube, Prime Video, AirPlay.
3. **Free placement on a fine snap grid.** Tiles snap to a small grid (default 24 px cells → 80 × 45 on a 1080p screen),
   fine enough to fit any wallpaper. Put tiles anywhere: one column, two columns, staggered, a row, whatever.
   Grid size is adjustable (12 / 24 / 48 px) and tile size is adjustable per tile.
4. **Takes over Home.** An accessibility service watches for Amazon's home screen and immediately switches back to this one.
   Same approach we verified works with Projectivy on this TV.
5. **Starts on power-up.** A boot receiver opens the launcher when the TV starts.

## Using it on the TV (remote only)
- **Home screen:** D-pad moves between tiles (by position, not list order). Select opens the app.
- **Menu button (☰)** opens the launcher menu:
  - **Apps** — every installed app with an on/off switch.
  - **Arrange tiles** — edit mode with a faint grid overlay:
    - pick a tile with Select, then the D-pad moves it one grid cell per press (hold to move fast);
    - Rewind / Fast-forward make the selected tile smaller / bigger;
    - Select drops it; Back leaves edit mode and saves.
  - **Wallpaper** — choose any image in the TV's Pictures folder.
  - **Grid size** — 12 / 24 / 48 px.
  - **Amazon home (60 s)** — opens Amazon's home screen once and pauses the Home takeover for a minute, for anything only reachable there.
  - **Settings** — opens Fire TV settings.

## Config
Stored on the TV as JSON (`/sdcard/Android/data/com.torgcrafted.launcher/files/config.json`) so it can also be
edited from the Mac and pushed over ADB:
```json
{ "wallpaper": "/sdcard/Pictures/torg-shop-wallpaper.png", "grid": 24,
  "tiles": [ { "pkg": "com.amazon.firetv.youtube.tv", "col": 27, "row": 14, "w": 10, "h": 6 } ] }
```
A tile in `tiles` = app is on. Removing it = off.

## Build
- Plain Java, no AndroidX/Gradle — built directly with `aapt2`, `javac`, `d8`, `zipalign`, `apksigner`.
- Toolchain: OpenJDK 17 (Homebrew) + Android SDK build-tools 34 and platform android-28 (Google).
- Signed with a key generated on first build in `keystore/` (git-ignored) (needed to install updates over it — keep it).
- Installed over ADB. No store involved.

## Limits
- An Amazon OS update could block the Home takeover; the launcher still works and still opens at boot.
- Factory reset removes it; reinstall with `./install.sh <tv-ip>`.
