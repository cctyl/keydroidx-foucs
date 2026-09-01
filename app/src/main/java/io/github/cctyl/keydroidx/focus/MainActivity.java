package io.github.cctyl.keydroidx.focus;

import android.content.Context;
import android.content.Intent;
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
        // 先于 super.onCreate 判定，避免先绘制本应用界面再跳转造成闪烁
        if (!isAccessibilityServiceEnabled(this)) {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            finish();
            return;
        }
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onInitViews() {
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
        String wanted = context.getPackageName() + "/" + FocusNavigationService.class.getName();
        for (String s : enabled.split(":")) {
            if (s != null && s.equalsIgnoreCase(wanted)) return true;
        }
        return false;
    }
}
