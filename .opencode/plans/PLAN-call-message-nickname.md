# 来电通话消息长昵称显示修复

> 修复 Call Started 卡片中过长昵称显示不全的问题

---

## 一、问题分析

### 1.1 当前实现

来电通话消息卡（`call_started_message.xml`）中，头像和昵称使用 `com.google.android.material.chip.Chip` 组件：

```xml
<com.google.android.material.chip.Chip
    android:id="@+id/call_author_chip"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    app:chipIcon="@drawable/account_circle_48dp"
    app:chipCornerRadius="@dimen/dialogBorderRadius"
    tools:text="Julius James Linus" />

<TextView
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:text="@string/started_a_call" />
```

### 1.2 根因

两个问题：

1. **昵称过长时 Chip 截断**：`Chip` 组件是**单行原子组件**，内部无法换行。超出 Chip 容器的文本被省略号截断，无法完整显示长昵称。
2. **"开始了通话"被换行**：父容器是水平 `LinearLayout`（`gravity="center"`），两个 `wrap_content` 子元素水平排列。昵称很长时 Chip 几乎占满全部空间，TextView 被推到右边；如果总宽度超屏，"开始了通话"被挤压到下一行或溢出。

### 1.3 Chip 的主题样式

`call_author_chip` 在 `MessageInputFragment.kt` 中通过 `viewThemeUtils.material.colorChipBackground(this)` 设置主题，效果：
- 背景色：动态主题色（primary）
- 文字色：`onPrimary`（通常白色）
- 圆角：`@dimen/dialogBorderRadius`

---

## 二、修复方案

### 2.1 方案

将 Chip 替换为 `LinearLayout(horizontal) + ImageView + TextView` 组合，昵称 `TextView` 不设行数限制，可自动换行。

| 对比项 | Chip | ImageView + TextView |
|--------|------|---------------------|
| 昵称换行 | ❌ 单行截断 | ✅ 自动换行，完整显示 |
| 头像 | ✅ chipIcon | ✅ ImageView + 圆形裁剪 |
| 圆角背景 | ✅ chipCornerRadius | ✅ shape drawable |
| 主题色 | ✅ colorChipBackground | ✅ 代码中手动设置 |
| 改动量 | — | 中等 |

### 2.2 布局变化

**修改前（水平排列）**：

```
[ Chip (头像+昵称) ] [ "开始了通话" ]
```

**修改后（垂直排列）**：

```
    [ 头像 + 昵称 ] → 昵称过长时自动换行
    [ "开始了通话" ]
```

### 2.3 视觉还原

| 视觉属性 | Chip 原有值 | 自定义组合 |
|---------|------------|-----------|
| 圆角背景 | `chipCornerRadius=8dp` | shape drawable radius 8dp |
| 背景颜色 | 动态 primary 色 | 代码中通过 theme dynamic color 设置 |
| 文字颜色 | 动态 onPrimary 色（白色） | `textColorOnPrimaryBackground` |
| 头像 | chipIcon 圆形裁剪 | ImageView + CircleCropTransformation |
| 头像大小 | Material Chip 默认 24dp | 24dp |

---

## 三、文件修改清单

| # | 文件 | 操作 | 说明 |
|---|------|------|------|
| 1 | `res/drawable/shape_call_chip_background.xml` | **新增** | 圆角矩形背景 drawable |
| 2 | `res/layout/call_started_message.xml` | **修改** | 替换 Chip 为 ImageView + TextView；改为垂直排列 |
| 3 | `MessageInputFragment.kt` | **修改** | 适配头像设置 + 主题色设置 |

---

## 四、完整代码

### 4.1 新增：`res/drawable/shape_call_chip_background.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <corners android:radius="8dp" />
    <solid android:color="@color/colorPrimary" />
