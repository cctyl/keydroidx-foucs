package io.github.cctyl.keydroidx.focus;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 服务运行时偏好：
 * 1. enabled 开关——控制无障碍服务内的"鼠标导航"是否真正生效；
 * 2. 名单模式（{@link #KEY_MODE}）——同一时刻只能选一种：
 *    <ul>
 *      <li>{@link #MODE_BLACKLIST}：黑名单里的应用不显示光标、按键全部原样放行，
 *          其余应用照常使用鼠标导航；</li>
 *      <li>{@link #MODE_WHITELIST}：只有白名单里的应用才显示光标，
 *          其余应用一律不显示、按键全部原样放行。</li>
 *    </ul>
 *    两种模式的数据<b>互不共享</b>：黑名单（{@link #KEY_BLACKLIST}）
 *    与白名单（{@link #KEY_WHITELIST}）是两套独立的 StringSet，切换模式不会把
 *    一边的勾选搬到另一边。
 *
 * 注意：无障碍服务与 Activity 运行在同一个进程，MODE_PRIVATE 的 SharedPreferences
 * 是进程内共享的，所以服务可以直接注册 OnSharedPreferenceChangeListener 实时感知变化。
 */
public final class NavigationPrefs {

    public static final String NAME = "focus_prefs";
    public static final String KEY_ENABLED = "enabled";
    /** 名单模式：取值为 {@link #MODE_BLACKLIST} 或 {@link #MODE_WHITELIST} */
    public static final String KEY_MODE = "list_mode";
    /** 应用黑名单：StringSet，元素是包名 */
    public static final String KEY_BLACKLIST = "blacklist_pkgs";
    /** 应用白名单：StringSet，元素是包名 */
    public static final String KEY_WHITELIST = "whitelist_pkgs";

    /** 黑名单模式：名单内的应用关闭光标，名单外照常 */
    public static final String MODE_BLACKLIST = "blacklist";
    /** 白名单模式：名单内的应用才开启光标，名单外一律关闭 */
    public static final String MODE_WHITELIST = "whitelist";

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

    // ---------------------------------------------------------------- 名单模式

    /** 当前生效的名单模式，默认黑名单 */
    public static String getMode(Context c) {
        String m = prefs(c).getString(KEY_MODE, MODE_BLACKLIST);
        return MODE_WHITELIST.equals(m) ? MODE_WHITELIST : MODE_BLACKLIST;
    }

    public static boolean isWhitelistMode(Context c) {
        return MODE_WHITELIST.equals(getMode(c));
    }

    public static void setMode(Context c, String mode) {
        if (!MODE_BLACKLIST.equals(mode) && !MODE_WHITELIST.equals(mode)) {
            return;
        }
        prefs(c).edit().putString(KEY_MODE, mode).apply();
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

    // ---------------------------------------------------------------- 应用白名单

    /** 白名单包名集合（只读视图，永不为 null；直接修改返回值无效） */
    public static Set<String> getWhitelist(Context c) {
        Set<String> s = prefs(c).getStringSet(KEY_WHITELIST, null);
        return s == null ? Collections.<String>emptySet() : s;
    }

    /** 某个前台应用是否在白名单里 */
    public static boolean isWhitelisted(Context c, String pkg) {
        if (pkg == null || pkg.length() == 0) return false;
        return getWhitelist(c).contains(pkg);
    }

    /** 把应用加入 / 移出白名单，立即持久化（同样要复制再写回，见 setBlacklisted 注释） */
    public static void setWhitelisted(Context c, String pkg, boolean whitelisted) {
        if (pkg == null || pkg.length() == 0) return;
        Set<String> cur = new HashSet<String>(getWhitelist(c));
        boolean changed = whitelisted ? cur.add(pkg) : cur.remove(pkg);
        if (changed) {
            prefs(c).edit().putStringSet(KEY_WHITELIST, cur).apply();
        }
    }

    /**
     * 统一判定：当前前台应用在当前模式下，光标是否应当被<b>关闭</b>（按键全部放行）。
     *
     * - 黑名单模式：pkg 在黑名单里 → 关闭；
     * - 白名单模式：pkg 不在白名单里 → 关闭。
     *
     * 调用方仍需自行处理"自家界面 / 输入态"等独立于名单的放行条件。
     */
    public static boolean isAppSuppressed(Context c, String pkg) {
        if (isWhitelistMode(c)) {
            // 白名单模式下，包名未知（取不到前台）不能视为"在白名单里"，
            // 否则会在拿不到前台包名的瞬间误点亮光标。
            return !isWhitelisted(c, pkg);
        }
        return isBlacklisted(c, pkg);
    }

    /** 让常驻的无障碍服务实时感知开关变化（监听器需由调用方长期持有引用，否则会被 GC） */
    public static void registerListener(Context c, SharedPreferences.OnSharedPreferenceChangeListener l) {
        prefs(c).registerOnSharedPreferenceChangeListener(l);
    }

    public static void unregisterListener(Context c, SharedPreferences.OnSharedPreferenceChangeListener l) {
        prefs(c).unregisterOnSharedPreferenceChangeListener(l);
    }
}
