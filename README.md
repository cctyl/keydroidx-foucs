抱歉，是我理解偏了。既然要的是**基于组件（View）本身的焦点**，而不是屏幕坐标点，那原型方案需要做如下调整。

---

# “现代功能机”焦点导航原型设计文档（修订版）

## 1. 核心目标
验证能否在不修改第三方 App 的前提下，通过无障碍服务遍历屏幕上的**控件节点**，构建出一条“虚拟焦点链”。用户按上/下/左/右键时，焦点在控件之间跳转，按确认键触发该控件的点击。

**关键点：不模拟屏幕坐标点击，而是直接对 AccessibilityNodeInfo 派发 `ACTION_CLICK`。**

---

## 2. 技术原理

### 2.1 Android 无障碍节点树
每一个 App 运行时，系统会构建一棵 `AccessibilityNodeInfo` 树（除非 App 明确禁用了无障碍）。这棵树包含了：

- 控件的类型：`Button`、`TextView`、`ImageView`、`RecyclerView` 等
- 控件是否可见：`isVisibleToUser`
- 控件是否可点击：`isClickable`
- 控件在屏幕上的位置：`getBoundsInScreen()`（返回一个 Rect）
- 控件的文本：`getText()`
- 控件的子节点和父节点：`getChild()` / `getParent()`

**我们可以利用这棵树，绕过触屏坐标，直接对控件本身进行操作。**

### 2.2 焦点模型
原生 Android 的焦点系统要求控件声明 `focusable=true`。现代手机 App 大多不声明。

我们的方案是：**在无障碍服务的内存里，自己维护一个“虚拟焦点”指针**，指向当前选中的 `AccessibilityNodeInfo`。

- 虚拟焦点不依赖系统的 `focusable` 属性。
- 虚拟焦点只依赖节点树中“可点击且可见”的节点。

---

## 3. 交互逻辑（焦点版）

### 3.1 基础操作

| 按键 | 行为 |
|------|------|
| 上/下/左/右 | 在当前焦点节点附近，根据几何位置寻找下一个最合适的可点击节点 |
| 确认键 | 对当前焦点节点执行 `ACTION_CLICK` |
| 返回键 | 执行系统返回操作 |
| 长按确认键 | 对当前焦点节点执行 `ACTION_LONG_CLICK` |

### 3.2 焦点跳转算法

这是整个原型的核心。因为第三方 App 没有提供焦点顺序，我们需要自己计算“下一个焦点”。

**思路：几何最近邻算法。**

给定当前焦点节点 `A`（已知它在屏幕上的 Rect），按下方向键 `D` 后：

1. 遍历整棵无障碍节点树，收集所有**可见且可点击**的节点。
2. 过滤掉**明显不合理**的候选节点：
   - 面积过小的（如小于 20×20 像素，可能是装饰元素）
   - 与 `A` 完全重叠的
3. 在 `D` 方向上，选择**几何距离最近**的节点作为下一个焦点。

**判断规则示例：**

- **按下键：** 候选节点的中心点 `y` 必须大于 `A` 的中心点 `y`。在满足条件的节点中，取 `y` 差最小且 `x` 差也较小的。
- **按左键：** 候选节点中心点 `x` 必须小于 `A` 的中心点 `x`，取 `x` 差最小且 `y` 差较小的。

### 3.3 焦点视觉反馈
我们需要在屏幕上画一个自定义的“焦点框”，覆盖在当前选中节点的 `Rect` 上。

- 使用悬浮窗绘制一个橙色边框。
- 每次焦点变化时，更新悬浮窗的位置和尺寸，使其完全包裹目标节点的 Rect。

这样用户看到的效果就跟 TV 上的焦点框一样，是“框住某个按钮”，而不是一个自由移动的光标。

---

## 4. 系统架构

```
┌─────────────────────────────┐
│         实体按键            │
│  (蓝牙键盘 / 音量键 / 外接)  │
└──────────────┬──────────────┘
               │ KeyEvent
               ▼
┌─────────────────────────────┐
│     无障碍服务 (核心)        │
│  - onKeyEvent 捕获按键      │
│  - 维护虚拟焦点指针          │
│  - 执行跳转算法              │
│  - 派发 ACTION_CLICK         │
│  - 控制悬浮窗位置             │
└──────┬───────────────┬──────┘
       │               │
       ▼               ▼
┌──────────────┐ ┌──────────────┐
│  悬浮窗绘制   │ │ 目标App节点树 │
│  (焦点框)    │ │ (读 + 点击)  │
└──────────────┘ └──────────────┘
```

