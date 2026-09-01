


## 1. 项目简介

`keydroidx-foucs`（包名 `io.github.cctyl.keydroidx.focus`）是一个 **屏幕鼠标（cursor）** 原型：

通过 `AccessibilityService` 在一块 `TYPE_ACCESSIBILITY_OVERLAY` 全屏透明画布上维护一个**屏幕坐标上的虚拟光标**，物理方向键 / 数字键 / 确认键操纵它，点击时在光标坐标上派发触摸手势（`dispatchGesture`）。

- 不修改任何第三方 App，也不依赖它们的 View 焦点系统；
- 目标设备是"现代功能机"（带物理键盘的安卓机），一切交互围绕物理按键；

## 2. 架构总览（大图）

核心是一个 `AccessibilityService`，它同时承担"按键输入、光标状态机、手势派发、悬浮绘制"四件事。要理解系统必须跨多个文件一起看：

```
物理按键(KeyEvent)
      │
      ▼
FocusNavigationService  ── 全部状态机都在这里（本项目的"心脏"）
  │  onKeyEvent 分发：
  │    方向键  → 移动光标（自驱动连发 + 加速）
  │    确认键  → dispatchGesture 在光标坐标点击/长按
  │    0+方向  → 从光标位置滑屏（拖拽）
  │    数字键  → 九宫格直送光标（无翻页功能）
  │    BACK    → GLOBAL_ACTION_BACK
  │
  ├─ CursorOverlay  ─── 全屏画布，只画光标本体（不画控件高亮/锁定框）
  │
  └─ FocusNavigator ── 纯算法工具类（无服务状态）
        findNextFocus/findInitialFocus/findNearestToPoint/findScrollableAncestor/collectCandidates  ← 旧焦点模型遗留
```

**数据流要点**：
- 光标位置是唯一的"真相"，存为屏幕坐标（不是某个 `AccessibilityNodeInfo`）。
- 窗口/根节点获取有坑：Dialog/PopupWindow 是独立 Window，`getRootInActiveWindow()` 拿不到，所以 `getFocusableRoot` 优先 isFocused 窗口、否则最上层窗口；前台包名以 `getWindows()` 的 Z 序最上层 APPLICATION 窗口为准。

## 3. 源码结构

单 Gradle 模块 `:app`，源码在 `app/src/main/java/io/github/cctyl/keydroidx/focus/`：

- **`FocusNavigationService.java`**（核心，全部状态机）
  - `onServiceConnected`：反射补齐 `canRequestFilterKeyEvents` / `canPerformGestures` 能力（见 §5.1）+ `FLAG_REQUEST_FILTER_KEY_EVENTS`；
  - `onKeyEvent`：按键分发 → `handleDirectionKey` / `handleNumberKey` / `handleZeroKey` / `handleSelectKey` / BACK；
  - 方向键连发：`startMoveRepeat` + `moveReleaseRunnable` + `moveKeyUpPending`；
  - 拖拽：`startDrag` / `stopDrag` / `swipeFromCursor`；
  - 前台包名：`refreshActivePackage` 以 `getWindows()` Z 序最上层 APPLICATION 窗口为准；
  - 窗口根：`getFocusableRoot` 优先 isFocused 的窗口，否则最上层；
  - `isOurOwnApp()` / `isInputMode()`：自家界面放行按键；输入态 = `editMode`（输入框焦点，FOCUSED/CLICKED 事件驱动）∨ `imeShowing`（IME 窗口检测，`TYPE_INPUT_METHOD`），任一命中即隐藏光标 + 放行按键；
  - `calibrateEditMode`：刷新时用 `findFocus(FOCUS_INPUT)` 单节点校准 editMode（节点级查询，不是整树遍历，不违反连发性能红线）。
  - **横屏重映射**：`screenRotation` 字段（`Surface.ROTATION_*`）在 `updateScreenRect()` 里刷新，按键热路径只读字段。`screenDirectionOf(keyCode)` 把物理方向键投影成屏幕方向；`gridCellOf(keyCode)` 把数字九宫格按同一旋转投影到屏幕 (col,row)，两者共用一套旋转避免错位。
