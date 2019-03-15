package com.kaibo.music.fragment.search

import android.os.Bundle
import android.view.View
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.flexbox.JustifyContent
import com.jakewharton.rxbinding2.widget.textChanges
import com.kaibo.core.adapter.withItems
import com.kaibo.core.fragment.BaseFragment
import com.kaibo.core.util.checkResult
import com.kaibo.core.util.easyClick
import com.kaibo.core.util.statusBarHeight
import com.kaibo.core.util.toMainThread
import com.kaibo.music.R
import com.kaibo.music.bean.HotSearchBean
import com.kaibo.music.item.search.HotSearchItem
import com.kaibo.music.net.Api
import kotlinx.android.synthetic.main.fragment_search.*
import java.util.concurrent.TimeUnit

/**
 * @author kaibo
 * @date 2018/11/1 9:49
 * @GitHub：https://github.com/yuxuelian
 * @email：kaibo1hao@gmail.com
 * @description：
 */


class SearchFragment : BaseFragment() {

    companion object {
        fun newInstance(arguments: Bundle = Bundle()): SearchFragment {
            return SearchFragment().apply {
                this.arguments = arguments
            }
        }
    }

    override fun onBackPressedSupport(): Boolean {
        pop()
        return true
    }

    override fun getLayoutRes() = R.layout.fragment_search

    override fun initViewCreated(savedInstanceState: Bundle?) {
        super.initViewCreated(savedInstanceState)
        appBarLayout.setPadding(0, mActivity.statusBarHeight, 0, 0)
        Api.instance.getHotSearch().checkResult().toMainThread().`as`(bindLifecycle()).subscribe({
            initHotSearchList(it)
        }) {
            it.printStackTrace()
        }

        // 获取搜索框中的内容
        searchContentInput.textChanges()
                .debounce(200L, TimeUnit.MILLISECONDS).toMainThread()
                .`as`(bindLifecycle()).subscribe {
                    if (it.isNotEmpty()) {
                        clearInput.visibility = View.VISIBLE
                        clearInput.isEnabled = true
                    } else {
                        clearInput.visibility = View.INVISIBLE
                        clearInput.isEnabled = false
                    }
                }

        // 点击清除按钮
        clearInput.easyClick(bindLifecycle()).subscribe {
            searchContentInput.setText("")
        }

        // 点击返回键
        backBtn.easyClick(bindLifecycle()).subscribe {
            pop()
        }
    }

    private fun initHotSearchList(hotSearchBeanList: List<HotSearchBean>) {
        val layoutManager = FlexboxLayoutManager(mActivity)
        layoutManager.justifyContent = JustifyContent.SPACE_BETWEEN
        hot_search_list.layoutManager = layoutManager
        hot_search_list.isNestedScrollingEnabled = false
        hot_search_list.withItems(hotSearchBeanList.asSequence()
                .filterIndexed { index, _ -> index < 10 }
                .map { hotSearchBean: HotSearchBean ->
                    HotSearchItem(hotSearchBean) {
                        initItem(hotSearchBean)
                    }
                }
                .toList())
    }

    private fun View.initItem(hotSearchBean: HotSearchBean) {
        setOnClickListener {
            searchContentInput.setText(hotSearchBean.searchKey)
            // TODO 执行搜索操作

        }
    }
}