</shape>
```

### 4.2 修改：`res/layout/call_started_message.xml`

将 `call_author_layout` 从水平 Chip+TextView 改为垂直头像昵称+文本：

```xml
<LinearLayout
    android:id="@+id/call_author_layout"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginBottom="@dimen/standard_half_margin"
    android:orientation="vertical"
    android:gravity="center_horizontal">

    <LinearLayout
        android:id="@+id/call_author_name_container"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:paddingStart="12dp"
        android:paddingEnd="12dp"
        android:paddingTop="4dp"
        android:paddingBottom="4dp"
        android:background="@drawable/shape_call_chip_background">

        <ImageView
            android:id="@+id/call_author_avatar"
            android:layout_width="24dp"
            android:layout_height="24dp"
            android:layout_marginEnd="6dp"
            android:importantForAccessibility="no"
            android:src="@drawable/account_circle_48dp" />

        <TextView
            android:id="@+id/call_author_name"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:maxWidth="280dp"
            android:textSize="14sp"
            android:textStyle="bold"
            tools:text="Julius James Linus" />

    </LinearLayout>

    <TextView
        android:id="@+id/call_started_status"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="4dp"
        android:text="@string/started_a_call"
        android:textSize="12sp"
        android:textColor="@color/warm_grey_four"
        android:gravity="center" />

</LinearLayout>
```

### 4.3 修改：`MessageInputFragment.kt`

#### 4.3.1 设置昵称和头像（~line 304-333）

**修改前：**

```kotlin
binding.fragmentCallStarted.callAuthorChip.text = message.actorDisplayName
binding.fragmentCallStarted.callAuthorChipSecondary.text = message.actorDisplayName

val imageRequest = ImageRequest.Builder(requireContext()).data(url).crossfade(true)
    .transformations(CircleCropTransformation())
    .target(object : Target {
        override fun onSuccess(result: Drawable) {
            binding.fragmentCallStarted.callAuthorChip.chipIcon = result
            binding.fragmentCallStarted.callAuthorChipSecondary.chipIcon = result
        }
    }).build()
```

**修改后：**

```kotlin
binding.fragmentCallStarted.callAuthorName.text = message.actorDisplayName
binding.fragmentCallStarted.callAuthorChipSecondary.text = message.actorDisplayName

val imageRequest = ImageRequest.Builder(requireContext()).data(url).crossfade(true)
    .transformations(CircleCropTransformation())
    .target(object : Target {
        override fun onStart(placeholder: Drawable?) {
            binding.fragmentCallStarted.callAuthorAvatar.setImageDrawable(
                placeholder ?: ContextCompat.getDrawable(
                    requireContext(), R.drawable.account_circle_48dp
                )
            )
        }
        override fun onError(error: Drawable?) {
            binding.fragmentCallStarted.callAuthorAvatar.setImageDrawable(
                ContextCompat.getDrawable(
                    requireContext(), R.drawable.account_circle_48dp
                )
            )
        }
        override fun onSuccess(result: Drawable) {
            binding.fragmentCallStarted.callAuthorAvatar.setImageDrawable(result)
            binding.fragmentCallStarted.callAuthorChipSecondary.chipIcon = result
        }
    }).build()
```

#### 4.3.2 主题化设置（~line 1231-1245）

**修改前：**

```kotlin
binding.fragmentCallStarted.callAuthorChip.apply {
    viewThemeUtils.material.colorChipBackground(this)
}
binding.fragmentCallStarted.callAuthorChipSecondary.apply {
    viewThemeUtils.material.colorChipBackground(this)
}
```

**修改后：**

```kotlin
binding.fragmentCallStarted.callAuthorNameContainer.apply {
    setBackgroundColor(
        ContextCompat.getColor(requireContext(), R.color.colorPrimary)
    )
}
binding.fragmentCallStarted.callAuthorName.setTextColor(
    ContextCompat.getColor(requireContext(), R.color.textColorOnPrimaryBackground)
)
binding.fragmentCallStarted.callAuthorChipSecondary.apply {
    viewThemeUtils.material.colorChipBackground(this)
}
```

---

## 五、验证清单

| # | 验证项 | 预期结果 |
|---|--------|---------|
| 1 | 短昵称（≤ 8 字） | 水平紧凑显示，居中 |
| 2 | 长昵称（≥ 15 字） | 昵称自动换行，"开始了通话"在下方独立一行 |
| 3 | 折叠状态 | Chip + "开始了通话" 正常 |
| 4 | 展开/折叠切换 | 正常，无闪烁 |
| 5 | 主题色 | 昵称容器背景=primary，文字色=onPrimary |
| 6 | 头像 | 正常加载，圆形裁剪 |
| 7 | 编译 | `compileClpsDebugKotlin` 通过 |