- **`FocusNavigator.java`**：纯算法。旧焦点模型遗留工具类，**改代码时别把它们当成现役逻辑**。
- **`CursorOverlay.java`**：全屏画布，**只画光标本体**（白圈黑描边 + 四向准星 + 橙点）。不要往里加控件高亮 / 锁定框。
- **`MainActivity.java`**：配置页（跳系统无障碍设置 + 启用开关 + **名单模式切换**（RadioGroup）+ 跳黑/白名单入口 + 说明文字）。按钮文字与跳转目标随当前模式动态变化。
- **`BlacklistActivity.java`**：应用黑名单配置页（列出带 LAUNCHER 的应用，整行点按勾选，写 `NavigationPrefs` 即时生效）。Manifest 里必须有 `<queries>`（MAIN/LAUNCHER），否则 targetSdk 30+ 查不到其他应用。
- **`WhitelistActivity.java`**：应用白名单配置页（结构与 `BlacklistActivity` 同构，复用 `item_blacklist_app.xml` 项布局；勾选 = 在白名单内 = 开启光标）。
- **`NavigationPrefs.java`**：`SharedPreferences` 持久化 `enabled` 开关 + `list_mode`（`blacklist`/`whitelist`）+ `blacklist_pkgs` + `whitelist_pkgs` 两套**互相独立**的 StringSet。`isAppSuppressed(pkg)` 统一按当前模式判定是否关闭光标。服务注册监听器实时感知 `KEY_BLACKLIST` / `KEY_WHITELIST` / `KEY_MODE` 三个 key 的变化。

## 4. 交互模型

| 操作 | 行为 |
|---|---|
| 方向键短按 | 光标走一步（细步）。竖屏物理上=屏幕上；横屏按 `screenRotation` 重映射（ROTATION_90：上→左、下→右、左→下、右→上；ROTATION_270：上→右、下→左、左→上、右→下），使“按下上键光标朝那键面向的屏幕方向走”依然成立 |
| 方向键按住 | 服务**自驱动连发**，按时间加速：1 秒内从 95ms/步 + 1.0x 步长 平滑升到 52ms/步 + 1.8x 步长，**之后恒定不再变** |
| 确认键短按 | 在光标坐标点击（手势） |
| 确认键长按 ≥500ms | 在光标坐标长按（700ms 手势） |
| **0 键 + 方向键同时按住** | 从光标位置朝该方向**滑动**（拖拽）。0 是纯修饰键：自身不点击、不长按、抬起不补动作，与确认键零冲突。松开任一键停止；松 0 而方向键仍按着 → 恢复移动光标 |
| **星号键 \* + 井号键 # 一起按** | 切换鼠标导航**挂起**状态（临时隐藏，再按一次恢复）。挂起 = 光标隐藏 + 除本组合键外**全部按键放行**给前台 App/系统（方向键在 App 里照常可见工作，与服务未拦截时一致）；恢复 = 光标从原位置继续。触发一次后锁定，两键安静 `HIDE_COMBO_REARM_QUIET_MS` 才重新武装（兼容 DOWN/UP 对设备的连发流，防止按住期间反复切换） |
| 数字键 1~9 | 九宫格跳转：光标直接送到该格几何中心，不吸附控件，无翻页功能（可预测性优先）。竖屏 1 左上、9 右下；横屏九宫格随屏幕旋转整体旋转（ROTATION_90 = 369上/258中/147下，ROTATION_270 = 741上/852中/963下），方向键与九宫格使用同一套旋转避免错位 |
| 光标顶到屏幕边缘继续按 | 光标 clamp 住停住，**不再触发滚动**。滚动只由 0+方向键拖拽触发 |
| 返回键 | `GLOBAL_ACTION_BACK` |
| 本 App 自己的界面 / 输入态（输入框聚焦或输入法弹出） | 放行按键、隐藏光标 |
| **名单内应关闭的应用**（黑名单模式下=在黑名单里；白名单模式下=不在白名单里） | 光标完全失效（不显示），方向键/数字键/确认键/星井组合/BACK 等**全部原样放行**给该 App，与服务未拦截时一致；离开该类应用光标自动恢复。判断在 `refreshActivePackage()` 之后，用 `isAppSuppressed()` + `releaseAllKeys()` 清场（连发/拖拽/长按定时器/组合键状态都要清，否则残留定时器会在放行期间继续点） |

