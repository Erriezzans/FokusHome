package app.olauncher.ui

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import app.olauncher.helper.WallpaperHelper
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.bundleOf
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import app.olauncher.BuildConfig
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.databinding.DialogTextSizeBinding
import app.olauncher.databinding.FragmentSettingsBinding
import app.olauncher.helper.appUsagePermissionGranted
import app.olauncher.helper.createDialog
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.hideStatusBar
import app.olauncher.helper.isAccessServiceEnabled
import app.olauncher.helper.isDarkThemeOn
import app.olauncher.helper.isEinkDisplay
import app.olauncher.helper.isOlauncherDefault
import app.olauncher.helper.isTablet
import app.olauncher.helper.openAppInfo
import app.olauncher.helper.openUrl
import app.olauncher.helper.rateApp
import app.olauncher.helper.setPlainWallpaper
import app.olauncher.helper.shareApp
import app.olauncher.helper.OlDialog
import app.olauncher.helper.FokusAnim
import app.olauncher.helper.navigateAnimated
import android.text.InputType
import android.widget.EditText
import app.olauncher.helper.showPopupMenu
import app.olauncher.helper.showStatusBar
import app.olauncher.helper.showToast
import app.olauncher.listener.DeviceAdmin

class SettingsFragment : BaseFragment(), View.OnClickListener, View.OnLongClickListener {

    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val pickWallpaperImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) importWallpaperImage(uri)
        }
    private val showPentastic = System.currentTimeMillis() % 2 == 0L
    private var dialog: OlDialog? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        viewModel = activity?.run {
            ViewModelProvider(this)[MainViewModel::class.java]
        } ?: throw Exception("Invalid Activity")
        viewModel.isOlauncherDefault()

        populateKeyboardText()
        // Home button for recents feature disabled
        // populateHomeButtonRecents()
        populateWallpaperText()
        populateAppThemeText()
        populateTextSize()
        populateBoldFont()
        populateAlignment()
        populateStatusBar()
        populateDateTime()
        populateClockFormat()
        populateClockAlignment()
        populateWallpaper()
        populateIconShape()
        populateDrawerGrid()
        populateAnimations()
        populateLayoutOptions()
        populateRecentApps()
        populateActionHints()
        initClickListeners()
        initObservers()

        if (showPentastic)
            binding.footer.text = getText(R.string.new_app_minimal_todo_lists)
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.olauncherHiddenApps -> showHiddenApps()
            R.id.appInfo -> openAppInfo(requireContext(), Process.myUserHandle(), BuildConfig.APPLICATION_ID)
            R.id.setLauncher -> viewModel.resetLauncherLiveData.call()
            // Home button for recents feature disabled
            // R.id.homeButtonRecents -> toggleHomeButtonRecents()
            R.id.autoShowKeyboard -> toggleKeyboardText()
            R.id.drawerGrid -> showGridMenu(view)
            R.id.transitionAnim -> showAnimMenu(view, AnimKind.TRANSITION)
            R.id.touchAnim -> showAnimMenu(view, AnimKind.TOUCH)
            R.id.focusAnim -> showAnimMenu(view, AnimKind.FOCUS)
            R.id.barsPosition -> {
                prefs.barsAtBottom = !prefs.barsAtBottom
                populateLayoutOptions()
            }
            R.id.pullDownToggle -> {
                prefs.pullDownNotifications = !prefs.pullDownNotifications
                populateLayoutOptions()
            }
            R.id.dailyWallpaperUrl -> requireContext().openUrl(prefs.dailyWallpaperUrl)
            R.id.dailyWallpaper -> toggleDailyWallpaperUpdate()
            R.id.alignment -> showAlignmentMenu(view)
            R.id.statusBar -> toggleStatusBar()
            R.id.dateTime -> showDateTimeMenu(view)
            R.id.clockFormat -> toggleClockFormat()
            R.id.clockAlignment -> toggleClockAlignment()
            R.id.wallpaperPicker -> showWallpaperDialog()
            R.id.donate -> openDonate()
            R.id.iconShape -> toggleIconShape()
            R.id.recentAppsToggle -> toggleRecentApps()
            R.id.appThemeText -> showAppThemeMenu(view, showSystem = false)
            R.id.textSizeValue -> showTextSizeDialog()
            R.id.boldFont -> toggleBoldFont()

            R.id.aboutOlauncher -> {
                prefs.aboutClicked = true
                requireContext().openUrl(Constants.URL_ABOUT_OLAUNCHER)
            }

            R.id.share -> requireActivity().shareApp()
            R.id.rate -> {
                prefs.rateClicked = true
                requireActivity().rateApp()
            }

            R.id.twitter -> requireContext().openUrl(Constants.URL_TWITTER_TANUJ)
            R.id.github -> requireContext().openUrl(Constants.URL_OLAUNCHER_GITHUB)
            R.id.privacy -> requireContext().openUrl(Constants.URL_OLAUNCHER_PRIVACY)
            R.id.footer -> {
                requireContext().openUrl(
                    if (showPentastic) Constants.URL_PENTASTIC else Constants.URL_NTS
                )
            }
        }
    }

    override fun onLongClick(view: View): Boolean {
        when (view.id) {
            R.id.alignment -> {
                prefs.appLabelAlignment = prefs.homeAlignment
                findNavController().navigateAnimated(R.id.action_settingsFragment_to_appListFragment, prefs.transitionAnim)
                requireContext().showToast(getString(R.string.alignment_changed))
            }

            R.id.dailyWallpaper -> removeWallpaper()
            R.id.appThemeText -> showAppThemeMenu(view, showSystem = true)
        }
        return true
    }

    private fun initClickListeners() {
        binding.olauncherHiddenApps.setOnClickListener(this)
        binding.appInfo.setOnClickListener(this)
        binding.setLauncher.setOnClickListener(this)
        binding.aboutOlauncher.setOnClickListener(this)
        binding.autoShowKeyboard.setOnClickListener(this)
        // Home button for recents feature disabled
        // binding.homeButtonRecents.setOnClickListener(this)
        binding.dailyWallpaperUrl.setOnClickListener(this)
        binding.dailyWallpaper.setOnClickListener(this)
        binding.alignment.setOnClickListener(this)
        binding.statusBar.setOnClickListener(this)
        binding.dateTime.setOnClickListener(this)
        binding.clockFormat.setOnClickListener(this)
        binding.clockAlignment.setOnClickListener(this)
        binding.wallpaperPicker.setOnClickListener(this)
        binding.donate.setOnClickListener(this)
        binding.iconShape.setOnClickListener(this)
        binding.drawerGrid.setOnClickListener(this)
        binding.transitionAnim.setOnClickListener(this)
        binding.touchAnim.setOnClickListener(this)
        binding.focusAnim.setOnClickListener(this)
        binding.barsPosition.setOnClickListener(this)
        binding.pullDownToggle.setOnClickListener(this)
        binding.recentAppsToggle.setOnClickListener(this)
        binding.appThemeText.setOnClickListener(this)
        binding.textSizeValue.setOnClickListener(this)
        binding.boldFont.setOnClickListener(this)

        binding.share.setOnClickListener(this)
        binding.rate.setOnClickListener(this)
        binding.twitter.setOnClickListener(this)
        binding.github.setOnClickListener(this)
        binding.privacy.setOnClickListener(this)
        binding.footer.setOnClickListener(this)

        binding.dailyWallpaper.setOnLongClickListener(this)
        binding.alignment.setOnLongClickListener(this)
        binding.appThemeText.setOnLongClickListener(this)
    }

    private fun initObservers() {
        viewModel.isOlauncherDefault.observe(viewLifecycleOwner) {
            if (it) {
                binding.setLauncher.text = getString(R.string.change_default_launcher)
                prefs.toShowHintCounter += 1
            }
        }
        viewModel.homeAppAlignment.observe(viewLifecycleOwner) {
            populateAlignment()
        }
    }

    // Popup menus

    private fun showDateTimeMenu(anchor: View) {
        anchor.showPopupMenu(R.menu.date_time) { item ->
            when (item.itemId) {
                R.id.dateTimeOn -> toggleDateTime(Constants.DateTime.ON)
                R.id.dateTimeOff -> toggleDateTime(Constants.DateTime.OFF)
                R.id.dateOnly -> toggleDateTime(Constants.DateTime.DATE_ONLY)
            }
        }
    }

    private fun showAlignmentMenu(anchor: View) {
        anchor.showPopupMenu(
            R.menu.alignment,
            configure = { menu ->
                menu.findItem(R.id.alignmentBottom).setTitle(
                    if (prefs.homeBottomAlignment) R.string.bottom_on else R.string.bottom_off
                )
            }
        ) { item ->
            when (item.itemId) {
                R.id.alignmentLeft -> viewModel.updateHomeAlignment(Gravity.START)
                R.id.alignmentCenter -> viewModel.updateHomeAlignment(Gravity.CENTER)
                R.id.alignmentRight -> viewModel.updateHomeAlignment(Gravity.END)
                R.id.alignmentBottom -> updateHomeBottomAlignment()
            }
        }
    }

    // "System" stays hidden unless the row is long pressed
    private fun showAppThemeMenu(anchor: View, showSystem: Boolean) {
        anchor.showPopupMenu(
            R.menu.app_theme,
            configure = { menu -> menu.findItem(R.id.themeSystem).isVisible = showSystem }
        ) { item ->
            when (item.itemId) {
                R.id.themeLight -> updateTheme(AppCompatDelegate.MODE_NIGHT_NO)
                R.id.themeDark -> updateTheme(AppCompatDelegate.MODE_NIGHT_YES)
                R.id.themeSystem -> updateTheme(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
        }
    }

    // Dialogs

    private fun showDialog(newDialog: OlDialog) {
        dialog?.dismiss()
        dialog = newDialog
        newDialog.showRespectingStatusBar()
    }

    private fun showTextSizeDialog() {
        var stepper: DialogTextSizeBinding? = null
        val dialog = requireContext().createDialog(R.string.text_size, R.string.okay) { container ->
            DialogTextSizeBinding.inflate(layoutInflater, container, false).also { stepper = it }.root
        }
        stepper?.apply {
            textSizeCurrent.text = formatScale(pendingOrCurrentTextSizeScale())
            textSizeMinus.setOnClickListener { adjustTextSizePreview(-0.1f, this) }
            textSizePlus.setOnClickListener { adjustTextSizePreview(0.1f, this) }
        }
        dialog.setOnDismissListener { applyTextSizeScale() }
        showDialog(dialog)
    }

    private fun toggleStatusBar() {
        prefs.showStatusBar = !prefs.showStatusBar
        populateStatusBar()
    }

    private fun populateStatusBar() {
        if (prefs.showStatusBar) {
            requireActivity().window.showStatusBar()
            binding.statusBar.text = getString(R.string.on)
        } else {
            requireActivity().window.hideStatusBar()
            binding.statusBar.text = getString(R.string.off)
        }
    }

    private fun populateClockFormat() {
        binding.clockFormat.text = getString(
            when (prefs.clockFormat) {
                1 -> R.string.fokus_clock_24
                2 -> R.string.fokus_clock_12
                else -> R.string.fokus_clock_system
            }
        )
    }

    private fun toggleClockFormat() {
        prefs.clockFormat = (prefs.clockFormat + 1) % 3
        populateClockFormat()
    }

    private fun populateRecentApps() {
        binding.recentAppsToggle.text =
            getString(if (prefs.showRecentApps) R.string.fokus_on else R.string.fokus_off)
    }

    private fun toggleRecentApps() {
        prefs.showRecentApps = !prefs.showRecentApps
        populateRecentApps()
    }

    private fun themeValue(attr: Int): Int {
        val value = TypedValue()
        requireContext().theme.resolveAttribute(attr, value, true)
        return value.data
    }

    private fun openDonate() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Constants.URL_DONATE)))
        } catch (e: Exception) {
            requireContext().showToast(R.string.fokus_donate_failed)
        }
    }

    private fun populateWallpaper() {
        binding.wallpaperPicker.text = getString(
            when (prefs.wallpaperMode) {
                1 -> R.string.fokus_wp_color
                2 -> R.string.fokus_wp_image
                else -> R.string.fokus_wp_phone
            }
        )
    }

    /** After any wallpaper change: refresh the row and let the theme follow the wallpaper's brightness. */
    private fun onWallpaperChanged() {
        populateWallpaper()
        populateAppThemeText()
        // prefs.appTheme follows the wallpaper; this recreates the screen if the theme flips
        AppCompatDelegate.setDefaultNightMode(prefs.appTheme)
    }

    private fun applyWallpaperColor(color: Int) {
        prefs.wallpaperColor = color
        prefs.wallpaperIsLight = WallpaperHelper.isLight(color)
        prefs.wallpaperMode = 1
        onWallpaperChanged()
        // Also the phone's own home screen and lock screen
        val appContext = requireContext().applicationContext
        runInBackground(appContext) { WallpaperHelper.setSystemColor(appContext, color) }
    }

    /** "Default wallpaper": FokusHome stops changing the phone wallpaper. The current one stays as it is. */
    private fun usePhoneWallpaper() {
        prefs.wallpaperMode = 0
        onWallpaperChanged()
        requireContext().showToast(R.string.fokus_wp_keep_note)
    }

    /** Runs slow wallpaper work off the main thread and warns if the phone refused it. */
    private fun runInBackground(appContext: Context, work: () -> Boolean) {
        val handler = Handler(Looper.getMainLooper())
        Thread {
            val ok = try {
                work()
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
            if (!ok) handler.post { appContext.showToast(R.string.fokus_wp_system_failed) }
        }.start()
    }

    private fun importWallpaperImage(uri: Uri) {
        val appContext = requireContext().applicationContext
        val handler = Handler(Looper.getMainLooper())
        Thread {
            val result = try {
                WallpaperHelper.saveImage(appContext, uri, prefs)
            } catch (e: Exception) {
                e.printStackTrace()
                WallpaperHelper.RESULT_FAILED
            }
            handler.post {
                when (result) {
                    WallpaperHelper.RESULT_FAILED -> appContext.showToast(R.string.fokus_wp_image_failed)
                    WallpaperHelper.RESULT_OK_PHONE_NOT_CHANGED ->
                        appContext.showToast(R.string.fokus_wp_system_failed)
                }
                if (result != WallpaperHelper.RESULT_FAILED && isAdded && _binding != null) onWallpaperChanged()
            }
        }.start()
    }

    /** Wallpaper picker: plain color, your own image, or leave the phone wallpaper alone. */
    private fun showWallpaperDialog() {
        val items = arrayOf(
            getString(R.string.fokus_wp_color_title),
            getString(R.string.fokus_wp_pick_image),
            getString(R.string.fokus_wp_keep_phone),
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.fokus_wallpaper)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showColorSheet()
                    1 -> pickWallpaperImage.launch("image/*")
                    else -> usePhoneWallpaper()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** "Color" sheet: Color Picker tile, "no color" tile, then a grid of colors. */
    private fun showColorSheet() {
        val context = requireContext()
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val textColor = themeValue(R.attr.primaryColor)
        val side = dp(20)
        val gap = dp(10)
        val tile = (resources.displayMetrics.widthPixels - 2 * side - 2 * gap) / 3
        val sheet = BottomSheetDialog(context)

        val tiles = mutableListOf<View>()

        // 1. Color Picker
        tiles.add(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0x22888888)
            }
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_colorize)
                setColorFilter(textColor)
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { bottomMargin = dp(10) }
            })
            addView(TextView(context).apply {
                text = getString(R.string.fokus_wp_color_picker)
                textSize = 15f
                setTextColor(textColor)
            })
            setOnClickListener {
                sheet.dismiss()
                showColorPicker()
            }
        })

        // 2. Checkerboard = no color: keep the phone wallpaper
        tiles.add(View(context).apply {
            background = CheckerDrawable(dp(10).toFloat(), dp(12).toFloat())
            setOnClickListener {
                sheet.dismiss()
                usePhoneWallpaper()
            }
        })

        // 3.. Colors
        for (color in WallpaperHelper.PALETTE) {
            tiles.add(View(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(color)
                    setStroke(dp(1), 0x33888888)
                }
                setOnClickListener {
                    sheet.dismiss()
                    applyWallpaperColor(color)
                }
            })
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, dp(28), side, dp(24))
            addView(TextView(context).apply {
                text = getString(R.string.fokus_wp_sheet_title)
                textSize = 26f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(textColor)
                setPadding(0, 0, 0, dp(16))
            })
        }
        for (rowTiles in tiles.chunked(3)) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = gap }
            }
            rowTiles.forEachIndexed { index, view ->
                view.layoutParams = LinearLayout.LayoutParams(tile, tile).apply {
                    marginEnd = if (index < 2) gap else 0
                }
                row.addView(view)
            }
            root.addView(row)
        }
        sheet.setContentView(NestedScrollView(context).apply { addView(root) })
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.show()
    }

    /** Own color: hue ring + diamond, preview bar, tick / cross and a grid of small swatches. */
    private fun showColorPicker() {
        val context = requireContext()
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val textColor = themeValue(R.attr.primaryColor)
        var current = if (prefs.wallpaperMode == 1) prefs.wallpaperColor else 0xFF1B8A8A.toInt()

        val preview = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44))
            setBackgroundColor(current)
        }
        val wheel = ColorWheelView(context).apply {
            color = current
            layoutParams = LinearLayout.LayoutParams(dp(260), dp(260)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(16)
                bottomMargin = dp(12)
            }
            onColorChanged = {
                current = it
                preview.setBackgroundColor(it)
            }
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(16))
        }
        val dialog = AlertDialog.Builder(context).setView(content).create()

        fun headerButton(symbol: String, onClick: () -> Unit) = TextView(context).apply {
            text = symbol
            textSize = 26f
            setTextColor(textColor)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { onClick() }
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(headerButton("✕") { dialog.dismiss() })
        header.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        header.addView(headerButton("✓") {
            prefs.addCustomColor(current)
            dialog.dismiss()
            applyWallpaperColor(current)
        })
        content.addView(header)
        content.addView(preview)
        content.addView(wheel)

        // 6 x 4 swatches: ready colors first, then the colors you picked before, then empty slots
        val slots = WallpaperHelper.PRESETS.toList() + prefs.customColors
        var index = 0
        for (rowIndex in 0 until 4) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(4), 0, dp(4))
            }
            for (column in 0 until 6) {
                val color = slots.getOrNull(index)
                index++
                row.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                        marginStart = dp(4)
                        marginEnd = dp(4)
                    }
                    background = GradientDrawable().apply {
                        cornerRadius = dp(8).toFloat()
                        if (color != null) setColor(color)
                        setStroke(dp(2), 0xAA888888.toInt())
                    }
                    if (color != null) {
                        setOnClickListener {
                            current = color
                            wheel.color = color
                            preview.setBackgroundColor(color)
                        }
                    }
                })
            }
            content.addView(row)
        }
        dialog.show()
    }

    private fun populateClockAlignment() {
        binding.clockAlignment.text = getString(
            when (prefs.clockAlignment) {
                0 -> R.string.fokus_align_left
                1 -> R.string.fokus_align_center
                else -> R.string.fokus_align_right
            }
        )
    }

    private fun toggleClockAlignment() {
        prefs.clockAlignment = (prefs.clockAlignment + 1) % 3
        populateClockAlignment()
    }

    private fun populateIconShape() {
        binding.iconShape.text = getString(
            if (prefs.iconShape == 1) R.string.fokus_shape_rounded else R.string.fokus_shape_circle
        )
    }

    private fun toggleIconShape() {
        prefs.iconShape = (prefs.iconShape + 1) % 2
        populateIconShape()
    }

    private fun toggleDateTime(selected: Int) {
        prefs.dateTimeVisibility = selected
        populateDateTime()
        viewModel.toggleDateTime()
    }

    private fun populateDateTime() {
        binding.dateTime.text = getString(
            when (prefs.dateTimeVisibility) {
                Constants.DateTime.DATE_ONLY -> R.string.date
                Constants.DateTime.ON -> R.string.on
                else -> R.string.off
            }
        )
    }

    private fun showHiddenApps() {
        if (prefs.hiddenApps.isEmpty()) {
            requireContext().showToast(getString(R.string.no_hidden_apps))
            return
        }
        viewModel.getHiddenApps()
        findNavController().navigateAnimated(
            R.id.action_settingsFragment_to_appListFragment,
            prefs.transitionAnim,
            bundleOf(Constants.Key.FLAG to Constants.FLAG_HIDDEN_APPS)
        )
    }

    private fun removeWallpaper() {
        if (requireContext().isEinkDisplay()) {
            prefs.appTheme = AppCompatDelegate.MODE_NIGHT_NO
            setPlainWallpaper(requireContext(), android.R.color.white)
        } else {
            prefs.appTheme = AppCompatDelegate.MODE_NIGHT_YES
            setPlainWallpaper(requireContext(), android.R.color.black)
        }
        if (!prefs.dailyWallpaper) return
        prefs.dailyWallpaper = false
        populateWallpaperText()
        viewModel.cancelWallpaperWorker()
    }

    private fun toggleDailyWallpaperUpdate() {
        if (prefs.dailyWallpaper.not() && prefs.appTheme == AppCompatDelegate.MODE_NIGHT_YES && viewModel.isOlauncherDefault.value == false) {
            requireContext().showToast(R.string.set_as_default_launcher_first)
            return
        }
        prefs.dailyWallpaper = !prefs.dailyWallpaper
        populateWallpaperText()
        if (prefs.dailyWallpaper) {
            viewModel.setWallpaperWorker()
            showWallpaperToasts()
        } else viewModel.cancelWallpaperWorker()
    }

    private fun showWallpaperToasts() {
        if (isOlauncherDefault(requireContext()))
            requireContext().showToast(getString(R.string.your_wallpaper_will_update_shortly))
        else
            requireContext().showToast(getString(R.string.olauncher_is_not_default_launcher), Toast.LENGTH_LONG)
    }

    private var pendingTextSizeScale: Float = -1f

    private fun pendingOrCurrentTextSizeScale(): Float =
        if (pendingTextSizeScale > 0) pendingTextSizeScale else prefs.textSizeScale

    private fun formatScale(scale: Float): String = String.format("%.1f", scale)

    private fun adjustTextSizePreview(delta: Float, dialogBinding: DialogTextSizeBinding) {
        val maxScale = if (isTablet(requireContext())) 2.0f else 1.5f
        val current = pendingOrCurrentTextSizeScale()
        val newScale = Math.round((current + delta) * 10f) / 10f
        val clamped = newScale.coerceIn(0.5f, maxScale)
        if (clamped == current) return
        pendingTextSizeScale = clamped
        val formatted = formatScale(clamped)
        binding.textSizeValue.text = formatted
        dialogBinding.textSizeCurrent.text = formatted
    }

    private fun applyTextSizeScale() {
        if (pendingTextSizeScale < 0 || prefs.textSizeScale == pendingTextSizeScale) {
            pendingTextSizeScale = -1f
            return
        }
        prefs.textSizeScale = pendingTextSizeScale
        pendingTextSizeScale = -1f
        val activity = activity ?: return
        if (activity.isChangingConfigurations.not())
            activity.recreate()
    }

    private fun toggleKeyboardText() {
        if (prefs.autoShowKeyboard && prefs.keyboardMessageShown.not()) {
            viewModel.showDialog.postValue(Constants.Dialog.KEYBOARD)
            prefs.keyboardMessageShown = true
        } else {
            prefs.autoShowKeyboard = !prefs.autoShowKeyboard
            populateKeyboardText()
        }
    }

    private fun updateTheme(appTheme: Int) {
        if (AppCompatDelegate.getDefaultNightMode() == appTheme) return
        prefs.appTheme = appTheme
        populateAppThemeText(appTheme)
        setAppTheme(appTheme)
    }

    private fun setAppTheme(theme: Int) {
        if (AppCompatDelegate.getDefaultNightMode() == theme) return
        if (prefs.dailyWallpaper) {
            setPlainWallpaper(theme)
            viewModel.setWallpaperWorker()
        }
        requireActivity().recreate()
    }

    private fun setPlainWallpaper(appTheme: Int) {
        when (appTheme) {
            AppCompatDelegate.MODE_NIGHT_YES -> setPlainWallpaper(requireContext(), android.R.color.black)
            AppCompatDelegate.MODE_NIGHT_NO -> setPlainWallpaper(requireContext(), android.R.color.white)
            else -> {
                if (requireContext().isDarkThemeOn())
                    setPlainWallpaper(requireContext(), android.R.color.black)
                else setPlainWallpaper(requireContext(), android.R.color.white)
            }
        }
    }

    private fun populateAppThemeText(appTheme: Int = prefs.appTheme) {
        when (appTheme) {
            AppCompatDelegate.MODE_NIGHT_YES -> binding.appThemeText.text = getString(R.string.dark)
            AppCompatDelegate.MODE_NIGHT_NO -> binding.appThemeText.text = getString(R.string.light)
            else -> binding.appThemeText.text = getString(R.string.system_default)
        }
    }

    private fun populateTextSize() {
        binding.textSizeValue.text = formatScale(prefs.textSizeScale)
    }

    private fun toggleBoldFont() {
        prefs.boldFont = !prefs.boldFont
        populateBoldFont()
        requireActivity().recreate()
    }

    private fun populateBoldFont() {
        binding.boldFont.text = getString(if (prefs.boldFont) R.string.on else R.string.off)
    }

    private fun populateKeyboardText() {
        if (prefs.autoShowKeyboard) binding.autoShowKeyboard.text = getString(R.string.on)
        else binding.autoShowKeyboard.text = getString(R.string.off)
    }

    private fun populateWallpaperText() {
        if (prefs.dailyWallpaper) binding.dailyWallpaper.text = getString(R.string.on)
        else binding.dailyWallpaper.text = getString(R.string.off)
    }

    private fun updateHomeBottomAlignment() {
        if (viewModel.isOlauncherDefault.value != true) {
            requireContext().showToast(getString(R.string.please_set_olauncher_as_default_first), Toast.LENGTH_LONG)
            return
        }
        prefs.homeBottomAlignment = !prefs.homeBottomAlignment
        populateAlignment()
        viewModel.updateHomeAlignment(prefs.homeAlignment)
    }

    private fun populateAlignment() {
        when (prefs.homeAlignment) {
            Gravity.START -> binding.alignment.text = getString(R.string.left)
            Gravity.CENTER -> binding.alignment.text = getString(R.string.center)
            Gravity.END -> binding.alignment.text = getString(R.string.right)
        }
    }

    // Home button for recents feature disabled
    // private fun toggleHomeButtonRecents() {
    //     if (!prefs.homeButtonShowRecents && !isAccessServiceEnabled(requireContext())) {
    //         showAccessibilityDialog()
    //         return
    //     }
    //     prefs.homeButtonShowRecents = !prefs.homeButtonShowRecents
    //     populateHomeButtonRecents()
    // }

    // private fun populateHomeButtonRecents() {
    //     binding.homeButtonRecents.text = getString(
    //         if (prefs.homeButtonShowRecents && isAccessServiceEnabled(requireContext())) R.string.on
    //         else R.string.off
    //     )
    // }

