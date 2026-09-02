package io.github.cctyl.keydroidx.focus;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.cctyl.nokia.common.ui.about.NokiaAboutConfig;
import io.github.cctyl.nokia.common.ui.NokiaFontManager;
import io.github.cctyl.nokia.common.ui.NokiaIcons;
import io.github.cctyl.nokia.common.ui.dialog.NokiaOptionsDialog;
import io.github.cctyl.nokia.common.ui.page.NokiaListPageFragment;
import io.github.cctyl.nokia.common.util.NokiaDimens;
import io.github.cctyl.nokia.keycore.ui.NokiaAboutActivity;
import io.github.cctyl.nokia.keycore.ui.NokiaBaseActivity;
import io.github.cctyl.nokia.keycore.ui.NokiaFeedbackActivity;

/**
 * 鼠标导航主菜单 Fragment（诺基亚复古风格列表）。
 */
public class FocusMainFragment extends NokiaListPageFragment {

    private static final int ITEM_SERVICE_STATUS = 0;
    private static final int ITEM_NOTIFICATION = 1;
    private static final int ITEM_NAV_SWITCH = 2;
    private static final int ITEM_MODE_SELECT = 3;
    private static final int ITEM_APP_LIST = 4;
    private static final int ITEM_HELP = 5;
    private static final int ITEM_FEEDBACK = 6;
    private static final int ITEM_ABOUT = 7;
    private static final int TOTAL_ITEMS = 8;

    private final String[] itemIcons = new String[]{
            NokiaIcons.SETTINGS,
            NokiaIcons.NOTIFICATIONS,
            NokiaIcons.POWER_SETTINGS,
            NokiaIcons.SORT,
            NokiaIcons.APPS,
            NokiaIcons.HELP,
            NokiaIcons.FEEDBACK,
            NokiaIcons.INFO
    };

    private TextView[] tvNames;
    private LinearLayout listLayout;

    @Override
    protected int getLayoutRes() {
        return R.layout.fragment_focus_main;
    }

    @Override
    protected void onPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        listLayout = view.findViewById(R.id.settingsList);
        if (listLayout == null) return;

        listScroll = view.findViewById(R.id.settingsScroll);
        constrainScrollHeight(view, listScroll);

        itemViews = new View[TOTAL_ITEMS];
        tvNames = new TextView[TOTAL_ITEMS];

