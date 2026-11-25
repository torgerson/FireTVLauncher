package com.torgcrafted.launcher;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The home screen: a full-screen wallpaper with app tiles placed anywhere on a snap grid.
 * Menu button opens the launcher menu (apps on/off, arrange, wallpaper, grid size, Amazon home, settings).
 */
public class HomeActivity extends Activity {
    private static final String PREFS = "torg";
    private static final String KEY_BYPASS = "bypassUntil";
    private static final int[] GRID_SIZES = {12, 24, 48};
    private static final int MIN_W = 96, MAX_W = 640;

    private Config cfg;
    private float scale; // real px per design px
    private FrameLayout root, tileLayer;
    private ImageView wallpaperView;
    private GridOverlay gridOverlay;
    private TextView hint;
    private final List<TileView> tileViews = new ArrayList<>();
    private int selected = -1;
    private boolean arranging, moving;
    private String loadedWallpaper;

    // ---------- lifecycle ----------

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        scale = Math.min(dm.widthPixels / (float) Config.DESIGN_W, dm.heightPixels / (float) Config.DESIGN_H);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        wallpaperView = new ImageView(this);
        wallpaperView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(wallpaperView, match());
        gridOverlay = new GridOverlay(this);
        gridOverlay.setVisibility(View.GONE);
        root.addView(gridOverlay, match());
        tileLayer = new FrameLayout(this);
        tileLayer.setClipChildren(false);
        root.addView(tileLayer, match());

        hint = new TextView(this);
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(16);
        hint.setPadding(dp(18), dp(10), dp(18), dp(10));
        GradientDrawable hb = new GradientDrawable();
        hb.setColor(0xCC000000);
        hb.setCornerRadius(dp(10));
        hint.setBackground(hb);
        hint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hlp.topMargin = dp(24);
        root.addView(hint, hlp);