**手势方向语义（极易写反，已踩坑）**：`Direction.DOWN` 表示"想看下面的内容"，对应手指**向上**滑（`swipeFromCursor` 拖拽）。若按"手指拖动方向"实现，0+下 会把页面往上拉。

## 5. 必须知道的坑

1. **ROM 上报的 capabilities 残缺**：本机 ROM 上报 41（取窗口内容|放大镜|截图），缺 `4=CAN_REQUEST_FILTER_KEY_EVENTS` 与 `16=CAN_PERFORM_GESTURES`，于是 `onKeyEvent` 永远不被回调、`dispatchGesture` 恒失败。`onServiceConnected` 里必须用反射逐项设置布尔字段并按位补 `mCapabilities`，不能依赖 XML 声明（`setServiceInfo()` 会覆盖 XML）。
2. **功能机按键有三种行为**，长按/连发逻辑必须三种都兼容：
   - 标准：DOWN + repeatCount 递增；
   - 不发重复：按住只有一个 DOWN、松手一个 UP；
   - 发 DOWN/UP 对：repeatCount 恒为 0，物理长按是一串 DOWN/UP。
   因此**一切按住语义都由服务自己的 Handler 定时器驱动**，按键事件只负责起定时器 / 保活（刷新看门狗时间）/ 延迟确认松手。收到 UP 不能立即停——DOWN/UP 对会掐死定时器（"长按只滚一下"就是这个原因）。
3. **"一次点按走两步"竞态**：DOWN 立即走第 1 步，连发定时器排在 260ms 处；UP 后的松手确认在 UP+220ms 处。点按时长落在两者之间时，定时器会在"已抬起但还没确认"的窗口里多走一步。修复是 `moveKeyUpPending` 标志：UP 立刻置位，连发 tick 见到它只重排自己（50ms）、绝不移动；任何 DOWN 清除它。另有 `DIR_DOWN_DEDUP_MS`(40ms) 去重：同键无 UP 的重复 DOWN 判为 ROM 重复投递。
4. **看门狗分档**：持续收到信号（repeat / DOWN-UP 对）的"健谈"设备用 600ms；沉默设备（只有第一个 DOWN）用 10s 兜底。统一用短看门狗会导致"按住超过 3 秒断流"。
5. **自己污染自己**：悬浮层 invalidate 会发 `TYPE_WINDOW_CONTENT_CHANGED` 且包名是本应用，若用它更新 `currentPackage`，`isOurOwnApp()` 恒为 true，表现"光标亮一下就灭"。包名只认 `getWindows()` 的 Z 序。
6. **性能红线**：连发期间（光标移动 / 拖拽）不做整树遍历（24ms 一步的节奏下遍历必掉帧）；手势时长必须短于连发间隔，否则上一次没演完、下一次被系统判无效。
7. **编译环境**：Windows 下必须 `gradlew.bat`，`./gradlew` 会报 `Could not find or load main class GradleWrapperMain`。
8. **打字途中输入态被误清**（已修，别回退）：输入态一旦误退出，确认键就在旧光标位置派发点击 → 点到输入框外 → 焦点丢失、输入法收起，整个输入流程被打断（"打完字按确认没选中词"）。三条误退出路径与对策：
   - IME 候选栏/软键盘自己也发 FOCUSED/CLICKED（source 是输入法按钮，不可编辑）→ FOCUSED/CLICKED 判定前必须 `eventFromForegroundApp()` 过滤事件来源（用 `resolveForegroundPackage()` 实时比，不能比 currentPackage——调用点之前它可能已被 IME 包名污染）；
   - 输入框确认/选词的 CLICKED 事件经常取不到 source → `src == null` 时按"仍在编辑"处理（"没有证据"≠"反证据"）；
   - `imeShowing`（IME 窗口存在）作为输入态兜底：editMode 被误清时 IME 还弹着就继续放行。误退出比多放行危害大，宁可持续到有明确退出证据（焦点事件/CLICKED 到非编辑控件/calibrateEditMode 校准）。

