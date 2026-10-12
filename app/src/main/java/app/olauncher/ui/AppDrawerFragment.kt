package app.olauncher.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import androidx.core.view.doOnNextLayout
import app.olauncher.databinding.AdapterAppDrawerBinding
import app.olauncher.helper.AppIconLoader
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.text.Spannable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
        tintSearchIcon()
        try {
            searchTextView = binding.search.findViewById(R.id.search_src_text)
            searchTextView?.gravity = prefs.appLabelAlignment
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
                    adapter.allowAutoLaunch = !isSearchComposing()
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

        val spanCount = prefs.drawerColumns.coerceIn(3, 7)
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

        binding.recyclerView.layoutManager = gridLayoutManager
        adapter.focusKeys = if (flag == Constants.FLAG_FOCUS_APPS) prefs.focusApps else emptySet()
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

        adapter.setAppList(combined)
        adapter.filter.filter(binding.search.query)
    }

    private fun initClickListeners() {
        binding.focusAction.setOnClickListener { onFocusActionClick() }
        binding.focusAction.setOnLongClickListener {
            if (flag != Constants.FLAG_LAUNCH_APP) return@setOnLongClickListener false
            if (prefs.focusModeEnabled)
                requireContext().showToast(R.string.focus_exit_to_edit)
            else
                openFocusPicker()
            true
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
        if (flag == Constants.FLAG_LAUNCH_APP)
            viewModel.checkForMessages.call()
    }

    private fun isRoot() = findNavController().currentDestination?.id == R.id.mainFragment

    private fun updateTopActions() {
        when (flag) {
            Constants.FLAG_FOCUS_APPS -> {
                binding.focusAction.text = getString(R.string.focus_done)
                binding.focusAction.visibility = View.VISIBLE
                binding.settingsAction.visibility = View.GONE
            }

            Constants.FLAG_LAUNCH_APP -> {
                binding.focusAction.text =
                    getString(if (prefs.focusModeEnabled) R.string.focus_exit else R.string.focus_on)
                binding.focusAction.visibility = View.VISIBLE
                binding.settingsAction.visibility = View.VISIBLE
            }

            else -> {
                binding.focusAction.visibility = View.GONE
                binding.settingsAction.visibility = View.GONE
            }
        }
    }

    private fun onFocusActionClick() {
        when {
            flag == Constants.FLAG_FOCUS_APPS -> findNavController().popBackStack()
            prefs.focusModeEnabled -> setFocusMode(false)
            prefs.focusApps.isEmpty() -> {
                requireContext().showToast(R.string.focus_pick_first)
                openFocusPicker()
            }

            else -> setFocusMode(true)
        }
    }

    private fun setFocusMode(enabled: Boolean) {
        prefs.focusModeEnabled = enabled
        updateTopActions()
        viewModel.getAppList()
        binding.recyclerView.scrollToPosition(0)
    }

    private fun openFocusPicker() {
        // Make sure the picker shows every app (focus mode is off here)
        viewModel.getAppList()
        findNavController().navigate(
            R.id.action_mainFragment_to_appListFragment,
            bundleOf(
                Constants.Key.FLAG to Constants.FLAG_FOCUS_APPS,
                Constants.Key.RENAME to false
            )
        )
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
            navController.navigate(R.id.action_mainFragment_to_settingsFragment)
        else
            navController.navigate(R.id.action_appListFragment_to_settingsFragment2)
    }

    private fun updateCellHeight() {
        if (_binding == null) return
        val rows = prefs.drawerRows.coerceAtLeast(1)
        val usable = maxRecyclerHeight - binding.recyclerView.paddingTop - binding.recyclerView.paddingBottom
        val cell = usable / rows
        if (cell > 0 && cell != adapter.cellHeight) {
            adapter.cellHeight = cell
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
            binding.search.findViewById<ImageView>(R.id.search_mag_icon)
                ?.setColorFilter(themeColor(R.attr.primaryColor))
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
        binding.clockTime.format12Hour = format12
        binding.clockTime.format24Hour = format24
        binding.clockDate.format12Hour = "EEEE, d MMM"
        binding.clockDate.format24Hour = "EEEE, d MMM"
        // The list changes height when the clock appears/disappears: let the grid re-measure its cells
        if (wasVisible != show) maxRecyclerHeight = 0
    }

    /** Row with the most recently launched apps, lined up with the grid columns. */
    private fun updateRecents() {
        if (_binding == null) return
        val row = binding.recentRow
        if (flag != Constants.FLAG_LAUNCH_APP) {
            row.visibility = View.GONE
            return
        }
        val columns = prefs.drawerColumns.coerceIn(3, 7)
        val byKey = HashMap<String, AppModel.App>()
        currentAppList.orEmpty().forEach {
            if (it is AppModel.App) byKey[it.appPackage + "|" + it.user.toString()] = it
        }
        val recents = prefs.recentApps.mapNotNull { byKey[it] }.take(minOf(5, columns))

        row.removeAllViews()
        row.visibility = if (recents.isEmpty()) View.INVISIBLE else View.VISIBLE
        for (app in recents) {
            val cell = AdapterAppDrawerBinding.inflate(layoutInflater, row, false)
            cell.root.layoutParams =
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
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
        repeat(columns - recents.size) {
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
        if (AppIconLoader.shape != prefs.iconShape) {
            // Icon shape was changed in Settings
            AppIconLoader.shape = prefs.iconShape
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
        searchTextView = null
        _binding = null
    }
}
