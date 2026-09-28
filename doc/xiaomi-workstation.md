# 小米澎湃OS「工作台」适配 / Xiaomi HyperOS Workstation adaptation

> 本文说明应用对小米澎湃OS 平板「工作台模式」（无极窗口 2.0）的适配方式：
> 纯 Manifest 声明、无代码 API、无权限要求，以及各参数的生效规则与验证步骤。
> This document describes how the app adapts to Xiaomi HyperOS Pad "Workstation
> mode" (infinitely resizable windows 2.0): a manifest-only declaration with no
> code API and no permissions, plus the parameter rules and verification steps.

## 中文

### 背景

澎湃OS 平板的「工作台」与传统 Pad 交互不同：应用图标集中在 Dock 栏，默认以
窗口启动（而非全屏），最多同开 4 个窗口，可自由调整大小与位置，交互逻辑接近
PC。下拉状态栏点击工作台图标即可在两种模式间切换。

「无极窗口 2.0」允许应用窗口在一定尺寸范围内任意缩放（用户拖动窗口左下/右下
角），提供三种缩放方式：全无极、竖无极、横无极。

### 适配方式

无需任何 Java/Kotlin API 调用，仅在 `app/app/src/main/AndroidManifest.xml` 的
`<application>` 内声明两个 `<property>`（注意不是 `<meta-data>`）：

```xml
<property
    android:name="miui.window.CVW_ENABLED"
    android:value="true" />
<property
    android:name="miui.window.CVW_MODE"
    android:value="1" />
```

### 生效规则

| 参数 | 作用 | 位置限制 |
|---|---|---|
| `miui.window.CVW_ENABLED` | 开启无极窗口 2.0 | 只能配置在 `<application>` 内 |
| `miui.window.CVW_MODE` | 缩放方式：`1` 全无极、`2` 竖无极、`3` 横无极 | 可配置在 `<application>` 或某个 `<activity>`；两者不一致时以 activity 为准 |

本应用取**全无极（`CVW_MODE=1`）**：全部 UI 只有 `MainActivity` 一个 Activity，
其已按可任意缩放的窗口设计——`resizeableActivity="true"`，并在
`configChanges` 中处理 `orientation|screenSize|screenLayout|smallestScreenSize`，
Compose 布局随窗口尺寸重组，无需按 Activity 差异化缩放方式。

### 验证

1. 安装到开启工作台模式的小米平板（澎湃OS）。
2. 下拉状态栏，点击工作台图标切入工作台模式。
3. 从 Dock 启动本应用，应默认以窗口打开。
4. 拖动窗口左下/右下角，窗口应在尺寸范围内连续缩放（而非固定三档等比缩放）。
5. 回到手机全屏场景对比显示效果，确认常规手机使用不受影响（小米文档的注意事项）。

该属性对非小米设备、未开工作台的设备无副作用，系统直接忽略。

### 参考

- 小米澎湃OS 开发者文档「工作台模式适配指南」：
  <https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2034>

## English

### Background

On HyperOS pads, "Workstation" mode differs from the classic pad interaction:
app icons live in a dock, apps launch in windows instead of full screen, up to
four windows can be open at once, and windows can be freely resized and moved —
an interaction model close to a PC. Toggle it from the control center via the
workstation tile.

"Infinitely resizable windows 2.0" lets an app window scale continuously within
a size range (the user drags the bottom-left/bottom-right corner), with three
modes: fully free, vertical-only, and horizontal-only.

### How the app adapts

No Java/Kotlin API calls are needed. Two `<property>` elements (not
`<meta-data>`) are declared inside `<application>` in
`app/app/src/main/AndroidManifest.xml`:

```xml
<property
    android:name="miui.window.CVW_ENABLED"
    android:value="true" />
<property
    android:name="miui.window.CVW_MODE"
    android:value="1" />
```

### Rules

| Property | Purpose | Placement constraint |
|---|---|---|
| `miui.window.CVW_ENABLED` | Enables resizable windows 2.0 | Must be declared inside `<application>` only |
| `miui.window.CVW_MODE` | Resize mode: `1` fully free, `2` vertical-only, `3` horizontal-only | Declared in `<application>` or per `<activity>`; when both exist, the activity value wins |

The app uses the **fully free mode (`CVW_MODE=1`)**: the entire UI is a single
`MainActivity`, already designed for arbitrary window sizes —
`resizeableActivity="true"`, with `orientation|screenSize|screenLayout|
smallestScreenSize` handled in `configChanges` and Compose layouts that
recompose with window size. There is no need to differentiate modes per
activity.

### Verification

1. Install on a Xiaomi pad (HyperOS) with workstation mode available.
2. Open the control center and tap the workstation tile.
3. Launch the app from the dock; it should open in a window by default.
4. Drag the bottom-left/bottom-right corner; the window should resize
   continuously within its range (not step through three fixed sizes).
5. Compare against the phone full-screen experience to confirm regular phone
   usage is unaffected (the caution noted in Xiaomi's guide).

The properties have no effect on non-Xiaomi devices or devices without
workstation mode; the system ignores them.

### Reference

- Xiaomi HyperOS developer documentation, "Workstation mode adaptation guide":
  <https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2034>
