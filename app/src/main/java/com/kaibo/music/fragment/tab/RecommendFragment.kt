package com.kaibo.music.fragment.tab

import android.os.Bundle
import androidx.recyclerview.widget.LinearLayoutManager
import com.kaibo.core.adapter.withItems
import com.kaibo.core.fragment.BaseFragment
import com.kaibo.core.util.animStartActivity
import com.kaibo.core.util.bindLifecycle
import com.kaibo.core.util.checkResult
import com.kaibo.core.util.toMainThread
import com.kaibo.music.R
import com.kaibo.music.activity.SongListActivity
import com.kaibo.music.item.recommend.BannerItem
import com.kaibo.music.item.recommend.RecommendItem
import com.kaibo.music.item.recommend.SongTitleItem
import com.kaibo.music.player.bean.BannerDataBean
import com.kaibo.music.player.bean.RecommendBean
import com.kaibo.music.player.net.Api
import io.reactivex.Observable
import io.reactivex.functions.BiFunction
import kotlinx.android.synthetic.main.fragment_recommend.*

/**
 * @author kaibo
 * @createDate 2018/10/9 10:31
 * @GitHub：https://github.com/yuxuelian
 * @email：kaibo1hao@gmail.com
 * @description：
 */
class RecommendFragment : BaseFragment() {

    private val bannerItem by lazy {
        BannerItem()
    }

    override fun getLayoutRes() = R.layout.fragment_recommend

    override fun initViewCreated(savedInstanceState: Bundle?) {
        Observable
                .zip(
                        Api.instance.getBannerList().checkResult(),
                        Api.instance.getRecommendList().checkResult(),
                        BiFunction<List<BannerDataBean>, List<RecommendBean>, Pair<List<BannerDataBean>, List<RecommendBean>>> { t1, t2 ->
                            Pair(t1, t2)
                        })
                .toMainThread()
                .`as`(bindLifecycle())
                .subscribe({ netRes ->
                    initRecommendList(netRes)
                }) {
                    it.printStackTrace()
                }
    }

    private fun initRecommendList(netRes: Pair<List<BannerDataBean>, List<RecommendBean>>) {
        recommendRecyclerView.layoutManager = LinearLayoutManager(context)
        // 设置纵向回弹
        recommendRecyclerView.withItems {
            // 设置轮播图数据
            bannerItem.setData(netRes.first)
            // 列表轮播图
            add(bannerItem)
            // 列表标题
            add(SongTitleItem())
            // 列表数据
            addAll(netRes.second.map { recommendBean: RecommendBean ->
                RecommendItem(recommendBean) {
                    setOnClickListener {
                        animStartActivity<SongListActivity>("disstid" to recommendBean.disstid)
                    }
                }
            })
        }
    }

    override fun onResume() {
        super.onResume()
        bannerItem.startAutoPlay()
    }

    override fun onPause() {
        bannerItem.stopAutoPlay()
        super.onPause()
    }

}