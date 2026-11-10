package com.github.kr328.clash.design.main

import android.view.FocusFinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.design.MainDesign.MainTab
import com.github.kr328.clash.design.MainDesign.Request
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.branding.BrandHolder
import com.github.kr328.clash.design.databinding.DesignMainBinding
import com.github.kr328.clash.design.model.AppLanguage
import com.github.kr328.clash.design.motion.transformExpressivePage
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.isTelevision
import com.github.kr328.clash.design.util.resolveThemedColor
import java.lang.ref.WeakReference
import com.google.android.material.R as MaterialR

/**
 * The Home / Profiles / Routing / Operator / Settings pager and its bottom nav. The page set follows
 * the active brand; the pager is circular (a boundary placeholder on each side), so a logical tab
 * and a pager position differ by one.
 */
internal class MainPager(
    private val binding: DesignMainBinding,
    private val uiStore: UiStore,
    private val sendRequest: (Request) -> Unit,
    private val onTabRendered: () -> Unit,
    private val onNavigationHeightChanged: (Int) -> Unit,
    /** TV without "Advanced tools on TV": the Routing tab (rule editors, proxy chain) is left out. */
    private val tvHidesRouting: () -> Boolean = { false },
    /** Runs after the tab set changed (the TV Settings entries follow the Routing tab). */
    private val onTabsChanged: () -> Unit = {},
) {
    private val tv = binding.root.context.isTelevision()

    /** TV: the control each tab's content last had, so RIGHT from the nav rail returns to it. */
    private val tvContentFocus = HashMap<MainTab, WeakReference<View>>()

    /** TV: the pending tab switch for the rail item focus rests on (see [scheduleRailTabSwitch]). */
    private var tvRailSwitch: Runnable? = null

    /** The tabs in the bottom nav (the TV rail), in display order. */
    val tabs: List<MainTab>
        get() = activeTabs

    /**
     * The set of tabs currently visible in bottom nav + ViewPager, in display order.
     * Default mode is 4 tabs (no Operator). Operator appears between Routing and
     * Settings when the operator brand is active; when the brand additionally sets
     * Hide-Routing=true, Operator replaces Routing in place (still 4 tabs).
     */
    private var activeTabs: List<MainTab> = DEFAULT_TABS

    /** Held so [rebuildMainPagerForTabs] can detach/reattach without piling up. */
    private var pagerPageChangeCallback: ViewPager2.OnPageChangeCallback? = null
    private var pagerInitialised: Boolean = false
    private var renderedTab: MainTab? = null

    /** One-time setup. Wires the OnPageChangeCallback exactly once. */
    fun setup() {
        if (pagerInitialised) return
        pagerInitialised = true
        if (tv) {
            applyTvNavigationRail()
        } else {
            binding.mainBottomNavCard.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                if (view.height > 0) onNavigationHeightChanged(view.height)
            }
        }

        attachPagerAdapter()
        binding.mainPager.setPageTransformer(::transformExpressivePage)

        val cb = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrollStateChanged(state: Int) {
                if (state != ViewPager2.SCROLL_STATE_IDLE) return
                val count = activeTabs.size
                when (binding.mainPager.currentItem) {
                    0 -> binding.mainPager.setCurrentItem(count, false)
                    count + 1 -> binding.mainPager.setCurrentItem(1, false)
                }
            }

            override fun onPageSelected(position: Int) {
                mainTabForPagerPosition(position)?.let(::renderMainTab)
            }
        }
        pagerPageChangeCallback = cb
        binding.mainPager.registerOnPageChangeCallback(cb)

        // Sync bottom-nav visibility with the active tab set.
        MainTab.values().forEach { tab ->
            navForMainTab(tab).visibility = if (tab in activeTabs) View.VISIBLE else View.GONE
        }
        wireBottomNavFocus()
        binding.mainPager.setCurrentItem(1, false)
        renderMainTab(activeTabs.firstOrNull() ?: MainTab.Home)
        binding.mainPager.post {
            tweakViewPagerHorizontalSwipeTolerance(binding.mainPager)
            requestInitialHomeFocus()
        }
    }

    /**
     * Give the home its initial D-pad focus on launch, so on TV the user immediately sees where
     * they are (focusedByDefault is unreliable because the home page is re-parented into the
     * ViewPager holder at runtime). Targets the primary action of the visible state.
     */
    private fun requestInitialHomeFocus() {
        val target = if (binding.mainHomeActiveContent.visibility == View.VISIBLE) {
            binding.mainPowerCard
        } else {
            binding.mainEmptyAddButton
        }
        target.requestFocus()
    }

    /**
     * Rebuild only the adapter + nav visibility when [activeTabs] actually changes.
     * Preserves: registered OnPageChangeCallback, current logical tab when possible,
     * the touch-slop tweak (we don't re-run it).
     */
    private fun rebuildMainPagerForTabs(currentLogical: MainTab?) {
        // Stay on the tab the user was on (the caller reads it before [activeTabs] changes),
        // or fall back to Home if it's no longer in the set.
        attachPagerAdapter()

        MainTab.values().forEach { tab ->
            navForMainTab(tab).visibility = if (tab in activeTabs) View.VISIBLE else View.GONE
        }
        wireBottomNavFocus()
        val targetTab = currentLogical?.takeIf { it in activeTabs }
            ?: activeTabs.firstOrNull() ?: MainTab.Home
        val targetItem = activeTabs.indexOf(targetTab).coerceAtLeast(0) + 1
        binding.mainPager.setCurrentItem(targetItem, false)
        renderMainTab(targetTab)
    }

    private fun attachPagerAdapter() {
        val pages = activeTabs.map { pageForMainTab(it) }
        // Detach every page (including ones we won't show) so the new adapter
        // can attach the ones we want without parent-collision exceptions.
        MainTab.values().forEach { tab ->
            val page = pageForMainTab(tab)
            (page.parent as? ViewGroup)?.removeView(page)
        }
        pages.forEach { it.visibility = View.VISIBLE }
        val leftExit: (() -> View?)? = if (tv) {
            { currentTab?.let { navForMainTab(it) } }
        } else {
            null
        }
        binding.mainPager.adapter = StaticPageAdapter(pages, leftExit)
        binding.mainPager.offscreenPageLimit = pages.lastIndex.coerceAtLeast(1)
    }

    /**
     * Compute the desired tab set from the active brand and rebuild the pager
     * if it changed. No-op when the layout is the same.
     *
     * The Operator tab is **explicit opt-in** via X-Brand-Show-Operator-Tab —
     * just having a brand name / logo / accent applies the visual brand but
     * does NOT add a tab. Hide-Routing only takes effect when the operator
     * also opted into the tab, otherwise it'd hide Routing without anything
     * replacing it.
     */
    /**
     * Bottom-nav set for the active brand. `X-Brand-Hide-Routing` removes the Routing tab on
     * its own (three tabs); `X-Brand-Show-Operator-Tab` adds Operator, and with both set
     * Operator takes Routing's slot. The original brand commit (3e8fb36a) honoured Hide-Routing
     * alone; the same-day palette refactor accidentally gated it behind the Operator tab.
     */
    fun reconcileTabs(brandHolder: BrandHolder) {
        val brand = brandHolder.manifest
        val desired = mainTabsFor(
            brandActive = brandHolder.isActive,
            showOperatorTab = brand.showOperatorTab == true,
            hideRouting = brand.hideRouting == true,
            tvHidesRouting = tvHidesRouting(),
        )
        if (desired == activeTabs) return
        // Read the shown tab against the old set: the same pager position names another tab
        // once the set changes (Settings would become Routing when Operator arrives).
        val shown = if (pagerInitialised) mainTabForPagerPosition(binding.mainPager.currentItem) else null
        activeTabs = desired
        if (pagerInitialised) rebuildMainPagerForTabs(shown)
        onTabsChanged()
    }

    /**
     * Nested home content (announcement + profiles) grows vertically; on narrow or very wide
     * aspect ratios, small diagonal motion while scrolling can be absorbed as a horizontal
     * page swipe. Raising RecyclerView touch slop reduces accidental tab changes.
     */
    private var touchSlopTweakApplied: Boolean = false
    private fun tweakViewPagerHorizontalSwipeTolerance(viewPager: ViewPager2) {
        if (touchSlopTweakApplied) return
        runCatching {
            val recyclerView = viewPager.getChildAt(0) as? RecyclerView ?: return
            val field = RecyclerView::class.java.getDeclaredField("mTouchSlop")
            field.isAccessible = true
            val slop = field.getInt(recyclerView)
            field.setInt(recyclerView, slop * 3)
            touchSlopTweakApplied = true
        }.onFailure {
            Log.w("ViewPager2 touch slop tweak skipped: ${it.message}")
        }
    }

    private fun mainTabForPagerPosition(position: Int): MainTab? {
        val size = activeTabs.size
        val logical = when (position) {
            0 -> size - 1
            size + 1 -> 0
            else -> position - 1
        }
        return activeTabs.getOrNull(logical)
    }

    /** The logical tab currently shown — captured by MainActivity before a soft recreate (D5). */
    val currentTab: MainTab?
        get() = mainTabForPagerPosition(binding.mainPager.currentItem)

    /**
     * Non-animated tab restore after a soft recreate. Reuses the pager-position math of
     * [selectMainTab] but skips the smooth scroll + scroll-to-top behavior — this runs on a
     * freshly inflated design before it is even attached. No-op if [tab] isn't in the current
     * tab set (e.g. the Operator tab vanished with the brand that owned it).
     */
    fun selectTab(tab: MainTab) {
        val logicalIndex = activeTabs.indexOf(tab)
        if (logicalIndex < 0) return
        binding.mainPager.setCurrentItem(logicalIndex + 1, false)
        renderMainTab(tab)
    }

    fun selectMainTab(tab: MainTab) {
        val logicalIndex = activeTabs.indexOf(tab)
        if (logicalIndex < 0) return
        val targetItem = logicalIndex + 1
        if (binding.mainPager.currentItem == targetItem) {
            val page = pageForMainTab(tab)
            if (page is androidx.core.widget.NestedScrollView) {
                page.smoothScrollTo(0, 0)
            } else if (tab == MainTab.Profiles) {
                binding.profilesTabList.smoothScrollToPosition(0)
            }
            return
        }
        binding.mainPager.setCurrentItem(targetItem, true)
    }

    private fun renderMainTab(tab: MainTab) {
        if (tab == MainTab.Settings) {
            binding.mainSettingsLanguageValue.setText(when (uiStore.appLanguage) {
                AppLanguage.System -> R.string.app_language_system
                AppLanguage.English -> R.string.app_language_en
                AppLanguage.Russian -> R.string.app_language_ru
                AppLanguage.Chinese -> R.string.app_language_zh
            })
        }
        if (tab == MainTab.Routing) sendRequest(Request.RefreshRouting)
        val animate = renderedTab != null && renderedTab != tab
        binding.mainBottomNavItems.selectItem(navForMainTab(tab), animate)
        MainTab.values().forEach {
            navForMainTab(it).isSelected = it == tab
            // The ViewPager keeps off-screen tab pages attached + VISIBLE (offscreenPageLimit =
            // all), so without this their content stays focusable and the D-pad escapes into the
            // hidden tab ("ghost" focus on TV). Only the current tab's page accepts focus.
            val page = pageForMainTab(it)
            (page as? ViewGroup)?.descendantFocusability =
                if (it == tab) ViewGroup.FOCUS_AFTER_DESCENDANTS
                else ViewGroup.FOCUS_BLOCK_DESCENDANTS
            // The page itself too: a scroll-view page is focusable, so RIGHT from the edge of the
            // current tab landed on the neighbouring (off-screen) page and focus vanished.
            if (tv && page is androidx.core.widget.NestedScrollView) page.isFocusable = it == tab
        }
        if (tv) {
            updateRailFocusability(tab)
            binding.root.post { keepFocusOnCurrentTab(tab) }
        }
        renderedTab = tab
        onTabRendered()
        sendRequest(Request.MainTabChanged)
    }

    private fun pageForMainTab(tab: MainTab) = when (tab) {
        MainTab.Home -> binding.mainHomePage
        MainTab.Profiles -> binding.mainProfilesPage
        MainTab.Routing -> binding.mainRoutingPage
        MainTab.Operator -> binding.mainOperatorPage
        MainTab.Settings -> binding.mainSettingsPage
    }

    private fun navForMainTab(tab: MainTab): View = when (tab) {
        MainTab.Home -> binding.mainNavHome
        MainTab.Profiles -> binding.mainNavProfiles
        MainTab.Routing -> binding.mainNavRouting
        MainTab.Operator -> binding.mainNavOperator
        MainTab.Settings -> binding.mainNavSettings
    }

    /**
     * Trap D-pad focus inside the bottom nav: each visible item points LEFT/RIGHT only to its
     * neighbours (the ends point to themselves), so pressing past the first/last item stays put
     * instead of escaping into the current tab's content ("focus into the void" on TV). DOWN also
     * stays — there's nothing below the nav. UP is left to the default search so it reaches content.
     */
    private fun wireBottomNavFocus() {
        val visible = activeTabs.map { navForMainTab(it) }
        if (tv) {
            wireRailFocus(visible)
            return
        }
        visible.forEachIndexed { i, view ->
            view.nextFocusLeftId = visible[(i - 1).coerceAtLeast(0)].id
            view.nextFocusRightId = visible[(i + 1).coerceAtMost(visible.lastIndex)].id
            view.nextFocusDownId = view.id
        }
    }

    /**
     * TV BACK on a tab other than Home goes to Home first (the rail is the only way between tabs
     * there, so BACK leaving the app from Settings was a surprise); BACK on Home exits as before.
     * Returns false when BACK should take its default path.
     */
    fun handleTvBack(): Boolean {
        if (!tv) return false
        val tab = currentTab ?: return false
        if (tab == MainTab.Home || MainTab.Home !in activeTabs) return false
        selectMainTab(MainTab.Home)
        focusNavItem(MainTab.Home)
        return true
    }

    /** Focus [tab]'s nav item, even while the TV rail is only offering the current tab's item. */
    private fun focusNavItem(tab: MainTab): Boolean {
        val nav = navForMainTab(tab)
        nav.isFocusable = true
        return nav.requestFocus()
    }

    /**
     * TV: the bottom nav becomes a compact vertical rail on the left, vertically centred, and the
     * tab pages move right of it. A 1080p TV is only ~540dp tall, so a bar along the bottom cost
     * the pages a fifth of the screen. The items, their IDs and click wiring stay the same; only
     * their arrangement and D-pad links change ([wireRailFocus]).
     */
    private fun applyTvNavigationRail() {
        val res = binding.root.resources
        val density = res.displayMetrics.density
        val railWidth = res.getDimensionPixelSize(R.dimen.tv_nav_rail_width)
        val sideMargin = res.getDimensionPixelSize(R.dimen.main_bottom_nav_horizontal_margin)
        val card = binding.mainBottomNavCard
        card.layoutParams = CoordinatorLayout.LayoutParams(railWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            marginStart = sideMargin
            // The bottomNavInset adapter keeps its bottom margin; the same on top keeps it centred.
            topMargin = res.getDimensionPixelSize(R.dimen.main_bottom_nav_bottom_margin)
            bottomMargin = topMargin
        }
        val rail = binding.mainBottomNavItems
        rail.vertical = true
        rail.railItemHeight = res.getDimensionPixelSize(R.dimen.tv_nav_rail_item_height)
        rail.railItemGap = (4 * density).toInt()
        rail.setPadding((4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt())
        rail.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )

        (binding.mainPager.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.marginStart = sideMargin + railWidth + res.getDimensionPixelSize(R.dimen.tv_nav_rail_gap)
            binding.mainPager.layoutParams = it
        }

        binding.root.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            val tab = currentTab ?: return@addOnGlobalFocusChangeListener
            if (newFocus != null && !isRailItem(newFocus) && isInside(newFocus, pageForMainTab(tab))) {
                tvContentFocus[tab] = WeakReference(newFocus)
            }
            scheduleRailTabSwitch(newFocus)
            updateRailFocusability(tab)
        }
    }

    /**
     * Google TV style: resting on a rail item opens its tab, so the content beside the rail always
     * belongs to the highlighted item. The switch waits for focus to settle, so walking the rail
     * quickly does not page through every tab on the way.
     */
    private fun scheduleRailTabSwitch(focused: View?) {
        tvRailSwitch?.let(binding.root::removeCallbacks)
        tvRailSwitch = null
        val tab = focused?.let(::railTabOf) ?: return
        if (tab == currentTab) return
        val switch = Runnable {
            tvRailSwitch = null
            if (binding.root.findFocus() === focused && currentTab != tab) selectMainTab(tab)
        }
        tvRailSwitch = switch
        binding.root.postDelayed(switch, RAIL_SETTLE_MS)
    }

    /** RIGHT before the settle delay ran: open the focused item's tab at once, then enter it. */
    private fun openRailTabNow(item: View) {
        tvRailSwitch?.let(binding.root::removeCallbacks)
        tvRailSwitch = null
        val tab = railTabOf(item) ?: return
        if (tab != currentTab) selectTab(tab)
    }

    /**
     * Rail D-pad: UP/DOWN walk the visible items (the ends stay put) and open the item's tab once
     * focus rests on it, LEFT stays put, RIGHT goes into that tab's content, to the control it last
     * had.
     */
    private fun wireRailFocus(visible: List<View>) {
        visible.forEachIndexed { i, view ->
            view.nextFocusUpId = visible[(i - 1).coerceAtLeast(0)].id
            view.nextFocusDownId = visible[(i + 1).coerceAtMost(visible.lastIndex)].id
            view.nextFocusLeftId = view.id
            view.nextFocusRightId = view.id
            view.setOnKeyListener { _, keyCode, event ->
                if (keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return@setOnKeyListener false
                if (event.action == KeyEvent.ACTION_DOWN) {
                    openRailTabNow(view)
                    focusCurrentTabContent()
                }
                true
            }
        }
    }

    /**
     * While the D-pad is in the content, only the current tab's rail item takes focus, so LEFT
     * from the content's left edge always lands on the tab the user is on. Inside the rail every
     * visible item is focusable again so UP/DOWN can move between them.
     */
    private fun updateRailFocusability(current: MainTab) {
        val focused = binding.root.findFocus()
        val inRail = focused != null && isRailItem(focused)
        activeTabs.forEach { tab ->
            val nav = navForMainTab(tab)
            if (nav !== focused) nav.isFocusable = inRail || tab == current
        }
    }

    /** Where RIGHT from the rail goes: the control the tab last had, or Home's power orb. */
    fun focusCurrentTabContent(): Boolean {
        val tab = currentTab ?: return false
        val page = pageForMainTab(tab)
        tvContentFocus[tab]?.get()
            ?.takeIf { it.isShown && it.isFocusable && isInside(it, page) }
            ?.let { if (it.requestFocus()) return true }
        if (tab == MainTab.Home) {
            val target = if (binding.mainHomeActiveContent.visibility == View.VISIBLE) {
                binding.mainPowerCard
            } else {
                binding.mainEmptyAddButton
            }
            if (target.requestFocus()) return true
        }
        val group = page as? ViewGroup ?: return false
        return FocusFinder.getInstance().findNextFocus(group, null, View.FOCUS_DOWN)?.requestFocus() == true
    }

    /**
     * A tab opened from page content (Home's "Active profile" row) left focus in the page that is
     * now hidden and blocked, or on the pager itself: no ring, and the D-pad went nowhere. Unless
     * focus is already on the rail or inside the new page, hand it to the new page's content.
     */
    private fun keepFocusOnCurrentTab(tab: MainTab) {
        val root = binding.root
        if (root.isInTouchMode || !root.hasWindowFocus()) return
        val page = pageForMainTab(tab)
        val focused = root.findFocus()
        if (focused != null && focused !== page && focused !is RecyclerView) {
            if (isRailItem(focused) || isInside(focused, page)) return
        }
        if (!focusCurrentTabContent()) navForMainTab(tab).requestFocus()
    }

    private fun isRailItem(view: View): Boolean = railTabOf(view) != null

    private fun railTabOf(view: View): MainTab? =
        MainTab.values().firstOrNull { navForMainTab(it) === view }

    private fun isInside(view: View, ancestor: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (v === ancestor) return true
            v = v.parent as? View
        }
        return false
    }

    private companion object {
        /** How long focus has to rest on a rail item before its tab opens (fast UP/DOWN skips). */
        private const val RAIL_SETTLE_MS = 300L
        private val DEFAULT_TABS = listOf(MainTab.Home, MainTab.Profiles, MainTab.Routing, MainTab.Settings)
    }
}

