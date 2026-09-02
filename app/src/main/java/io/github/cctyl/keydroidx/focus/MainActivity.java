package io.github.cctyl.keydroidx.focus;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;

import io.github.cctyl.nokia.common.R;
import io.github.cctyl.nokia.keycore.ui.NokiaBaseActivity;

/**
 * 鼠标导航主入口 Activity（基于 NokiaBaseActivity 与 NokiaListPageFragment 实现诺基亚复古风格）。
 * <p>
 * 启动行为：仅在无障碍服务未开启时跳转系统无障碍设置页引导用户开启；
 * 已开启则不跳转，直接进入本应用配置页。
 */
public class MainActivity extends NokiaBaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 仅在无障碍服务未开启时跳转系统无障碍设置页引导用户开启
        if (!isAccessibilityServiceEnabled(this)) {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            finish();
            return;
        }

        // API 23~25（Android 6.0 ~ 7.1）悬浮光标使用 TYPE_SYSTEM_ALERT，需要运行时悬浮窗授权
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        }
    }

    @Override
    protected void onInitViews() {
        if (!isAccessibilityServiceEnabled(this)) {
            return;
        }
        if (getSupportFragmentManager().findFragmentById(R.id.midPanel) == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.midPanel, new FocusMainFragment())
                    .commitNow();
        }
        refreshPageBar();
    }

    /**
     * 判定本应用的 {@link FocusNavigationService} 是否已在系统无障碍设置里被开启。
     */
    private static boolean isAccessibilityServiceEnabled(Context context) {
        String enabled = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        ComponentName wanted = new ComponentName(context, FocusNavigationService.class);
        for (String s : enabled.split(":")) {
            if (TextUtils.isEmpty(s)) continue;
            ComponentName cn = ComponentName.unflattenFromString(s.trim());
            if (wanted.equals(cn)) return true;
        }
        return false;
    }
}
