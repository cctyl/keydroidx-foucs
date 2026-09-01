package io.github.cctyl.keydroidx.focus;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Set;

import io.github.cctyl.nokia.common.R;
import io.github.cctyl.nokia.common.ui.apppicker.NokiaAppPickerFragment;
import io.github.cctyl.nokia.keycore.ui.NokiaBaseActivity;

/**
 * 应用白名单配置页（诺基亚复古风格）。
 * 勾选的应用 = 白名单：仅在这些 App 内开启光标。
 */
public class WhitelistActivity extends NokiaBaseActivity {

    @Override
    protected void onInitViews() {
        if (getSupportFragmentManager().findFragmentById(R.id.midPanel) == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.midPanel, new WhitelistPickerFragment())
                    .commitNow();
        }
        refreshPageBar();
    }

    public static class WhitelistPickerFragment extends NokiaAppPickerFragment {

        @NonNull
        @Override
        protected Set<String> getInitialSelectedPackages() {
            return NavigationPrefs.getWhitelist(requireContext());
        }

        @Override
        protected void onSelectionChanged(@NonNull Set<String> selectedPackages, @NonNull String pkg, boolean isSelected) {
            NavigationPrefs.setWhitelisted(requireContext(), pkg, isSelected);
        }

        @Override
        public String getPageTitle() {
            return "白名单设置";
        }
    }
}
