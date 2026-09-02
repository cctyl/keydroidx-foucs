package io.github.cctyl.keydroidx.focus;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

/**
 * 屏幕光标前台保活服务通知辅助类。
 *
 * 1. 负责创建通知渠道（API 26+，IMPORTANCE_LOW 无声静默）；
 * 2. 构造并维护前台服务常驻通知（API 19+ 兼容，点击直达应用界面）；
 * 3. 安全调用 startForeground / stopForeground，适配 API 34+ FOREGROUND_SERVICE_TYPE_SPECIAL_USE；
 * 4. 检测通知权限（含 API 33+ POST_NOTIFICATIONS 及各版本系统级通知开关），提供申请与跳转系统设置引导。
 */
public final class FocusNotificationHelper {

    private static final String TAG = "FocusNotification";

    public static final String CHANNEL_ID = "keydroidx_focus_service_v3";
    public static final String CHANNEL_NAME = "原键鼠标常驻服务";
    public static final int NOTIFICATION_ID = 1001;
    public static final int REQ_CODE_NOTIFICATION_PERMISSION = 2001;

    private FocusNotificationHelper() {
    }

    /**
     * 创建静默通知渠道（API 26+）
     */
    public static void createNotificationChannel(@NonNull Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    // 清理旧版静音低优先级渠道（低优先级渠道在 Android 12+ 状态栏默认隐藏小图标）
                    try {
                        nm.deleteNotificationChannel("keydroidx_focus_keepalive");
                    } catch (Throwable ignored) {
                    }

                    if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                        NotificationChannel channel = new NotificationChannel(
                                CHANNEL_ID,
                                CHANNEL_NAME,
                                NotificationManager.IMPORTANCE_DEFAULT
                        );
                        channel.setDescription("保持原键鼠标后台无障碍光标服务稳定运行");
                        channel.setShowBadge(false);
                        channel.enableLights(false);
                        channel.enableVibration(false);
                        channel.setSound(null, null);
                        nm.createNotificationChannel(channel);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "createNotificationChannel failed", t);
            }
        }
    }

    /**
     * 构建保活常驻通知
     *
     * @param context    上下文
     * @param statusText 当前状态文本（如“屏幕光标运行中”、“原键鼠标已挂起 (*+#恢复)”等）
     */
    @NonNull
    public static Notification buildNotification(@NonNull Context context, @NonNull String statusText) {
        createNotificationChannel(context);

        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(context, 0, intent, flags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_mouse)
                .setContentTitle("原键鼠标")
                .setContentText(statusText)
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setShowWhen(false);

        return builder.build();
    }

    /**
     * 安全启动前台服务保活
     */
    public static void startForegroundSafe(@NonNull Service service, @NonNull String statusText) {
        try {
            Notification notification = buildNotification(service, statusText);
            if (Build.VERSION.SDK_INT >= 34) { // Android 14+
                service.startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                );
            } else {
                service.startForeground(NOTIFICATION_ID, notification);
            }
            Log.d(TAG, "startForeground success: " + statusText);
        } catch (Throwable t) {
            Log.w(TAG, "startForegroundSafe failed", t);
        }
    }

    /**
     * 更新前台通知内容
     */
    public static void updateNotification(@NonNull Context context, @NonNull String statusText) {
        try {
            if (!isNotificationEnabled(context)) {
                return;
            }
            Notification notification = buildNotification(context, statusText);
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification);
            Log.d(TAG, "updateNotification: " + statusText);
        } catch (SecurityException se) {
            Log.w(TAG, "Notification permission missing: " + se.getMessage());
        } catch (Throwable t) {
            Log.w(TAG, "updateNotification failed", t);
        }
    }

    /**
     * 停止前台服务并移除通知
     */
    public static void stopForegroundSafe(@NonNull Service service) {
        try {
            service.stopForeground(true);
        } catch (Throwable t) {
            Log.w(TAG, "stopForegroundSafe failed", t);
        }
    }

    /**
     * 检测通知权限是否已开启。
     * 包含 API 33+ POST_NOTIFICATIONS 动态权限及全版本系统级通知总开关。
     */
    public static boolean isNotificationEnabled(@NonNull Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled();
    }

    /**
     * 申请通知权限或引导跳转通知设置页
     */
    public static void requestNotificationPermission(@NonNull Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        activity,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQ_CODE_NOTIFICATION_PERMISSION
                );
                return;
            }
        }
        openNotificationSettings(activity);
    }

    /**
     * 跳转至系统通知设置页面
     */
    public static void openNotificationSettings(@NonNull Context context) {
        try {
            Intent intent = new Intent();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                intent.setAction(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                intent.setAction("android.settings.APP_NOTIFICATION_SETTINGS");
                intent.putExtra("app_package", context.getPackageName());
                intent.putExtra("app_uid", context.getApplicationInfo().uid);
            } else {
                intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.fromParts("package", context.getPackageName(), null));
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            try {
                Intent fallback = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                fallback.setData(Uri.fromParts("package", context.getPackageName(), null));
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(fallback);
            } catch (Exception ignored) {
            }
        }
    }
}
