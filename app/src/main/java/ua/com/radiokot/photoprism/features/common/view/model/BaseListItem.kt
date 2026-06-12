package ua.com.radiokot.photoprism.features.common.view.model

import android.view.View
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.items.AbstractItem
import com.squareup.picasso.Picasso
import ua.com.radiokot.photoprism.extension.hardwareConfigIfAvailable
import kotlin.reflect.KClass

/**
 * 通用的列表项基类，封装了 FastAdapter Item 的通用模式：
 * - Koin 作用域和 Picasso 注入
 * - 缩略图加载工具方法
 * - ViewHolder 中的图片取消请求
 *
 * 使用方式：
 * ```
 * class MyListItem(
 *     private val thumbnailUrl: String,
 * ) : BaseListItem<MyListItem.ViewHolder>() {
 *     override val type: KClass<*> = MyListItem::class
 *     override val layoutRes: Int = R.layout.list_item_my
 *     override fun getViewHolder(v: View) = ViewHolder(v)
 *
 *     class ViewHolder(itemView: View) : BaseViewHolder(itemView) {
 *         fun bindView(item: MyListItem) {
 *             loadThumbnail(item.thumbnailUrl, imageView)
 *         }
 *     }
 * }
 * ```
 *
 * @param VH ViewHolder 类型
 */
abstract class BaseListItem<VH : BaseListItem.BaseViewHolder> : AbstractItem<VH>() {

    /**
     * 通用的 ViewHolder 基类，提供：
     * - Koin 作用域访问
     * - Picasso 注入
     * - 缩略图加载快捷方法
     * - 自动取消图片请求
     */
    abstract class BaseViewHolder(itemView: View) : FastAdapter.ViewHolder<BaseListItem<*>>(itemView) {

        final override fun bindView(item: BaseListItem<*>, payloads: List<Any>) {
            // 由子类实现具体的绑定逻辑
            onBindView(item)
        }

        /**
         * 子类实现具体的视图绑定逻辑。
         */
        protected abstract fun onBindView(item: BaseListItem<*>)

        override fun unbindView(item: BaseListItem<*>) {
            // Cancel pending Picasso requests for this view
        }

        /**
         * 加载缩略图到 ImageView。
         * 使用硬解配置（API 26+）、占位图、fit+CenterCrop。
         */
        protected fun loadThumbnail(url: String, target: android.widget.ImageView) {
            Picasso.get()
                .load(url)
                .hardwareConfigIfAvailable()
                .fit()
                .centerCrop()
                .into(target)
        }
    }
}
