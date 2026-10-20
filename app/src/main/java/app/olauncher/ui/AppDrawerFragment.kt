package app.olauncher.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.updatePadding
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.Space
import androidx.core.view.doOnNextLayout
import app.olauncher.databinding.AdapterAppDrawerBinding
import app.olauncher.databinding.ViewRecentLabelBinding
import app.olauncher.helper.AppIconLoader
import app.olauncher.helper.WallpaperHelper
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.text.Spannable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.AnimationUtils
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.core.os.bundleOf
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.Recycler
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentAppDrawerBinding
import app.olauncher.helper.deletePinnedShortcut
import app.olauncher.helper.hideKeyboard
import app.olauncher.helper.isEinkDisplay
import app.olauncher.helper.FokusAnim
import app.olauncher.helper.navigateAnimated
import app.olauncher.helper.isSystemAnimationsDisabled
import app.olauncher.helper.isSystemApp
import app.olauncher.helper.openAppInfo
import app.olauncher.helper.openSearch
import app.olauncher.helper.openUrl
import app.olauncher.helper.showKeyboard
import app.olauncher.helper.showToast
import java.text.Normalizer
import app.olauncher.helper.uninstall

class AppDrawerFragment : BaseFragment() {

    private lateinit var prefs: Prefs
    private lateinit var adapter: AppDrawerAdapter
    private lateinit var gridLayoutManager: GridLayoutManager
    private var letterPositions: Map<Char, Int> = emptyMap()
    private var maxRecyclerHeight = 0
    private var needsListRefresh = false
    private var shownRecentKeys: List<String>? = null
    private var hintPopup: PopupWindow? = null
    // Focus picker: apps that were already picked when it opened (listed first, kept out of the A-Z bar)
    private var focusPinned: Set<String> = emptySet()
    private var letterRuns: Map<Char, IntRange> = emptyMap()
    private var flashToken = 0
    private val flashedViews = mutableListOf<View>()
    private val flashAnimators = mutableListOf<ValueAnimator>()
    private var searchTextView: TextView? = null
    private var cachedIsCjkKeyboard: Boolean? = null

    private var flag = Constants.FLAG_LAUNCH_APP
    private var canRename = false
    private var currentAppList: List<AppModel>? = null
    private var currentPrivateSpaceApps: List<AppModel>? = null
    private var currentPrivateSpaceLocked: Boolean = true
    private var currentPrivateSpaceAvailable: Boolean = false

    private val viewModel: MainViewModel by activityViewModels()
    private var _binding: FragmentAppDrawerBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAppDrawerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        AppIconLoader.shape = prefs.iconShape
        arguments?.let {
            flag = it.getInt(Constants.Key.FLAG, Constants.FLAG_LAUNCH_APP)
            canRename = it.getBoolean(Constants.Key.RENAME, false)
        }