---

## 5. 核心代码逻辑（Kotlin 示例）

### 5.1 获取根节点

```kotlin
val root = rootInActiveWindow ?: return
```

### 5.2 收集所有可点击节点

```kotlin
fun collectClickableNodes(node: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>) {
    if (node.isVisibleToUser && node.isClickable) {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() > 20 && rect.height() > 20) {
            result.add(AccessibilityNodeInfo.obtain(node))
        }
    }
    for (i in 0 until node.childCount) {
        val child = node.getChild(i) ?: continue
        collectClickableNodes(child, result)
        child.recycle()
    }
}
```

### 5.3 方向键跳转逻辑

```kotlin
fun findNextFocus(current: AccessibilityNodeInfo, direction: Direction): AccessibilityNodeInfo? {
    val all = mutableListOf<AccessibilityNodeInfo>()
    collectClickableNodes(rootInActiveWindow!!, all)

    val currentRect = Rect()
    current.getBoundsInScreen(currentRect)
    val cx = currentRect.centerX()
    val cy = currentRect.centerY()

    var best: AccessibilityNodeInfo? = null
    var bestDistance = Int.MAX_VALUE

    for (node in all) {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val nx = rect.centerX()
        val ny = rect.centerY()

        val dx = nx - cx
        val dy = ny - cy

        val valid = when (direction) {
            Direction.UP -> dy < 0
            Direction.DOWN -> dy > 0
            Direction.LEFT -> dx < 0
            Direction.RIGHT -> dx > 0
        }
        if (!valid) continue

        // 距离计算：主方向距离权重高，副方向权重低
        val distance = when (direction) {
            Direction.UP, Direction.DOWN -> abs(dy) * 2 + abs(dx)
            Direction.LEFT, Direction.RIGHT -> abs(dx) * 2 + abs(dy)
        }

        if (distance < bestDistance) {
            bestDistance = distance
            best = node
        }
    }
    return best
}
```

### 5.4 执行点击

```kotlin
fun clickCurrentNode() {
    currentFocusNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
}
```

### 5.5 更新焦点框悬浮窗

```kotlin
fun updateFocusOverlay(node: AccessibilityNodeInfo) {
    val rect = Rect()
    node.getBoundsInScreen(rect)
    // 通过 WindowManager 更新悬浮窗的 x, y, width, height
    overlayView.updateBounds(rect)
}
```

---

## 6. 原型验证步骤

1. 创建一个简单的无障碍服务。
2. 服务启动后，默认将根节点下第一个可点击的控件设为初始焦点。
3. 画一个橙色悬浮框包裹它。
4. 监听方向键，调用 `findNextFocus` 跳转。
5. 监听确认键，调用 `clickCurrentNode`。
6. 打开系统设置 App 测试。

---

## 7. 已知限制与后续优化

| 限制 | 原因 | 可能的优化方向 |
|------|------|----------------|
| 某些控件不可见但可点击 | App 布局残留 | 加入 `isVisibleToUser` 严格过滤 |
| 跳转顺序不自然 | 纯几何算法不够智能 | 结合控件类型和文本语义排序 |
| 悬浮窗无法覆盖在某些安全页面上 | 系统限制 | 降级为屏幕边缘高亮提示 |
| Flutter/游戏等自绘界面没有节点树 | 引擎不暴露无障碍节点 | 降级为坐标模式 |
| 某些 App 屏蔽无障碍 | 开发者主动禁用 | 无法绕过，原型阶段先测兼容性好的 App |

---

## 8. MVP 判定标准

打开系统设置后：

- [ ] 按下方向键，橙色焦点框能在“WLAN”、“蓝牙”、“移动网络”等条目之间跳转
- [ ] 焦点框始终完整包裹当前选中的条目
- [ ] 按确认键后，能进入对应设置页面
- [ ] 全程不触碰屏幕

**如果以上都通过，说明基于无障碍节点树的“焦点导航”方案成立，可以继续迭代。**