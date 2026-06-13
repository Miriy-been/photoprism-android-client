package ua.com.radiokot.photoprism.features.gallery.view

import android.content.Intent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.drawerlayout.widget.DrawerLayout
import androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationView
import com.google.android.material.navigationrail.NavigationRailView
import ua.com.radiokot.photoprism.R
import ua.com.radiokot.photoprism.databinding.DialogMoreMenuBinding
import ua.com.radiokot.photoprism.features.gallery.data.storage.BottomNavItemId
import ua.com.radiokot.photoprism.features.gallery.search.view.GallerySearchBarView
import ua.com.radiokot.photoprism.features.gallery.view.model.GalleryViewModel
import ua.com.radiokot.photoprism.features.prefs.navcustomize.view.CustomizeNavActivity

class GalleryNavigationView(
    private val viewModel: GalleryViewModel,
) {
    /**
     * 当 [switchToTab] 程序化设置 selectedItemId 时设为 true，
     * 防止 OnItemSelectedListener 递归触发。
     */
    var suppressProgrammaticSelection = false

    private var closeDrawer: (() -> Unit)? = null
    val backPressedCallback: OnBackPressedCallback =
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                closeDrawer?.invoke()
            }
        }

    /** 底部导航项 → 点击行为 映射表 */
    private val bottomNavActions: Map<Int, (GalleryViewModel) -> Unit> = mapOf(
        R.id.bottom_photos to { vm -> vm.onPhotosClicked() },
        R.id.bottom_search to { vm -> vm.searchViewModel.onSearchSummaryClicked() },
        R.id.bottom_albums to { vm -> vm.onAlbumsClicked() },
        R.id.favorites to { vm -> vm.onFavoritesClicked() },
        R.id.places to { vm -> vm.onPlacesClicked() },
        R.id.calendar to { vm -> vm.onCalendarClicked() },
        R.id.labels to { vm -> vm.onLabelsClicked() },
        R.id.folders to { vm -> vm.onFoldersClicked() },
        R.id.upload to { vm -> vm.onUploadClicked() },
        R.id.preferences to { vm -> vm.onPreferencesClicked() },
    )

    fun initWithDrawer(
        drawerLayout: DrawerLayout,
        navigationView: NavigationView,
        searchBarView: GallerySearchBarView,
    ) {
        initMenu(
            navigationMenu = navigationView.menu,
            onItemClicked = drawerLayout::close,
        )

        searchBarView.addNavigationMenuIcon {
            drawerLayout.openDrawer(navigationView, true)
        }

        closeDrawer = drawerLayout::closeDrawers
        drawerLayout.addDrawerListener(object : SimpleDrawerListener() {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                backPressedCallback.isEnabled = slideOffset >= 0.4f
            }
        })
    }

    fun initWithRail(
        navigationRail: NavigationRailView,
    ) {
        initMenu(
            navigationMenu = navigationRail.menu,
        )
    }

    fun initWithBottomNav(
        bottomNavigation: BottomNavigationView,
        searchBarView: GallerySearchBarView,
        config: List<BottomNavItemId>,
    ) {
        // 动态构建菜单
        val menu = bottomNavigation.menu
        menu.clear()

        config.forEach { itemId ->
            menu.add(Menu.NONE, itemId.menuResId, Menu.NONE, bottomNavigation.context.getString(itemId.labelRes))
                .setIcon(itemId.iconRes)
        }
        // "更多" 始终在最后
        menu.add(Menu.NONE, R.id.bottom_more, Menu.NONE, bottomNavigation.context.getString(R.string.more))
            .setIcon(R.drawable.ic_gear)

        // 统一点击分发
        bottomNavigation.setOnItemSelectedListener { item ->
            // 防止程序化选中触发递归
            if (suppressProgrammaticSelection) {
                suppressProgrammaticSelection = false
                return@setOnItemSelectedListener true
            }

            val action = bottomNavActions[item.itemId]
            if (action != null) {
                action(viewModel)
            } else if (item.itemId == R.id.bottom_more) {
                showMoreMenu(bottomNavigation, config)
            }
            true
        }
    }

    private fun showMoreMenu(anchorView: View, bottomConfig: List<BottomNavItemId>) {
        val dialog = BottomSheetDialog(anchorView.context)
        val binding = DialogMoreMenuBinding.inflate(
            LayoutInflater.from(anchorView.context)
        )

        fun closeAnd(action: () -> Unit) {
            dialog.dismiss()
            action()
        }

        with(binding) {
            moreSyncSettings.setOnClickListener {
                closeAnd(viewModel::onSyncClicked)
            }
            moreCustomizeNav.setOnClickListener {
                closeAnd {
                    viewModel.run {
                        anchorView.context.startActivity(
                            Intent(anchorView.context, CustomizeNavActivity::class.java)
                        )
                    }
                }
            }
            morePreferences.setOnClickListener {
                closeAnd(viewModel::onPreferencesClicked)
            }
        }

        dialog.setContentView(binding.root)
        dialog.show()
    }

    private fun initMenu(
        navigationMenu: Menu,
        onItemClicked: () -> Unit = {},
    ) {
        fun getClickListener(extraAction: () -> Any) = MenuItem.OnMenuItemClickListener {
            extraAction()
            onItemClicked()
            true
        }

        navigationMenu.findItem(R.id.albums)
            .setOnMenuItemClickListener(getClickListener(viewModel::onAlbumsClicked))

        navigationMenu.findItem(R.id.favorites)
            .setOnMenuItemClickListener(getClickListener(viewModel::onFavoritesClicked))

        with(navigationMenu.findItem(R.id.places)) {
            isVisible = viewModel.canSeePlaces
            setOnMenuItemClickListener(getClickListener(viewModel::onPlacesClicked))
        }

        navigationMenu.findItem(R.id.calendar)
            .setOnMenuItemClickListener(getClickListener(viewModel::onCalendarClicked))

        navigationMenu.findItem(R.id.labels)
            .setOnMenuItemClickListener(getClickListener(viewModel::onLabelsClicked))

        navigationMenu.findItem(R.id.folders)
            .setOnMenuItemClickListener(getClickListener(viewModel::onFoldersClicked))

        navigationMenu.findItem(R.id.sync_settings)
            .setOnMenuItemClickListener(getClickListener(viewModel::onSyncClicked))

        navigationMenu.findItem(R.id.upload)
            .setOnMenuItemClickListener(getClickListener(viewModel::onUploadClicked))

        navigationMenu.findItem(R.id.preferences)
            .setOnMenuItemClickListener(getClickListener(viewModel::onPreferencesClicked))
    }
}
