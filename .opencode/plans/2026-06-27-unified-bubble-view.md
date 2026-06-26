# 合并为单一 UnifiedBubbleView 实施计划

> **Plan Mode** - READ ONLY. No edits allowed.

**Goal:** 将主按钮 "支" + 状态显示合并为一个可拖拽的悬浮窗，减少 WindowManager 条目，简化位置同步。

---

## 架构设计

### UnifiedBubbleView (单一 View)

```
┌──────────────────────┐  总高 ≈ 56 + 4 + 28 = 88dp
│                      │
│      ● 支            │  ← 上半区：56dp 直径圆形，点击触发分析
│                      │
├──────────────────────┤
│  ┌──────────────┐   │  ← 下半区：胶囊状态条 (约 100×28dp)
│  │   就绪        │   │
│  └──────────────┘   │
└──────────────────────┘
宽度 = max(56dp, 状态条宽度) ≈ 100dp
```

**状态枚举：**
```kotlin
enum class State { IDLE("就绪"), PROCESSING("处理中"), SUCCESS, FAILED }
```

**触摸区域判断：**
- `event.y < circleRadius * 2` → 圆形按钮区 → 触发 `onClick`
- 其他区域 → 仅拖拽，不响应点击

---

## 任务清单

### 任务 1：创建 UnifiedBubbleView.kt (新建)

**文件：** `android/app/src/main/java/io/github/jiangood/xq/service/UnifiedBubbleView.kt`

**核心逻辑：**
```kotlin
class UnifiedBubbleView(context: Context) : View(context) {
    // 绘制：上半圆形按钮 + 下半胶囊状态条
    // onTouchEvent：统一拖拽 + 上半区点击判断
    // updateState(State, move?, error?)：重绘下半区文本
    // onClick: (() -> Unit)? 回调
}
```

**关键方法：**
- `onDraw(canvas)` - 绘制圆形 + 胶囊 + 文字
- `onTouchEvent(event)` - 拖拽 + 点击区域判断
- `updateState(state, move?, error?)` - 状态切换并 invalidate()

---

### 任务 2：重构 FloatingBubbleService.kt (修改)

**移除：**
- `BubbleView` 相关字段和逻辑
- `ResultOverlayView` 相关字段、导入、`showResult()` 方法
- `resultOverlay` 字段

**新增/修改：**
```kotlin
private var unifiedView: UnifiedBubbleView? = null

override fun onCreate() {
    // ... 前台服务启动 ...
    showUnifiedView()  // 单次 addView
}

private fun showUnifiedView() {
    unifiedView = UnifiedBubbleView(this).apply {
        onClick = { onUnifiedClick() }
    }
    windowManager.addView(unifiedView, params)
}

private fun onUnifiedClick() {
    if (CaptureState.mediaProjection == null) {
        // 可选：更新状态为"未授权"
    } else {
        unifiedView?.updateState(UnifiedBubbleView.State.PROCESSING)
        captureAndAnalyze()
    }
}

private fun captureAndAnalyze() {
    // ... 截屏 ...
    // 成功：
    unifiedView?.updateState(UnifiedBubbleView.State.SUCCESS, move = chineseMove)
    // 失败：
    unifiedView?.updateState(UnifiedBubbleView.State.FAILED, error = msg)
}

override fun onDestroy() {
    unifiedView?.let { windowManager.removeView(it) }
    // ...
}
```

---

### 任务 3：删除废弃文件

- `android/app/src/main/java/io/github/jiangood/xq/service/BubbleView.kt` → **删除**
- `android/app/src/main/java/io/github/jiangood/xq/service/ResultOverlayView.kt` → **删除**

---

### 任务 4：清理导入和引用

- `FloatingBubbleService.kt` 移除 `BubbleView`、`ResultOverlayView` 导入
- 确保无编译错误

---

## 文件变更汇总

| 文件 | 操作 |
|------|------|
| `UnifiedBubbleView.kt` | **新建** |
| `FloatingBubbleService.kt` | **大幅修改** - 单一 View 管理 |
| `BubbleView.kt` | **删除** |
| `ResultOverlayView.kt` | **删除** |

---

## 验收标准

- [ ] 服务启动：单一悬浮窗出现，显示"支" + "就绪"
- [ ] 点击圆形区：触发截屏分析，状态变"处理中"
- [ ] 分析成功：状态显示走法 (如"马八进七")
- [ ] 分析失败：状态显示错误 (如"识别失败")
- [ ] 拖拽任意区域：整体移动，位置持久化
- [ ] 服务停止：悬浮窗消失，无残留
- [ ] 编译通过，无废弃引用

---

## 可选增强 (后续)

- 状态颜色区分：成功绿、失败红、处理中灰
- 圆形区文字随状态变化："支" → "⏳" → "✓"/"✗"
- 长按重置位置到默认

---

**确认计划无误，退出 Plan Mode 开始执行？**