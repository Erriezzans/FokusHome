package app.olauncher.ui

import android.content.Context
import android.content.pm.LauncherApps
import android.os.UserHandle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Filter
import android.widget.Filterable
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Constants
import app.olauncher.databinding.AdapterAppDrawerBinding
import app.olauncher.databinding.AdapterPrivateSpaceHeaderBinding
import app.olauncher.helper.AppIconLoader
import java.text.Normalizer

class AppDrawerAdapter(
    private var flag: Int,
    private val appLabelGravity: Int,
    private val appClickListener: (AppModel) -> Unit,
    private val appInfoListener: (AppModel) -> Unit,
    private val appDeleteListener: (AppModel) -> Unit,
    private val appHideListener: (AppModel, Int) -> Unit,
    private val appRenameListener: (AppModel, String) -> Unit,
    private val privateSpaceToggleListener: () -> Unit = {},
    private val privateSpaceSettingsListener: () -> Unit = {},
) : ListAdapter<AppModel, RecyclerView.ViewHolder>(DIFF_CALLBACK), Filterable {

    companion object {
        const val VIEW_TYPE_APP = 0
        const val VIEW_TYPE_PRIVATE_HEADER = 1

        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<AppModel>() {
            override fun areItemsTheSame(oldItem: AppModel, newItem: AppModel): Boolean = when {
                oldItem is AppModel.App && newItem is AppModel.App ->
                    oldItem.appPackage == newItem.appPackage && oldItem.user == newItem.user

                oldItem is AppModel.PinnedShortcut && newItem is AppModel.PinnedShortcut ->
                    oldItem.identity == newItem.identity

                oldItem is AppModel.PrivateSpaceHeader && newItem is AppModel.PrivateSpaceHeader -> true

                else -> false
            }

            override fun areContentsTheSame(oldItem: AppModel, newItem: AppModel): Boolean =
                oldItem == newItem
        }
    }

    private var autoLaunch = true
    private var isBangSearch = false
    var allowAutoLaunch = true
    private val diacriticsRegex = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val separatorsRegex = Regex("[-_+,.`'\\s\\p{Z}]")
    private val appFilter = createAppFilter()
    private val myUserHandle = android.os.Process.myUserHandle()

    // Focus-app picker: keys ("package|user") currently selected, and a hook fired after every list change
    var focusKeys: Set<String> = emptySet()
    var onListChanged: (() -> Unit)? = null

    // Height of one grid cell in px (0 = wrap content until the screen has been measured)
    var cellHeight = 0

    var appsList: MutableList<AppModel> = mutableListOf()
    var appFilteredList: MutableList<AppModel> = mutableListOf()

    override fun getItemViewType(position: Int): Int {
        return when (appFilteredList.getOrNull(position)) {
            is AppModel.PrivateSpaceHeader -> VIEW_TYPE_PRIVATE_HEADER
            else -> VIEW_TYPE_APP
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_PRIVATE_HEADER -> PrivateSpaceHeaderViewHolder(
                AdapterPrivateSpaceHeaderBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            else -> ViewHolder(
                AdapterAppDrawerBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        try {
            if (appFilteredList.isEmpty() || position == RecyclerView.NO_POSITION) return
            val appModel = appFilteredList[holder.bindingAdapterPosition]
            when (holder) {
                is PrivateSpaceHeaderViewHolder -> {
                    holder.bind(
                        appLabelGravity,
                        privateSpaceToggleListener,
                        privateSpaceSettingsListener,
                    )
                }

                is ViewHolder -> holder.bind(
                    flag,
                    appModel is AppModel.App &&
                        focusKeys.contains(appModel.appPackage + "|" + appModel.user.toString()),
                    appLabelGravity,
                    myUserHandle,
                    appModel,
                    appClickListener,
                    appDeleteListener,
                    appInfoListener,
                    appHideListener,
                    appRenameListener,
                    cellHeight
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun getFilter(): Filter = this.appFilter

    private fun createAppFilter(): Filter {
        return object : Filter() {
            override fun performFiltering(charSearch: CharSequence?): FilterResults {
                isBangSearch = charSearch?.startsWith("!") ?: false
                autoLaunch = allowAutoLaunch && (charSearch?.startsWith(" ")?.not() ?: true)

                val appFilteredList = (if (charSearch.isNullOrBlank()) appsList
                else appsList.filter { app ->
                    app !is AppModel.PrivateSpaceHeader && appLabelMatches(app.appLabel, charSearch)
                } as MutableList<AppModel>)

                val filterResults = FilterResults()
                filterResults.values = appFilteredList
                return filterResults
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                results?.values?.let {
                    val items = it as MutableList<AppModel>
                    appFilteredList = items
                    submitList(appFilteredList) {
                        onListChanged?.invoke()
                        autoLaunch()
                    }
                }
            }
        }
    }

    private fun autoLaunch() {
        try {
            if (itemCount == 1
                && autoLaunch
                && isBangSearch.not()
                && flag == Constants.FLAG_LAUNCH_APP
                && appFilteredList.isNotEmpty()
                && appFilteredList[0] !is AppModel.PrivateSpaceHeader
            ) appClickListener(appFilteredList[0])
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun appLabelMatches(appLabel: String, charSearch: CharSequence): Boolean {
        if (appLabel.contains(charSearch.trim(), true)) return true
        val query = charSearch.normalizeForSearch()
        return query.isNotEmpty() && appLabel.normalizeForSearch().contains(query, true)
    }

    private fun CharSequence.normalizeForSearch(): String =
        Normalizer.normalize(this, Normalizer.Form.NFD)
            .replace(diacriticsRegex, "")
            .replace(separatorsRegex, "")

    fun setAppList(appsList: MutableList<AppModel>) {
        // Add empty app for bottom padding in recyclerview and assign to list
        appsList.add(
            AppModel.App(
                appLabel = "",
                key = null,
                appPackage = "",
                activityClassName = "",
                isNew = false,
                user = android.os.Process.myUserHandle()
            )
        )
        this.appsList = appsList
        this.appFilteredList = appsList
        submitList(appsList) { onListChanged?.invoke() }
    }

    fun launchFirstInList() {
        val first = appFilteredList.firstOrNull { it !is AppModel.PrivateSpaceHeader }
        if (first != null) appClickListener(first)
    }

    class PrivateSpaceHeaderViewHolder(private val binding: AdapterPrivateSpaceHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(
            appLabelGravity: Int,
            toggleListener: () -> Unit,
            settingsListener: () -> Unit,
        ) = with(binding) {
            privateSpaceTitle.gravity = appLabelGravity
            privateSpaceTitle.setOnClickListener { toggleListener() }
            privateSpaceTitle.setOnLongClickListener {
                settingsListener()
                true
            }
        }
    }

    class ViewHolder(private val binding: AdapterAppDrawerBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(
            flag: Int,
            focusMarked: Boolean,
            appLabelGravity: Int,
            myUserHandle: UserHandle,
            appModel: AppModel,
            clickListener: (AppModel) -> Unit,
            appDeleteListener: (AppModel) -> Unit,
            appInfoListener: (AppModel) -> Unit,
            appHideListener: (AppModel, Int) -> Unit,
            appRenameListener: (AppModel, String) -> Unit,
            cellHeight: Int,
        ) = with(binding) {
            // The blank item at the end of the list is only bottom padding
            val isSpacer = appModel.appPackage.isEmpty()

            val params = root.layoutParams
            params.height = when {
                isSpacer -> (cellHeight / 2).coerceAtLeast(1)
                cellHeight > 0 -> cellHeight
                else -> ViewGroup.LayoutParams.WRAP_CONTENT
            }
            root.layoutParams = params

            if (isSpacer) {
                appIcon.visibility = View.GONE
                appTitle.text = ""
                root.setOnClickListener(null)
                root.setOnLongClickListener(null)
                root.isClickable = false
                return@with
            }

            // Clear any left-over letter "flash" from a recycled view
            root.animate().cancel()
            root.scaleX = 1f
            root.scaleY = 1f
            root.foreground = null

            appIcon.visibility = View.VISIBLE
            AppIconLoader.load(appIcon, appModel)

            appTitle.text = buildString {
                append(appModel.appLabel)
                if (appModel.isNew) append(" ✦")
                if (focusMarked) append(" ●")
            }
            // In the focus picker, dim the apps that are not selected
            root.alpha = if (flag == Constants.FLAG_FOCUS_APPS && !focusMarked) 0.4f else 1f

            root.setOnClickListener { clickListener(appModel) }
            root.setOnLongClickListener {
                if (flag != Constants.FLAG_FOCUS_APPS) {
                    showMenu(
                        flag,
                        appModel,
                        appInfoListener,
                        appDeleteListener,
                        appHideListener,
                        appRenameListener
                    )
                }
                true
            }
        }

        private fun showMenu(
            flag: Int,
            appModel: AppModel,
            appInfoListener: (AppModel) -> Unit,
            appDeleteListener: (AppModel) -> Unit,
            appHideListener: (AppModel, Int) -> Unit,
            appRenameListener: (AppModel, String) -> Unit,
        ) {
            val context = binding.root.context
            val isHiddenList = flag == Constants.FLAG_HIDDEN_APPS
            val labels = mutableListOf<String>()
            val actions = mutableListOf<() -> Unit>()

            labels += context.getString(R.string.app_info)
            actions += { appInfoListener(appModel) }
            labels += context.getString(R.string.delete)
            actions += { appDeleteListener(appModel) }
            labels += context.getString(if (isHiddenList) R.string.adapter_show else R.string.adapter_hide)
            actions += { appHideListener(appModel, bindingAdapterPosition) }
            if (!isHiddenList) {
                labels += context.getString(R.string.rename)
                actions += { showRenameDialog(appModel, appRenameListener) }
            }

            AlertDialog.Builder(context)
                .setTitle(appModel.appLabel)
                .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
                .show()
        }

        private fun showRenameDialog(
            appModel: AppModel,
            appRenameListener: (AppModel, String) -> Unit,
        ) {
            val context = binding.root.context
            val input = EditText(context).apply {
                inputType = InputType.TYPE_CLASS_TEXT
                setSingleLine()
                hint = getAppName(context, appModel.appPackage, appModel.user)
                setText(appModel.appLabel)
                setSelectAllOnFocus(true)
            }
            val dialog = AlertDialog.Builder(context)
                .setTitle(R.string.rename)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val label = input.text.toString().trim()
                    // Empty label resets to the original app name
                    appRenameListener(
                        appModel,
                        label.ifBlank { getAppName(context, appModel.appPackage, appModel.user) }
                    )
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            dialog.setView(input, 48, 16, 48, 0)
            dialog.show()
        }

        private fun getAppName(context: Context, appPackage: String, user: UserHandle): String {
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            return try {
                val activityList = launcherApps.getActivityList(appPackage, user)
                if (activityList.isNotEmpty()) {
                    activityList.first().label.toString()
                } else {
                    val packageManager = context.packageManager
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(appPackage, 0)
                    ).toString()
                }
            } catch (_: Exception) {
                "" // As a fallback, display an empty string.
            }
        }
    }
}
