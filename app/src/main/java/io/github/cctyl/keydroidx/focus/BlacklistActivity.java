package io.github.cctyl.keydroidx.focus;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 应用黑名单配置页。
 *
 * 勾选的应用 = 黑名单：这些 App 内不显示光标，方向键 / 数字键 / 确认键等
 * 按键全部原样放行，交还给应用自己处理（与服务未拦截时行为完全一致）；
 * 未勾选的应用照常使用鼠标导航。
 *
 * 每次勾选立即写入 SharedPreferences；无障碍服务注册了对应的
 * OnSharedPreferenceChangeListener，无需重启服务即可生效。
 *
 * 注意：targetSdk 30+ 受包可见性限制，必须依赖 AndroidManifest 里的
 * &lt;queries&gt; 声明（MAIN / LAUNCHER），否则这里查不到任何其他应用。
 */
public class BlacklistActivity extends AppCompatActivity {

    private final List<AppInfo> apps = new ArrayList<>();
    private AppAdapter adapter;
    private TextView tvSummary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_blacklist);

        tvSummary = findViewById(R.id.tvBlacklistSummary);

        ListView listView = findViewById(R.id.lvApps);
        adapter = new AppAdapter();
        listView.setAdapter(adapter);
        // 整行点按切换勾选；CheckBox 本身设为不可点（见 item 布局），
        // 行为在功能机上更可预测：点哪行就切哪行，不会出现“点到勾没切到”的歧义。
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= apps.size()) return;
                AppInfo app = apps.get(position);
                app.blacklisted = !app.blacklisted;
                NavigationPrefs.setBlacklisted(BlacklistActivity.this,
                        app.packageName, app.blacklisted);
                updateSummary();
                adapter.notifyDataSetChanged();
            }
        });

        loadApps();
    }

    private void updateSummary() {
        int n = NavigationPrefs.getBlacklist(this).size();
        tvSummary.setText(getString(R.string.blacklist_summary, n));
    }

    /** 列出所有带桌面入口（LAUNCHER）的应用。放后台线程，避免大列表卡主线程。 */
    private void loadApps() {
        final PackageManager pm = getPackageManager();
        final String selfPkg = getPackageName();
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<AppInfo> result = new ArrayList<>();
                try {
                    Intent main = new Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_LAUNCHER);
                    List<ResolveInfo> ris;
                    if (Build.VERSION.SDK_INT >= 33) {
                        ris = pm.queryIntentActivities(
                                main, PackageManager.ResolveInfoFlags.of(0));
                    } else {
                        @SuppressWarnings("deprecation")
                        List<ResolveInfo> legacy = pm.queryIntentActivities(main, 0);
                        ris = legacy;
                    }
                    Set<String> blacklist = new HashSet<>(
                            NavigationPrefs.getBlacklist(BlacklistActivity.this));
                    Set<String> seen = new HashSet<>();
                    for (ResolveInfo ri : ris) {
                        if (ri == null || ri.activityInfo == null) continue;
                        String pkg = ri.activityInfo.packageName;
                        // 自己排除：自家界面本来就不拦截按键，无需进黑名单；
                        // seen 去重：一个应用可能有多个 LAUNCHER activity。
                        if (pkg == null || pkg.equals(selfPkg) || !seen.add(pkg)) continue;
                        AppInfo info = new AppInfo();
                        info.packageName = pkg;
                        CharSequence label = ri.loadLabel(pm);
                        info.label = (label == null || label.length() == 0) ? pkg : label;
                        info.icon = ri.loadIcon(pm);
                        info.blacklisted = blacklist.contains(pkg);
                        result.add(info);
                    }
                    Collections.sort(result, new Comparator<AppInfo>() {
                        @Override
                        public int compare(AppInfo a, AppInfo b) {
                            return String.CASE_INSENSITIVE_ORDER.compare(
                                    a.label.toString(), b.label.toString());
                        }
                    });
                } catch (Exception ignored) {
                }
                final List<AppInfo> loaded = result;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        apps.clear();
                        apps.addAll(loaded);
                        adapter.notifyDataSetChanged();
                        updateSummary();
                    }
                });
            }
        }, "blacklist-load").start();
    }

    private static class AppInfo {
        String packageName;
        CharSequence label;
        Drawable icon;
        boolean blacklisted;
    }

    private class AppAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return apps.size();
        }

        @Override
        public Object getItem(int position) {
            return apps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(BlacklistActivity.this)
                        .inflate(R.layout.item_blacklist_app, parent, false);
            }
            AppInfo app = apps.get(position);
            ImageView icon = v.findViewById(R.id.ivAppIcon);
            TextView label = v.findViewById(R.id.tvAppLabel);
            TextView pkg = v.findViewById(R.id.tvAppPackage);
            CheckBox box = v.findViewById(R.id.cbBlacklisted);
            icon.setImageDrawable(app.icon);
            label.setText(app.label);
            pkg.setText(app.packageName);
            box.setChecked(app.blacklisted);
            return v;
        }
    }
}
