package io.github.cctyl.keydroidx.focus;

import android.graphics.Rect;
import android.os.Build;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于无障碍节点树的“虚拟焦点”导航算法。
 *
 * 设计要点（相对第一版的修正）：
 *
 * 1. 叶子优先剪枝：父容器与子项同时可点击时只保留子项，避免焦点在重叠矩形之间横跳。
 * 2. 屏幕内过滤：isVisibleToUser 只表示没被 GONE/INVISIBLE，不代表在可视区内，
 *    必须用 boundsInScreen 与屏幕求交。
 * 3. 巨型容器剔除：面积占比过大的容器（RecyclerView / WebView / 整页布局）不作为候选。
 * 4. 交互判定放宽：不只看 isClickable，还看 isLongClickable / isCheckable / ACTION_CLICK。
 * 5. 跳转用边缘间距 + 投影 beam 惩罚，而不是中心点距离，避免焦点“斜着飞”。
 */
public final class FocusNavigator {

    /** 候选节点最小边长（像素），过滤掉装饰性小元素 */
    public static final int MIN_NODE_SIZE = 24;
    /** 遍历深度上限，防止 WebView / 深层布局把遍历拖死 */
    public static final int MAX_DEPTH = 40;
    /** 一次最多保留的候选数，防止 AccessibilityNodeInfo 实例耗尽被系统回收 */
    public static final int MAX_CANDIDATES = 80;

    /** 单个节点面积占屏幕面积的上限，超过则视为容器而非可聚焦项 */
    private static final float MAX_SCREEN_RATIO = 0.55f;
    /** 不在当前投影束(beam)内的候选所付的代价，单位是像素，需远大于一屏尺寸 */
    private static final long BEAM_PENALTY = 1_000_000L;

    public enum Direction { UP, DOWN, LEFT, RIGHT }

    private FocusNavigator() {
    }

    // ------------------------------------------------------------------ 数据结构

    /**
     * 候选节点快照。持有的是一个 obtain 出来的 AccessibilityNodeInfo 副本，
     * 由收集方负责在 focal node 之外的所有实例上调用 {@link #recycle()}。
     */
    public static final class Node {
        public final AccessibilityNodeInfo info;
        public final Rect bounds;
        public final String id;
        public final String cls;
        public final String text;
        public final int[] path;
        public final int windowId;
        /** 节点自身没有有效矩形，bounds 是借用祖先的（弹窗按钮常见） */
        public boolean zeroSized;

        private Node(AccessibilityNodeInfo src, int[] stack, int depth) {
            this.info = AccessibilityNodeInfo.obtain(src);
            this.bounds = new Rect();
            src.getBoundsInScreen(this.bounds);
            // 有些 App 的 Dialog 按钮（B站/微信等）在无障碍树里 bounds 是 [0,0][0,0]，
            // 但节点本身 clickable=true。这类节点必须保留，否则弹窗里一个候选都没有。
            // 此时向上借用第一个有尺寸的祖先矩形作为它的有效矩形。
            if (this.bounds.width() <= 0 || this.bounds.height() <= 0) {
                Rect inherited = findNonEmptyAncestorRect(src);
                if (inherited != null) {
                    this.bounds.set(inherited);
                    this.zeroSized = true;
                }
            }
            CharSequence rid = src.getViewIdResourceName();
            this.id = rid == null ? "" : rid.toString();
            CharSequence cn = src.getClassName();
            this.cls = cn == null ? "" : cn.toString();
            this.text = extractText(src);
            this.path = Arrays.copyOf(stack, depth);
            this.windowId = src.getWindowId();
        }

        public void recycle() {
            try {
                info.recycle();
            } catch (IllegalStateException ignored) {
                // 已经回收过，忽略
            }
        }

        @Override
        public String toString() {
            return "Node{" + cls + " '" + text + "' " + bounds.toShortString() + "}";
        }
    }

    /**
     * 焦点锚点：页面内容变化时用于把焦点“粘”回原来那一项，
     * 而不是粗暴地弹回左上角。位置会因滚动变化，所以匹配主要依赖
     * className + 文本 / 资源 id + 尺寸，而不是绝对坐标。
     */
    public static final class Anchor {
        private final int windowId;
        private final String id;
        private final String cls;
        private final String text;
        private final int width;
        private final int height;

        private Anchor(Node n) {
            this.windowId = n.windowId;
            this.id = n.id;
            this.cls = n.cls;
            this.text = n.text;
            this.width = n.bounds.width();
            this.height = n.bounds.height();
        }

        public static Anchor of(Node n) {
            return n == null ? null : new Anchor(n);
        }

