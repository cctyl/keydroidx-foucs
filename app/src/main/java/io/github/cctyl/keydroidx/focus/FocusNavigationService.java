package io.github.cctyl.keydroidx.focus;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 鼠标模式导航无障碍服务。
 *
 * 与“虚拟焦点在控件之间跳转”的区别：这里维护的是一个<b>屏幕坐标上的鼠标</b>。
 *
 * 1. 方向键移动鼠标（短按走一步；按住则连发，1 秒内加速到恒速后不再变）；
 * 2. 数字键 1~9 = 屏幕九宫格，短按把鼠标送到该格中心；
 * 3. 返回键 = 全局返回；
 * 4. 确认键 = 在光标坐标处点击；长按确认键 = 在光标坐标处长按；
 * 5. <b>0 键 + 方向键同时按住</b> = 从光标当前位置朝该方向滑动（拖拽）。
 *    0 键是纯修饰键，自身不产生任何动作，所以与确认键的“点击”语义零冲突。
 *
 * <b>核心原则：光标就是唯一的真相。</b>
 * 屏幕上不画任何控件高亮 / 锁定框，点击也不经过“选中某个 AccessibilityNodeInfo
 * 再对它派发 ACTION_CLICK”这一步，而是直接在光标坐标上派发手势。
 * 只要引入“猜控件”，就必然出现“光标在这里、却点了别处”（重叠控件、零尺寸节点、
 * 把 OnClickListener 挂在父容器上的 App 都会导致猜错），坐标点击没有这个环节。
 *
 * 悬浮层用 TYPE_ACCESSIBILITY_OVERLAY，不需要 SYSTEM_ALERT_WINDOW 悬浮窗权限，
 * 前提是用户已在系统设置里开启本无障碍服务。
 */
public class FocusNavigationService extends AccessibilityService {

    private static final String TAG = "FocusNavigationService";

    private static final long LONG_PRESS_MS = 500;
    /** 内容变化后重新收集控件的防抖延迟：把洪水般的 contentChanged 合并成一次 */
    private static final long REFRESH_DEBOUNCE_MS = 200;
    /** 两次重新收集之间的最小间隔 */
    private static final long REFRESH_MIN_INTERVAL_MS = 350;
    /** 按住方向键多久之后开始连续移动；在此之前只是“点一下走一步” */
    private static final long MOVE_REPEAT_DELAY_MS = 260;
    /**
     * 加速阶段的时长：从连发开始的那一刻起算，在这段时间里速度由慢平滑升到
     * {@link #MOVE_REPEAT_FINAL_MS}，<b>到点之后就恒定，不再继续加速</b>。
     *
     * 用时间而不是“步数”来衡量的原因：步长和间隔是同时变化的，按步数算的话
     * 实际加速时长会随参数漂移，无法保证“按住一秒后速度稳定”。
     */
    private static final long MOVE_ACCEL_DURATION_MS = 1000;
    /** 连发刚起步时的每一步间隔，故意放慢，方便精细对准 */
    private static final long MOVE_REPEAT_START_MS = 95;
    /** 加速结束后的恒定间隔；之后无论按多久都是这个速度 */
    private static final long MOVE_REPEAT_FINAL_MS = 52;
    /**
     * 连续移动已经跑起来之后，判定“同一次按压”的窗口。
     *
     * 比 {@link #KEY_STILL_HELD_MS} 短得多：连发中松手要尽快停，
     * 否则按 120~220ms 的判定窗口会多冲出去几百像素。
     * 又必须大于设备发 DOWN/UP 对的间隔（常见 50~100ms），否则连发会被自己掐死。
     */
    private static final long MOVE_STILL_HELD_MS = 120;
    /**
     * 已收到 ACTION_UP、正等待确认“是不是真松手”期间，连发定时器的重试间隔。
     *
     * 这段等待里<b>绝不能移动光标</b>——否则一次点按会走两步：
     * DOWN 立刻走第 1 步，{@link #MOVE_REPEAT_DELAY_MS}(260ms) 时连发定时器又走第 2 步，
     * 而释放确认要到 UP+220ms 才执行，来不及拦住它。
     * 但定时器也不能直接死掉：设备若用 DOWN/UP 对模拟长按，下一个 DOWN 会撤销“待释放”状态，
     * 定时器必须还活着才能接着跑，所以只重排、不移动。
     */
    private static final long MOVE_RELEASE_CHECK_MS = 50;
    /**
     * 同一个方向键、中间没有 UP、却在这么短时间内又来一个 repeatCount=0 的 DOWN，
     * 判定为 ROM 重复投递（过滤 + 兜底各一次），丢弃。人手连按必然夹着一次 UP。
     */
    private static final long DIR_DOWN_DEDUP_MS = 40;
    /**
     * 抬键后这么短的时间内又按下同一个键，视为<b>同一次按压</b>（按键 repeat），
     * 而不是一次新的短按。必须大于设备的按键重复间隔（常见 30~100ms），
     * 又不能太大，否则短按会有明显延迟。
     */
    private static final long KEY_STILL_HELD_MS = 220;
    /** 进入新页面后等待窗口就绪 */
    private static final long WINDOW_READY_DELAY_MS = 250;
    /** 两次方向键按下间隔超过这个值，就认为是一次新的点按（连发计数清零） */
    private static final long NEW_GESTURE_GAP_MS = 300;

