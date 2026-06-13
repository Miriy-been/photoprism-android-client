package ua.com.radiokot.photoprism.features.prefs.navcustomize.view

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.koin.android.ext.android.inject
import ua.com.radiokot.photoprism.base.view.BaseActivity
import ua.com.radiokot.photoprism.databinding.ActivityCustomizeNavBinding
import ua.com.radiokot.photoprism.databinding.ListItemCustomizeNavBinding
import ua.com.radiokot.photoprism.R
import ua.com.radiokot.photoprism.features.albums.data.model.Album
import ua.com.radiokot.photoprism.features.albums.view.AlbumsActivity
import ua.com.radiokot.photoprism.features.gallery.data.model.SearchConfig
import ua.com.radiokot.photoprism.features.gallery.data.storage.BottomNavItemId
import ua.com.radiokot.photoprism.features.gallery.data.storage.GalleryNavPreferences
import ua.com.radiokot.photoprism.features.gallery.data.storage.SimpleGalleryMediaRepository
import ua.com.radiokot.photoprism.features.gallery.view.GalleryActivity
import ua.com.radiokot.photoprism.features.gallery.view.GallerySingleRepositoryActivity

/**
 * 侘寂 — 底部菜单栏自定义界面。
 * 支持拖拽排序 + 开关切换显示/隐藏。
 */
class CustomizeNavActivity : BaseActivity() {

    private val navPreferences: GalleryNavPreferences by inject()
    private lateinit var binding: ActivityCustomizeNavBinding
    private lateinit var adapter: NavItemAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper

    /** 当前列表项（含顺序） */
    private val items: MutableList<BottomNavItemId> =
        BottomNavItemId.ALL_ITEMS.toMutableList()

    /** 当前在底部栏显示的项 */
    private val visibleItems: MutableSet<BottomNavItemId> =
        BottomNavItemId.DEFAULT.toMutableSet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (goToEnvConnectionIfNoSession()) {
            return
        }

        // 加载已保存的配置
        val savedConfig = navPreferences.bottomNavItems.value ?: BottomNavItemId.DEFAULT
        visibleItems.clear()
        visibleItems.addAll(savedConfig)

        // 排序：已显示项在前，未显示项在后
        items.clear()
        items.addAll(savedConfig)
        items.addAll(BottomNavItemId.ALL_ITEMS.filter { it !in savedConfig })

        binding = ActivityCustomizeNavBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        adapter = NavItemAdapter()
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        // 拖拽排序
        itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                source: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                val fromPos = source.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                val moved = items.removeAt(fromPos)
                items.add(toPos, moved)
                adapter.notifyItemMoved(fromPos, toPos)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun isLongPressDragEnabled(): Boolean = false
        })
        itemTouchHelper.attachToRecyclerView(binding.recyclerView)

        initBottomNav()
    }

    private fun initBottomNav() {
        // Customize nav is a settings page, no bottom nav item is "active"
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            saveConfig()
            when (item.itemId) {
                R.id.bottom_photos -> {
                    startActivity(
                        Intent(this, GalleryActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                    )
                    true
                }

                R.id.bottom_albums -> {
                    startActivity(
                        Intent(this, AlbumsActivity::class.java)
                            .setAction(intent.action)
                            .putExtras(intent.extras ?: Bundle())
                            .putExtras(
                                AlbumsActivity.getBundle(
                                    albumType = Album.TypeName.FOLDER,
                                    defaultSearchConfig = SearchConfig.DEFAULT,
                                )
                            )
                    )
                    true
                }

                R.id.bottom_favorites -> {
                    startActivity(
                        Intent(this, GallerySingleRepositoryActivity::class.java)
                            .putExtras(intent.extras ?: Bundle())
                            .putExtras(
                                GallerySingleRepositoryActivity.getBundle(
                                    title = getString(R.string.favorites),
                                    repositoryParams = SimpleGalleryMediaRepository.Params(
                                        searchConfig = SearchConfig.DEFAULT.copy(
                                            onlyFavorite = true,
                                        ),
                                    ),
                                )
                            )
                    )
                    true
                }

                R.id.bottom_more -> {
                    finish()
                    true
                }

                else -> false
            }
        }
    }

    override fun onBackPressed() {
        saveConfig()
        super.onBackPressed()
    }

    private fun saveConfig() {
        val bottomConfig = items
            .filter { it in visibleItems }
            .take(BottomNavItemId.MAX_VISIBLE_ITEMS)

        if (bottomConfig.isEmpty()) {
            navPreferences.bottomNavItems.onNext(listOf(BottomNavItemId.PHOTOS))
        } else {
            navPreferences.bottomNavItems.onNext(bottomConfig)
        }
    }

    // --- Adapter ---

    private inner class NavItemAdapter :
        RecyclerView.Adapter<NavItemAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ListItemCustomizeNavBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(
            private val binding: ListItemCustomizeNavBinding,
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(item: BottomNavItemId) {
                binding.iconView.setImageResource(item.iconRes)
                binding.labelTextView.text = getString(item.labelRes)

                val isVisible = item in visibleItems
                val isLocked = item in BottomNavItemId.LOCKED_ITEMS

                binding.visibilitySwitch.apply {
                    setOnCheckedChangeListener(null)
                    isChecked = isVisible
                    isEnabled = !isLocked
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) {
                            if (visibleItems.size >= BottomNavItemId.MAX_VISIBLE_ITEMS) {
                                isChecked = false
                            } else {
                                visibleItems.add(item)
                            }
                        } else {
                            visibleItems.remove(item)
                        }
                    }
                }

                // 拖拽手柄长按触发拖拽
                binding.dragHandle.setOnTouchListener { _, event ->
                    if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                        itemTouchHelper.startDrag(this@ViewHolder)
                    }
                    false
                }
            }
        }
    }
}
