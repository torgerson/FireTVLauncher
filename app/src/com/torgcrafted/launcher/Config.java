package com.torgcrafted.launcher;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Launcher configuration. Positions and sizes are in "design pixels" on a 1920x1080 canvas,
 * scaled to the real screen at draw time. Stored as JSON so it can be edited on the Mac and pushed over ADB.
 */
final class Config {
    static final int DESIGN_W = 1920;
    static final int DESIGN_H = 1080;
    static final String DEFAULT_WALLPAPER = "/sdcard/Pictures/torg-shop-wallpaper.png";

    static final class Tile {
        String pkg;
        int x, y, w; // design px; height is w * 9 / 16
        Tile(String pkg, int x, int y, int w) { this.pkg = pkg; this.x = x; this.y = y; this.w = w; }
        int h() { return Math.round(w * 9f / 16f); }
    }

    String wallpaper = DEFAULT_WALLPAPER;
    int grid = 24;
    final List<Tile> tiles = new ArrayList<>();

    static File file(Context c) {
        File dir = c.getExternalFilesDir(null);
        if (dir == null) dir = c.getFilesDir();
        return new File(dir, "config.json");
    }

    static Config load(Context c) {
        Config cfg = new Config();
        File f = file(c);
        if (!f.exists()) return cfg;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            JSONObject o = new JSONObject(new String(buf, 0, off, StandardCharsets.UTF_8));
            cfg.wallpaper = o.optString("wallpaper", DEFAULT_WALLPAPER);
            cfg.grid = o.optInt("grid", 24);
            JSONArray arr = o.optJSONArray("tiles");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject t = arr.getJSONObject(i);
                    cfg.tiles.add(new Tile(t.getString("pkg"), t.optInt("x", 0), t.optInt("y", 0), t.optInt("w", 240)));
                }
            }
        } catch (Exception ignored) {
            // A broken file falls back to defaults rather than crashing the home screen.
        }
        return cfg;
    }

    void save(Context c) {
        try {
            JSONObject o = new JSONObject();
            o.put("wallpaper", wallpaper);
            o.put("grid", grid);
            JSONArray arr = new JSONArray();
            for (Tile t : tiles) {
                JSONObject j = new JSONObject();
                j.put("pkg", t.pkg);
                j.put("x", t.x);
                j.put("y", t.y);
                j.put("w", t.w);
                arr.put(j);
            }
            o.put("tiles", arr);
            File f = file(c);
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(o.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(f)) {
                f.delete();
                tmp.renameTo(f);
            }
        } catch (Exception ignored) {
        }
    }

    Tile find(String pkg) {
        for (Tile t : tiles) if (t.pkg.equals(pkg)) return t;
        return null;
    }
}
