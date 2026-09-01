package io.github.cctyl.keydroidx.focus;

import android.app.Application;

import io.github.cctyl.nokia.common.feedback.NokiaFeedback;
import io.github.cctyl.nokia.common.feedback.NokiaFeedbackConfig;
import io.github.cctyl.nokia.common.log.NokiaLog;
import io.github.cctyl.nokia.keycore.NokiaClient;

/**
 * KeydroidX-Focus 全局 Application。
 * 负责初始化 NokiaClient、日志收集系统以及用户意见反馈组件。
 */
public class FocusApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        // 早初始化 NokiaClient（建立 Provider 同步与 ThemeProvider 注册）
        NokiaClient.get(this);

        // 统一日志与崩溃捕获
        NokiaLog.setTag("KeydroidX-Focus");
        NokiaLog.init(this);
        NokiaLog.installCrashHandler(this);
        NokiaLog.i("App", "FocusApplication onCreate");

        // 初始化意见反馈组件
        NokiaFeedback.init(
                new NokiaFeedbackConfig(
                        BuildConfig.FEEDBACK_UPLOAD_URL,
                        BuildConfig.FEEDBACK_SECRET_KEY,
                        "KeydroidX-Focus",
                        BuildConfig.VERSION_NAME,
                        null
                )
        );
    }
}