    /** 单步像素 = 屏幕短边 * 该比例，且不小于 STEP_MIN_PX */
    private static final float STEP_RATIO = 0.03f;
    private static final float STEP_MIN_PX = 18f;
    /**
     * 加速结束时步长相对单步的倍数，之后恒定。
     *
     * 速度 = 步长 / 间隔，起步 32px/95ms ≈ 340px/s，恒速 58px/52ms ≈ 1100px/s，
     * 约 2 秒扫过一屏。之前是 2.4 倍 + 24ms 间隔（≈3200px/s），实测过快。
     */
    private static final float ACCEL_FACTOR = 1.8f;
    /**
     * 设备“健谈”（会持续发按键重复 / DOWN-UP 对）时，这么久没信号就认为按键已丢失。
     * 必须大于常见重复间隔（30~120ms），取 600ms 留足余量。
     */
    private static final long WATCHDOG_CHATTY_MS = 600;
    /**
     * 设备沉默（按住只发一个 DOWN）时没法判断还按没按着，只能给一个很长的兜底，
     * 让“按住 3 秒以上”也不断流。正常松手都由 ACTION_UP 处理，这里只防 UP 丢失。
     */
    private static final long WATCHDOG_SILENT_MS = 10000;
    /**
     * 拖拽（0+方向键）的速度曲线，与方向键移动光标同款：按住时长决定距离与间隔，
     * 到 {@link #DRAG_ACCEL_DURATION_MS} 后恒定不再变。
     * <pre>
     *   按住时长  0 ──── DRAG_ACCEL_DURATION_MS ────▶ ∞
     *   单次距离  0.12 ────── 线性增大 ──────▶ 0.30   恒定
     *   间隔      420ms ──── 线性减小 ────▶ 220ms     恒定
     *   手势时长  100ms（恒定，快甩 fling，不随距离拉长）
     * </pre>
     * 轻点只触发首段小步滑动（按一下滚一点），按住则持续滚、越滚越快直到稳速。
     * <p>关键：每次 swipe 走短时长 fling（{@link #DRAG_SWIPE_DURATION_MS}），
     * 不是慢拖。否则横向 ViewPager 会把短而慢的拖动判成"没到底"再弹回当前页，
     * 表现为"往左一下又往右一下"的抖动。短时长 → 速度高 → ViewPager 按速度提交切换。
     */
    private static final float DRAG_RATIO_START = 0.12f;
    /** 加速结束后的恒定滑动比例，比旧的 0.42 温和 */
    private static final float DRAG_RATIO_FINAL = 0.30f;
    /** 加速时长：到点后距离与间隔都恒定，不再增大 */
    private static final long DRAG_ACCEL_DURATION_MS = 1000;
    /** 连滑起步间隔（手势时长必须短于此值，否则上一次没演完下一次被判无效） */
    private static final long DRAG_INTERVAL_START_MS = 420;
    /** 连滑恒定后的最快间隔；必须 &gt; {@link #DRAG_SWIPE_DURATION_MS}(100) */
    private static final long DRAG_INTERVAL_FINAL_MS = 220;
    /**
     * 拖拽每次 swipe 的手势时长（恒定）。故意短：让每次都是快甩 fling 而非慢拖，
     * 横向 ViewPager 才会按速度提交 tab 切换，而不是摸一下又弹回。
     * 必须短于 {@link #DRAG_INTERVAL_FINAL_MS}。
     */
    private static final long DRAG_SWIPE_DURATION_MS = 100;
    /** 单次滑动的最短距离占屏幕短边比例，太短系统不认（拖拽起步小步也要保证过得去） */
    private static final float DRAG_SWIPE_MIN_RATIO = 0.06f;

    private WindowManager windowManager;
    private CursorOverlay overlay;
    private WindowManager.LayoutParams overlayParams;

    private int cursorX = -1;
    private int cursorY = -1;
    /** 连续同方向移动的步数，用于加速 */
    private int moveStreak;

    /** 方向键连发任务（自己驱动，不依赖系统按键重复） */
    private Runnable moveRepeatRunnable;
    /** 本次按住已连发的步数，仅用于日志 */
    private int moveRepeatCount;
    /** 是否已进入连续移动 */
    private boolean moveRepeating;
    /** 连发开始的时刻，用于计算加速进度 */
    private long moveRepeatStart;
    /** 当前步长相对单步的倍数，1=细步，连发时由加速进度决定 */
    private float moveSpeedScale = 1f;
    /** 最近一次收到方向键 DOWN/UP 信号的时刻，供看门狗判断按键是否还“活着” */
    private long moveKeyLastSignal;
    /**
     * 当前这次按压中，设备是否持续发来信号（repeat 事件 / DOWN-UP 对）。
     * 决定用短的还是长的看门狗：
     * 健谈的设备能靠信号判活，用 600ms；沉默的设备只能给 10s 兜底。
     */
    private boolean moveKeyChatty;
    /**
     * 已收到方向键的 ACTION_UP，正在等待确认是不是真松手。
     *
     * 物理上按键已经抬起了，这段时间内连发定时器必须停止移动（否则一次点按走两步），
     * 但又要保留自身，以便下一个 DOWN（设备用 DOWN/UP 对模拟长按）撤销该状态后能继续。
     */
    private boolean moveKeyUpPending;
    /** 上一次方向键 DOWN 的键码与时刻，用于识别 ROM 的重复投递 */
    private int lastDirDownCode = -1;
    private long lastDirDownTime;
    /** 上一次收到 ACTION_UP 的键码与时刻，用于在没有 repeat 机制的设备上把频繁 DOWN/UP 识别为同一次长按 */
    private int lastKeyUpCode = -1;
    private long lastKeyUpTime;

    /** 当前按住的方向键；null 表示没有方向键被按住 */
    private FocusNavigator.Direction heldDirection;
    /** 0 键（滑动修饰键）是否处于按下状态 */
    private boolean zeroDown;
    /** 上一次真正遍历过节点树的时刻 */
    private long lastCandidateScan;

    // ------------------------------------------------- 方向键 + 确认键 = 拖拽滑动
    private Runnable dragRepeatRunnable;
    /** 本次拖拽已滑动的次数，仅用于日志 */
    private int dragRepeatCount;
    /** 正在拖拽的方向；null 表示没在拖拽 */
    private FocusNavigator.Direction dragDirection;
    /** 本次拖拽开始的时刻，用于计算加速进度 */
    private long dragStart;

    private boolean editMode;
    private String currentPackage;
    private long lastMoveTime;
    private long lastRefreshTime;
    private final Rect screenRect = new Rect();

    private final Handler handler = new Handler(Looper.getMainLooper());