        public boolean matches(Node n) {
            if (n == null || n.windowId != windowId) return false;
            if (cls == null || !cls.equals(n.cls)) return false;
            if (text != null && text.length() > 0) return text.equals(n.text);
            if (id != null && id.length() > 0) return id.equals(n.id);
            // 既没文本也没 id（纯图标按钮）：退化为尺寸 + 屏幕位置接近
            return Math.abs(width - n.bounds.width()) <= 2
                    && Math.abs(height - n.bounds.height()) <= 2;
        }

        /** 位置是否只是小幅移动（例如被顶部的状态栏推下去了） */
        public boolean near(Rect r, int tolerance) {
            return Math.abs(width - r.width()) <= tolerance
                    && Math.abs(height - r.height()) <= tolerance;
        }
    }

    // ------------------------------------------------------------------ 收集

    /**
     * 收集当前窗口里所有“值得聚焦”的节点。
     *
     * @param root   当前窗口根节点（调用方负责 recycle）
     * @param screen 屏幕可视区域（一般是 0,0,w,h）
     * @return 候选列表；除被选中的那一个之外，其余都必须由调用方 recycle
     */
    public static List<Node> collectCandidates(AccessibilityNodeInfo root, Rect screen) {
        List<Node> out = new ArrayList<>();
        if (root == null) return out;
        walk(root, new int[MAX_DEPTH + 1], 0, screen, out, false);
        if (out.isEmpty()) {
            // 严格条件下一个都找不到（自定义 View / 只声明了 focusable 的 App），放宽重试
            walk(root, new int[MAX_DEPTH + 1], 0, screen, out, true);
        }
        splitZeroSizedSiblings(out);
        return out;
    }

    /**
     * 把“零尺寸兄弟节点”借用来的同一个祖先矩形按序号均分。
     *
     * 典型场景：B站这类 Dialog 里，底部按钮条的容器和按钮本身在无障碍树中 bounds 全是
     * [0,0][0,0]，按钮只能借用更上层容器的矩形。若不处理，同一个父节点下的所有按钮会拿到
     * <b>完全相同</b>的矩形，结果：
     * 1. 焦点框分不清选中的是“取消”还是“确定”；
     * 2. findNextFocus 里 r.equals(cur) 会直接跳过其余按钮，导致永远只能选中一个。
     *
     * 按兄弟顺序在主轴上均分，至少能让焦点框与真实布局的左右/上下关系一致。
     */
    private static void splitZeroSizedSiblings(List<Node> nodes) {
        Map<String, List<Node>> groups = new HashMap<>();
        for (Node n : nodes) {
            if (!n.zeroSized) continue;
            String key = parentKey(n.path);
            List<Node> list = groups.get(key);
            if (list == null) {
                list = new ArrayList<>();
                groups.put(key, list);
            }
            list.add(n);
        }

        for (List<Node> g : groups.values()) {
            if (g.size() < 2) continue;
            Collections.sort(g, new Comparator<Node>() {
                @Override
                public int compare(Node a, Node b) {
                    return lastIndex(a.path) - lastIndex(b.path);
                }
            });

            Rect total = new Rect(g.get(0).bounds);
            int count = g.size();
            boolean horizontal = total.width() >= total.height();
            for (int i = 0; i < count; i++) {
                Rect r = g.get(i).bounds;
                if (horizontal) {
                    int w = total.width() / count;
                    int left = total.left + w * i;
                    int right = (i == count - 1) ? total.right : left + w;
                    r.set(left, total.top, right, total.bottom);
                } else {
                    int h = total.height() / count;
                    int top = total.top + h * i;
                    int bottom = (i == count - 1) ? total.bottom : top + h;
                    r.set(total.left, top, total.right, bottom);
                }
            }
        }
    }

    private static String parentKey(int[] path) {
        return Arrays.toString(Arrays.copyOf(path, Math.max(0, path.length - 1)));
    }

    private static int lastIndex(int[] path) {
        return path.length == 0 ? 0 : path[path.length - 1];
    }

    /**
     * 后序遍历。返回该子树中入选的候选数量，供“叶子优先剪枝”判断。
     */
    private static int walk(AccessibilityNodeInfo node, int[] stack, int depth,
                            Rect screen, List<Node> out, boolean relaxed) {
        if (node == null || depth > MAX_DEPTH) return 0;
        // 候选已满：返回 1 阻止祖先被选中（否则祖先会被当成“没有合格子孙的叶子”）
        if (out.size() >= MAX_CANDIDATES) return 1;

        int subtree = 0;
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount && out.size() < MAX_CANDIDATES; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            stack[depth] = i;
            subtree += walk(child, stack, depth + 1, screen, out, relaxed);
            child.recycle();
        }

