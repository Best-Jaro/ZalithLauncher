package com.movtery.zalithlauncher.feature.download

import android.annotation.SuppressLint
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.flexbox.FlexboxLayout
import com.movtery.zalithlauncher.event.value.DownloadRecyclerEnableEvent
import com.movtery.zalithlauncher.feature.download.enums.Platform
import com.movtery.zalithlauncher.feature.download.item.InfoItem
import com.movtery.zalithlauncher.feature.download.item.ModInfoItem
import com.movtery.zalithlauncher.feature.download.item.SearchResult
import com.movtery.zalithlauncher.feature.download.platform.PlatformNotSupportedException
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.task.TaskExecutors
import com.movtery.zalithlauncher.ui.fragment.DownloadModFragment
import com.movtery.zalithlauncher.utils.NumberWithUnits
import com.movtery.zalithlauncher.utils.ZHTools
import com.movtery.zalithlauncher.utils.stringutils.StringUtils
import net.kdt.pojavlaunch.R
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.databinding.ItemDownloadInfoBinding
import org.greenrobot.eventbus.EventBus
import org.jackhuang.hmcl.ui.versions.ModTranslations
import java.io.File
import java.util.Collections
import java.util.Locale
import java.util.StringJoiner
import java.util.TimeZone
import java.util.WeakHashMap
import java.util.concurrent.Future