private class StaticPageAdapter(
    private val pages: List<View>,
    /**
     * TV: where LEFT goes once nothing in the page is left of the focused control (the nav rail).
     * The pager's RecyclerView otherwise answers it by trying to scroll to the neighbouring page,
     * whose content is blocked, and keeps focus where it is.
     */
    private val leftExit: (() -> View?)? = null,
) : RecyclerView.Adapter<StaticPageAdapter.Holder>() {
    class Holder(val container: FrameLayout) : RecyclerView.ViewHolder(container)

    private fun isBoundaryPage(position: Int): Boolean {
        val count = pages.size
        return position == 0 || position == count + 1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val exit = leftExit
        val container = object : FrameLayout(parent.context) {
            override fun focusSearch(focused: View?, direction: Int): View? {
                if (exit == null || direction != View.FOCUS_LEFT) return super.focusSearch(focused, direction)
                return FocusFinder.getInstance().findNextFocus(this, focused, direction)
                    ?: exit()
                    ?: super.focusSearch(focused, direction)
            }
        }.apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        return Holder(container)
    }

    private fun logicalPosition(position: Int): Int {
        val count = pages.size
        return when (position) {
            0 -> count - 1
            count + 1 -> 0
            else -> position - 1
        }
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.container.removeAllViews()
        val page = pages[logicalPosition(position)]
        if (isBoundaryPage(position)) {
            // Circular wrap placeholders: avoid drawToBitmap (memory churn / jank on low-RAM devices).
            val bg = FrameLayout(holder.container.context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(context.resolveThemedColor(MaterialR.attr.colorSurface))
            }
            holder.container.addView(bg)
            return
        }

        (page.parent as? ViewGroup)?.removeView(page)
        page.visibility = View.VISIBLE
        holder.container.addView(
            page,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    override fun onViewRecycled(holder: Holder) {
        holder.container.removeAllViews()
    }

    override fun getItemCount(): Int = pages.size + 2
}

/**
 * Bottom-nav (TV rail) tab set: `X-Brand-Hide-Routing` removes Routing on its own,
 * `X-Brand-Show-Operator-Tab` adds Operator, and with both set Operator takes Routing's slot. On a
 * TV without "Advanced tools on TV" Routing is left out as well. Only an active brand counts.
 */
internal fun mainTabsFor(
    brandActive: Boolean,
    showOperatorTab: Boolean,
    hideRouting: Boolean,
    tvHidesRouting: Boolean,
): List<MainTab> {
    val tabs = when {
        !brandActive -> listOf(MainTab.Home, MainTab.Profiles, MainTab.Routing, MainTab.Settings)
        showOperatorTab && hideRouting -> listOf(MainTab.Home, MainTab.Profiles, MainTab.Operator, MainTab.Settings)
        showOperatorTab -> listOf(MainTab.Home, MainTab.Profiles, MainTab.Routing, MainTab.Operator, MainTab.Settings)
        hideRouting -> listOf(MainTab.Home, MainTab.Profiles, MainTab.Settings)
        else -> listOf(MainTab.Home, MainTab.Profiles, MainTab.Routing, MainTab.Settings)
    }
    return if (tvHidesRouting) tabs - MainTab.Routing else tabs
}