        setContentView(root);

        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        HomeWatchService.ensureEnabled(this);
        if (!arranging) reload();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        loadedWallpaper = null;
        reload();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (arranging) exitArrange();
    }

    private void reload() {
        String keepPkg = selected >= 0 && selected < tileViews.size() ? tileViews.get(selected).tile.pkg : null;
        cfg = Config.load(this);
        loadWallpaper();
        buildTiles();
        selected = -1;
        if (keepPkg != null) {
            for (int i = 0; i < tileViews.size(); i++) if (tileViews.get(i).tile.pkg.equals(keepPkg)) selected = i;
        }
        if (selected < 0 && !tileViews.isEmpty()) selected = topLeftTile();
        refreshSelection();
        if (tileViews.isEmpty()) showHint("Press ☰ Menu → Apps to choose apps", 0);
        else if (!arranging) hint.setVisibility(View.GONE);
    }

    // ---------- wallpaper ----------

    private void loadWallpaper() {
        if (cfg.wallpaper.equals(loadedWallpaper)) return;
        loadedWallpaper = cfg.wallpaper;
        Bitmap bmp = decodeScaled(cfg.wallpaper, Math.round(Config.DESIGN_W * scale), Math.round(Config.DESIGN_H * scale));
        if (bmp != null) wallpaperView.setImageBitmap(bmp);
        else {
            wallpaperView.setImageDrawable(null);
            wallpaperView.setBackgroundColor(0xFF15120F);
        }
    }

    private static Bitmap decodeScaled(String path, int reqW, int reqH) {
        try {
            if (path == null || !new File(path).canRead()) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= reqW && o.outHeight / (sample * 2) >= reqH) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            return BitmapFactory.decodeFile(path, o);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------- tiles ----------

    private final class TileView extends FrameLayout {
        final Config.Tile tile;
        final View ring;

        TileView(Context c, Config.Tile tile) {
            super(c);
            this.tile = tile;
            setClipChildren(false);
            FrameLayout body = new FrameLayout(c);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF1E1B18);
            bg.setCornerRadius(dp(10));
            body.setBackground(bg);
            body.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10)); }
            });
            body.setClipToOutline(true);
            body.setElevation(dp(8));
            addView(body, match());

            PackageManager pm = getPackageManager();
            Drawable banner = bannerFor(tile.pkg);
            if (banner != null) {
                ImageView iv = new ImageView(c);
                iv.setScaleType(ImageView.ScaleType.FIT_XY);
                iv.setImageDrawable(banner);
                body.addView(iv, match());
            } else {
                LinearLayout col = new LinearLayout(c);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setGravity(Gravity.CENTER);
                ImageView iv = new ImageView(c);
                try { iv.setImageDrawable(pm.getApplicationIcon(tile.pkg)); } catch (Exception ignored) { }
                int icon = Math.round(px(tile.h()) * 0.5f);
                col.addView(iv, new LinearLayout.LayoutParams(icon, icon));
                TextView tv = new TextView(c);
                tv.setText(labelFor(tile.pkg));
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Math.max(12, px(tile.h()) * 0.13f));
                tv.setSingleLine(true);
                tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                tv.setGravity(Gravity.CENTER);
                tv.setPadding(dp(6), Math.round(px(tile.h()) * 0.04f), dp(6), 0);
                col.addView(tv);
                body.addView(col, match());
            }

            ring = new View(c);
            ring.setVisibility(View.INVISIBLE);
            ring.setElevation(dp(9));
            FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            int out = -dp(6);
            rlp.setMargins(out, out, out, out);
            addView(ring, rlp);
        }

        void place() {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(tile.w), px(tile.h()));
            lp.leftMargin = px(tile.x);
            lp.topMargin = px(tile.y);
            setLayoutParams(lp);
        }

        void setState(boolean sel, boolean mov) {
            if (!sel) {
                ring.setVisibility(View.INVISIBLE);
                animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                setElevation(0);
                return;
            }
            GradientDrawable r = new GradientDrawable();
            r.setColor(Color.TRANSPARENT);
            r.setCornerRadius(dp(14));
            r.setStroke(dp(4), mov ? 0xFFFFC233 : 0xFFF3EFE8);
            ring.setBackground(r);
            ring.setVisibility(View.VISIBLE);
            float s = mov ? 1f : 1.06f;
            animate().scaleX(s).scaleY(s).setDuration(120).start();
            setElevation(dp(12));
        }
    }

    private void buildTiles() {
        tileLayer.removeAllViews();
        tileViews.clear();
        PackageManager pm = getPackageManager();
        List<Config.Tile> keep = new ArrayList<>();
        for (Config.Tile t : cfg.tiles) {
            if (pm.getLaunchIntentForPackage(t.pkg) == null && pm.getLeanbackLaunchIntentForPackage(t.pkg) == null) continue; // uninstalled
            keep.add(t);
            TileView tv = new TileView(this, t);
            tileLayer.addView(tv);
            tv.place();
            tileViews.add(tv);
        }
    }

    private Drawable bannerFor(String pkg) {
        PackageManager pm = getPackageManager();
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(pkg);
            List<ResolveInfo> ris = pm.queryIntentActivities(i, 0);
            for (ResolveInfo ri : ris) {
                Drawable d = ri.activityInfo.loadBanner(pm);
                if (d != null) return d;
            }
            Drawable d = pm.getApplicationBanner(pkg);
            if (d != null) return d;
        } catch (Exception ignored) {
        }
        return null;
    }

    private String labelFor(String pkg) {
        PackageManager pm = getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private void refreshSelection() {
        for (int i = 0; i < tileViews.size(); i++) tileViews.get(i).setState(i == selected, moving && i == selected);
        if (selected >= 0 && selected < tileViews.size()) tileViews.get(selected).bringToFront();
    }

    private int topLeftTile() {
        int best = -1;
        long bestScore = Long.MAX_VALUE;
        for (int i = 0; i < tileViews.size(); i++) {
            Config.Tile t = tileViews.get(i).tile;
            long s = (long) t.y * 4 + t.x;
            if (s < bestScore) { bestScore = s; best = i; }
        }
        return best;
    }

    /** Pick the nearest tile in a direction, by tile centres, favouring tiles straight ahead. */
    private int neighbour(int from, int dx, int dy) {
        if (from < 0) return topLeftTile();
        Config.Tile a = tileViews.get(from).tile;
        float ax = a.x + a.w / 2f, ay = a.y + a.h() / 2f;
        int best = -1;
        float bestScore = Float.MAX_VALUE;
        for (int i = 0; i < tileViews.size(); i++) {
            if (i == from) continue;
            Config.Tile b = tileViews.get(i).tile;
            float bx = b.x + b.w / 2f, by = b.y + b.h() / 2f;
            float along = (bx - ax) * dx + (by - ay) * dy;
            if (along <= 1) continue;
            float across = Math.abs((bx - ax) * dy) + Math.abs((by - ay) * dx);
            float score = along + across * 2.5f;
            if (score < bestScore) { bestScore = score; best = i; }
        }
        return best < 0 ? from : best;
    }

    private void launch(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (ActivityNotFoundException ignored) { }
    }

    // ---------- keys ----------

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        int dx = 0, dy = 0;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: dx = -1; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: dx = 1; break;
            case KeyEvent.KEYCODE_DPAD_UP: dy = -1; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: dy = 1; break;
        }
        if (dx != 0 || dy != 0) {
            if (moving) moveSelected(dx, dy, e.getRepeatCount() > 0 ? 3 : 1);
            else if (!tileViews.isEmpty()) {
                selected = neighbour(selected, dx, dy);
                refreshSelection();
            }
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (e.getRepeatCount() == 0) e.startTracking();
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (arranging) exitArrange();
                else showMenu();
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (moving) resizeSelected(-1);
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (moving) resizeSelected(1);
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (moving) { moving = false; refreshSelection(); showArrangeHint(); }
                else if (arranging) exitArrange();
                return true; // never leave the home screen with Back
        }
        return super.onKeyDown(code, e);
    }

    @Override
    public boolean onKeyLongPress(int code, KeyEvent e) {
        if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && !arranging) {
            centerLongPressed = true;
            showMenu();
            return true;
        }
        return super.onKeyLongPress(code, e);
    }

    private boolean centerLongPressed;

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            if (centerLongPressed) { centerLongPressed = false; return true; }
            if (e.isCanceled()) return true;
            if (selected < 0 || selected >= tileViews.size()) return true;
            if (arranging) {
                moving = !moving;
                refreshSelection();
                if (moving) showHint("Move: D-pad (hold = fast)   Size: ⏪ ⏩   Drop: Select", 0);
                else { cfg.save(this); showArrangeHint(); }
            } else {
                launch(tileViews.get(selected).tile.pkg);
            }
            return true;
        }
        return super.onKeyUp(code, e);
    }

    // ---------- arrange ----------

    private void enterArrange() {
        arranging = true;
        moving = false;
        gridOverlay.setVisibility(View.VISIBLE);
        gridOverlay.invalidate();
        if (selected < 0 && !tileViews.isEmpty()) selected = topLeftTile();
        refreshSelection();
        showArrangeHint();
    }

    private void showArrangeHint() {
        showHint("Arrange: pick a tile with Select   •   Back or ☰ = done", 0);
    }

    private void exitArrange() {
        arranging = false;
        moving = false;
        gridOverlay.setVisibility(View.GONE);
        cfg.save(this);
        refreshSelection();
        showHint("Layout saved", 2000);
    }

    private void moveSelected(int dx, int dy, int steps) {
        Config.Tile t = tileViews.get(selected).tile;
        int g = cfg.grid;
        t.x = clamp(snap(t.x) + dx * g * steps, 0, snapDown(Config.DESIGN_W - t.w));
        t.y = clamp(snap(t.y) + dy * g * steps, 0, snapDown(Config.DESIGN_H - t.h()));
        tileViews.get(selected).place();
    }

    private void resizeSelected(int dir) {
        Config.Tile t = tileViews.get(selected).tile;
        int step = Math.max(cfg.grid, 16) * 2;
        t.w = clamp(t.w + dir * step, MIN_W, MAX_W);
        t.x = clamp(t.x, 0, Math.max(0, Config.DESIGN_W - t.w));
        t.y = clamp(t.y, 0, Math.max(0, Config.DESIGN_H - t.h()));
        tileViews.get(selected).place();
    }

    private int snap(int v) { return Math.round(v / (float) cfg.grid) * cfg.grid; }
    private int snapDown(int v) { return (v / cfg.grid) * cfg.grid; }
    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Faint snap grid, with every fourth line a little stronger. */
    private final class GridOverlay extends View {
        private final Paint fine = new Paint(), bold = new Paint();

        GridOverlay(Context c) {
            super(c);
            fine.setColor(0x22FFFFFF);
            fine.setStrokeWidth(1);
            bold.setColor(0x55FFFFFF);
            bold.setStrokeWidth(1);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int g = cfg == null ? 24 : cfg.grid;
            int n = 0;
            for (int x = 0; x <= Config.DESIGN_W; x += g, n++) {
                float p = x * scale;
                canvas.drawLine(p, 0, p, getHeight(), n % 4 == 0 ? bold : fine);
            }
            n = 0;
            for (int y = 0; y <= Config.DESIGN_H; y += g, n++) {
                float p = y * scale;
                canvas.drawLine(0, p, getWidth(), p, n % 4 == 0 ? bold : fine);
            }
        }
    }

    // ---------- menu ----------

    private void showMenu() {
        final String[] items = {"Apps", "Arrange tiles", "Wallpaper", "Grid size (" + cfg.grid + " px)", "Amazon home (60 s)", "Fire TV settings"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Torg Launcher")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: showApps(); break;
                        case 1: enterArrange(); break;
                        case 2: showWallpapers(); break;
                        case 3: showGridSizes(); break;
                        case 4: openAmazonHome(); break;
                        case 5: openSettings(); break;
                    }
                })
                .show();
    }

    private void showApps() {
        PackageManager pm = getPackageManager();
        Map<String, String> apps = new HashMap<>();
        Map<String, String> byLabel = new HashMap<>(); // label -> pkg; Amazon ships duplicates (e.g. two "Prime Video")
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                ActivityInfo ai = ri.activityInfo;
                if (ai.packageName.equals(getPackageName()) || apps.containsKey(ai.packageName)) continue;
                String label = ri.loadLabel(pm).toString();
                String other = byLabel.get(label);
                if (other != null) {
                    // Keep the one already on the home screen; otherwise the first (TV/leanback) one wins.
                    if (cfg.find(ai.packageName) == null || cfg.find(other) != null) continue;
                    apps.remove(other);
                }
                byLabel.put(label, ai.packageName);
                apps.put(ai.packageName, label);
            }
        }
        final List<String> pkgs = new ArrayList<>(apps.keySet());
        Collections.sort(pkgs, (p1, p2) -> apps.get(p1).compareToIgnoreCase(apps.get(p2)));
        final String[] labels = new String[pkgs.size()];
        final boolean[] on = new boolean[pkgs.size()];
        for (int i = 0; i < pkgs.size(); i++) {
            labels[i] = apps.get(pkgs.get(i));
            on[i] = cfg.find(pkgs.get(i)) != null;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Apps on the home screen")
                .setMultiChoiceItems(labels, on, (d, i, checked) -> on[i] = checked)
                .setPositiveButton("Done", (d, w) -> {
                    for (int i = 0; i < pkgs.size(); i++) {
                        Config.Tile t = cfg.find(pkgs.get(i));
                        if (on[i] && t == null) cfg.tiles.add(newTileFor(pkgs.get(i)));
                        if (!on[i] && t != null) cfg.tiles.remove(t);
                    }
                    cfg.save(this);
                    reload();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** New tiles go in a column starting at the middle of the screen, then wrap to the next column. */
    private Config.Tile newTileFor(String pkg) {
        int w = 264, h = Math.round(w * 9f / 16f), gap = cfg.grid;
        int x = snap(Config.DESIGN_W / 2 - w / 2), y = snap(240);
        for (int guard = 0; guard < 200; guard++) {
            if (!overlaps(x, y, w, h)) break;
            y += h + gap;
            if (y + h > Config.DESIGN_H - 48) { y = snap(240); x += w + gap; }
            if (x + w > Config.DESIGN_W) { x = 0; }
        }
        return new Config.Tile(pkg, snap(x), snap(y), w);
    }

    private boolean overlaps(int x, int y, int w, int h) {
        for (Config.Tile t : cfg.tiles) {
            if (x < t.x + t.w && t.x < x + w && y < t.y + t.h() && t.y < y + h) return true;
        }
        return false;
    }

    private void showWallpapers() {
        final List<File> files = new ArrayList<>();
        for (String dir : new String[]{"/sdcard/Pictures", "/sdcard/Download", "/sdcard/DCIM"}) {
            File[] list = new File(dir).listFiles();
            if (list == null) continue;
            Arrays.sort(list);
            for (File f : list) {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".webp")) files.add(f);
            }
        }
        if (files.isEmpty()) {
            showHint("No pictures found in Pictures, Download or DCIM", 4000);
            return;
        }
        String[] names = new String[files.size() + 1];
        for (int i = 0; i < files.size(); i++) names[i] = files.get(i).getParentFile().getName() + " / " + files.get(i).getName();
        names[files.size()] = "Plain dark background";
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Wallpaper")
                .setItems(names, (d, which) -> {
                    cfg.wallpaper = which < files.size() ? files.get(which).getAbsolutePath() : "";
                    cfg.save(this);
                    loadedWallpaper = null;
                    loadWallpaper();
                })
                .show();
    }

    private void showGridSizes() {
        String[] names = new String[GRID_SIZES.length];
        for (int i = 0; i < GRID_SIZES.length; i++) names[i] = GRID_SIZES[i] + " px" + (GRID_SIZES[i] == cfg.grid ? "  (current)" : "");
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Snap grid")
                .setItems(names, (d, which) -> {
                    cfg.grid = GRID_SIZES[which];
                    cfg.save(this);
                    gridOverlay.invalidate();
                    showHint("Grid set to " + cfg.grid + " px", 2000);
                })
                .show();
    }

    static long bypassUntil(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getLong(KEY_BYPASS, 0);
    }

    private void openAmazonHome() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong(KEY_BYPASS, System.currentTimeMillis() + 60_000).apply();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(HomeWatchService.AMAZON_LAUNCHER);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (ActivityNotFoundException ignored) { }
    }

    private void openSettings() {
        Intent i = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (ActivityNotFoundException ignored) { }
    }

    // ---------- helpers ----------

    private final Runnable hideHint = () -> hint.setVisibility(View.GONE);

    private void showHint(String text, long ms) {
        hint.setText(text);
        hint.setVisibility(View.VISIBLE);
        hint.removeCallbacks(hideHint);
        if (ms > 0) hint.postDelayed(hideHint, ms);
    }

    private int px(int design) { return Math.round(design * scale); }
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }
}
