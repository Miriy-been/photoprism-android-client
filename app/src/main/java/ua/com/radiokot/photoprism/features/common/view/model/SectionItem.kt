package ua.com.radiokot.photoprism.features.common.view.model

import android.view.View
import android.view.ViewGroup
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.items.AbstractItem

/**
 * 分组列表中的 section 标识接口。
 * 实现此接口的 item 可作为列表中的分组头（header）。
 *
 * 用于日期分组的 month/day header、字母索引等场景。
 */
interface SectionItem {

    /**
     * 分组的标识键，用于 DiffUtil 比较。
     * 同一分组的所有 item 应有相同的 sectionKey。
     */
    val sectionKey: String

    /**
     * 分组标题的显示文本。
     */
    val sectionLabel: String
}

/**
 * 空/加载状态列表项。
 * 用于列表底部加载指示器或空状态占位。
 *
 * @param id 唯一标识符
 * @param layoutRes 布局资源 ID
 */
open class LoadingFooterListItem(
    private val id: Long = Long.MIN_VALUE,
    override val layoutRes: Int,
) : AbstractItem<LoadingFooterListItem.ViewHolder>() {

    override val type: Int
        get() = LoadingFooterListItem::class.java.hashCode()

    override var identifier: Long = id
        get() = field

    override fun getViewHolder(v: View): ViewHolder = ViewHolder(v)

    class ViewHolder(itemView: View) :
        FastAdapter.ViewHolder<LoadingFooterListItem>(itemView) {

        override fun bindView(item: LoadingFooterListItem, payloads: List<Any>) {
            // Bind if needed
        }

        override fun unbindView(item: LoadingFooterListItem) {
            // Unbind if needed
        }
    }
}