        initViews()
        initSearch()
        initAdapter()
        initObservers()
        initClickListeners()
    }

    private fun initViews() {
        if (flag == Constants.FLAG_HIDDEN_APPS)
            binding.search.queryHint = getString(R.string.hidden_apps)
        else if (flag == Constants.FLAG_FOCUS_APPS)
            binding.search.queryHint = getString(R.string.focus_select_hint)
        else if (flag in Constants.FLAG_SET_HOME_APP_1..Constants.FLAG_SET_CALENDAR_APP)
            binding.search.queryHint = "Please select an app"
        updateTopActions()
        updateClock()
        applyWallpaper()
        tintSearchIcon()
        leaveRoomForStatusBar()
        try {
            searchTextView = binding.search.findViewById(R.id.search_src_text)
            searchTextView?.gravity = prefs.appLabelAlignment
            searchTextView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun initSearch() {
        binding.search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                if (query?.startsWith("!") == true)
                    requireContext().openUrl(Constants.URL_DUCK_SEARCH + query.replace(" ", "%20"))
                else if (adapter.itemCount == 0)
                    requireContext().openSearch(query?.trim())
                else
                    adapter.launchFirstInList()
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                try {
                    adapter.filter.filter(newText)
                    binding.appRename.visibility =
                        if (canRename && newText.isNotBlank()) View.VISIBLE else View.GONE
                    return true
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                return false
            }
        })
    }

    private fun isSearchComposing(): Boolean {
        val text = searchTextView?.text
        if (text !is Spannable) return false
        val start = BaseInputConnection.getComposingSpanStart(text)
        val end = BaseInputConnection.getComposingSpanEnd(text)
        if (start !in 0 until end) return false
        return isCjkKeyboard()
    }

    private fun isCjkKeyboard(): Boolean {
        cachedIsCjkKeyboard?.let { return it }
        val result = try {
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val subtype = imm.currentInputMethodSubtype
            val language = when {
                subtype == null -> ""
                subtype.languageTag.isNotEmpty() -> subtype.languageTag // e.g. "zh-CN", "ja-JP", "en-US"
                else -> subtype.locale // deprecated fallback, e.g. "zh_CN"
            }
            language.startsWith("zh") || language.startsWith("ja") || language.startsWith("ko")
        } catch (e: Exception) {
            false
        }
        cachedIsCjkKeyboard = result
        return result
    }

    private fun initAdapter() {
        // New view: cell size must be measured again (grid size or text size may have changed)
        maxRecyclerHeight = 0
        adapter = AppDrawerAdapter(
            flag,
            prefs.appLabelAlignment,
            appClickListener = { appModel ->
                if (flag == Constants.FLAG_FOCUS_APPS) {
                    toggleFocusApp(appModel)
                } else {
                    if (flag == Constants.FLAG_LAUNCH_APP && appModel is AppModel.App)
                        prefs.addRecentApp(appModel.appPackage + "|" + appModel.user.toString())
                    viewModel.selectedApp(appModel, flag)
                    if (flag == Constants.FLAG_LAUNCH_APP || flag == Constants.FLAG_HIDDEN_APPS)
                        findNavController().popBackStack(R.id.mainFragment, false)
                    else
                        findNavController().popBackStack()
                }
            },
            appInfoListener = {
                openAppInfo(
                    requireContext(),
                    it.user,
                    it.appPackage
                )
                findNavController().popBackStack(R.id.mainFragment, false)
            },
            appDeleteListener = { appModel ->
                when (appModel) {
                    is AppModel.PrivateSpaceHeader -> {}
                    is AppModel.PinnedShortcut ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                            requireContext().deletePinnedShortcut(
                                packageName = appModel.appPackage,
                                shortcutIdToDelete = appModel.shortcutId,
                                user = appModel.user,
                            )
                        }

                    is AppModel.App -> {
                        if (appModel.user != Process.myUserHandle()) {
                            openAppInfo(requireContext(), appModel.user, appModel.appPackage)
                        } else if (requireContext().isSystemApp(appModel.appPackage, appModel.user)) {
                            requireContext().showToast(getString(R.string.system_app_cannot_delete))
                            openAppInfo(requireContext(), appModel.user, appModel.appPackage)
                        } else {
                            requireContext().uninstall(appModel.appPackage)
                        }
                    }
                }
                viewModel.getAppList()
            },
            appHideListener = { appModel, position ->
                if (appModel is AppModel.PinnedShortcut) {
                    requireContext().showToast("Hiding pinned shortcuts is not supported")
                    return@AppDrawerAdapter
                }
                adapter.appFilteredList.removeAt(position)
                adapter.notifyItemRemoved(position)
                adapter.appsList.remove(appModel)

                val newSet = mutableSetOf<String>()
                newSet.addAll(prefs.hiddenApps)
                if (flag == Constants.FLAG_HIDDEN_APPS)
                    newSet.remove(appModel.appPackage + "|" + appModel.user.toString())
                else
                    newSet.add(appModel.appPackage + "|" + appModel.user.toString())

                prefs.hiddenApps = newSet
                if (newSet.isEmpty())
                    findNavController().popBackStack()
                if (prefs.firstHide) {
                    binding.search.hideKeyboard()
                    prefs.firstHide = false
                    viewModel.showDialog.postValue(Constants.Dialog.HIDDEN)
                    openSettings()
                }
                viewModel.getAppList()
                viewModel.getHiddenApps()
            },
            appRenameListener = { appModel, renameLabel ->
                val identifier = when (appModel) {
                    is AppModel.PinnedShortcut -> appModel.identity
                    is AppModel.App -> appModel.appPackage
                    else -> return@AppDrawerAdapter
                }
                prefs.setAppRenameLabel(identifier, renameLabel)
                viewModel.getAppList()
            },
            privateSpaceToggleListener = {
                viewModel.togglePrivateSpaceLock()
            },
            privateSpaceSettingsListener = {
                viewModel.openPrivateSpaceSettings()
                findNavController().popBackStack(R.id.mainFragment, false)
            }
        )

        val spanCount = prefs.drawerColumns
        gridLayoutManager = object : GridLayoutManager(requireContext(), spanCount) {
            override fun scrollVerticallyBy(
                dy: Int,
                recycler: Recycler,
                state: RecyclerView.State,
            ): Int {
                val scrollRange = super.scrollVerticallyBy(dy, recycler, state)
                val overScroll = dy - scrollRange
                if (overScroll < -10 && binding.recyclerView.scrollState == RecyclerView.SCROLL_STATE_DRAGGING)
                    checkMessageAndExit()
                return scrollRange
            }
        }.apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    val item = adapter.currentList.getOrNull(position)
                    // Private space header and the bottom spacer take a full row
                    return if (item is AppModel.PrivateSpaceHeader || (item != null && item.appPackage.isEmpty()))
                        spanCount
                    else
                        1
                }
            }
        }

        adapter.touchAnim = prefs.touchAnim
        binding.recyclerView.layoutManager = gridLayoutManager
        adapter.focusKeys = if (flag == Constants.FLAG_FOCUS_APPS) prefs.focusApps else emptySet()
        focusPinned = if (flag == Constants.FLAG_FOCUS_APPS) prefs.focusApps else emptySet()
        adapter.onListChanged = { updateAlphabet() }
        binding.alphabetIndex.onLetterSelected = { letter, centerY ->
            showLetterBubble(letter, centerY)
            jumpToLetter(letter)
        }
        binding.alphabetIndex.onTouchEnd = { hideLetterBubble() }
        // Size grid cells so that `drawerRows` rows fill the screen (largest height seen, ignores the keyboard)
        binding.recyclerView.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val height = bottom - top
            if (height > maxRecyclerHeight) {
                maxRecyclerHeight = height
                binding.recyclerView.post { updateCellHeight() }
            }
        }
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addOnScrollListener(getRecyclerViewOnScrollListener())
        binding.recyclerView.itemAnimator = null
        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.setItemViewCacheSize(30)
        if (requireContext().isEinkDisplay())
            binding.recyclerView.overScrollMode = View.OVER_SCROLL_NEVER
        else if (requireContext().isSystemAnimationsDisabled().not())
            binding.recyclerView.layoutAnimation =
                AnimationUtils.loadLayoutAnimation(requireContext(), R.anim.layout_anim_from_bottom)
    }

    private fun initObservers() {
        viewModel.firstOpen.observe(viewLifecycleOwner) {
        }
        if (flag == Constants.FLAG_HIDDEN_APPS) {
            viewModel.hiddenApps.observe(viewLifecycleOwner) {
                it?.let {
                    adapter.setAppList(it.toMutableList())
                }
            }
        } else {
            viewModel.appList.observe(viewLifecycleOwner) {
                currentAppList = it
                updateCombinedAppList()
                it?.let { apps -> AppIconLoader.preload(requireContext(), apps) }
                updateRecents()
            }
            if (flag == Constants.FLAG_LAUNCH_APP) {
                viewModel.privateSpaceAvailable.observe(viewLifecycleOwner) {
                    currentPrivateSpaceAvailable = it
                    updateCombinedAppList()
                }
                viewModel.privateSpaceLocked.observe(viewLifecycleOwner) {
                    currentPrivateSpaceLocked = it
                    updateCombinedAppList()
                }
                viewModel.privateSpaceApps.observe(viewLifecycleOwner) {
                    currentPrivateSpaceApps = it
                    updateCombinedAppList()
                }
            }
        }
    }

    private fun updateCombinedAppList() {
        val apps = currentAppList ?: return
        val combined = apps.toMutableList()

        if (flag == Constants.FLAG_LAUNCH_APP && currentPrivateSpaceAvailable && !prefs.focusModeEnabled) {
            combined.add(AppModel.PrivateSpaceHeader(isLocked = currentPrivateSpaceLocked))
            if (!currentPrivateSpaceLocked) {
                currentPrivateSpaceApps?.let { combined.addAll(it) }
            }
        }

        if (flag == Constants.FLAG_FOCUS_APPS && focusPinned.isNotEmpty()) {
            val (picked, rest) = combined.partition {
                it is AppModel.App && focusPinned.contains(it.appPackage + "|" + it.user.toString())
            }
            combined.clear()
            combined.addAll(picked)
            combined.addAll(rest)
        }

        adapter.setAppList(combined)
        adapter.filter.filter(binding.search.query)
        if (focusInMode >= 0) binding.recyclerView.post { runFocusIn() }
    }

    private fun initClickListeners() {
        binding.focusAction.setOnClickListener { onFocusActionClick() }
        binding.focusAction.setOnLongClickListener {
            if (flag != Constants.FLAG_LAUNCH_APP) return@setOnLongClickListener false
            if (prefs.focusModeEnabled)
                openFocusPicker()
            else
                requireContext().showToast(R.string.fokus_enter_first)
            true
        }
        binding.exitAction.setOnClickListener {
            confirm(R.string.fokus_confirm_exit) { setFocusMode(false) }
        }
        binding.settingsAction.setOnClickListener { openSettings() }
        binding.appRename.setOnClickListener {
            val name = binding.search.query.toString().trim()
            if (name.isEmpty()) {
                requireContext().showToast(getString(R.string.type_a_new_app_name_first))
                binding.search.showKeyboard()
                return@setOnClickListener
            }

            when (flag) {
                Constants.FLAG_SET_HOME_APP_1 -> prefs.appName1 = name
                Constants.FLAG_SET_HOME_APP_2 -> prefs.appName2 = name
                Constants.FLAG_SET_HOME_APP_3 -> prefs.appName3 = name
                Constants.FLAG_SET_HOME_APP_4 -> prefs.appName4 = name
                Constants.FLAG_SET_HOME_APP_5 -> prefs.appName5 = name
                Constants.FLAG_SET_HOME_APP_6 -> prefs.appName6 = name
                Constants.FLAG_SET_HOME_APP_7 -> prefs.appName7 = name
                Constants.FLAG_SET_HOME_APP_8 -> prefs.appName8 = name
            }
            findNavController().popBackStack()
        }
    }

    private fun getRecyclerViewOnScrollListener(): RecyclerView.OnScrollListener {
        return object : RecyclerView.OnScrollListener() {

            var onTop = false

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                when (newState) {

                    RecyclerView.SCROLL_STATE_DRAGGING -> {
                        onTop = !recyclerView.canScrollVertically(-1)
                        if (onTop)
                            binding.search.hideKeyboard()
                    }

                    RecyclerView.SCROLL_STATE_IDLE -> {
                        if (!recyclerView.canScrollVertically(1))
                            binding.search.hideKeyboard()
                        else if (!recyclerView.canScrollVertically(-1))
                            if (!onTop && isRemoving.not())
                                binding.search.showKeyboard(prefs.autoShowKeyboard)
                    }
                }
            }
        }
    }

    private fun checkMessageAndExit() {
        // The drawer is the home screen: nothing to exit to
        if (findNavController().currentDestination?.id == R.id.mainFragment) return
        findNavController().popBackStack()
    }

    private fun isRoot() = findNavController().currentDestination?.id == R.id.mainFragment

    private fun updateTopActions() {
        when (flag) {
            Constants.FLAG_FOCUS_APPS -> {
                binding.focusAction.text = getString(R.string.focus_done)
                binding.focusAction.visibility = View.VISIBLE
                binding.exitAction.visibility = View.GONE
                binding.settingsAction.visibility = View.GONE
            }

            Constants.FLAG_LAUNCH_APP -> {
                val inFocus = prefs.focusModeEnabled
                // Outside: "FOCUS". Inside the focus space: "FOCUS SPACE" + "EXIT"
                binding.focusAction.text =
                    getString(if (inFocus) R.string.fokus_focus_space else R.string.focus_on)
                binding.focusAction.visibility = View.VISIBLE
                binding.exitAction.visibility = if (inFocus) View.VISIBLE else View.GONE
                binding.settingsAction.visibility = View.VISIBLE
            }

            else -> {
                binding.focusAction.visibility = View.GONE
                binding.exitAction.visibility = View.GONE
                binding.settingsAction.visibility = View.GONE
            }
        }
    }

    private fun onFocusActionClick() {
        when {
            flag == Constants.FLAG_FOCUS_APPS -> findNavController().popBackStack()
            prefs.focusModeEnabled -> showEditHint()
            else -> requestEnterFocus()
        }
    }

    /** First time: explain how focus mode works. Always: ask Yes/No before entering. */
    private fun requestEnterFocus() {
        val askToEnter = { confirm(R.string.fokus_confirm_enter) { enterFocusMode() } }
        if (prefs.focusIntroShown) {
            askToEnter()
        } else {
            AlertDialog.Builder(requireContext())
                .setMessage(R.string.fokus_focus_intro)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    prefs.focusIntroShown = true
                    askToEnter()
                }
                .show()
        }
    }

    private fun confirm(messageRes: Int, onYes: () -> Unit) {
        AlertDialog.Builder(requireContext())
            .setMessage(messageRes)
            .setPositiveButton(R.string.fokus_yes) { _, _ -> onYes() }
            .setNegativeButton(R.string.fokus_no, null)
            .show()
    }

    private fun enterFocusMode() {
        if (prefs.focusApps.isEmpty()) {
            // Nothing picked yet: go straight to the picker. It loads every app by itself,
            // so no focus-only list is loaded at the same time.
            prefs.focusModeEnabled = true
            updateTopActions()
            binding.recyclerView.scrollToPosition(0)
            requireContext().showToast(R.string.focus_pick_first)
            openFocusPicker()
            return
        }
        animateFocusSwitch(entering = true) {
            prefs.focusModeEnabled = true
            updateTopActions()
            binding.recyclerView.scrollToPosition(0)
            viewModel.getAppList()
            binding.focusAction.post { showEditHint() }
        }
    }

    private fun setFocusMode(enabled: Boolean) {
        animateFocusSwitch(entering = enabled) {
            prefs.focusModeEnabled = enabled
            updateTopActions()
            viewModel.getAppList()
            binding.recyclerView.scrollToPosition(0)
        }
    }

    // Focus space in/out animation: the app grid leaves, the list is swapped, the new grid arrives
    private var focusInMode = -1
    private var focusInEntering = true

    private fun focusAnimViews(): List<View> =
        listOf(binding.recyclerView, binding.alphabetIndex, binding.recentRow)

    private fun animateFocusSwitch(entering: Boolean, change: () -> Unit) {
        val mode = prefs.focusAnim
        if (mode == FokusAnim.FOCUS_OFF || requireContext().isSystemAnimationsDisabled()) {
            change()
            return
        }
        val height = binding.recyclerView.height.toFloat()
        focusAnimViews().forEachIndexed { index, view ->
            val out = view.animate().alpha(0f).setDuration(140)
            when (mode) {
                FokusAnim.FOCUS_SLIDE -> out.translationY(if (entering) -height * 0.05f else height * 0.05f)
                FokusAnim.FOCUS_ZOOM -> out.scaleX(if (entering) 1.08f else 0.92f)
                    .scaleY(if (entering) 1.08f else 0.92f)
            }
            if (index == 0) out.withEndAction {
                if (_binding == null) return@withEndAction
                focusInMode = mode
                focusInEntering = entering
                change()
                // The new list arrives from the view model; if it does not, play the entrance anyway
                binding.recyclerView.postDelayed({ runFocusIn() }, 350)
            }
            out.start()
        }
    }

    private fun runFocusIn() {
        if (_binding == null || focusInMode < 0) return
        val mode = focusInMode
        val entering = focusInEntering
        focusInMode = -1
        val height = binding.recyclerView.height.toFloat()
        focusAnimViews().forEach { view ->
            view.alpha = 0f
            when (mode) {
                FokusAnim.FOCUS_SLIDE -> view.translationY = if (entering) height * 0.05f else -height * 0.05f
                FokusAnim.FOCUS_ZOOM -> {
                    view.scaleX = if (entering) 0.92f else 1.08f
                    view.scaleY = view.scaleX
                }
            }
            view.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    /** Edit the focus apps. Only reachable from inside the focus space; the picker shows every app. */
    private fun openFocusPicker() {
        hintPopup?.dismiss()
        needsListRefresh = true
        viewModel.getAppList(ignoreFocus = true)
        findNavController().navigateAnimated(
            R.id.action_mainFragment_to_appListFragment,
            prefs.transitionAnim,
            bundleOf(
                Constants.Key.FLAG to Constants.FLAG_FOCUS_APPS,
                Constants.Key.RENAME to false
            )
        )
    }

    /** FokusHome-only wallpaper: plain color or own image. Default keeps the phone wallpaper showing through. */
    private fun applyWallpaper() {
        if (_binding == null) return
        val background = WallpaperHelper.background(requireContext(), prefs)
        if (background != null)
            binding.root.background = background
        else
            binding.root.setBackgroundColor(themeColor(R.attr.primaryShadeDarkColor, Color.TRANSPARENT))
    }

    /** Small bubble above "FOCUS SPACE": how to edit the focus apps. */
    private fun showEditHint() {
        if (_binding == null || flag != Constants.FLAG_LAUNCH_APP || !prefs.focusModeEnabled) return
        hintPopup?.dismiss()
        val anchor = binding.focusAction
        val density = resources.displayMetrics.density
        val bubble = TextView(requireContext()).apply {
            text = getString(R.string.fokus_hold_hint)
            setTextColor(themeColor(R.attr.primaryInverseColor, Color.BLACK))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setBackgroundResource(R.drawable.bg_hint_bubble)
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
        }
        bubble.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val popup = PopupWindow(
            bubble,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        popup.isOutsideTouchable = true
        popup.isFocusable = false
        popup.elevation = 8 * density
        popup.showAsDropDown(
            anchor,
            (anchor.width - bubble.measuredWidth) / 2,
            -(anchor.height + bubble.measuredHeight + (4 * density).toInt())
        )
        hintPopup = popup
        anchor.postDelayed({
            if (hintPopup === popup) popup.dismiss()
        }, 2500)
    }

    /** The home screen is drawn under the translucent status bar: push our content below it. */
    private fun leaveRoomForStatusBar() {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBarHeight = if (resId > 0) resources.getDimensionPixelSize(resId) else 0
        val extra = (8 * resources.displayMetrics.density).toInt()
        binding.root.updatePadding(top = binding.root.paddingTop + statusBarHeight + extra)
    }

    private fun toggleFocusApp(appModel: AppModel) {
        if (appModel !is AppModel.App || appModel.appPackage.isEmpty()) return
        val key = appModel.appPackage + "|" + appModel.user.toString()
        val focusApps = prefs.focusApps
        if (!focusApps.add(key)) focusApps.remove(key)
        prefs.focusApps = focusApps
        adapter.focusKeys = focusApps
        val index = adapter.appFilteredList.indexOf(appModel)
        if (index >= 0) adapter.notifyItemChanged(index)
    }

    private fun openSettings() {
        val navController = findNavController()
        if (isRoot())
            navController.navigateAnimated(R.id.action_mainFragment_to_settingsFragment, prefs.transitionAnim)
        else
            navController.navigateAnimated(
                R.id.action_appListFragment_to_settingsFragment2,
                prefs.transitionAnim,
                popUpTo = R.id.appListFragment,
                inclusive = true
            )
    }

    private fun updateCellHeight() {
        if (_binding == null) return
        val rows = prefs.drawerRows.coerceAtLeast(1)
        val columns = prefs.drawerColumns.coerceAtLeast(1)
        val recycler = binding.recyclerView
        val usable = maxRecyclerHeight - recycler.paddingTop - recycler.paddingBottom
        val cell = usable / rows
        if (cell <= 0) return

        // Icons and labels follow the grid: big grids get smaller icons, small grids get bigger ones
        val density = resources.displayMetrics.density
        val cellWidth = (recycler.width - recycler.paddingLeft - recycler.paddingRight) / columns
        val icon = if (cellWidth > 0)
            minOf(cellWidth * 0.7f, cell * 0.5f).coerceIn(26 * density, 56 * density).toInt()
        else 0
        val label = when {
            columns >= 9 -> 9f
            columns >= 7 -> 10f
            columns >= 5 -> 11f
            else -> 12f
        }

        if (cell != adapter.cellHeight || icon != adapter.iconSizePx || label != adapter.labelSizeSp) {
            adapter.cellHeight = cell
            adapter.iconSizePx = icon
            adapter.labelSizeSp = label
            adapter.notifyDataSetChanged()
        }
    }

    /** Rebuilds the A-Z strip from the apps currently shown (first position and run of each letter). */
    private fun updateAlphabet() {
        if (_binding == null) return
        val list = adapter.appFilteredList
        val positions = LinkedHashMap<Char, Int>()
        for ((index, app) in list.withIndex()) {
            if (app is AppModel.PrivateSpaceHeader) break
            if (app.appPackage.isEmpty()) continue
            if (app is AppModel.App && focusPinned.contains(app.appPackage + "|" + app.user.toString())) continue
            val letter = indexLetter(app.appLabel)
            if (!positions.containsKey(letter)) positions[letter] = index
        }
        val runs = HashMap<Char, IntRange>()
        for ((letter, start) in positions) {
            var end = start
            while (end + 1 < list.size) {
                val next = list[end + 1]
                if (next is AppModel.PrivateSpaceHeader || next.appPackage.isEmpty()) break
                if (indexLetter(next.appLabel) != letter) break
                end++
            }
            runs[letter] = start..end
        }
        letterPositions = positions
        letterRuns = runs
        binding.alphabetIndex.setLetters(positions.keys.toList())
    }

    private fun indexLetter(label: String): Char {
        val first = label.trim().firstOrNull() ?: return '#'
        val base = Normalizer.normalize(first.toString(), Normalizer.Form.NFD).firstOrNull() ?: first
        return if (base.isLetter()) base.uppercaseChar() else '#'
    }

    private fun themeColor(attr: Int, fallback: Int = Color.WHITE): Int {
        val value = TypedValue()
        return if (requireContext().theme.resolveAttribute(attr, value, true)) value.data else fallback
    }

    private fun tintSearchIcon() {
        try {
            val searchIcon = binding.search.findViewById<ImageView>(R.id.search_mag_icon)
            searchIcon?.setColorFilter(themeColor(R.attr.primaryColor))
            // Tapping the magnifier starts a search, same as tapping the text
            searchIcon?.setOnClickListener {
                searchTextView?.requestFocus()
                binding.search.showKeyboard(true)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Clock + day at the top. Follows "Show date & time" and "Clock format" in Settings. */
    private fun updateClock() {
        if (_binding == null) return
        val wasVisible = binding.clockLayout.visibility == View.VISIBLE
        val mode = prefs.dateTimeVisibility
        val show = flag == Constants.FLAG_LAUNCH_APP && mode != Constants.DateTime.OFF
        binding.clockLayout.visibility = if (show) View.VISIBLE else View.GONE
        binding.clockTime.visibility =
            if (mode == Constants.DateTime.DATE_ONLY) View.GONE else View.VISIBLE
        val (format12, format24) = when (prefs.clockFormat) {
            1 -> "HH:mm" to "HH:mm"
            2 -> "h:mm a" to "h:mm a"
            else -> "h:mm a" to "HH:mm"
        }
        binding.clockLayout.gravity = when (prefs.clockAlignment) {
            0 -> Gravity.START or Gravity.BOTTOM
            1 -> Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            else -> Gravity.END or Gravity.BOTTOM
        }
        binding.clockTime.format12Hour = format12
        binding.clockTime.format24Hour = format24
        binding.clockDate.format12Hour = "EEE, d MMM"
        binding.clockDate.format24Hour = "EEE, d MMM"
        // The list changes height when the clock appears/disappears: let the grid re-measure its cells
        if (wasVisible != show) maxRecyclerHeight = 0
    }

    /**
     * Row with the most recently launched apps. A narrow label column ("Recently open" + "Clear")
     * takes the first grid column, the apps fill the rest. The row is gone when there is nothing to show.
     */
    private fun updateRecents() {
        if (_binding == null) return
        val row = binding.recentRow
        val wasVisible = row.visibility == View.VISIBLE
        val columns = prefs.drawerColumns

        val recents = if (flag == Constants.FLAG_LAUNCH_APP && prefs.showRecentApps) {
            val byKey = HashMap<String, AppModel.App>()
            currentAppList.orEmpty().forEach {
                if (it is AppModel.App) byKey[it.appPackage + "|" + it.user.toString()] = it
            }
            prefs.recentApps.mapNotNull { byKey[it] }.take(minOf(4, columns - 1))
        } else {
            emptyList()
        }

        // Same recent apps as before: keep the row as it is (no rebuild on every resume)
        val recentKeys = recents.map { it.appPackage + "|" + it.user.toString() }
        if (recentKeys == shownRecentKeys) return
        shownRecentKeys = recentKeys

        row.removeAllViews()
        val show = recents.isNotEmpty()
        row.visibility = if (show) View.VISIBLE else View.GONE
        // The list changes height when the row appears/disappears: let the grid re-measure its cells
        if (wasVisible != show) maxRecyclerHeight = 0
        if (!show) return

        val label = ViewRecentLabelBinding.inflate(layoutInflater, row, false)
        label.root.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        label.recentClear.setOnClickListener {
            prefs.recentApps = emptyList()
            updateRecents()
        }
        row.addView(label.root)

        val iconSize = (40 * resources.displayMetrics.density).toInt()
        for (app in recents) {
            val cell = AdapterAppDrawerBinding.inflate(layoutInflater, row, false)
            cell.root.layoutParams =
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            cell.appIcon.layoutParams = cell.appIcon.layoutParams.apply {
                width = iconSize
                height = iconSize
            }
            cell.appTitle.maxLines = 1
            AppIconLoader.load(cell.appIcon, app)
            cell.appTitle.text = app.appLabel
            cell.root.setOnClickListener {
                prefs.addRecentApp(app.appPackage + "|" + app.user.toString())
                viewModel.selectedApp(app, Constants.FLAG_LAUNCH_APP)
                findNavController().popBackStack(R.id.mainFragment, false)
            }
            row.addView(cell.root)
        }
        // Empty slots keep the cells aligned with the grid columns
        repeat(columns - 1 - recents.size) {
            row.addView(Space(requireContext()), LinearLayout.LayoutParams(0, 1, 1f))
        }
    }

    private fun showLetterBubble(letter: Char, centerY: Float) {
        val bubble = binding.letterBubble
        bubble.text = letter.toString()
        val bubbleHeight = bubble.layoutParams.height.toFloat()
        val maxY = (binding.alphabetIndex.parent as View).height - bubbleHeight
        bubble.translationY =
            (binding.alphabetIndex.top + centerY - bubbleHeight / 2f).coerceIn(0f, maxOf(0f, maxY))
        bubble.animate().cancel()
        bubble.alpha = 1f
        bubble.visibility = View.VISIBLE
    }

    private fun hideLetterBubble() {
        if (_binding == null) return
        val bubble = binding.letterBubble
        bubble.animate().cancel()
        bubble.animate().alpha(0f).setDuration(180).withEndAction {
            bubble.visibility = View.GONE
        }.start()
    }

    /** Scroll to a letter (from the side bar), then flash that letter's apps once. */
    private fun jumpToLetter(letter: Char) {
        val run = letterRuns[letter] ?: return
        gridLayoutManager.scrollToPositionWithOffset(run.first, 0)
        val token = ++flashToken
        binding.recyclerView.doOnNextLayout {
            if (token == flashToken && _binding != null) flashLetterApps(run)
        }
    }

    private fun flashLetterApps(run: IntRange) {
        clearFlash()
        val glowColor = themeColor(R.attr.primaryColor)
        val radius = 16f * resources.displayMetrics.density
        for (position in run) {
            val view = gridLayoutManager.findViewByPosition(position) ?: continue
            val glow = GradientDrawable().apply {
                cornerRadius = radius
                setColor(glowColor)
                alpha = 0
            }
            view.foreground = glow
            val glowAnimator = ValueAnimator.ofInt(0, 90, 0).apply {
                duration = 550
                addUpdateListener { glow.alpha = it.animatedValue as Int }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        view.foreground = null
                    }
                })
            }
            view.animate().scaleX(1.18f).scaleY(1.18f).setDuration(240).withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(240).start()
            }.start()
            glowAnimator.start()
            flashedViews.add(view)
            flashAnimators.add(glowAnimator)
        }
    }

    private fun clearFlash() {
        flashAnimators.forEach { it.cancel() }
        flashAnimators.clear()
        flashedViews.forEach {
            it.animate().cancel()
            it.scaleX = 1f
            it.scaleY = 1f
            it.foreground = null
        }
        flashedViews.clear()
    }

    override fun onStart() {
        super.onStart()
        cachedIsCjkKeyboard = null
        binding.search.showKeyboard(prefs.autoShowKeyboard)
    }

    override fun onResume() {
        super.onResume()
        updateTopActions()
        updateClock()
        applyWallpaper()
        if (needsListRefresh && flag == Constants.FLAG_LAUNCH_APP) {
            // Back from the focus picker: show only the focus apps again
            needsListRefresh = false
            viewModel.getAppList()
        }
        if (AppIconLoader.shape != prefs.iconShape) {
            // Icon shape was changed in Settings
            AppIconLoader.shape = prefs.iconShape
            shownRecentKeys = null
            adapter.notifyDataSetChanged()
            currentAppList?.let { AppIconLoader.preload(requireContext(), it) }
        }
        updateRecents()
    }

    override fun onStop() {
        binding.search.hideKeyboard()
        super.onStop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        clearFlash()
        hintPopup?.dismiss()
        searchTextView = null
        _binding = null
    }
}
