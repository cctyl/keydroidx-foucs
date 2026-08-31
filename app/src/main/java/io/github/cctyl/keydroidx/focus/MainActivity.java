package io.github.cctyl.keydroidx.focus;

import android.content.Intent;
import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private TextView tvServiceStatus;
    private Switch swEnabled;
    private Button btnOpenSettings;
    private Button btnBlacklist;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvServiceStatus = findViewById(R.id.tvServiceStatus);
        swEnabled = findViewById(R.id.swEnabled);
        btnOpenSettings = findViewById(R.id.btnOpenSettings);
        btnBlacklist = findViewById(R.id.btnBlacklist);

        btnOpenSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 跳到系统无障碍设置页，让用户手动开启本服务
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });

        btnBlacklist.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 配置应用黑名单：黑名单 App 内不显示光标、按键全部放行
                startActivity(new Intent(MainActivity.this, BlacklistActivity.class));
            }
        });

        swEnabled.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                NavigationPrefs.setEnabled(MainActivity.this, isChecked);
                Toast.makeText(MainActivity.this,
                        isChecked ? "鼠标导航已启用" : "鼠标导航已停用",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean serviceOn = isAccessibilityServiceEnabled(this);
        boolean navOn = NavigationPrefs.isEnabled(this);

        if (serviceOn) {
            tvServiceStatus.setText("无障碍服务：已开启");
            tvServiceStatus.setTextColor(0xFF0055AA);
        } else {
            tvServiceStatus.setText("无障碍服务：未开启（点下方按钮去开启）");
            tvServiceStatus.setTextColor(0xFFB00020);
        }

        // 服务没开时强制把开关视作不可用
        swEnabled.setEnabled(serviceOn);
        swEnabled.setChecked(serviceOn && navOn);
    }

    /** 通过 Settings.Secure 检查本无障碍服务是否已被用户启用 */
    private boolean isAccessibilityServiceEnabled(Context context) {
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