    private Runnable longPressRunnable;
    private boolean longPressFired;

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.uptimeMillis();
            long elapsed = now - lastRefreshTime;
            if (elapsed < REFRESH_MIN_INTERVAL_MS) {
                handler.postDelayed(this, REFRESH_MIN_INTERVAL_MS - elapsed);
                return;
            }
            lastRefreshTime = now;
            refreshCurrentWindow();
        }
    };

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
                    if (NavigationPrefs.KEY_ENABLED.equals(key)) {
                        if (NavigationPrefs.isEnabled(FocusNavigationService.this)) {
                            scheduleRefresh(WINDOW_READY_DELAY_MS);
                        } else {
                            hideOverlay("pref-disabled");
                        }
                    }
                }
            };

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) {
            info = new AccessibilityServiceInfo();
        }

        // 关键：一旦调用 setServiceInfo()，系统就以这个对象为准，XML 里声明的属性
        // 不会被自动合并进来。实测该 ROM 上报 capabilities=41（只有 取窗口内容/放大镜/截图），
        // 缺了 4=CAN_REQUEST_FILTER_KEY_EVENTS 与 16=CAN_PERFORM_GESTURES，
        // 于是 onKeyEvent 从不被回调、dispatchGesture 恒失败——表现为“按键无响应/手势无效”。
        // 所以这里必须逐项显式声明，不能依赖 XML。
        // 这几个字段在部分 compileSdk 下不可见，用反射设置，编译与运行都稳。
        dumpCapabilityFields(info);
        setCapability(info, "canRetrieveWindowContent", true);
        setCapability(info, "canRequestFilterKeyEvents", true);
        setCapability(info, "canPerformGestures", true);
        patchCapabilities(info);

        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        // 关键：不申请这个 flag，onKeyEvent 永远不会被回调
        info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        info.eventTypes |= AccessibilityEvent.TYPE_VIEW_FOCUSED;
        setServiceInfo(info);

        Log.d(TAG, "serviceInfo applied, capabilities=" + info.getCapabilities()
                + " flags=" + Integer.toHexString(info.flags));

        updateScreenRect();
        initOverlay();
        NavigationPrefs.registerListener(this, prefListener);

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (NavigationPrefs.isEnabled(FocusNavigationService.this)) {
                    refreshCurrentWindow();
                }
            }
        }, WINDOW_READY_DELAY_MS);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateScreenRect();
        // 光标还没初始化过（值是 -1）时不要 clamp，否则会被钉在 (1,1) 而不是屏幕中心
        if (cursorX >= 0) cursorX = clamp(cursorX, 1, screenRect.width() - 1);
        if (cursorY >= 0) cursorY = clamp(cursorY, 1, screenRect.height() - 1);
        if (NavigationPrefs.isEnabled(this)) {
            scheduleRefresh(WINDOW_READY_DELAY_MS);
        }
    }

    /**
     * 设置 AccessibilityServiceInfo 上的布尔能力字段。
     *
     * 这些字段（canRequestFilterKeyEvents / canPerformGestures 等）在部分 compileSdk 下
     * 不对外可见，直接赋值编译不过；但它们在运行时的 AccessibilityServiceInfo 类里是真实存在的，
     * 且正是 getCapabilities() 的计算依据。反射是这里唯一既编译得过、又真的生效的写法。
     */
    private static void setCapability(AccessibilityServiceInfo info, String name, boolean value) {
        try {
            java.lang.reflect.Field f = AccessibilityServiceInfo.class.getDeclaredField(name);
            f.setAccessible(true);
            f.setBoolean(info, value);
            Log.d(TAG, "capability " + name + " -> " + value);
        } catch (Throwable t) {
            Log.w(TAG, "capability " + name + " unavailable: " + t);
        }
    }

    /**
     * 临时诊断：把 AccessibilityServiceInfo 里与能力相关的真实字段名与取值打出来。
     * 不同 ROM 的字段命名差异很大，靠猜名字改不到点上，先看清楚再动手。
     */
    private static void dumpCapabilityFields(AccessibilityServiceInfo info) {
        try {
            java.lang.reflect.Field[] fs = AccessibilityServiceInfo.class.getDeclaredFields();
            StringBuilder sb = new StringBuilder();
            for (java.lang.reflect.Field f : fs) {
                String n = f.getName().toLowerCase();
                if (n.contains("capab") || n.contains("gesture")
                        || n.contains("keyevent") || n.contains("window")
                        || n.contains("magnif") || n.contains("can")) {
                    f.setAccessible(true);
                    sb.append(f.getName()).append("=").append(f.get(info)).append("; ");
                }
            }
            Log.d(TAG, "a11y fields: " + sb);
        } catch (Throwable t) {
            Log.w(TAG, "dumpCapabilityFields failed: " + t);
        }
    }

    /**
     * 直接补齐 capabilities 位。
     *
     * 部分 ROM（如本机）根本没有 canPerformGestures 之类的布尔字段，
     * 能力是以一个整数字段 mCapabilities 保存的，XML 声明的属性未必被完整解析
     * （实测上报 41 = 取窗口内容|放大镜|截图，缺 4=按键过滤 与 16=手势）。
     * 这里按位补上，拿不到就放弃，不影响主流程。
     */
    private static void patchCapabilities(AccessibilityServiceInfo info) {
        final int CAP_KEY_EVENTS = 0x00000004;
        final int CAP_GESTURES = 0x00000010;
        try {
            java.lang.reflect.Field f =
                    AccessibilityServiceInfo.class.getDeclaredField("mCapabilities");
            f.setAccessible(true);
            int caps = f.getInt(info);
            int patched = caps | CAP_KEY_EVENTS | CAP_GESTURES;
            f.setInt(info, patched);
            Log.d(TAG, "patchCapabilities " + caps + " -> " + patched);
        } catch (Throwable t) {
            Log.w(TAG, "patchCapabilities unavailable: " + t);
        }
    }

    private void updateScreenRect() {
        // 光标活动范围 = 整个物理屏幕（含系统状态栏 / 导航栏 inset），
        // 不是应用内容区。原因：悬浮层本身是 MATCH_PARENT 铺满整屏，
        // 而系统给应用的 DisplayMetrics.heightPixels 往往已把导航栏 inset 扣掉
        // （本机 320x480 物理屏，应用 app 尺寸是 320x439），
        // 若用它当 maxY，光标永远到不了底部被 inset 遮住的那几行——
        // 表现正是“选不中最底下一行 tab”。
        int w;
        int h;
        WindowManager wm = windowManager;
        if (wm == null) {
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
            try {
                android.graphics.Rect max =
                        wm.getMaximumWindowMetrics().getBounds();
                w = max.width();
                h = max.height();
            } catch (Throwable t) {
                Log.w(TAG, "getMaximumWindowMetrics failed: " + t);
                DisplayMetrics dm = getResources().getDisplayMetrics();
                w = dm.widthPixels;
                h = dm.heightPixels;
            }
        } else {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            w = dm.widthPixels;
            h = dm.heightPixels;
        }
        screenRect.set(0, 0, w, h);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        Log.d(TAG, "updateScreenRect screen=" + w + "x" + h
                + " dm=" + dm.widthPixels + "x" + dm.heightPixels);
    }

    // ---------------------------------------------------------------- 悬浮鼠标层

    private void initOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) return;

        overlay = new CursorOverlay(this);

        overlayParams = new WindowManager.LayoutParams();
        // 无障碍服务专用窗口类型：不需要 SYSTEM_ALERT_WINDOW 悬浮窗权限
        overlayParams.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        overlayParams.format = PixelFormat.TRANSLUCENT;
        // 必须显式指定 gravity：不同 ROM 对默认值的处理不一致，否则整块画布会偏移
        overlayParams.gravity = Gravity.TOP | Gravity.START;
        overlayParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        overlayParams.width = WindowManager.LayoutParams.MATCH_PARENT;
        overlayParams.height = WindowManager.LayoutParams.MATCH_PARENT;
        overlayParams.x = 0;
        overlayParams.y = 0;

        try {
            windowManager.addView(overlay, overlayParams);
            overlay.setVisibility(View.GONE);
        } catch (Exception e) {
            Log.w(TAG, "addView failed", e);
            overlay = null;
        }
    }

    private void hideOverlay() {
        hideOverlay("");
    }

    private void hideOverlay(final String reason) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (overlay == null || overlay.getVisibility() == View.GONE) return;
                Log.d(TAG, "hideOverlay reason=" + reason + " pkg=" + currentPackage);
                overlay.setVisibility(View.GONE);
            }
        });
    }

    /**
     * 把光标位置交给悬浮层重绘。
     *
     * 只画光标本身，没有任何高亮 / 锁定框：屏幕上看到的光标点就是确认键会点下去的坐标，
     * 两者不可能不一致。
     */
    private void drawCursor() {
        final int cx = cursorX;
        final int cy = cursorY;
        if (cx < 0 || cy < 0) return;
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (overlay == null) return;
                overlay.update(cx, cy);
                if (overlay.getVisibility() != View.VISIBLE) {
                    overlay.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    /**
     * 页面变化后刷新光标显示。
     *
     * 光标位置不受影响：它属于“鼠标”，不属于任何页面内容。
     */
    private void refreshCurrentWindow() {
        refreshActivePackage();

        if (!NavigationPrefs.isEnabled(this) || isOurOwnApp()) {
            hideOverlay("refresh-ownApp:" + currentPackage);
            return;
        }
        if (editMode) {
            hideOverlay("refresh-editMode");
            return;
        }
        if (cursorX < 0 || cursorY < 0) {
            cursorX = screenRect.centerX();
            cursorY = screenRect.centerY();
        }
        drawCursor();
    }

    private void scheduleRefresh(long delay) {
        handler.removeCallbacks(refreshRunnable);
        handler.postDelayed(refreshRunnable, delay);
    }

    // ---------------------------------------------------------------- 鼠标移动

    private void moveCursor(FocusNavigator.Direction dir) {
        if (cursorX < 0 || cursorY < 0) {
            cursorX = screenRect.centerX();
            cursorY = screenRect.centerY();
        }

        int minX = 1;
        int maxX = screenRect.width() - 1;
        int minY = 1;
        int maxY = screenRect.height() - 1;

        // 光标只负责移动，不触发滚动：顶到边缘就 clamp 住停在那。
        // 滚动只由 0+方向键（拖拽）触发，与光标解耦。
        int step = cursorStep();
        int nx = cursorX;
        int ny = cursorY;
        switch (dir) {
            case UP:
                ny -= step;
                break;
            case DOWN:
                ny += step;
                break;
            case LEFT:
                nx -= step;
                break;
            case RIGHT:
            default:
                nx += step;
                break;
        }
        cursorX = clamp(nx, minX, maxX);
        cursorY = clamp(ny, minY, maxY);
        if (cursorY >= maxY || cursorX <= minX || cursorX >= maxX) {
            Log.d(TAG, "moveCursor clamped (" + cursorX + "," + cursorY
                    + ") maxY=" + maxY + " maxX=" + maxX);
        }
        drawCursor();
    }

    private int cursorStep() {
        int base = Math.round(Math.min(screenRect.width(), screenRect.height()) * STEP_RATIO);
        if (base < STEP_MIN_PX) base = (int) STEP_MIN_PX;
        return Math.round(base * moveSpeedScale);
    }

    /**
     * 九宫格跳转：1~9 对应屏幕 3x3 的九块区域（1 左上，9 右下）。
     *
     * 落点<b>严格是该格的几何中心，不参考任何控件</b>。
     *
     * 为什么不做“吸附到格内控件”：那会让落点变得不可预测——列表项常常横跨整屏宽，
     * 一旦某格是空的、退化成“与格子相交就吸附”，光标就会被拽到邻格某个大控件的中心，
     * 表现就是“按 5 却跳到了下面”“最下面一格粒度特别大”。
     * 而“按哪个键就到哪一格”的可预测性最重要：现在确认键就是坐标点击，
     * 光标落在哪就点哪，不需要任何“锁定”。
     */
    private void jumpToGrid(int keyCode) {
        int index = gridIndexOf(keyCode);
        if (index < 0) return;

        int col = index % 3;
        int row = index / 3;
        // 用浮点算边界再取整，保证严格九等分（屏宽不能整除 3 时也不会让最后一格偏大）
        int w = screenRect.width();
        int h = screenRect.height();
        int left = Math.round(col * w / 3f);
        int right = Math.round((col + 1) * w / 3f);
        int top = Math.round(row * h / 3f);
        int bottom = Math.round((row + 1) * h / 3f);

        cursorX = clamp((left + right) / 2, 1, w - 1);
        cursorY = clamp((top + bottom) / 2, 1, h - 1);

        Log.d(TAG, "jumpToGrid key=" + (index + 1)
                + " cell=[" + left + "," + top + "][" + right + "," + bottom + "]"
                + " -> (" + cursorX + "," + cursorY + ")");

        drawCursor();
    }

    private static int gridIndexOf(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_1: return 0;
            case KeyEvent.KEYCODE_2: return 1;
            case KeyEvent.KEYCODE_3: return 2;
            case KeyEvent.KEYCODE_4: return 3;
            case KeyEvent.KEYCODE_5: return 4;
            case KeyEvent.KEYCODE_6: return 5;
            case KeyEvent.KEYCODE_7: return 6;
            case KeyEvent.KEYCODE_8: return 7;
            case KeyEvent.KEYCODE_9: return 8;
            default: return -1;
        }
    }

    // ---------------------------------------------------------------- 滚动 / 翻页（已移除 2468 翻页，保留确认键等逻辑）

    // ---------------------------------------------------------------- 确认键

    /**
     * 确认键：<b>点光标所在的坐标</b>。
     *
     * 不再走“锁定某个控件再对该控件派发 ACTION_CLICK”那条路。
     * 那条路的问题在于：选中的控件是用几何算法从节点树里猜出来的，
     * 只要猜错一个（重叠控件、零尺寸节点、把点击挂在父容器上的 App），
     * 就会出现“光标在这里、却点了别处”。坐标点击没有这个中间环节，所见即所点。
     */
    private void performCursorClick() {
        if (cursorX < 0 || cursorY < 0) {
            cursorX = screenRect.centerX();
            cursorY = screenRect.centerY();
            drawCursor();
        }
        Log.d(TAG, "click at (" + cursorX + "," + cursorY + ")");
        if (!clickByGesture(cursorX, cursorY)) {
            toast("该位置无法点击");
        }
    }

    /** 按屏幕坐标派发一次点击手势 */
    private boolean clickByGesture(int x, int y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        if (x <= 0 || y <= 0) return false;
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 60);
        GestureDescription gd = new GestureDescription.Builder().addStroke(stroke).build();
        return dispatchGesture(gd, null, null);
    }

    /** 长按确认键：在光标所在坐标长按（与点击同理，所见即所点） */
    private void performCursorLongClick() {
        if (cursorX < 0 || cursorY < 0) {
            cursorX = screenRect.centerX();
            cursorY = screenRect.centerY();
        }
        Log.d(TAG, "longClick at (" + cursorX + "," + cursorY + ")");
        longPressByGesture(cursorX, cursorY);
    }

    private boolean longPressByGesture(int x, int y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        if (x <= 0 || y <= 0) return false;
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 700);
        GestureDescription gd = new GestureDescription.Builder().addStroke(stroke).build();
        return dispatchGesture(gd, null, null);
    }

    private void toast(final String msg) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(FocusNavigationService.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ---------------------------------------------------------------- 事件处理

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 关键：我们自己的悬浮层重绘会产生 contentChanged 事件，且包名是本应用。
        // 若据此覆盖 currentPackage，isOurOwnApp() 会恒为 true，鼠标刚亮起就被自己藏掉。
        CharSequence pkgCs = event.getPackageName();
        if (pkgCs != null) {
            String pkg = pkgCs.toString();
            if (pkg.length() > 0 && !pkg.equals(getPackageName())) {
                currentPackage = pkg;
            }
        }

        int type = event.getEventType();

        // 追踪输入框焦点，进入编辑模式后不再拦截按键
        if (type == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            AccessibilityNodeInfo src = null;
            try {
                src = event.getSource();
            } catch (Exception ignored) {
            }
            if (src != null) {
                boolean editable = src.isEditable() || isEditableClassName(src.getClassName());
                if (editable != editMode) {
                    editMode = editable;
                    if (editMode) hideOverlay("editMode-enter");
                }
                src.recycle();
            }
        } else if (type == AccessibilityEvent.TYPE_VIEW_CLICKED && editMode) {
            // 点了非输入类控件，视为退出编辑模式（仅靠 VIEW_FOCUSED 会卡死在编辑态）
            AccessibilityNodeInfo src = null;
            try {
                src = event.getSource();
            } catch (Exception ignored) {
            }
            boolean stillEditing = src != null
                    && (src.isEditable() || isEditableClassName(src.getClassName()));
            if (src != null) src.recycle();
            if (!stillEditing) {
                editMode = false;
                scheduleRefresh(REFRESH_DEBOUNCE_MS);
            }
        }

        if (!NavigationPrefs.isEnabled(this)) {
            hideOverlay("disabled");
            return;
        }
        if (isOurOwnApp()) {
            hideOverlay("ownApp:" + currentPackage);
            return;
        }
        if (editMode) {
            hideOverlay("editMode");
            return;
        }

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // 进入新页面：控件快照作废，重新收集；光标位置保留
            moveStreak = 0;
            scheduleRefresh(WINDOW_READY_DELAY_MS);
        } else if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_SCROLLED
                || type == AccessibilityEvent.TYPE_VIEW_SELECTED) {
            scheduleRefresh(REFRESH_DEBOUNCE_MS);
        }
    }

    private boolean isEditableClassName(CharSequence cn) {
        return cn != null && cn.toString().contains("EditText");
    }

    private boolean isOurOwnApp() {
        return currentPackage != null && currentPackage.equals(getPackageName());
    }

    /**
     * 权威地判定前台应用包名：取 Z 序最靠上的 APPLICATION 窗口。
     *
     * 不能只信事件里的包名的原因：悬浮层是我们自己进程里的真实 View，
     * invalidate 会发出 TYPE_WINDOW_CONTENT_CHANGED 且包名是本应用，
     * 一旦据此更新 currentPackage，isOurOwnApp() 就会恒为 true——表现为“亮一下就灭”。
     */
    private void refreshActivePackage() {
        String pkg = resolveForegroundPackage();
        if (pkg != null) {
            currentPackage = pkg;
        }
    }

    /**
     * 取当前应该被导航的窗口根节点。
     *
     * 不能用 getRootInActiveWindow() 了事的原因：Dialog / PopupWindow / BottomSheet 往往是
     * 独立的 Window，而 getRootInActiveWindow() 返回的不一定是它们。结果就是弹窗弹出后
     * 鼠标仍然停留在被遮住的底层页面上——表现为“弹窗里的按钮选不中”。
     *
     * 这里按 Z 序从上层往下找：优先已获得焦点的 APPLICATION 窗口，否则取最上层的那个。
     */
    private AccessibilityNodeInfo getFocusableRoot() {
        List<AccessibilityWindowInfo> windows;
        try {
            windows = getWindows();
        } catch (Exception e) {
            windows = null;
        }

        if (windows != null && !windows.isEmpty()) {
            try {
                // getWindows() 返回顺序为 Z 序由下到上
                AccessibilityNodeInfo focused = null;
                AccessibilityNodeInfo topMost = null;

                for (int i = windows.size() - 1; i >= 0; i--) {
                    AccessibilityWindowInfo w = windows.get(i);
                    if (w == null) continue;
                    try {
                        if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                        AccessibilityNodeInfo root = w.getRoot();
                        if (root == null) continue;
                        // 同一个实例可能被同时记为 topMost 和 focused，用 used 保证只保留一份
                        boolean used = false;
                        if (topMost == null) {
                            topMost = root;
                            used = true;
                        }
                        if (w.isFocused()) {
                            if (focused != null) focused.recycle();
                            focused = root;
                            used = true;
                        }
                        if (!used) root.recycle();
                    } finally {
                        w.recycle();
                    }
                }

                if (focused != null) {
                    if (topMost != null && topMost != focused) topMost.recycle();
                    return focused;
                }
                if (topMost != null) return topMost;
            } catch (Exception ignored) {
            }
        }

        try {
            return getRootInActiveWindow();
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveForegroundPackage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            List<AccessibilityWindowInfo> windows;
            try {
                windows = getWindows();
            } catch (Exception e) {
                windows = null;
            }
            if (windows != null) {
                try {
                    // getWindows() 返回顺序为 Z 序由下到上，取最顶层的 APPLICATION 窗口
                    for (int i = windows.size() - 1; i >= 0; i--) {
                        AccessibilityWindowInfo w = windows.get(i);
                        if (w == null) continue;
                        try {
                            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                            AccessibilityNodeInfo root = w.getRoot();
                            if (root == null) continue;
                            CharSequence cs = root.getPackageName();
                            root.recycle();
                            if (cs != null && cs.length() > 0) return cs.toString();
                        } finally {
                            w.recycle();
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        AccessibilityNodeInfo root;
        try {
            root = getRootInActiveWindow();
        } catch (Exception e) {
            return null;
        }
        if (root == null) return null;
        CharSequence cs = root.getPackageName();
        String pkg = cs == null ? null : cs.toString();
        root.recycle();
        return pkg;
    }

    // ---------------------------------------------------------------- 按键

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        Log.d(TAG, "onKeyEvent code=" + event.getKeyCode()
                + " action=" + event.getAction()
                + " repeat=" + event.getRepeatCount()
                + " pkg=" + currentPackage);

        if (!NavigationPrefs.isEnabled(this)) {
            stopMoveRepeat();
            return false;
        }
        refreshActivePackage();
        // 本 App 自己的界面、以及输入框获得焦点时，放行按键
        if (isOurOwnApp() || editMode) {
            stopMoveRepeat();
            return false;
        }

        int keyCode = event.getKeyCode();
        int action = event.getAction();

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return handleDirectionKey(event, directionOf(keyCode));

            case KeyEvent.KEYCODE_0:
                return handleZeroKey(event);

            case KeyEvent.KEYCODE_1:
            case KeyEvent.KEYCODE_2:
            case KeyEvent.KEYCODE_3:
            case KeyEvent.KEYCODE_4:
            case KeyEvent.KEYCODE_5:
            case KeyEvent.KEYCODE_6:
            case KeyEvent.KEYCODE_7:
            case KeyEvent.KEYCODE_8:
            case KeyEvent.KEYCODE_9:
                return handleNumberKey(event);

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                return handleSelectKey(event);

            case KeyEvent.KEYCODE_BACK:
                if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    performGlobalAction(GLOBAL_ACTION_BACK);
                }
                return true;

            default:
                return false;
        }
    }

    /**
     * 方向键：短按走一步；按住则<b>由服务自己驱动连发</b>，间隔递减 + 步长递增。
     *
     * 为什么不依赖系统按键重复（repeatCount）：不同设备按键行为差异巨大，
     * 不少功能机 / 蓝牙键盘按住方向键时<b>根本不发重复事件</b>，
     * 要么只有按下时一个 DOWN、松手时一个 UP，要么发一串 DOWN/UP 对而 repeatCount 始终为 0。
     * 两种情况下“按住让光标连续移动”都不会发生，只会走一步。
     * 全靠服务自己的定时器自驱动，按键事件只用来起定时器、保活和确认松手，
     * 才能在两类设备上都稳稳连发。
     * - 首次 DOWN：立刻走一步（保住点按的即时反馈），并起连发定时器；
     * - 同一次按压的后续信号（repeatCount&gt;0 的 DOWN，或 {@link #KEY_STILL_HELD_MS} 内
     *   同一个键的再次 DOWN）：只刷新看门狗时间，定时器继续跑，绝不重启；
     * - UP：不立即停，延迟一小会儿确认真的松手，避免 DOWN/UP 对把定时器掐死在第一次触发前。
     */
    private boolean handleDirectionKey(KeyEvent event, final FocusNavigator.Direction dir) {
        final int keyCode = event.getKeyCode();
        final long now = SystemClock.uptimeMillis();

        if (event.getAction() == KeyEvent.ACTION_UP) {
            moveKeyLastSignal = now;
            lastKeyUpCode = keyCode;
            lastKeyUpTime = now;
            // 物理上已经抬起：立刻禁止连发再移动，只是不马上销毁状态
            moveKeyUpPending = true;
            // 不立即判定为松手：等一小会儿，看它是不是同一次长按里的又一次按下
            handler.removeCallbacks(moveReleaseRunnable);
            handler.postDelayed(moveReleaseRunnable,
                    moveRepeating ? MOVE_STILL_HELD_MS : KEY_STILL_HELD_MS);
            Log.v(TAG, "dir UP " + keyCode + " repeating=" + moveRepeating);
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;

        moveKeyLastSignal = now;
        // 又按下了，撤销“待释放”状态
        moveKeyUpPending = false;

        // 同一个键在极短时间内又来一个 repeatCount=0 的 DOWN，且中间没有 UP。
        // 物理上不可能（人手连按中间必有一次 UP；真连发会带 repeatCount>0），
        // 只可能是 ROM 把同一个事件既走过滤又走兜底、投递了两次。丢掉，避免一次点按走两步。
        if (keyCode == lastDirDownCode && now - lastDirDownTime < DIR_DOWN_DEDUP_MS) {
            Log.d(TAG, "dir DOWN " + keyCode + " duplicate ignored (" + (now - lastDirDownTime)
                    + "ms)");
            return true;
        }
        lastDirDownCode = keyCode;
        lastDirDownTime = now;

        // 系统的按键重复：定时器已经在跑，只保活
        if (event.getRepeatCount() > 0) {
            moveKeyChatty = true;
            handler.removeCallbacks(moveReleaseRunnable);
            return true;
        }

        // 抬键后很快又按下同一个键 = 同一次按压，沿用原计时
        boolean samePress = (keyCode == lastKeyUpCode)
                && (now - lastKeyUpTime
                < (moveRepeating ? MOVE_STILL_HELD_MS : KEY_STILL_HELD_MS));

        // 来了新的按下，说明不是松手，取消待判定的收尾
        handler.removeCallbacks(moveReleaseRunnable);

        if (samePress) {
            // 设备确实在持续发信号，可以用短看门狗
            moveKeyChatty = true;
            Log.v(TAG, "dir DOWN " + keyCode + " samePress");
            return true;
        }

        // 一次全新的按压
        stopMoveRepeat();
        // 间隔较久的一次新点按，清零加速计数（部分设备不保证送达 ACTION_UP）
        if (now - lastMoveTime > NEW_GESTURE_GAP_MS) moveStreak = 0;
        lastMoveTime = now;
        moveRepeatCount = 0;
        moveKeyChatty = false;
        // 全新按压一律从细步起步
        moveSpeedScale = 1f;
        heldDirection = dir;
        Log.d(TAG, "dir DOWN " + keyCode + " newPress zeroDown=" + zeroDown);

        // 0 键已经按住：这次方向键不移动光标，而是“在光标处朝该方向滑动”
        if (zeroDown) {
            startDrag(dir);
            return true;
        }

        moveCursor(dir);
        startMoveRepeat(dir);
        return true;
    }

    /**
     * 起连发定时器：到点走一步，再排下一次，直到按键被判定为松开。
     *
     * 速度曲线由“按住的时长”决定，而不是“已经走了多少步”：
     * <pre>
     *   按住时长  0 ────── MOVE_ACCEL_DURATION_MS(1s) ──────▶ ∞
     *   步长      base ──────── 线性增大 ────────▶ base*1.8   恒定
     *   间隔      95ms ──────── 线性减小 ────────▶ 52ms       恒定
     * </pre>
     * 到点后 clamp 住进度，速度就锁死在恒定值，按多久都不会再快。
     */
    private void startMoveRepeat(final FocusNavigator.Direction dir) {
        moveRepeatStart = SystemClock.uptimeMillis();
        final Runnable[] holder = new Runnable[1];
        holder[0] = new Runnable() {
            @Override
            public void run() {
                long now = SystemClock.uptimeMillis();
                // 看门狗：仅在 ACTION_UP 丢失时兜底，正常松手由 UP 分支停止。
                // 沉默的设备（按住只发一个 DOWN）没法靠信号判活，给它很长的兜底，
                // 否则“按住超过 3 秒就断流”。
                long watchdog = moveKeyChatty ? WATCHDOG_CHATTY_MS : WATCHDOG_SILENT_MS;
                if (now - moveKeyLastSignal > watchdog) {
                    Log.d(TAG, "moveRepeat stop: no key signal for " + watchdog + "ms");
                    stopMoveRepeat();
                    return;
                }
                // 按键已经抬起（只是在等确认）：只重排自己，绝不移动。
                // 少了这个判断，一次点按就会走两步——DOWN 走第 1 步，
                // 260ms 后连发定时器再走第 2 步，而释放确认要到 UP+220ms 才来得及拦。
                if (moveKeyUpPending) {
                    handler.postDelayed(holder[0], MOVE_RELEASE_CHECK_MS);
                    return;
                }
                moveRepeating = true;

                // 加速进度 0~1，到 1 之后就卡住不再增长 —— 这就是“一秒后速度恒定”
                float t = (float) (now - moveRepeatStart) / (float) MOVE_ACCEL_DURATION_MS;
                if (t < 0f) t = 0f;
                if (t > 1f) t = 1f;

                long interval = Math.round(MOVE_REPEAT_START_MS
                        + (MOVE_REPEAT_FINAL_MS - MOVE_REPEAT_START_MS) * t);
                moveSpeedScale = 1f + (ACCEL_FACTOR - 1f) * t;

                moveRepeatCount++;
                moveStreak++;
                lastMoveTime = now;
                moveCursor(dir);
                Log.v(TAG, "moveRepeat #" + moveRepeatCount + " t=" + t
                        + " interval=" + interval + " scale=" + moveSpeedScale);
                handler.postDelayed(holder[0], interval);
            }
        };
        moveRepeatRunnable = holder[0];
        handler.postDelayed(holder[0], MOVE_REPEAT_DELAY_MS);
    }

    /** 真正松手后的收尾：停掉连发与滑动、清零加速、补一次节点收集 */
    private final Runnable moveReleaseRunnable = new Runnable() {
        @Override
        public void run() {
            stopMoveRepeat();
            stopDrag();
            heldDirection = null;
            moveStreak = 0;
            moveSpeedScale = 1f;
            Log.v(TAG, "dir released");
        }
    };

    private void stopMoveRepeat() {
        moveKeyUpPending = false;
        if (moveRepeatRunnable != null) {
            handler.removeCallbacks(moveRepeatRunnable);
            moveRepeatRunnable = null;
        }
        if (moveRepeating) {
            moveRepeating = false;
            moveRepeatCount = 0;
            // 连发期间跳过了整树遍历，停下后补一次，让候选与停下来后的界面对上
            scheduleRefresh(0);
        }
    }

    // ------------------------------------------------- 0 键 + 方向键 = 拖拽滑动

    /**
     * 开始“在光标处朝 dir 方向滑动”。
     *
     * 触发条件是<b>0 键与方向键同时按住</b>（谁先按都行）：此时方向键不再移动光标，
     * 而是变成一次拖拽手势，起点就是光标当前所在的坐标——相当于按住屏幕往外拖。
     * 松开任意一个键就停。
     */
    private void startDrag(final FocusNavigator.Direction dir) {
        stopMoveRepeat();
        if (dragDirection == dir && dragRepeatRunnable != null) return;

        stopDrag();
        dragDirection = dir;
        dragRepeatCount = 0;
        dragStart = SystemClock.uptimeMillis();
        Log.d(TAG, "drag start dir=" + dir + " at (" + cursorX + "," + cursorY + ")");

        final Runnable[] holder = new Runnable[1];
        holder[0] = new Runnable() {
            @Override
            public void run() {
                // 任一键松开就停
                if (!zeroDown || heldDirection == null) {
                    stopDrag();
                    return;
                }
                long now = SystemClock.uptimeMillis();
                long watchdog = moveKeyChatty ? WATCHDOG_CHATTY_MS : WATCHDOG_SILENT_MS;
                if (now - moveKeyLastSignal > watchdog) {
                    Log.d(TAG, "drag stop: no key signal for " + watchdog + "ms");
                    stopDrag();
                    return;
                }
                // 加速进度 0~1，到 1 后恒定——与 startMoveRepeat 同一约定
                float t = (float) (now - dragStart) / (float) DRAG_ACCEL_DURATION_MS;
                if (t < 0f) t = 0f;
                if (t > 1f) t = 1f;
                float ratio = DRAG_RATIO_START + (DRAG_RATIO_FINAL - DRAG_RATIO_START) * t;
                long interval = Math.round(DRAG_INTERVAL_START_MS
                        + (DRAG_INTERVAL_FINAL_MS - DRAG_INTERVAL_START_MS) * t);
                // 手势时长必须短于间隔，否则上一次没演完下一次就来了，会被判无效
                // 恒定短时长 fling：不随距离/间隔拉长，保证每次都是快甩，
                // ViewPager 按速度提交、ScrollView 按 fling 滚。
                long duration = Math.max(80, Math.min(DRAG_SWIPE_DURATION_MS, interval - 60));
                dragRepeatCount++;
                swipeFromCursor(dir, ratio, duration);
                Log.v(TAG, "drag #" + dragRepeatCount + " t=" + t
                        + " ratio=" + ratio + " interval=" + interval);
                handler.postDelayed(holder[0], interval);
            }
        };
        dragRepeatRunnable = holder[0];
        // 立刻来一次起步小步滑动，反馈即时（轻点就只滚这一点）。
        // 同样用短时长 fling，起步也别慢拖（慢拖会被 ViewPager 弹回）。
        swipeFromCursor(dir, DRAG_RATIO_START, DRAG_SWIPE_DURATION_MS);
        handler.postDelayed(holder[0], DRAG_INTERVAL_START_MS);
    }

    private void stopDrag() {
        if (dragRepeatRunnable != null) {
            handler.removeCallbacks(dragRepeatRunnable);
            dragRepeatRunnable = null;
        }
        if (dragDirection != null) {
            dragDirection = null;
            dragRepeatCount = 0;
            dragStart = 0;
            // 滑动之后界面内容变了，补收一次控件（只为滚动服务）
            scheduleRefresh(0);
        }
    }

    /**
     * 从光标当前位置做一次滑动手势，<b>dir 表示“想看哪个方向的内容”</b>。
     *
     * 方向约定：
     * 按下 = 想看下面 = 手指向上滑 = 内容上移。
     * 注意这与“手指的拖动方向”相反，容易写反：写成“按下就往下滑”的话，
     * 按 0+下 会把页面往上拉，与直觉相反。
     *
     * 起点严格是光标坐标；终点按屏幕比例推算后 clamp 进屏幕。
     * 若光标已经贴着对侧边缘、剩余距离太短，就把起点往回退一点凑够最短距离，
     * 否则系统会把这个手势判成点击而不是滑动。
     */
    private boolean swipeFromCursor(FocusNavigator.Direction dir, float ratio, long durationMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;

        int w = screenRect.width();
        int h = screenRect.height();
        if (w <= 0 || h <= 0) return false;

        int cx = (cursorX < 0) ? w / 2 : clamp(cursorX, 1, w - 1);
        int cy = (cursorY < 0) ? h / 2 : clamp(cursorY, 1, h - 1);

        // dir 是“想看的方向”，手势方向与之相反（看下面 = 手指向上滑）
        int dx = 0;
        int dy = 0;
        switch (dir) {
            case DOWN:   // 看下面 -> 手指向上滑
                dy = -Math.round(h * ratio);
                break;
            case UP:     // 看上面 -> 手指向下滑
                dy = Math.round(h * ratio);
                break;
            case RIGHT:  // 看右边 -> 手指向左滑
                dx = -Math.round(w * ratio);
                break;
            case LEFT:
            default:     // 看左边 -> 手指向右滑
                dx = Math.round(w * ratio);
                break;
        }

        int x1 = cx;
        int y1 = cy;
        int x2 = clamp(cx + dx, 1, w - 1);
        int y2 = clamp(cy + dy, 1, h - 1);

        int minDist = Math.round(Math.min(w, h) * DRAG_SWIPE_MIN_RATIO);
        if (dy != 0 && Math.abs(y2 - y1) < minDist) {
            int need = minDist - Math.abs(y2 - y1);
            y1 = (y2 > y1) ? clamp(y1 - need, 1, h - 1) : clamp(y1 + need, 1, h - 1);
        }
        if (dx != 0 && Math.abs(x2 - x1) < minDist) {
            int need = minDist - Math.abs(x2 - x1);
            x1 = (x2 > x1) ? clamp(x1 - need, 1, w - 1) : clamp(x1 + need, 1, w - 1);
        }

        Log.v(TAG, "swipe (" + x1 + "," + y1 + ")->(" + x2 + "," + y2 + ") " + durationMs + "ms");
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, durationMs);
        GestureDescription gd = new GestureDescription.Builder().addStroke(stroke).build();
        return dispatchGesture(gd, null, null);
    }

    /**
     * 数字键 1~9：九宫格直送光标
     * - <b>按下即跳</b>到对应九宫格的几何中心（ACTION_DOWN 触发，松开忽略）；
     * - 无长按翻页功能，2468 仅用于九宫格定位。
     */
    private boolean handleNumberKey(KeyEvent event) {
        final int keyCode = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                jumpToGrid(keyCode);
            }
        }
        return true;
    }

    private static FocusNavigator.Direction directionOf(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return FocusNavigator.Direction.UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return FocusNavigator.Direction.DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return FocusNavigator.Direction.LEFT;
            default:
                return FocusNavigator.Direction.RIGHT;
        }
    }

    /**
     * 确认键：短按 = 在光标处点击；长按(≥500ms) = 在光标处长按。
     *
     * 语义纯粹：不再兼任滑动修饰键。之前让确认键兼任时，按下确认键的瞬间无法判断
     * 用户是要点一下还是准备拖，只能靠事后“刚才在拖拽就取消点击”来补救——
     * 那是先误判再纠正。滑动改由 0 键担任修饰键（见 {@link #handleZeroKey}）。
     */
    private boolean handleSelectKey(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() > 0) return true;
            longPressFired = false;
            longPressRunnable = new Runnable() {
                @Override
                public void run() {
                    longPressFired = true;
                    performCursorLongClick();
                }
            };
            handler.postDelayed(longPressRunnable, LONG_PRESS_MS);
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_UP) return true;

        // ACTION_UP
        if (longPressRunnable != null) {
            handler.removeCallbacks(longPressRunnable);
            longPressRunnable = null;
        }
        if (!longPressFired) {
            performCursorClick();
        }
        return true;
    }

    /**
     * 0 键：纯粹的滑动修饰键，<b>自身不产生任何动作</b>。
     *
     * 按下不点击、长按不触发任何东西、抬起也不补点击，因此与“确认 = 点击”零冲突。
     * 它只改变方向键的含义：
     * <pre>
     *   0 + 方向键  同时按住  →  从光标当前位置朝该方向滑动（相当于在屏幕上拖拽）
     *   松开任意一个          →  停止滑动
     * </pre>
     * 两个键谁先按都行。若松 0 时方向键仍按着，方向键会恢复成移动光标。
     */
    private boolean handleZeroKey(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() > 0) return true;
            zeroDown = true;
            // 方向键已经按住：把它从“移动光标”切换成“拖拽滑动”
            if (heldDirection != null) {
                startDrag(heldDirection);
            }
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_UP) return true;

        // ACTION_UP
        zeroDown = false;
        // 方向键还按着（且物理上没抬）→ 恢复为移动光标，而不是僵着不动
        boolean dirStillHeld = (heldDirection != null && !moveKeyUpPending);
        stopDrag();
        if (dirStillHeld) {
            startMoveRepeat(heldDirection);
        }
        return true;
    }

    // ---------------------------------------------------------------- 销毁

    @Override
    public void onInterrupt() {
        handler.removeCallbacks(moveReleaseRunnable);
        stopMoveRepeat();
        stopDrag();
        zeroDown = false;
        heldDirection = null;
        hideOverlay();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        NavigationPrefs.unregisterListener(this, prefListener);
        if (overlay != null && windowManager != null) {
            try {
                windowManager.removeView(overlay);
            } catch (Exception ignored) {
            }
            overlay = null;
        }
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }
}