        if (subtree == 0 && isCandidate(node, screen, relaxed)) {
            out.add(new Node(node, stack, depth));
            return 1;
        }
        return subtree;
    }

    /** 向上找第一个尺寸有效的祖先矩形，用于给零尺寸节点兜底 */
    private static Rect findNonEmptyAncestorRect(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = null;
        try {
            cur = node.getParent();
        } catch (Exception ignored) {
        }
        for (int i = 0; i < 8 && cur != null; i++) {
            Rect r = new Rect();
            cur.getBoundsInScreen(r);
            if (r.width() > 0 && r.height() > 0) {
                cur.recycle();
                return r;
            }
            AccessibilityNodeInfo parent = null;
            try {
                parent = cur.getParent();
            } catch (Exception ignored) {
            }
            cur.recycle();
            cur = parent;
        }
        if (cur != null) cur.recycle();
        return null;
    }

    private static boolean isCandidate(AccessibilityNodeInfo n, Rect screen, boolean relaxed) {
        if (!n.isVisibleToUser() || !n.isEnabled()) return false;

        Rect r = new Rect();
        n.getBoundsInScreen(r);

        boolean zeroSized = (r.width() <= 0 || r.height() <= 0);
        if (zeroSized) {
            // 零尺寸节点：只有在明确可点击/可长按时才收（否则会混入大量无意义的空容器）
            if (!(n.isClickable() || n.isLongClickable())) return false;
            Rect inherited = findNonEmptyAncestorRect(n);
            if (inherited == null) return false;
            r.set(inherited);
        } else if (r.width() < MIN_NODE_SIZE || r.height() < MIN_NODE_SIZE) {
            return false;
        }

        if (!visibleEnough(r, screen)) return false;

        // 面积占比过大 -> 是容器/整页布局，不是可聚焦项
        long screenArea = (long) screen.width() * (long) screen.height();
        if (screenArea > 0 && (long) r.width() * (long) r.height() > screenArea * MAX_SCREEN_RATIO) {
            return false;
        }

        return isInteractive(n, relaxed);
    }

    /** 至少一半面积落在屏幕可视区内 */
    private static boolean visibleEnough(Rect r, Rect screen) {
        int iw = Math.min(r.right, screen.right) - Math.max(r.left, screen.left);
        int ih = Math.min(r.bottom, screen.bottom) - Math.max(r.top, screen.top);
        if (iw <= 0 || ih <= 0) return false;
        return (long) iw * (long) ih * 2 >= (long) r.width() * (long) r.height();
    }

    private static boolean isInteractive(AccessibilityNodeInfo n, boolean relaxed) {
        if (n.isClickable() || n.isLongClickable() || n.isCheckable()) return true;
        if (relaxed && n.isFocusable()) return true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            List<AccessibilityNodeInfo.AccessibilityAction> actions = n.getActionList();
            if (actions != null) {
                for (AccessibilityNodeInfo.AccessibilityAction a : actions) {
                    int id = a.getId();
                    if (id == AccessibilityNodeInfo.ACTION_CLICK
                            || id == AccessibilityNodeInfo.ACTION_LONG_CLICK) {
                        return true;
                    }
                }
            }
        } else {
            int actions = n.getActions();
            if ((actions & AccessibilityNodeInfo.ACTION_CLICK) != 0
                    || (actions & AccessibilityNodeInfo.ACTION_LONG_CLICK) != 0) {
                return true;
            }
        }
        return false;
    }

    private static String extractText(AccessibilityNodeInfo n) {
        StringBuilder sb = new StringBuilder();
        CharSequence t = n.getText();
        if (t != null) sb.append(t);
        if (sb.length() == 0) {
            CharSequence d = n.getContentDescription();
            if (d != null) sb.append(d);
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------ 选择

    /** 初始焦点：屏幕中最靠上、其次最靠左的候选 */
    public static Node findInitialFocus(List<Node> nodes) {
        Node best = null;
        long bestScore = Long.MAX_VALUE;
        for (Node n : nodes) {
            long score = (long) n.bounds.top * 10000L + n.bounds.left;
            if (score < bestScore) {
                bestScore = score;
                best = n;
            }
        }
        return best;
    }

    /**
     * 在给定方向上寻找下一个焦点。
     *
     * 代价模型（借鉴 TV FocusFinder 的 beam 思路）：
     * - 主方向用<b>边缘间距</b>，而不是中心点距离，这样大卡片与小图标可以公平竞争；
     * - 副轴有投影重叠（在同一个“束”里）时只比主方向间距；
     * - 副轴完全没有重叠时额外付一个巨大的 beam 惩罚，保证“正下方”永远优先于“斜下方”。
     */
    public static Node findNextFocus(Rect cur, Direction dir, List<Node> all) {
        if (cur == null || all == null || all.isEmpty()) return null;

        Node best = null;
        long bestCost = Long.MAX_VALUE;

        for (Node n : all) {
            Rect r = n.bounds;
            if (r.equals(cur)) continue;

            int majorGap;
            int minorOverlap;
            int minorCenterDelta;

            switch (dir) {
                case UP:
                    if (!(r.centerY() < cur.centerY())) continue;
                    majorGap = Math.max(0, cur.top - r.bottom);
                    minorOverlap = Math.min(cur.right, r.right) - Math.max(cur.left, r.left);
                    minorCenterDelta = Math.abs(r.centerX() - cur.centerX());
                    break;
                case DOWN:
                    if (!(r.centerY() > cur.centerY())) continue;
                    majorGap = Math.max(0, r.top - cur.bottom);
                    minorOverlap = Math.min(cur.right, r.right) - Math.max(cur.left, r.left);
                    minorCenterDelta = Math.abs(r.centerX() - cur.centerX());
                    break;
                case LEFT:
                    if (!(r.centerX() < cur.centerX())) continue;
                    majorGap = Math.max(0, cur.left - r.right);
                    minorOverlap = Math.min(cur.bottom, r.bottom) - Math.max(cur.top, r.top);
                    minorCenterDelta = Math.abs(r.centerY() - cur.centerY());
                    break;
                case RIGHT:
                default:
                    if (!(r.centerX() > cur.centerX())) continue;
                    majorGap = Math.max(0, r.left - cur.right);
                    minorOverlap = Math.min(cur.bottom, r.bottom) - Math.max(cur.top, r.top);
                    minorCenterDelta = Math.abs(r.centerY() - cur.centerY());
                    break;
            }

            long cost;
            if (minorOverlap > 0) {
                // 在同一个投影束内：主方向间距主导，副轴中心偏差只做微调
                cost = (long) majorGap * 10L + minorCenterDelta;
            } else {
                // 完全错开：付 beam 惩罚，除非主方向上没有任何同束候选
                cost = BEAM_PENALTY + (long) (majorGap + (-minorOverlap)) * 10L + minorCenterDelta;
            }

            if (cost < bestCost) {
                bestCost = cost;
                best = n;
            }
        }
        return best;
    }

    /**
     * 按屏幕坐标找回焦点：翻页之后内容整体挪动了，光标应当停在<b>原坐标附近</b>的那个控件上，
     * 而不是回到列表顶部，也不是继续朝某个方向找。
     *
     * 优先级：
     * 1. 覆盖该坐标的节点里面积最小的那个（大容器也覆盖该点，但我们要的是具体控件）；
     * 2. 没有节点覆盖该坐标时，退化为“中心点离该坐标最近的节点”。
     */
    public static Node findNearestToPoint(int x, int y, List<Node> all) {
        if (all == null || all.isEmpty()) return null;

        Node contained = null;
        long containedArea = Long.MAX_VALUE;
        Node nearest = null;
        long nearestDist = Long.MAX_VALUE;

        for (Node n : all) {
            if (n.bounds.contains(x, y)) {
                long area = (long) n.bounds.width() * (long) n.bounds.height();
                if (area < containedArea) {
                    containedArea = area;
                    contained = n;
                }
            }
            int dx = n.bounds.centerX() - x;
            int dy = n.bounds.centerY() - y;
            long dist = (long) dx * dx + (long) dy * dy;
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = n;
            }
        }
        return contained != null ? contained : nearest;
    }

    // ------------------------------------------------------------------ 滚动

    /**
     * 向上找最近的可滚动祖先（调用方负责 recycle 返回值）。
     */
    public static AccessibilityNodeInfo findScrollableAncestor(AccessibilityNodeInfo node) {
        if (node == null) return null;
        AccessibilityNodeInfo cur = AccessibilityNodeInfo.obtain(node);
        int guard = 0;
        while (cur != null && guard++ < MAX_DEPTH) {
            if (cur.isScrollable() && hasScrollAction(cur)) {
                return cur;
            }
            AccessibilityNodeInfo parent = cur.getParent();
            cur.recycle();
            cur = parent;
        }
        if (cur != null) cur.recycle();
        return null;
    }

    private static boolean hasScrollAction(AccessibilityNodeInfo n) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            List<AccessibilityNodeInfo.AccessibilityAction> actions = n.getActionList();
            if (actions == null) return false;
            for (AccessibilityNodeInfo.AccessibilityAction a : actions) {
                int id = a.getId();
                if (id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        || id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                    return true;
                }
            }
        } else {
            int actions = n.getActions();
            if ((actions & AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) != 0
                    || (actions & AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) != 0) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 工具

    public static void recycleAll(List<Node> nodes, Node except) {
        if (nodes == null) return;
        for (Node n : nodes) {
            if (n != except) n.recycle();
        }
        nodes.clear();
    }
}
