package io.github.cctyl.keydroidx.focus;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Set;

import io.github.cctyl.nokia.common.R;
import io.github.cctyl.nokia.common.ui.apppicker.NokiaAppPickerFragment;
import io.github.cctyl.nokia.keycore.ui.NokiaBaseActivity;

/**
 * 应用黑名单配置页（诺基亚复古风格）。
 * 勾选的应用 = 黑名单：这些 App 内关闭光标并放行按键。
 */
public class BlacklistActivity extends NokiaBaseActivity {

    @Override
    protected void onInitViews() {
        if (getSupportFragmentManager().findFragmentById(R.id.midPanel) == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.midPanel, new BlacklistPickerFragment())
                    .commitNow();
        }
        refreshPageBar();
    }

    public static class BlacklistPickerFragment extends NokiaAppPickerFragment {

        @NonNull
        @Override
        protected Set<String> getInitialSelectedPackages() {
            return NavigationPrefs.getBlacklist(requireContext());
        }

        @Override
        protected void onSelectionChanged(@NonNull Set<String> selectedPackages, @NonNull String pkg, boolean isSelected) {
            NavigationPrefs.setBlacklisted(requireContext(), pkg, isSelected);
        }

        @Override
        public String getPageTitle() {
            return "黑名单设置";
        }
    }
}
