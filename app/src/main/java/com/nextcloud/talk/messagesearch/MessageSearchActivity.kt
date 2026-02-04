/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2023 Ezhil Shanmugham <ezhil56x.contact@gmail.com>
 * SPDX-FileCopyrightText: 2022 Álvaro Brey <alvaro@alvarobrey.com>
 * SPDX-FileCopyrightText: 2022 Nextcloud GmbH
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.messagesearch

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.TextUtils
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.AppCompatAutoCompleteTextView
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.ViewModelProvider
import autodagger.AutoInjector
import com.google.android.material.snackbar.Snackbar
import com.nextcloud.talk.R
import com.nextcloud.talk.activities.BaseActivity
import com.nextcloud.talk.adapters.items.LoadMoreResultsItem
import com.nextcloud.talk.adapters.items.MessageFilterItem
import com.nextcloud.talk.adapters.items.MessageFilterItemListener
import com.nextcloud.talk.adapters.items.MessageResultItem
import com.nextcloud.talk.application.NextcloudTalkApplication
import com.nextcloud.talk.components.RoundedBackgroundSpan
import com.nextcloud.talk.conversationlist.ConversationsListActivity
import com.nextcloud.talk.data.message.model.MessageFilter
import com.nextcloud.talk.data.message.model.MessageFilterType
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.databinding.ActivityMessageSearchBinding
import com.nextcloud.talk.utils.bundle.BundleKeys
import com.nextcloud.talk.utils.rx.SearchViewObservable.Companion.observeSearchView
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractFlexibleItem
import eu.davidea.viewholders.FlexibleViewHolder
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.inject.Inject

@AutoInjector(NextcloudTalkApplication::class)
class MessageSearchActivity : BaseActivity() {

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    private lateinit var binding: ActivityMessageSearchBinding
    private lateinit var searchView: SearchView
    private lateinit var editInput: AppCompatAutoCompleteTextView

    private lateinit var user: User

    private lateinit var viewModel: MessageSearchViewModel

    private var searchViewDisposable: Disposable? = null
    private var adapter: FlexibleAdapter<AbstractFlexibleItem<*>>? = null

    private var filterAdapter: FlexibleAdapter<AbstractFlexibleItem<*>>? = null
    private val filters: List<MessageFilter> = listOf(
        MessageFilter(10001,  "文件", MessageFilterType.FILE),
        MessageFilter(10002,  "图片", MessageFilterType.IMAGE)
    )
    private var filterItemChoose: MessageFilter = MessageFilter(-10000, "", MessageFilterType.TEXT)

    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NextcloudTalkApplication.sharedApplication!!.componentApplication.inject(this)

        binding = ActivityMessageSearchBinding.inflate(layoutInflater)
        setupActionBar()
        setContentView(binding.root)
        initSystemBars()

        initFilter()

        viewModel = ViewModelProvider(this, viewModelFactory)[MessageSearchViewModel::class.java]
        user = currentUserProvider.currentUser.blockingGet()
        val roomToken = intent.getStringExtra(BundleKeys.KEY_ROOM_TOKEN)!!
        viewModel.initialize(roomToken)
        setupStateObserver()

