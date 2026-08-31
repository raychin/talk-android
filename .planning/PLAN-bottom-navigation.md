# 底部导航重构开发计划

> 将 App 从多 Activity 架构重构为单一宿主 Activity + 底部导航（微信风格）

---

## 一、重构目标

将 `MainActivity` 改造为**单一宿主 Activity**，使用 `BottomNavigationView` + `Fragment`（`show/hide` 保持实例）承载三个 Tab，其余 Activity（ChatActivity、CallActivity 等）保持独立不放入 Tab。

### 底部 Tab 结构

| Tab | 内容 | 当前实现 | 提取方式 |
|-----|------|---------|---------|
| Talk | 会话列表 | `ConversationsListActivity` | 提取为 `ConversationsListFragment` |
| 通讯录 | 联系人列表（含新增会话） | `ContactsActivity`（Compose） | 提取为 `ContactsFragment`（ComposeView） |
| 设定 | 设置页面 | `SettingsActivity` | 提取为 `SettingsFragment` |

---

## 二、关键设计决策

| 决策项 | 选择 | 说明 |
|--------|------|------|
| Tab 状态管理 | **show/hide 保持实例** | 参照微信，切换 Tab 不销毁 Fragment，保留滚动位置和状态 |
| 切换动画 | 无 | `FragmentTransaction.hide()/show()`，无过渡动画 |
| 返回栈 | **不加入返回栈** | Tab 切换不压栈 |
| 返回键行为 | 非 Talk → 切回 Talk；Talk → finishAffinity | |
| 搜索栏 | **固定顶部，不随列表滚动** | 修改 `activity_conversations.xml` 中 AppBarLayout 的 scrollFlags |
| 外部 Intent | MainActivity 直接跳转目标 Activity | 通知点击→ChatActivity，分享文件→ChatActivity 等 |
| 原有 Activity | 保留（作为外部入口） | ConversationsListActivity/ContactsActivity/SettingsActivity 仍可独立打开 |

---

## 三、文件修改清单

### 3.1 新增文件

| # | 文件路径 | 说明 | 预估行数 |
|---|---------|------|---------|
| 1 | `res/menu/menu_bottom_navigation.xml` | 底部导航菜单项（Talk/通讯录/设定） | 15 |
| 2 | `res/values/strings_bottom_nav.xml` | 底部导航字符串资源 | 10 |
| 3 | `res/color/bottom_nav_color.xml` | 选中/未选中颜色 selector | 10 |
| 4 | `conversationlist/ConversationsListFragment.kt` | 从 `ConversationsListActivity` 提取的 Fragment | ~2400 |
| 5 | `contacts/ContactsFragment.kt` | 通讯录 Fragment（ComposeView + ContactsScreen） | 30 |
| 6 | `settings/SettingsFragment.kt` | 从 `SettingsActivity` 提取的 Fragment | ~1500 |

### 3.2 修改文件

| # | 文件 | 修改内容 | 工作量 |
|---|------|---------|--------|
| 1 | `res/layout/activity_main.xml` | 重写为 FragmentContainerView + BottomNavigationView | 小 |
| 2 | `res/layout/activity_conversations.xml` | 移除 AppBarLayout 的 scrollFlags（固定搜索栏） | 中 |
| 3 | `MainActivity.kt` | 重写为宿主，Fragment show/hide + handleIntent 适配 | 中 |
| 4 | `AndroidManifest.xml` | 可选调整 ConversationsListActivity 的 intent-filter | 小 |

### 3.3 保留文件（不修改）

| 文件 | 说明 |
|------|------|
| `ConversationsListActivity.kt` | 保留供外部 Intent 打开（分享文件等） |
| `ContactsActivity.kt` | 保留供外部 Intent 调用 |
| `SettingsActivity.kt` | 保留供外部 Intent 调用 |
| `BaseActivity.kt` | 不变，MainActivity 仍继承 |

---

## 四、详细实现步骤

### 阶段 1：基础设施

#### 步骤 1.1：新增资源文件

**`res/menu/menu_bottom_navigation.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<menu xmlns:android="http://schemas.android.com/apk/res/android">
    <item
        android:id="@+id/nav_talk"
        android:icon="@drawable/ic_baseline_chat_bubble_24"
        android:title="@string/bottom_nav_talk" />
    <item
        android:id="@+id/nav_contacts"
        android:icon="@drawable/ic_baseline_contacts_24"
        android:title="@string/bottom_nav_contacts" />
    <item
        android:id="@+id/nav_settings"
        android:icon="@drawable/ic_baseline_settings_24"
        android:title="@string/bottom_nav_settings" />
</menu>
```

