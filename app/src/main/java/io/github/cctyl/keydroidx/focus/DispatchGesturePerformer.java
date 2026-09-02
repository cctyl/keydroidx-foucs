package io.github.cctyl.keydroidx.focus;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.TargetApi;
import android.graphics.Path;
import android.os.Build;
import android.util.Log;

/**
 * Android 7.0+ (API 24+) 手势派发实现。
 * 使用系统的 AccessibilityService.dispatchGesture()，零延迟、不需要 root/shizuku。
 */
@TargetApi(Build.VERSION_CODES.N)
public class DispatchGesturePerformer implements GesturePerformer {

    private static final String TAG = "DispatchGesture";
    private static final long DEFAULT_TAP_DURATION_MS = 60;
    private static final long DEFAULT_LONG_PRESS_MS = 700;

    private final AccessibilityService service;

    public DispatchGesturePerformer(AccessibilityService service) {
        this.service = service;
    }

    @Override
    public void click(float x, float y) {
        if (x <= 0 || y <= 0) return;
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, DEFAULT_TAP_DURATION_MS);
        GestureDescription gd = new GestureDescription.Builder().addStroke(stroke).build();
        boolean ok = service.dispatchGesture(gd, null, null);
        if (!ok) {
            Log.w(TAG, "click dispatchGesture returned false at (" + x + "," + y + ")");
        }
    }

    @Override
    public void longClick(float x, float y, long durationMs) {
        if (x <= 0 || y <= 0) return;
        long dur = durationMs > 0 ? durationMs : DEFAULT_LONG_PRESS_MS;
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, dur);
        GestureDescription gd = new GestureDescription.Builder().addStroke(stroke).build();
        boolean ok = service.dispatchGesture(gd, null, null);
        if (!ok) {
            Log.w(TAG, "longClick dispatchGesture returned false at (" + x + "," + y + ")");
        }
    }

    @Override
    public void swipe(float startX, float startY, float endX, float endY, long durationMs) {
        Path path = new Path();
        path.moveTo(startX, startY);
        path.lineTo(endX, endY);
        long dur = durationMs > 0 ? durationMs : 100;
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, dur);
        GestureDescription gd = new GestureDescription.Builder().addStroke(stroke).build();
        boolean ok = service.dispatchGesture(gd, null, null);
        if (!ok) {
            Log.w(TAG, "swipe dispatchGesture returned false (" + startX + "," + startY + ")->(" + endX + "," + endY + ")");
        }
    }

    @Override
    public void onDestroy() {
        // 无需额外资源释放
    }
}
