package io.github.cctyl.keydroidx.focus;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/**
 * 鼠标模式的悬浮层：一块全屏透明画布，<b>只画光标本体</b>（描边光晕 + 圆环 + 四向准星 + 实心点）。
 *
 * 之前这里还画过“附近控件的感应框”和“锁定控件的橙色实框”，已经全部移除：
 * 那些高亮会让用户以为“亮起的那个就是会被点中的”，但真正点击用的是光标坐标，
 * 两者不一致时（重叠控件、零尺寸节点、大容器）就变成“点 A 却跳到 B”。
 * 既然确认键就是坐标点击，屏幕上唯一的真相就应该是光标本身。
 */
public class CursorOverlay extends View {

    private static final int ACCENT = Color.parseColor("#FF9100");

    private int cursorX;
    private int cursorY;
    private boolean hasCursor;

    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public CursorOverlay(Context context) {
        super(context);
        setWillNotDraw(false);

        // 深色描边：浅色背景（设置页、浏览器）上也能看清光标
        shadowPaint.setColor(Color.parseColor("#CC000000"));
        shadowPaint.setStyle(Paint.Style.STROKE);
        shadowPaint.setStrokeWidth(8f);

        haloPaint.setColor(Color.parseColor("#B3FFFFFF"));
        haloPaint.setStyle(Paint.Style.STROKE);
        haloPaint.setStrokeWidth(6f);

        ringPaint.setColor(Color.WHITE);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(3f);

        dotPaint.setColor(ACCENT);
        dotPaint.setStyle(Paint.Style.FILL);
    }

    /**
     * @param cx 光标屏幕 X
     * @param cy 光标屏幕 Y
     */
    public void update(int cx, int cy) {
        cursorX = cx;
        cursorY = cy;
        hasCursor = true;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!hasCursor) return;

        // 先描边后本体，形成“白圈黑边”，任何背景上都可见
        canvas.drawCircle(cursorX, cursorY, 11f, shadowPaint);
        canvas.drawCircle(cursorX, cursorY, 15f, haloPaint);
        canvas.drawCircle(cursorX, cursorY, 11f, ringPaint);

        canvas.drawLine(cursorX - 28, cursorY, cursorX - 18, cursorY, shadowPaint);
        canvas.drawLine(cursorX + 18, cursorY, cursorX + 28, cursorY, shadowPaint);
        canvas.drawLine(cursorX, cursorY - 28, cursorX, cursorY - 18, shadowPaint);
        canvas.drawLine(cursorX, cursorY + 18, cursorX, cursorY + 28, shadowPaint);

        canvas.drawLine(cursorX - 28, cursorY, cursorX - 18, cursorY, ringPaint);
        canvas.drawLine(cursorX + 18, cursorY, cursorX + 28, cursorY, ringPaint);
        canvas.drawLine(cursorX, cursorY - 28, cursorX, cursorY - 18, ringPaint);
        canvas.drawLine(cursorX, cursorY + 28, cursorX, cursorY + 18, ringPaint);

        canvas.drawCircle(cursorX, cursorY, 5.5f, dotPaint);
    }
}