//    private fun populateDigitalWellbeing() {
//        binding.digitalWellbeing.isVisible = requireContext().isPackageInstalled(Constants.DIGITAL_WELLBEING_PACKAGE_NAME).not()
//                && requireContext().isPackageInstalled(Constants.DIGITAL_WELLBEING_SAMSUNG_PACKAGE_NAME).not()
//                && prefs.hideDigitalWellbeing.not()
//    }

    private fun populateActionHints() {
        if (prefs.aboutClicked.not())
            binding.aboutOlauncher.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_info, 0)
        if (viewModel.isOlauncherDefault.value != true) return
        if (prefs.rateClicked.not() && prefs.toShowHintCounter > Constants.HINT_RATE_US && prefs.toShowHintCounter < Constants.HINT_RATE_US + 100)
            binding.rate.setCompoundDrawablesWithIntrinsicBounds(0, android.R.drawable.arrow_down_float, 0, 0)
    }

    // App drawer grid + animations

    private enum class AnimKind { TRANSITION, TOUCH, FOCUS }

    private fun populateDrawerGrid() {
        val columns = prefs.drawerColumns
        val rows = prefs.drawerRows
        binding.drawerGrid.text = if (columns == rows && columns in 3..9)
            "$columns × $rows"
        else
            getString(R.string.fokus_grid_custom) + " $columns × $rows"
    }

    private fun showGridMenu(anchor: View) {
        val custom = Menu.FIRST + 100
        anchor.showPopupMenu(
            configure = { menu ->
                for (n in 3..9) menu.add(Menu.NONE, n, n, "$n × $n")
                menu.add(Menu.NONE, custom, custom, R.string.fokus_grid_custom)
            }
        ) { item ->
            if (item.itemId == custom) showCustomGridDialog()
            else updateGrid(item.itemId, item.itemId)
        }
    }

    private fun updateGrid(columns: Int, rows: Int) {
        prefs.drawerColumns = columns
        prefs.drawerRows = rows
        populateDrawerGrid()
    }

    private fun showCustomGridDialog() {
        val context = requireContext()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        fun field(hintRes: Int, min: Int, max: Int, value: Int) = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(hintRes, min, max)
            setText(value.toString())
            setSelectAllOnFocus(true)
        }
        val columns = field(R.string.fokus_grid_columns, Constants.GRID_MIN_COLUMNS, Constants.GRID_MAX_COLUMNS, prefs.drawerColumns)
        val rows = field(R.string.fokus_grid_rows, Constants.GRID_MIN_ROWS, Constants.GRID_MAX_ROWS, prefs.drawerRows)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(columns)
            addView(rows)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.fokus_grid_custom_title)
            .setView(content)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val c = columns.text.toString().toIntOrNull()
                val r = rows.text.toString().toIntOrNull()
                if (c == null || r == null ||
                    c !in Constants.GRID_MIN_COLUMNS..Constants.GRID_MAX_COLUMNS ||
                    r !in Constants.GRID_MIN_ROWS..Constants.GRID_MAX_ROWS
                ) requireContext().showToast(R.string.fokus_grid_invalid)
                else updateGrid(c, r)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun transitionLabel(mode: Int) = getString(
        when (mode) {
            FokusAnim.TRANSITION_FADE -> R.string.fokus_anim_fade
            FokusAnim.TRANSITION_SLIDE -> R.string.fokus_anim_slide
            FokusAnim.TRANSITION_ZOOM -> R.string.fokus_anim_zoom
            FokusAnim.TRANSITION_OFF -> R.string.fokus_anim_off
            else -> R.string.fokus_anim_default
        }
    )

    private fun touchLabel(mode: Int) = getString(
        when (mode) {
            FokusAnim.TOUCH_PRESS -> R.string.fokus_anim_press
            FokusAnim.TOUCH_BOUNCE -> R.string.fokus_anim_bounce
            else -> R.string.fokus_anim_off
        }
    )

    private fun focusLabel(mode: Int) = getString(
        when (mode) {
            FokusAnim.FOCUS_FADE -> R.string.fokus_anim_fade
            FokusAnim.FOCUS_SLIDE -> R.string.fokus_anim_slide
            FokusAnim.FOCUS_ZOOM -> R.string.fokus_anim_zoom
            else -> R.string.fokus_anim_off
        }
    )

    private fun populateLayoutOptions() {
        binding.barsPosition.text = getString(
            if (prefs.barsAtBottom) R.string.fokus_position_bottom else R.string.fokus_position_top
        )
        binding.pullDownToggle.text =
            getString(if (prefs.pullDownNotifications) R.string.fokus_on else R.string.fokus_off)
    }

    private fun populateAnimations() {
        binding.transitionAnim.text = transitionLabel(prefs.transitionAnim)
        binding.touchAnim.text = touchLabel(prefs.touchAnim)
        binding.focusAnim.text = focusLabel(prefs.focusAnim)
    }

    private fun showAnimMenu(anchor: View, kind: AnimKind) {
        val modes = when (kind) {
            AnimKind.TRANSITION -> listOf(
                FokusAnim.TRANSITION_DEFAULT, FokusAnim.TRANSITION_FADE, FokusAnim.TRANSITION_SLIDE,
                FokusAnim.TRANSITION_ZOOM, FokusAnim.TRANSITION_OFF
            )
            AnimKind.TOUCH -> listOf(FokusAnim.TOUCH_OFF, FokusAnim.TOUCH_PRESS, FokusAnim.TOUCH_BOUNCE)
            AnimKind.FOCUS -> listOf(
                FokusAnim.FOCUS_OFF, FokusAnim.FOCUS_FADE, FokusAnim.FOCUS_SLIDE, FokusAnim.FOCUS_ZOOM
            )
        }
        anchor.showPopupMenu(
            configure = { menu ->
                modes.forEach { mode ->
                    val label = when (kind) {
                        AnimKind.TRANSITION -> transitionLabel(mode)
                        AnimKind.TOUCH -> touchLabel(mode)
                        AnimKind.FOCUS -> focusLabel(mode)
                    }
                    menu.add(Menu.NONE, mode, mode, label)
                }
            }
        ) { item ->
            when (kind) {
                AnimKind.TRANSITION -> prefs.transitionAnim = item.itemId
                AnimKind.TOUCH -> prefs.touchAnim = item.itemId
                AnimKind.FOCUS -> prefs.focusAnim = item.itemId
            }
            populateAnimations()
        }
    }

    override fun onDestroyView() {
        // Dismissing the text size dialog applies any pending scale via its dismiss listener
        dialog?.dismiss()
        dialog = null
        applyTextSizeScale()
        super.onDestroyView()
        _binding = null
    }

}