class InfoAdapter(
    private val parentFragment: Fragment,
    private val mSearchResultCallback: SearchResultCallback,
    private val targetPath: File?
) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private lateinit var mPlatform: Platform
    private val mViewHolderSet: MutableSet<ViewHolder> = Collections.newSetFromMap(WeakHashMap())
    private var mItems: MutableList<InfoItem> = ArrayList()

    private var mTaskInProgress: Future<*>? = null
    private var mCurrentResult: SearchResult? = null
    private var mLastPage = false

    fun setPlatform(platform: Platform) {
        this.mPlatform = platform
    }

    fun checkPlatform(platform: Platform): Boolean = this.mPlatform != platform

    fun performSearchQuery() {
        if (mTaskInProgress != null) {
            mTaskInProgress!!.cancel(true)
            mTaskInProgress = null
        }
        this.mLastPage = false
        mTaskInProgress = SelfReferencingFuture(SearchApiTask(null))
            .startOnExecutor(TaskExecutors.getDefault())
    }

    override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layoutInflater = LayoutInflater.from(viewGroup.context)
        val view: View
        when (viewType) {
            VIEW_TYPE_MOD_ITEM -> {
                // Create a new view, which defines the UI of the list item
                return ViewHolder(ItemDownloadInfoBinding.inflate(LayoutInflater.from(viewGroup.context), viewGroup, false))
            }

            VIEW_TYPE_LOADING -> {
                // Create a new view, which is actually just the progress bar
                view = layoutInflater.inflate(R.layout.view_loading, viewGroup, false)
                return LoadingViewHolder(view)
            }

            else -> throw RuntimeException("Unimplemented view type!")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (getItemViewType(position)) {
            VIEW_TYPE_MOD_ITEM -> (holder as ViewHolder).setStateLimited(mItems[position])
            VIEW_TYPE_LOADING -> loadMoreResults()
            else -> throw RuntimeException("Unimplemented view type!")
        }
    }

    override fun getItemCount(): Int {
        if (mLastPage || mItems.isEmpty()) return mItems.size
        return mItems.size + 1
    }

    private fun loadMoreResults() {
        if (mTaskInProgress != null) return
        mTaskInProgress = SelfReferencingFuture(SearchApiTask(mCurrentResult))
            .startOnExecutor(TaskExecutors.getDefault())
    }

    override fun getItemViewType(position: Int): Int {
        if (position < mItems.size) return VIEW_TYPE_MOD_ITEM
        return VIEW_TYPE_LOADING
    }

    /**
     * Basic viewholder with expension capabilities
     */
    inner class ViewHolder(val binding: ItemDownloadInfoBinding) : RecyclerView.ViewHolder(binding.root) {
        private val mContext = binding.root.context
        private var mExtensionFuture: Future<*>? = null
        private var item: InfoItem? = null

        init {
            mViewHolderSet.add(this)
        }

        /** Display basic info about the moditem  */
        @SuppressLint("CheckResult")
        fun setStateLimited(item: InfoItem) {
            this.item = item
            val mod = ModTranslations.getTranslationsByRepositoryType(item.platform.helper.currentClassify)
                .getModByCurseForgeId(item.slug)

            if (mExtensionFuture != null) {
                /*
                 * Since this method reinitializes the ViewHolder for a new mod, this Future stops being ours, so we cancel it
                 * and null it. The rest is handled above
                 */
                mExtensionFuture!!.cancel(true)
                mExtensionFuture = null
            }

            binding.apply {
                root.setOnClickListener {
                    EventBus.getDefault().post(DownloadRecyclerEnableEvent(false))

                    val infoViewModel = ViewModelProvider(parentFragment.requireActivity())[InfoViewModel::class.java]
                    infoViewModel.infoItem = item.copy()
                    infoViewModel.platformHelper = item.platform.helper.copy()
                    infoViewModel.targetPath = targetPath

                    ZHTools.addFragment(
                        parentFragment,
                        DownloadModFragment::class.java, DownloadModFragment.TAG, null
                    )
                }

                titleTextview.text =
                    if (ZHTools.areaChecks("zh")) {
                        mod?.displayName ?: item.title
                    } else {
                        item.title
                    }
                descriptionTextview.text = item.description
                platformImageview.setImageDrawable(getPlatformIcon(item.platform))
                //设置类别
                categoriesLayout.removeAllViews()
                item.category.forEach { item ->
                    addCategoryView(categoriesLayout, mContext.getString(item.resNameID))
                }
                //设置标签
                tagsLayout.removeAllViews()

                val downloadCount = NumberWithUnits.formatNumberWithUnit(item.downloadCount, ZHTools.isEnglish(mContext))
                tagsLayout.addView(getTagTextView(R.string.download_info_downloads, downloadCount))

                item.author?.let {
                    val authorSJ = StringJoiner(", ")
                    for (s in item.author) {
                        authorSJ.add(s)
                    }
                    tagsLayout.addView(getTagTextView(R.string.download_info_author, authorSJ.toString()))
                }

                tagsLayout.addView(getTagTextView(R.string.download_info_date, StringUtils.formatDate(item.uploadDate, Locale.getDefault(), TimeZone.getDefault())))
                if (item is ModInfoItem) {
                    val modloaderSJ = StringJoiner(", ")
                    for (s in item.modloaders) {
                        modloaderSJ.add(s.loaderName)
                    }
                    val modloaderText = if (modloaderSJ.length() > 0) modloaderSJ.toString()
                    else mContext.getString(R.string.generic_unknown)
                    tagsLayout.addView(getTagTextView(R.string.download_info_modloader, modloaderText))
                }

                item.iconUrl?.apply {
                    Glide.with(mContext).load(this).apply {
                        if (!AllSettings.resourceImageCache) diskCacheStrategy(DiskCacheStrategy.NONE)
                    }.into(thumbnailImageview)
                }
            }
            binding.tagsLayout
        }

        private fun getPlatformIcon(platform: Platform): Drawable? {
            return when (platform) {
                Platform.MODRINTH -> ContextCompat.getDrawable(mContext, R.drawable.ic_modrinth)
                Platform.CURSEFORGE -> ContextCompat.getDrawable(mContext, R.drawable.ic_curseforge)
            }
        }

        private fun addCategoryView(layout: FlexboxLayout, text: String) {
            val inflater = LayoutInflater.from(mContext)
            val textView = inflater.inflate(R.layout.item_mod_category_textview, layout, false) as TextView
            textView.text = text
            textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, Tools.dpToPx(9F))

            layout.addView(textView)
        }

        private fun getTagTextView(string: Int, value: String): TextView {
            val textView = TextView(mContext)
            textView.text = StringUtils.insertSpace(mContext.getString(string), value)
            val layoutParams = FlexboxLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            layoutParams.setMargins(0, 0, Tools.dpToPx(10f).toInt(), 0)
            textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, Tools.dpToPx(9F))
            textView.layoutParams = layoutParams
            return textView
        }
    }

    /**
     * The view holder used to hold the progress bar at the end of the list
     */
    private class LoadingViewHolder(view: View) : RecyclerView.ViewHolder(view)

    private inner class SearchApiTask(
        private val mPreviousResult: SearchResult?
    ) :
        SelfReferencingFuture.FutureInterface {

        @SuppressLint("NotifyDataSetChanged")
        override fun run(myFuture: Future<*>) {
            runCatching {
                val result: SearchResult? = mPlatform.helper.search(mPreviousResult ?: SearchResult())

                TaskExecutors.runInUIThread {
                    if (myFuture.isCancelled) return@runInUIThread
                    mTaskInProgress = null

                    when {
                        result == null -> {
                            mSearchResultCallback.onSearchError(SearchResultCallback.ERROR_INTERNAL)
                        }
                        result.isLastPage -> {
                            if (result.infoItems.isEmpty()) {
                                mSearchResultCallback.onSearchError(SearchResultCallback.ERROR_NO_RESULTS)
                            } else {
                                mLastPage = true
                                mItems = result.infoItems
                                notifyDataSetChanged()
                                mSearchResultCallback.onSearchFinished()
                                return@runInUIThread
                            }
                        }
                        else -> {
                            mSearchResultCallback.onSearchFinished()
                        }
                    }

                    if (result == null) {
                        mItems = MOD_ITEMS_EMPTY
                        notifyDataSetChanged()
                        return@runInUIThread
                    } else {
                        mItems = result.infoItems
                        notifyDataSetChanged()
                        mCurrentResult = result
                    }
                }
            }.getOrElse { e ->
                TaskExecutors.runInUIThread {
                    mItems = MOD_ITEMS_EMPTY
                    notifyDataSetChanged()
                    Logging.e("SearchTask", Tools.printToString(e))
                    if (e is PlatformNotSupportedException) {
                        mSearchResultCallback.onSearchError(SearchResultCallback.ERROR_PLATFORM_NOT_SUPPORTED)
                    } else {
                        mSearchResultCallback.onSearchError(SearchResultCallback.ERROR_NO_RESULTS)
                    }
                }
            }
        }
    }

    interface SearchResultCallback {
        fun onSearchFinished()
        fun onSearchError(error: Int)

        companion object {
            const val ERROR_INTERNAL: Int = 0
            const val ERROR_NO_RESULTS: Int = 1
            const val ERROR_PLATFORM_NOT_SUPPORTED: Int = 2
        }
    }

    companion object {
        private val MOD_ITEMS_EMPTY: MutableList<InfoItem> = ArrayList()
        private const val VIEW_TYPE_MOD_ITEM = 0
        private const val VIEW_TYPE_LOADING = 1
    }
}
