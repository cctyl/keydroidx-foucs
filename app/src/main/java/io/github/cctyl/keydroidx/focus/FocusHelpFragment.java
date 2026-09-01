package io.github.cctyl.keydroidx.focus;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.cctyl.nokia.common.ui.NokiaFontManager;
import io.github.cctyl.nokia.common.ui.page.NokiaScrollPageFragment;

/**
 * 使用说明与操作指南页面（诺基亚复古风格）。
 */
public class FocusHelpFragment extends NokiaScrollPageFragment {

    @Override
    protected int getLayoutRes() {
        return R.layout.fragment_focus_help;
    }

    @Override
    protected void onScrollPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        TextView tvHelp = view.findViewById(R.id.tvHelpText);
        if (tvHelp != null) {
            tvHelp.setText(R.string.usage_tips);
            NokiaFontManager.setTextSize(tvHelp, 11);
        }
    }

    @Override
    public String getPageTitle() {
        return "使用说明";
    }

    @Override
    public String getSoftLeftText() {
        return null;
    }

    @Override
    public String getSoftRightText() {
        return "返回";
    }

    @Override
    public boolean onSoftRight() {
        if (getActivity() != null) {
            getActivity().onBackPressed();
        }
        return true;
    }
}