## 6. 构建 / 运行 / 调试

```powershell
./gradlew.bat assembleDebug          # 构建（test.jks 签名，密码在本地 gradle.properties）
./gradlew.bat lint
adb install -r app/build/outputs/apk/debug/app-debug.apk   # 设备常在 192.168.1.8:5555
adb shell am start -n io.github.cctyl.keydroidx.focus/.MainActivity
adb logcat | findstr FocusNavigationService                # 实时日志（PowerShell 用 findstr）
```

- 装完若 `onKeyEvent` 失灵：先去系统设置把无障碍服务关掉再开（旧绑定可能还在）。
- **无单测**（`FocusNavigator` 是纯算法，最适合补测试；运行 `./gradlew.bat testDebugUnitTest`，单类用 `--tests "..."`）。
- `minSdk 27`：`TYPE_ACCESSIBILITY_OVERLAY` 需要 26+，取 27 稳妥。
- `local.properties` / `gradle.properties` 被 gitignore，含 JDK17 路径与 `test.jks` 签名凭据，**不要提交**。

## 7. 关键可调参数

都在 `FocusNavigationService` 头部常量区：

| 参数 | 现值 | 作用 |
|---|---|---|
| `MOVE_REPEAT_DELAY_MS` | 260 | 按住多久开始连发 |
| `MOVE_ACCEL_DURATION_MS` | 1000 | 加速时长，到点后恒速 |
| `MOVE_REPEAT_START_MS` / `_FINAL_MS` | 95 / 52 | 连发间隔起止 |
| `ACCEL_FACTOR` | 1.8 | 恒速阶段步长倍数 |
| `KEY_STILL_HELD_MS` / `MOVE_STILL_HELD_MS` | 220 / 120 | UP 后判定"同一次按压"的窗口 |
| `WATCHDOG_CHATTY_MS` / `_SILENT_MS` | 600 / 10000 | 看门狗两档 |
| `DRAG_SWIPE_RATIO` | 0.42 | 0+方向键单次滑动距离（占屏幕比例） |
| `DRAG_REPEAT_START_MS` / `_MIN_MS` | 520 / 260 | 拖拽连滑间隔 |
| `HIDE_COMBO_WINDOW_MS` / `HIDE_COMBO_REARM_QUIET_MS` | 300 / 250 | 星+井“同时按下”判定窗口 / 触发后两键静默解锁期 |
| （已移除）`EDGE_*` | — | 光标不再触发贴边滚动，相关常量与字段已删 |

## 8. 遗留死代码（改代码时别碰、别信）

`FocusNavigator.findNextFocus` / `findInitialFocus` / `findNearestToPoint` 是旧"焦点跳控件"模型的残留，已无任何调用方。历史上还有一版"光标附近的控件按距离亮起、最近的一个被锁定（橙色实框）、确认键对锁定的 `AccessibilityNodeInfo` 派发 `ACTION_CLICK`"的模型，被整体移除——原因是几何算法会猜错重叠控件、零尺寸节点（B站 Dialog 按钮 bounds=[0,0][0,0]）、把 `OnClickListener` 挂在父容器的 App，表现为"光标在这、却点了别处"。坐标点击没有这个中间环节，所见即所点。

## 9. 已知限制与后续方向

- Flutter / 游戏 / 自绘 UI 无节点树：坐标点击与手势不受影响（这正是不锁控件的好处），但滚动会退化为手势滑动；
- 有些 App 屏蔽无障碍，无法绕过；
- 纯几何九宫格不识别语义，后续可考虑结合控件类型与文本；
- 方向键 + 0 键的组合在"连滑超快"场景下手势时长与间隔的配比还可再调。
