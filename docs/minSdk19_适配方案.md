# keydroidx-focus minSdk 19 适配方案

## 背景
- 当前 `minSdk = 27` (Android 8.1)，依赖 `TYPE_ACCESSIBILITY_OVERLAY` (API 26) + `dispatchGesture` (API 24)
- 目标：降级到 `minSdk = 19` (Android 4.4)，按版本分档适配
- 参考：`keymapperMouse` (minSdk 19，用 `input swipe` 实现点击/滑动) + `nokia-mini-shizuku` (core 模块，TCP 10500 执行 shell 命令)

---

## 版本三档分层

| 档位 | API | 悬浮窗类型 | 点击/滑动实现 | 窗口/IME 检测 |
|------|-----|------------|---------------|--------------|
| **A 高** | 26+ | `TYPE_ACCESSIBILITY_OVERLAY` (无需权限) | `dispatchGesture` | `getWindows()` + `TYPE_INPUT_METHOD` |
| **B 中** | 24-25 | `TYPE_SYSTEM_ALERT` (需 `canDrawOverlays` 授权) | `dispatchGesture` | `getWindows()` + `TYPE_INPUT_METHOD` |
| **C 低** | 19-23 | `TYPE_SYSTEM_ALERT` (19-22 自动，23+ 需授权) | `MiniShizuku.exec("input swipe …")` | 19-20 无 `getWindows`，21+ 有 |

> **关键点**：B 档 (24-25) 有 `dispatchGesture`，直接用，**不降级到 shell**；只有 C 档 (19-23) 走 Shizuku。

---

## 架构：单 Service + 策略模式

**拒绝双 Service** 的理由：
- AccessibilityService 只能由用户在系统设置手动开启；两个服务会让用户困惑，或同时开启导致 `onKeyEvent` 冲突
- 双 Service 意味着两套生命周期、overlay、状态机维护成本

**方案**：单 `FocusNavigationService`，运行时按能力注入不同实现：

```
FocusNavigationService (唯一 Service，保持现有结构)
  ├─ 按键状态机 ── 完全不动（纯坐标/计时，零 Android API 依赖，已验证）
  ├─ Overlay ── 构造时按版本选 TYPE_ACCESSIBILITY_OVERLAY / TYPE_SYSTEM_ALERT
  ├─ GesturePerformer (接口)
  │     ├─ DispatchGesturePerformer (API 24+)
  │     └─ ShellGesturePerformer (API 19-23，MiniShizuku 长连接 + 命令队列)
  └─ 窗口/IME 检测 ── 现有逻辑加 if(SDK>=21) 保护，19-20 退化
```

---

## 核心接口设计

```java
// app/src/main/java/.../gesture/GesturePerformer.java
public interface GesturePerformer {
    boolean click(int x, int y);                      // 单次点击
    boolean longClick(int x, int y);                  // 长按
    boolean swipe(int x1, int y1, int x2, int y2, long durationMs); // 滑动/拖拽
    void close();                                     // 释放资源
}
```

### 实现 1：DispatchGesturePerformer (API 24+)
- 直接用 `AccessibilityService.dispatchGesture(GestureDescription)`
- 现有 `clickByGesture` / `longPressByGesture` / `swipeFromCursor` 逻辑搬过来即可

### 实现 2：ShellGesturePerformer (API 19-23)
- **长连接 + 命令队列** (参考 `keymapperMouse.SocketClient`)，**禁止每次新建 Socket**
- 协议：`input swipe x1 y1 x2 y2 duration`
- 拖拽优化：`input swipe` 进程冷启动慢，连发拖拽改为**单次长 swipe + 低频触发**，不再用 220ms 高频连发
- 仅走 `MiniShizuku.exec(command)`，root 由桌面 launcher 启动 shizuku 服务端，本 App 不处理 root

---

## Overlay 悬浮窗适配

```java
// CursorOverlay 构造/初始化时
if (Build.VERSION.SDK_INT >= 26) {
    params.type = TYPE_ACCESSIBILITY_OVERLAY;  // 无需权限
} else {
    params.type = TYPE_SYSTEM_ALERT;           // 需权限
    // API 23+ 检查 Settings.canDrawOverlays()，无权限则引导用户授权
}
```

- `TYPE_SYSTEM_ALERT` 在 19-22 是普通权限 (Manifest 声明即可)，23+ 需 `canDrawOverlays` 运行时授权
- MainActivity 增加低版本悬浮窗权限引导入口 (跳 `ACTION_MANAGE_OVERLAY_PERMISSION`)

---

## 窗口/IME 检测兼容

