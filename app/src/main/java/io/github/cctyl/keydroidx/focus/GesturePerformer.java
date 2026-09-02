package io.github.cctyl.keydroidx.focus;

/**
 * 手势派发策略接口。
 * 屏蔽版本差异：API 24+ 使用 AccessibilityService.dispatchGesture()；
 * API 19-23 使用 MiniShizuku 执行 shell input 命令。
 */
public interface GesturePerformer {

    /** 在指定屏幕坐标派发点击 */
    void click(float x, float y);

    /** 在指定屏幕坐标派发长按 */
    void longClick(float x, float y, long durationMs);

    /** 从起始坐标滑动到终止坐标 */
    void swipe(float startX, float startY, float endX, float endY, long durationMs);

    /** 释放资源（如关闭后台线程、Socket 等） */
    void onDestroy();
}