> 图标说明：检查 `res/drawable/` 中是否存在 `ic_baseline_chat_bubble_24`、`ic_baseline_contacts_24`、`ic_baseline_settings_24`；若不存在，从 Material Icons Extended 生成或在菜单中用现有图标替代（如 `ic_round_forum_24`、`ic_person_add_24dp`、`ic_settings_24`）。

**`res/values/strings_bottom_nav.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="bottom_nav_talk">Talk</string>
    <string name="bottom_nav_contacts">通讯录</string>
    <string name="bottom_nav_settings">设定</string>
</resources>
```

**`res/color/bottom_nav_color.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:color="@color/colorPrimary" android:state_checked="true" />
    <item android:color="@color/warm_grey_four" />
</selector>
```

#### 步骤 1.2：重写 `activity_main.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.coordinatorlayout.widget.CoordinatorLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:fitsSystemWindows="true">

    <FrameLayout
        android:id="@+id/fragment_container"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:layout_marginBottom="?attr/actionBarSize" />

    <com.google.android.material.bottomnavigation.BottomNavigationView
        android:id="@+id/bottom_navigation"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom"
        style="@style/Widget.MaterialComponents.BottomNavigationView.Colored"
        app:menu="@menu/menu_bottom_navigation"
        app:itemIconTint="@color/bottom_nav_color"
        app:itemTextColor="@bottom_nav_color" />

</androidx.coordinatorlayout.widget.CoordinatorLayout>
```

#### 步骤 1.3：修改 `activity_conversations.xml` 固定搜索栏

```xml
<!-- 修改前 -->
<com.google.android.material.appbar.AppBarLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    app:layout_scrollFlags="scroll|enterAlways">

<!-- 修改后（固定顶部） -->
<com.google.android.material.appbar.AppBarLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content">
```

> **注意**：此修改同时影响 `ConversationsListActivity`，但搜索栏固定行为是可接受的。

---

### 阶段 2：改造 `MainActivity.kt`

**完整代码框架**：

```kotlin
@AutoInjector(NextcloudTalkApplication::class)
class MainActivity : BaseActivity() {

    @Inject lateinit var userManager: UserManager
    @Inject lateinit var eventBus: EventBus
    @Inject lateinit var viewThemeUtils: ViewThemeUtils
    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var currentUserProvider: CurrentUserProviderOld

    private val fragments = mutableMapOf<Int, Fragment>()
    private var currentNavId = R.id.nav_talk

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val user = userManager.currentUser.blockingGet()
        if (user == null) {
            startActivity(Intent(this, ServerSelectionActivity::class.java))
            finish()
            return
        }

        // OTA 升级、清理等初始化工作（从原 handleIntent 搬过来）
        initWorkers()

        if (intent != null && handleDeferredIntent(intent)) return

        setContentView(R.layout.activity_main)
        setupBottomNavigation(savedInstanceState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (handleDeferredIntent(intent)) return
    }

    private fun handleDeferredIntent(intent: Intent): Boolean {
        return when {
            intent.hasExtra(BundleKeys.KEY_ROOM_TOKEN) ||
            intent.hasExtra(BundleKeys.KEY_CONVERSATION_PASSWORD) -> {
                startActivity(intent.setClass(this, ChatActivity::class.java))
                true
            }
            else -> false
        }
    }

    private fun initWorkers() {
        // 从原 MainActivity.handleIntent() 中搬过来
        // OTA upgrade worker、chat system message clean worker 等
    }

    private fun setupBottomNavigation(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            fragments[R.id.nav_talk] = ConversationsListFragment()
            fragments[R.id.nav_contacts] = ContactsFragment()
            fragments[R.id.nav_settings] = SettingsFragment()

            supportFragmentManager.beginTransaction()
                .add(R.id.fragment_container, fragments[R.id.nav_talk]!!, "talk")
                .add(R.id.fragment_container, fragments[R.id.nav_contacts]!!, "contacts")
                .hide(fragments[R.id.nav_contacts]!!)
                .add(R.id.fragment_container, fragments[R.id.nav_settings]!!, "settings")
                .hide(fragments[R.id.nav_settings]!!)
                .commit()
        } else {
            fragments[R.id.nav_talk] = supportFragmentManager.findFragmentByTag("talk")
            fragments[R.id.nav_contacts] = supportFragmentManager.findFragmentByTag("contacts")
            fragments[R.id.nav_settings] = supportFragmentManager.findFragmentByTag("settings")
        }

