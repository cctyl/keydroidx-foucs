package io.github.cctyl.keydroidx.focus;

import android.util.Log;

import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

import io.github.cctyl.nokia.shizuku.MiniShizuku;

/**
 * Android 4.4~6.0 (API 19-23) 手势派发实现。
 * 通过 mini_shizuku 发送 "input swipe" 命令，由桌面 launcher 或 shizuku 服务端执行。
 * 参考 keymapperMouse 的 SocketClient 命令队列模式，使用单线程排队执行，避免阻塞主线程。
 */
public class ShellGesturePerformer implements GesturePerformer {

    private static final String TAG = "ShellGesturePerformer";

    private final BlockingQueue<String> cmdQueue = new LinkedBlockingQueue<>(20);
    private final ExecutorService executor;
    private volatile boolean destroyed = false;

    public ShellGesturePerformer() {
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ShellGesturePerformer-Worker");
            t.setDaemon(true);
            return t;
        });
        executor.execute(this::runWorker);
    }

    @Override
    public void click(float x, float y) {
        if (x <= 0 || y <= 0) return;
        int ix = Math.round(x);
        int iy = Math.round(y);
        // input swipe x y x y 100 模拟点击（Android 4.4+ 支持 input swipe）
        String cmd = String.format(Locale.US, "input swipe %d %d %d %d 100", ix, iy, ix, iy);
        enqueue(cmd);
    }

    @Override
    public void longClick(float x, float y, long durationMs) {
        if (x <= 0 || y <= 0) return;
        int ix = Math.round(x);
        int iy = Math.round(y);
        long dur = durationMs > 0 ? durationMs : 700;
        String cmd = String.format(Locale.US, "input swipe %d %d %d %d %d", ix, iy, ix, iy, dur);
        enqueue(cmd);
    }

    @Override
    public void swipe(float startX, float startY, float endX, float endY, long durationMs) {
        int x1 = Math.round(startX);
        int y1 = Math.round(startY);
        int x2 = Math.round(endX);
        int y2 = Math.round(endY);
        long dur = durationMs > 0 ? durationMs : 200;
        String cmd = String.format(Locale.US, "input swipe %d %d %d %d %d", x1, y1, x2, y2, dur);
        enqueue(cmd);
    }

    private void enqueue(String cmd) {
        if (destroyed) return;
        // 如果队列堆积过多（例如连发拖拽超速），清空旧命令避免手势延迟雪崩
        if (cmdQueue.size() >= 5) {
            Log.w(TAG, "Backlog excessive, dropping stale gesture commands");
            cmdQueue.clear();
        }
        cmdQueue.offer(cmd);
    }

    private void runWorker() {
        while (!destroyed) {
            try {
                String cmd = cmdQueue.take();
                Log.d(TAG, "exec: " + cmd);
                boolean ok = MiniShizuku.exec(cmd);
                if (!ok) {
                    Log.w(TAG, "MiniShizuku.exec failed for: " + cmd);
                }
            } catch (InterruptedException e) {
                break;
            } catch (Throwable t) {
                Log.e(TAG, "Worker error", t);
            }
        }
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        cmdQueue.clear();
        executor.shutdownNow();
    }
}
