package com.torgcrafted.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Fire OS always sends the Home button to Amazon's launcher. This service notices when Amazon's
 * home screen comes up and immediately brings Torg Launcher back to the front.
 * "Amazon home (60 s)" in the launcher menu pauses this so Amazon's screen can be reached on purpose.
 */
public class HomeWatchService extends AccessibilityService {
    static final String AMAZON_LAUNCHER = "com.amazon.tv.launcher";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        CharSequence cls = event.getClassName();
        if (pkg == null || !AMAZON_LAUNCHER.contentEquals(pkg)) return;
        if (cls == null || !cls.toString().contains("HomeActivity")) return;
        if (System.currentTimeMillis() < HomeActivity.bypassUntil(this)) return;

        Intent i = new Intent(this, HomeActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(i);
    }

    @Override
    public void onInterrupt() {
    }

    /**
     * Fire OS clears the enabled-accessibility list when the app is updated. If the app has been granted
     * WRITE_SECURE_SETTINGS once over ADB, re-add this service whenever the launcher starts, boots or is updated.
     */
    static void ensureEnabled(android.content.Context c) {
        String me = c.getPackageName() + "/" + HomeWatchService.class.getName();
        try {
            android.content.ContentResolver cr = c.getContentResolver();
            String cur = android.provider.Settings.Secure.getString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (cur != null && cur.contains(me)) return;
            String next = (cur == null || cur.isEmpty() || "null".equals(cur)) ? me : cur + ":" + me;
            android.provider.Settings.Secure.putString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
            android.provider.Settings.Secure.putString(cr, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, "1");
        } catch (SecurityException ignored) {
            // Permission not granted; install.sh grants it.
        }
    }
}