        binding.swipeRefreshLayout.setOnRefreshListener {
            val newText = searchView.query.toString()
            viewModel.refresh("${processedSearchText(newText)}${filterItemChoose.filterType.value}")
        }

        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
    }

    /**
     * 搜索关键字去除筛选类型
     * add by ray on 2026/02/04
     */
    private fun processedSearchText(newText: String): String {
        val processedText = if (TextUtils.isEmpty(filterItemChoose.filterText)) {
            newText
        } else {
            removeFirstMatchCharacter(newText, filterItemChoose.filterText.toString())
        }

        return processedText
    }

    private fun setupActionBar() {
        setSupportActionBar(binding.messageSearchToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val conversationName = intent.getStringExtra(BundleKeys.KEY_CONVERSATION_NAME)
        supportActionBar?.title = conversationName
        viewThemeUtils.material.themeToolbar(binding.messageSearchToolbar)
    }

    private fun setupStateObserver() {
        viewModel.state.observe(this) { state ->
            when (state) {
                MessageSearchViewModel.InitialState -> showInitial()
                MessageSearchViewModel.EmptyState -> showEmpty()
                is MessageSearchViewModel.LoadedState -> showLoaded(state)
                MessageSearchViewModel.LoadingState -> showLoading()
                MessageSearchViewModel.ErrorState -> showError()
                is MessageSearchViewModel.FinishedState -> onFinish()
            }
        }
    }

    private fun showError() {
        displayLoading(false)
        Snackbar.make(binding.root, "Error while searching", Snackbar.LENGTH_SHORT).show()
    }

    private fun showLoading() {
        displayLoading(true)
    }

    private fun displayLoading(loading: Boolean) {
        binding.swipeRefreshLayout.isRefreshing = loading
    }

    private fun initFilter() {
        val filterItems = filters.map {
            MessageFilterItem(
                this,
                it,
                object : MessageFilterItemListener {

                    override fun onMessageFilterItemClicked(
                        view: View,
                        position: Int,
                        messageFilter: MessageFilter
                    ) {
                        Log.e("Ray", "onClick")
                    }
                }
            )
        }
        filterAdapter = FlexibleAdapter(filterItems)
        binding.emptyContainer.messageSearchFilterRecycler.adapter = filterAdapter
        filterAdapter!!.addListener(object : FlexibleAdapter.OnItemClickListener {
            override fun onItemClick(view: View?, position: Int): Boolean {
                // val item = filterAdapter!!.getItem(position)
                filterItemChoose = filters[position]
                Log.e("Ray", filterItemChoose.toString())
                highlightText(editInput, filterItemChoose.filterText)
                return false
            }
        })
    }
    private fun showLoaded(state: MessageSearchViewModel.LoadedState) {
        displayLoading(false)
        binding.emptyContainer.emptyListView.visibility = View.GONE
        binding.messageSearchRecycler.visibility = View.VISIBLE
        setAdapterItems(state)
    }

    private fun setAdapterItems(state: MessageSearchViewModel.LoadedState) {
        val loadMoreItems = if (state.hasMore) {
            listOf(LoadMoreResultsItem)
        } else {
            emptyList()
        }
        val newItems =
            state.results.map { MessageResultItem(this, user, it, false, viewThemeUtils) } + loadMoreItems

        if (adapter != null) {
            adapter!!.updateDataSet(newItems)
        } else {
            createAdapter(newItems)
        }
    }

    private fun createAdapter(items: List<AbstractFlexibleItem<out FlexibleViewHolder>>) {
        adapter = FlexibleAdapter(items)
        binding.messageSearchRecycler.adapter = adapter
        adapter!!.addListener(object : FlexibleAdapter.OnItemClickListener {
            override fun onItemClick(view: View?, position: Int): Boolean {
                val item = adapter!!.getItem(position)
                when (item) {
                    is LoadMoreResultsItem -> {
                        viewModel.loadMore()
                    }
                    is MessageResultItem -> {
                        viewModel.selectMessage(item.messageEntry)
                    }
                }
                return false
            }
        })
    }

    private fun onFinish() {
        val state = viewModel.state.value
        if (state is MessageSearchViewModel.FinishedState) {
            val resultIntent = Intent().apply {
                putExtra(RESULT_KEY_MESSAGE_ID, state.selectedMessageId)
                putExtra(RESULT_KEY_THREAD_ID, state.selectedThreadId)
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }

    private fun showInitial() {
        displayLoading(false)
        binding.messageSearchRecycler.visibility = View.GONE
        binding.emptyContainer.emptyListViewHeadline.text = getString(R.string.message_search_begin_typing)
        binding.emptyContainer.emptyListView.visibility = View.VISIBLE
    }

    private fun showEmpty() {
        displayLoading(false)
        binding.messageSearchRecycler.visibility = View.GONE
        binding.emptyContainer.emptyListViewHeadline.text = getString(R.string.message_search_begin_empty)
        binding.emptyContainer.emptyListView.visibility = View.VISIBLE
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_search, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val menuItem = menu.findItem(R.id.action_search)
        searchView = menuItem.actionView as SearchView
        setupSearchView()
        menuItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                searchView.requestFocus()
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                onBackPressedDispatcher.onBackPressed()
                return false
            }
        })
        menuItem.expandActionView()
        return true
    }

    private fun highlightText(editText: AppCompatAutoCompleteTextView, text: String?) {
        if (TextUtils.isEmpty(filterItemChoose.filterText)) {
            return
        }
        val ss = SpannableString(text)
        // 替换关键字模式或正则表达式
        val pattern = Pattern.compile(filterItemChoose.filterText)
        val matcher = pattern.matcher(text)

        // 将 while (matcher.find()) 改为 if (matcher.find())
        if (matcher.find()) {
            // ss.setSpan(
            //     ForegroundColorSpan(getColor(R.color.colorPrimary)),
            //     matcher.start(),
            //     matcher.end(),
            //     Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            // )
            ss.setSpan(
                RoundedBackgroundSpan(
                    getColor(R.color.transparent),
                    getColor(R.color.colorPrimary),
                    // 圆角半径
                    cornerRadius = 4f,
                    // 四周边距
                    padding = 20f
                ),
                matcher.start(),
                matcher.end(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        } else {
            filterItemChoose = MessageFilter(-10000, "", MessageFilterType.TEXT)
            return
        }
        editText.setText(ss)
        // 将光标移动到文本末尾
        editText.setSelection(ss.length)
    }
    @SuppressLint("RestrictedApi")
    private fun setupSearchView() {
        searchView.queryHint = getString(R.string.message_search_hint)
        editInput = searchView.findViewById(androidx.appcompat.R.id.search_src_text)
        searchViewDisposable = observeSearchView(searchView)
            .debounce { query ->
                when {
                    TextUtils.isEmpty(query) -> Observable.empty()
                    else -> Observable.timer(
                        // 搜索延迟执行时间
                        ConversationsListActivity.SEARCH_DEBOUNCE_INTERVAL_MS * 2.toLong(),
                        TimeUnit.MILLISECONDS
                    )
                }
            }
            .distinctUntilChanged()
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { newText ->
                // 以下逻辑使用防抖功能实现
                highlightText(editInput, newText)

                // newText和筛选项都为空则显示binding.emptyContainer
                if (TextUtils.isEmpty(newText) && TextUtils.isEmpty(filterItemChoose.filterType.value)) {
                    showInitial()
                    return@subscribe
                }

                // viewModel.onQueryTextChange(newText)
                val processedText = processedSearchText(newText)
                viewModel.onQueryTextChange("$processedText${filterItemChoose.filterType.value}")
            }
    }

    private fun removeFirstMatchCharacter(newText: String, charToRemove: String): String {
        val index = newText.indexOf(charToRemove)
        return if (index != -1) {
            newText.substring(index + charToRemove.length, newText.length)
        } else {
            newText // 如果未找到匹配字符，返回原字符串
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    override fun onDestroy() {
        super.onDestroy()
        searchViewDisposable?.dispose()
    }

    companion object {
        const val RESULT_KEY_MESSAGE_ID = "MessageSearchActivity.result.message"
        const val RESULT_KEY_THREAD_ID = "MessageSearchActivity.result.thread"
    }
}