        val navView = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        navView.selectedItemId = R.id.nav_talk

        navView.setOnItemSelectedListener { item ->
            if (item.itemId == currentNavId) return@setOnItemSelectedListener true
            currentNavId = item.itemId
            supportFragmentManager.beginTransaction().apply {
                fragments.values.forEach { hide(it) }
                show(fragments[item.itemId]!!)
                commit()
            }
            true
        }
    }

    override fun onBackPressed() {
        if (currentNavId != R.id.nav_talk) {
            findViewById<BottomNavigationView>(R.id.bottom_navigation).selectedItemId = R.id.nav_talk
        } else {
            finishAffinity()
        }
    }
}
```

**关键行为**：
- Fragment 通过 `add + show/hide` 保持实例状态
- 返回键：非 Talk Tab → 切回 Talk；Talk Tab → finishAffinity
- 外部 Intent（通知点击）→ 直接跳转 ChatActivity
- `BaseActivity` 的 EventBus 注册在 `onStart/onStop` 仍然有效

---

### 阶段 3：创建三个 Fragment

#### 步骤 3.1：`ConversationsListFragment.kt`

**提取源**：`ConversationsListActivity.kt`（~2450 行）

**适配对照表**：

| ConversationsListActivity → | ConversationsListFragment |
|---------------------------|--------------------------|
| `class ... : BaseActivity()` | `class ... : Fragment()` + `@AutoInjector(...)` |
| `binding = ActivityConversationsBinding.inflate(layoutInflater)` | `_binding = ActivityConversationsBinding.inflate(inflater, container, false)` |
| `onCreate()` 中初始化 | `onCreateView` → inflate；`onViewCreated` → 初始化逻辑 |
| `onDestroy()` 清理 | `onDestroyView` → `_binding = null` |
| EventBus 自动注册（BaseActivity） | 手动在 `onStart`/`onStop` 注册 |
| `baseActivity?.initSystemBars()` | `ViewCompat.setOnApplyWindowInsetsListener(binding.root) { }` |
| `setSupportActionBar(toolbar)` | 直接用 `toolbar` 设置标题，绕过 setSupportActionBar |
| `context` / `this` | `requireContext()` / `requireActivity()` |
| `recreate()` | `requireActivity().recreate()` |
| `setRequestedOrientation()` | `requireActivity().requestedOrientation` |
| `ViewModelProvider(this, factory)` | `ViewModelProvider(this@ConversationsListFragment, factory)` |

**EventBus 注册**：

```kotlin
override fun onStart() {
    super.onStart()
    if (!EventBus.getDefault().isRegistered(this)) {
        EventBus.getDefault().register(this)
    }
}

override fun onStop() {
    if (EventBus.getDefault().isRegistered(this)) {
        EventBus.getDefault().unregister(this)
    }
    super.onStop()
}
```

**需要注意**：
- 原来使用 `BaseActivity` 获取的注入字段（viewThemeUtils、eventBus、appPreferences 等）需要在 Fragment 中通过 `@Inject` 注入或从 `requireActivity()` 获取
- 所有业务逻辑（搜索、过滤、下拉刷新、FAB、RecyclerView 等）完全不变
- `initObservers()` 中的 ViewModel 观察直接保留
- 原来 Intent 相关的处理（如 `handleConversation()`）保持 `startActivity(Intent(...))` 不变

#### 步骤 3.2：`ContactsFragment.kt`

```kotlin
package com.nextcloud.talk.contacts

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import autodagger.AutoInjector
import com.nextcloud.talk.application.NextcloudTalkApplication
import com.nextcloud.talk.application.NextcloudTalkApplication.Companion.sharedApplication
import com.nextcloud.talk.ui.theme.ViewThemeUtils
import javax.inject.Inject