        for (int i = 0; i < TOTAL_ITEMS; i++) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, NokiaDimens.dp(getResources(), 38)));
            row.setPadding(NokiaDimens.dp(getResources(), 10), NokiaDimens.dp(getResources(), 4),
                    NokiaDimens.dp(getResources(), 10), NokiaDimens.dp(getResources(), 4));
            row.setClickable(true);

            ImageView ivIcon = new ImageView(requireContext());
            ivIcon.setLayoutParams(new LinearLayout.LayoutParams(
                    NokiaDimens.dp(getResources(), 20), NokiaDimens.dp(getResources(), 20)));
            ivIcon.setImageDrawable(NokiaIcons.get(requireContext(), itemIcons[i], 0xFFFFFFFF, 20));
            row.addView(ivIcon);

            row.addView(spaceView(NokiaDimens.dp(getResources(), 8), 1));

            TextView tvName = new TextView(requireContext());
            tvName.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            tvName.setText(getItemDisplayName(i));
            tvName.setTextColor(0xFFFFFFFF);
            NokiaFontManager.setTextSize(tvName, 12);
            tvNames[i] = tvName;
            row.addView(tvName);

            TextView tvArrow = new TextView(requireContext());
            tvArrow.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            tvArrow.setText(">");
            tvArrow.setTextColor(0xFFAAAAAA);
            NokiaFontManager.setTextSize(tvArrow, 14);
            row.addView(tvArrow);

            final int index = i;
            row.setOnClickListener(v -> {
                setFocusIndex(index);
                onSelect();
            });

            listLayout.addView(row);
            itemViews[i] = row;
        }

        setFocusIndex(0);
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshItemLabels();
    }

    public void refreshItemLabels() {
        if (tvNames == null) return;
        for (int i = 0; i < tvNames.length; i++) {
            if (tvNames[i] != null) {
                tvNames[i].setText(getItemDisplayName(i));
            }
        }
    }

    private String getItemDisplayName(int index) {
        if (getContext() == null) return "";
        boolean serviceOn = isAccessibilityServiceEnabled(requireContext());
        boolean notifOn = FocusNotificationHelper.isNotificationEnabled(requireContext());
        boolean navOn = NavigationPrefs.isEnabled(requireContext());
        boolean isWhitelist = NavigationPrefs.isWhitelistMode(requireContext());

        switch (index) {
            case ITEM_SERVICE_STATUS:
                return "无障碍服务：" + (serviceOn ? "已开启" : "未开启 (点击开启)");
            case ITEM_NOTIFICATION:
                return "通知与保活：" + (notifOn ? "已开启" : "未开启 (点击开启)");
            case ITEM_NAV_SWITCH:
                return "原键鼠标：" + (navOn ? "已启用" : "已停用");
            case ITEM_MODE_SELECT:
                return "生效模式：" + (isWhitelist ? "白名单模式" : "黑名单模式");
            case ITEM_APP_LIST:
                int count = isWhitelist ? NavigationPrefs.getWhitelist(requireContext()).size()
                        : NavigationPrefs.getBlacklist(requireContext()).size();
                return (isWhitelist ? "白名单应用管理" : "黑名单应用管理") + " (" + count + "项)";
            case ITEM_HELP:
                return "使用说明与操作指南";
            case ITEM_FEEDBACK:
                return "问题反馈";
            case ITEM_ABOUT:
                return "关于原键鼠标";
            default:
                return "";
        }
    }

    @Override
    public boolean onSelect() {
        if (focusIndex < 0 || getContext() == null) return false;
        switch (focusIndex) {
            case ITEM_SERVICE_STATUS:
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                return true;
            case ITEM_NOTIFICATION:
                if (FocusNotificationHelper.isNotificationEnabled(requireContext())) {
                    Toast.makeText(requireContext(), "通知与保活权限已开启", Toast.LENGTH_SHORT).show();
                } else {
                    FocusNotificationHelper.requestNotificationPermission(requireActivity());
                }
                return true;
            case ITEM_NAV_SWITCH:
                toggleNavSwitch();
                return true;
            case ITEM_MODE_SELECT:
                showModeDialog();
                return true;
            case ITEM_APP_LIST:
                if (NavigationPrefs.isWhitelistMode(requireContext())) {
                    startActivity(new Intent(requireContext(), WhitelistActivity.class));
                } else {
                    startActivity(new Intent(requireContext(), BlacklistActivity.class));
                }
                return true;
            case ITEM_HELP:
                if (getActivity() instanceof NokiaBaseActivity) {
                    ((NokiaBaseActivity) getActivity()).getSupportFragmentManager()
                            .beginTransaction()
                            .replace(io.github.cctyl.nokia.common.R.id.midPanel, new FocusHelpFragment())
                            .addToBackStack(null)
                            .commit();
                    ((NokiaBaseActivity) getActivity()).refreshPageBar();
                }
                return true;
            case ITEM_FEEDBACK:
                startActivity(new Intent(requireContext(), NokiaFeedbackActivity.class));
                return true;
            case ITEM_ABOUT:
                NokiaAboutConfig config = NokiaAboutConfig.createDefault(requireContext())
                        .setAppName("原键鼠标")
                        .setVersionName("1.0.0")
                        .setAuthor("cctyl")
                        .setRepoUrl("https://github.com/cctyl/keydroidx-foucs")
                        .setVideoUrl("https://www.bilibili.com/video/BV1WxMX6yEHX/")
                        .setDescription("专为现代按键功能机设计的屏幕虚拟光标与按键映射工具。\n\n" +
                                "· 无障碍透明悬浮光标\n" +
                                "· 方向键/连发移动 + 确认键坐标点击\n" +
                                "· 0+方向键顺滑拖拽 + 1~9九宫格极速跳跃\n" +
                                "· 独立黑/白名单按键放行机制");
                NokiaAboutActivity.start(requireContext(), config);
                return true;
            default:
                return false;
        }
    }

    private void toggleNavSwitch() {
        boolean current = NavigationPrefs.isEnabled(requireContext());
        boolean next = !current;
        NavigationPrefs.setEnabled(requireContext(), next);
        refreshItemLabels();
        Toast.makeText(requireContext(), next ? "原键鼠标已启用" : "原键鼠标已停用", Toast.LENGTH_SHORT).show();
    }

    private void showModeDialog() {
        boolean isWhitelist = NavigationPrefs.isWhitelistMode(requireContext());
        NokiaOptionsDialog dialog = new NokiaOptionsDialog(requireContext(), "选择生效模式");
        dialog.addItem(1, "黑名单模式 (名单内关闭光标)");
        dialog.addItem(2, "白名单模式 (名单内开启光标)");
        dialog.setOnOptionSelectedListener((index, item) -> {
            if (item.getId() == 1) {
                NavigationPrefs.setMode(requireContext(), NavigationPrefs.MODE_BLACKLIST);
                refreshItemLabels();
                Toast.makeText(requireContext(), "已切换到黑名单模式", Toast.LENGTH_SHORT).show();
            } else if (item.getId() == 2) {
                NavigationPrefs.setMode(requireContext(), NavigationPrefs.MODE_WHITELIST);
                refreshItemLabels();
                Toast.makeText(requireContext(), "已切换到白名单模式", Toast.LENGTH_SHORT).show();
            }
        });
        dialog.show();
    }

    private boolean isAccessibilityServiceEnabled(Context context) {
        return MainActivity.isAccessibilityServiceEnabled(context);
    }

    private View spaceView(int w, int h) {
        View v = new View(requireContext());
        v.setLayoutParams(new LinearLayout.LayoutParams(w, h));
        return v;
    }

    @Override
    public boolean onSoftLeft() {
        return onSelect();
    }

    @Override
    public boolean onSoftRight() {
        if (getActivity() != null) {
            getActivity().finish();
        }
        return true;
    }

    @Override
    public boolean onBack() {
        if (getActivity() != null) {
            getActivity().finish();
        }
        return true;
    }

    @Override
    public String getPageTitle() {
        return "原键鼠标";
    }

    @Override
    public String getSoftLeftText() {
        return "选择";
    }

    @Override
    public String getSoftRightText() {
        return "退出";
    }
}
