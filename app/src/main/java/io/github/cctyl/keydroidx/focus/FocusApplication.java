package io.github.cctyl.keydroidx.focus;

import android.app.Application;

import io.github.cctyl.nokia.common.feedback.NokiaFeedback;
import io.github.cctyl.nokia.common.feedback.NokiaFeedbackConfig;
import io.github.cctyl.nokia.common.feedback.NokiaInstall;
import io.github.cctyl.nokia.common.log.NokiaLog;
import io.github.cctyl.nokia.keycore.NokiaClient;

/**
 * 原键鼠标 / KeydroidXFocus 全局 Application。
 * 负责初始化 NokiaClient、日志收集系统以及用户意见反馈组件。
 */
public class FocusApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        // 早初始化 NokiaClient（建立 Provider 同步与 ThemeProvider 注册）
        NokiaClient.get(this);

        // 统一日志与崩溃捕获
        NokiaLog.setTag("KeydroidXFocus");
        NokiaLog.init(this);
        NokiaLog.installCrashHandler(this);
        NokiaLog.i("App", "FocusApplication onCreate");

        // 初始化意见反馈 + 安装统计组件（共用同一份配置）
        NokiaFeedback.init(
                new NokiaFeedbackConfig(
                        BuildConfig.FEEDBACK_UPLOAD_URL,
                        BuildConfig.FEEDBACK_INSTALL_URL,
                        BuildConfig.FEEDBACK_SECRET_KEY,
                        "KeydroidXFocus",
                        BuildConfig.VERSION_NAME,
                        null
                )
        );
        // 首次安装 / 版本升级时自动上报一次设备信息（后台、幂等、静默）
        NokiaInstall.reportOnce(this);

        // 初始化 MiniShizuku（用于 API < 24 上的 shell 模拟手势）
        io.github.cctyl.nokia.shizuku.MiniShizuku.init(this);
    }
}
