package io.github.cctyl.keydroidx.focus;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 简单的开关偏好：用于控制无障碍服务内的“焦点导航”是否真正生效。
 * 服务在系统设置里开启后，用户可回到本 App 用这个开关临时停用，避免配置时互相干扰。
 *
 * 注意：无障碍服务与 Activity 运行在同一个进程，MODE_PRIVATE 的 SharedPreferences
 * 是进程内共享的，所以服务可以直接注册 OnSharedPreferenceChangeListener 实时感知开关变化。
 */
public final class NavigationPrefs {

    public static final String NAME = "focus_prefs";
    public static final String KEY_ENABLED = "enabled";

    private NavigationPrefs() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context c) {
        return prefs(c).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context c, boolean v) {
        prefs(c).edit().putBoolean(KEY_ENABLED, v).apply();
    }

    /** 让常驻的无障碍服务实时感知开关变化（监听器需由调用方长期持有引用，否则会被 GC） */
    public static void registerListener(Context c, SharedPreferences.OnSharedPreferenceChangeListener l) {
        prefs(c).registerOnSharedPreferenceChangeListener(l);
    }

    public static void unregisterListener(Context c, SharedPreferences.OnSharedPreferenceChangeListener l) {
        prefs(c).unregisterOnSharedPreferenceChangeListener(l);
    }
}
