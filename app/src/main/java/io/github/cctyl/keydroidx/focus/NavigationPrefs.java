package io.github.cctyl.keydroidx.focus;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 服务运行时偏好：
 * 1. enabled 开关——控制无障碍服务内的“鼠标导航”是否真正生效；
 * 2. blacklist_pkgs 黑名单——黑名单里的应用不显示光标、按键全部原样放行，
 *    相当于“在这个 App 内服务不存在”。
 *
 * 注意：无障碍服务与 Activity 运行在同一个进程，MODE_PRIVATE 的 SharedPreferences
 * 是进程内共享的，所以服务可以直接注册 OnSharedPreferenceChangeListener 实时感知变化。
 */
public final class NavigationPrefs {

    public static final String NAME = "focus_prefs";
    public static final String KEY_ENABLED = "enabled";
    /** 应用黑名单：StringSet，元素是包名 */
    public static final String KEY_BLACKLIST = "blacklist_pkgs";

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

    // ---------------------------------------------------------------- 应用黑名单

    /** 黑名单包名集合（只读视图，永不为 null；直接修改返回值无效） */
    public static Set<String> getBlacklist(Context c) {
        Set<String> s = prefs(c).getStringSet(KEY_BLACKLIST, null);
        return s == null ? Collections.<String>emptySet() : s;
    }

    /** 某个前台应用是否在黑名单里 */
    public static boolean isBlacklisted(Context c, String pkg) {
        if (pkg == null || pkg.length() == 0) return false;
        return getBlacklist(c).contains(pkg);
    }

    /**
     * 把应用加入 / 移出黑名单，立即持久化。
     *
     * getStringSet 返回的集合与内部存储共享，绝不能原地修改——
     * 必须复制成新 HashSet 改完再整体写回，否则变更既不触发监听器、也不落盘。
     */
    public static void setBlacklisted(Context c, String pkg, boolean blacklisted) {
        if (pkg == null || pkg.length() == 0) return;
        Set<String> cur = new HashSet<String>(getBlacklist(c));
        boolean changed = blacklisted ? cur.add(pkg) : cur.remove(pkg);
        if (changed) {
            prefs(c).edit().putStringSet(KEY_BLACKLIST, cur).apply();
        }
    }

    /** 让常驻的无障碍服务实时感知开关变化（监听器需由调用方长期持有引用，否则会被 GC） */
    public static void registerListener(Context c, SharedPreferences.OnSharedPreferenceChangeListener l) {
        prefs(c).registerOnSharedPreferenceChangeListener(l);
    }

    public static void unregisterListener(Context c, SharedPreferences.OnSharedPreferenceChangeListener l) {
        prefs(c).unregisterOnSharedPreferenceChangeListener(l);
    }
}