@AutoInjector(NextcloudTalkApplication::class)
class ContactsFragment : Fragment() {

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    @Inject
    lateinit var viewThemeUtils: ViewThemeUtils

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        sharedApplication!!.componentApplication.inject(this)
        return ComposeView(requireContext()).apply {
            setContent {
                MaterialTheme(colorScheme = viewThemeUtils.getColorScheme(requireContext())) {
                    ContactsScreen(
                        viewModel = ViewModelProvider(
                            this@ContactsFragment,
                            viewModelFactory
                        )[ContactsViewModel::class.java]
                    )
                }
            }
        }
    }
}
```

#### 步骤 3.3：`SettingsFragment.kt`

**提取源**：`SettingsActivity.kt`（~1568 行）

**适配对照表**：

| SettingsActivity → | SettingsFragment |
|-------------------|-----------------|
| `binding = ActivitySettingsBinding.inflate(layoutInflater)` | `_binding = ActivitySettingsBinding.inflate(inflater, container, false)` |
| `setupActionBar()` → 返回按钮 | Fragment 中不需要返回按钮；或在 toolbar 左侧加一个空图标 |
| EventBus 注册 | 手动在 `onStart/onStop` |
| `viewThemeUtils` 来自 BaseActivity | 注入或 requireActivity() |
| 点击事件中的 `this` | `requireContext()` |

---

### 阶段 4：AndroidManifest 适配

检查 `ConversationsListActivity` 当前的 intent-filter：

```xml
<activity
    android:name=".conversationlist.ConversationsListActivity"
    android:exported="true"
    android:windowSoftInputMode="adjustResize">
    <intent-filter>
        <action android:name="android.intent.action.SEND" />
        <action android:name="android.intent.action.SEND_MULTIPLE" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="*/*" />
    </intent-filter>
</activity>
```

**建议**：保留不变。`ConversationsListActivity` 作为独立 Activity 仍然可以接收分享 Intent。

---

## 五、验证清单

| # | 验证项 | 预期结果 |
|---|--------|---------|
| 1 | 编译 | `./gradlew :app:compileClpsDebugKotlin` 通过 |
| 2 | 启动 App | 直接进入底部导航，默认显示 Talk Tab |
| 3 | Tab 切换 | 点击 Talk/通讯录/设定 切换，无闪烁、保持滚动位置 |
| 4 | 返回键 | 非 Talk → 切回 Talk；Talk → 退出 |
| 5 | 通知点击 | 直接打开 ChatActivity |
| 6 | 分享文件 | 打开 ChatActivity 或会话选择 |
| 7 | 通讯录 Tab | 显示联系人列表，可创建新会话 |
| 8 | 设定 Tab | 所有设置项正常，点击有效 |
| 9 | 会话列表搜索 | 搜索栏固定在顶部，列表正常滚动 |
| 10 | 消息聊天 | 从会话列表进入 ChatActivity，返回后回到 Talk Tab |

---

## 六、风险与注意事项

1. **`ConversationsListFragment` 是大文件**（~2400 行），提取时需仔细逐行检查生命周期和方法引用，防止遗漏
2. **`BaseActivity` 中的公共方法**（`initSystemBars`、`colorizeStatusBar`、`colorizeNavigationBar`）在 Fragment 中不可用，需用 `ViewCompat.setOnApplyWindowInsetsListener` 或 `requireActivity().window` 替代
3. **EventBus 手动注册**——确保 `onStart/onStop` 成对注册/反注册
4. **Toolbar** —— Fragment 中不能使用 `setSupportActionBar`，需要使用工具栏的 `toolbar.title` 等直接设置
5. **`ConversationsListActivity` 中的 `recreate()` 调用**——改为 `requireActivity().recreate()`
6. **`SwipeRefreshLayout` 的 `onRefresh` 逻辑**在 Fragment 中保持不变
7. **Tab 切换不隐藏** `ChatActivity` 的 `onResume` 通知——`ChatActivity` 是独立 Activity，不受影响
8. **内存**：三个 Fragment 同时存在于内存中，`ConversationsListFragment` 有大量 View 绑定，注意 `onDestroyView` 中释放 ViewBinding

---

## 七、实施顺序建议

```
Phase 1 — 基础设施（30 min）
├── 新增 menu_bottom_navigation.xml
├── 新增 strings_bottom_nav.xml
├── 新增 bottom_nav_color.xml
├── 重写 activity_main.xml
└── 修改 activity_conversations.xml 固定搜索栏

Phase 2 — MainActivity + ContactsFragment（30 min）
├── 改造 MainActivity.kt
└── 新建 ContactsFragment.kt（最简单，Compose）

Phase 3 — ConversationsListFragment（2~3 小时）
├── 提取 Activity → Fragment（最大工作量）
└── 验证会话列表功能完整

Phase 4 — SettingsFragment（1~2 小时）
├── 提取 Activity → Fragment
└── 验证所有设置项

Phase 5 — 收尾验证（30 min）
├── 编译
├── Tab 切换测试
├── 外部 Intent 测试
└── 返回栈测试
```

**总预估**：4~6 小时（含提取和测试）
