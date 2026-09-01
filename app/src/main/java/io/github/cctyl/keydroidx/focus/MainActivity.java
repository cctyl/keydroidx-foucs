package io.github.cctyl.keydroidx.focus;

import io.github.cctyl.nokia.common.R;
import io.github.cctyl.nokia.keycore.ui.NokiaBaseActivity;

/**
 * 鼠标导航主入口 Activity（基于 NokiaBaseActivity 与 NokiaListPageFragment 实现诺基亚复古风格）。
 */
public class MainActivity extends NokiaBaseActivity {

    @Override
    protected void onInitViews() {
        if (getSupportFragmentManager().findFragmentById(R.id.midPanel) == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.midPanel, new FocusMainFragment())
                    .commitNow();
        }
        refreshPageBar();
    }
}