现有代码已用 `Build.VERSION.SDK_INT` 保护 `getWindows()` / `TYPE_INPUT_METHOD` (API 21+)。
- API 19-20：`getWindows()` 不可用，`refreshActivePackage()` 仅靠 `getRootInActiveWindow()` 兜底；`imeShowing` 永远返回 false
- 影响：19-20 上输入态检测靠 `editMode` (焦点事件驱动) 单一信号，误退出风险稍高，但可接受

---

## 依赖与配置变更

### build.gradle (app)
```gradle
android {
    defaultConfig {
        minSdk 19  // 从 27 降级
    }
}

dependencies {
    implementation 'io.github.cctyl.nokia:nokia-mini-shizuku:1.0.0'  // core 模块已发布
}
```

### AndroidManifest.xml
```xml
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<!-- 现有 AccessibilityService 声明保持不变，系统按 minSdk 自动适配 -->
```

### Application 子类 (新建或复用)
```java
public class FocusApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        MiniShizuku.init(this);  // 初始化 Shizuku 客户端 (同签名 launcher 必须已运行)
    }
}
```

---

## 实现步骤清单

| 步骤 | 文件/任务 | 说明 |
|------|-----------|------|
| 1 | `build.gradle` (root + app) | `MIN_SDK = 19`，加 mini-shizuku 依赖，加 `SYSTEM_ALERT_WINDOW` 权限 |
| 2 | `GesturePerformer.java` (新建) | 接口定义 |
| 3 | `DispatchGesturePerformer.java` (新建) | API 24+ 实现，搬运现有手势代码 |
| 4 | `ShellGesturePerformer.java` (新建) | 长连接+队列执行 `input swipe`，拖拽降频 |
| 5 | `CursorOverlay.java` | 按版本选窗口类型，API 23+ 加 `canDrawOverlays` 检查 |
| 6 | `FocusNavigationService.java` | 注入 `GesturePerformer`；手势调用改为 `performer.click/swipe/...`；版本保护现有 API |
| 7 | `FocusApplication.java` (新建或复用) | `MiniShizuku.init(this)` |
| 8 | `MainActivity.java` | 低版本悬浮窗权限引导 (API 23+ 跳设置) |
| 9 | 测试 | API 26+/24-25/19-23 三档真机验证：光标显示、按键移动、点击、拖拽、长按、九宫格、星井组合 |

---

## 关键约束与前提

1. **Shizuku 前置**：目标设备必须运行同签名的 launcher (含 `NokiaShizukuProvider`)，且 mini_shizuku 服务端已在 TCP 10500 监听。无 Shizuku 时低版本点击失效。
2. **Launcher 负责 root**：launcher 使用 root 启动 mini_shizuku 服务端，本 App 仅做同签名 TCP 客户端。
3. **onKeyEvent 可用性**：已由 `keymapperMouse` 验证，API 19 上 AccessibilityService 能拦截物理键盘按键。
4. **拖拽性能**：C 档拖拽因 `input` 进程启动延迟，体验不如高版本；已通过"长 swipe + 低频"缓解，不追求与高版本一致的 220ms 高频连发。

---

## 风险点与应对

| 风险 | 应对 |
|------|------|
| 19-20 无 `getWindows()`，前台包名判断不准 | 仅用 `getRootInActiveWindow()` 兜底；黑白名单按包名生效可能延迟，但核心按键逻辑不依赖包名 |
| `TYPE_SYSTEM_ALERT` 在部分 ROM 被限制/需特殊权限 | 19-22 正常；23+ 引导授权；极少数 ROM 不支持则降级 `TYPE_TOAST` (不保证) |
| Shizuku 连接断开/未就绪 | `ShellGesturePerformer` 内部重连 + 命令队列缓冲；`isRunning()` 探测失败时 toast 提示用户检查 launcher |
| 拖拽连发在 C 档卡顿 | 单次长 swipe 替代高频连发，降频至 500-800ms 一次，避免进程启动堵塞 |

---

## 验收标准

- **API 26+ 设备**：现有全部功能零回归
- **API 24-25 设备**：光标显示、方向键移动、确认键点击/长按、0+方向拖拽、九宫格、星井组合、黑白名单、输入态隐藏 —— 全部正常，仅悬浮窗需用户授权一次
- **API 19-23 设备 (有 Shizuku)**：上述功能全部正常，拖拽频率降低但可用；无 Shizuku 时点击/拖拽失效，给出明确提示

---

## 相关参考代码

- `keymapperMouse`：`RootShellCmd` (input swipe)、`SocketClient` (长连接队列)、`KeyService` (按键状态机)
- `nokia-mini-shizuku`：`MiniShizuku` / `MiniShizukuClient` (TCP 客户端)、`MiniShizukuConst` (协议常量)
- 当前 `FocusNavigationService`：按键状态机、overlay、版本兼容逻辑 (已有大量 `Build.VERSION.SDK_INT` 保护)