package io.github.romanvht.byedpi.activities

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.adapters.BlockedAppAdapter
import io.github.romanvht.byedpi.adapters.BlockedAppAdapter.Row
import io.github.romanvht.byedpi.data.AppStatus
import io.github.romanvht.byedpi.data.BlockedApp
import io.github.romanvht.byedpi.data.Mode
import io.github.romanvht.byedpi.databinding.ActivityBlockedAppsBinding
import io.github.romanvht.byedpi.services.ServiceManager
import io.github.romanvht.byedpi.services.TestService
import io.github.romanvht.byedpi.services.appStatus
import io.github.romanvht.byedpi.utility.AppAction
import io.github.romanvht.byedpi.utility.AppCatalogUtils
import io.github.romanvht.byedpi.utility.AppDownloads
import io.github.romanvht.byedpi.utility.ApkInstallUtils
import io.github.romanvht.byedpi.utility.NetUtils
import io.github.romanvht.byedpi.utility.RegionUtils
import io.github.romanvht.byedpi.utility.getPreferences
import io.github.romanvht.byedpi.utility.getStringNotNull
import io.github.romanvht.byedpi.utility.mode
import kotlinx.coroutines.launch
import java.text.Collator
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale

/** Apps that are blocked in the user's country, with ways to install them. */
class BlockedAppsActivity : BaseActivity(), BlockedAppAdapter.Listener {
    private lateinit var binding: ActivityBlockedAppsBinding
    private lateinit var adapter: BlockedAppAdapter
    private var catalog = AppCatalogUtils.Catalog.EMPTY

    /** App waiting for the permission to install apps. */
    private var pendingInstall: String? = null

    /** Downloads started on this screen; the installer opens when they finish. */
    private val autoInstall = mutableSetOf<String>()

    /** Apps that were installed when the screen was last shown, to notice new ones. */
    private var knownInstalled: Set<String>? = null
    private var updating = false

    companion object {
        private const val STATE_PENDING = "pending_install"
        private const val STATE_AUTO_INSTALL = "auto_install"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBlockedAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupToolbar()
        supportActionBar?.setTitle(R.string.blocked_apps)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        pendingInstall = savedInstanceState?.getString(STATE_PENDING)
        savedInstanceState?.getStringArrayList(STATE_AUTO_INSTALL)?.let { autoInstall.addAll(it) }

        adapter = BlockedAppAdapter(this)
        binding.list.adapter = adapter
        binding.list.itemAnimator = null

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isTaskRoot) startActivity(Intent(this@BlockedAppsActivity, MainActivity::class.java))
                finish()
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                AppDownloads.states.collect { states ->
                    adapter.setDownloads(states)
                    handleFinishedDownloads(states)
                }
            }
        }

        AppDownloads.cleanUp(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PENDING, pendingInstall)
        outState.putStringArrayList(STATE_AUTO_INSTALL, ArrayList(autoInstall))
    }

    override fun onResume() {
        super.onResume()
        refresh()

        // Back from the "install unknown apps" settings
        val pending = pendingInstall
        if (pending != null && ApkInstallUtils.canInstallApps(this)) {
            pendingInstall = null
            catalog.apps.firstOrNull { it.id == pending }?.let { startDownload(it) }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            onBackPressedDispatcher.onBackPressed()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun refresh() {
        catalog = AppCatalogUtils.load(this)
        val country = RegionUtils.currentCountry(this)
        val countryName = country?.let { RegionUtils.countryName(this, it) }
        val apps = AppCatalogUtils.forCountry(catalog, country)

        val installed = catalog.apps
            .filter { ApkInstallUtils.installedPackage(this, it.packages) != null }
            .map { it.id }
            .toSet()
        onInstalledChanged(installed)

        val notices = buildList {
            if (!NetUtils.usesProxy(this@BlockedAppsActivity)) add(getString(R.string.blocked_apps_connect_hint))
            if (apps.noPlay && countryName != null) add(getString(R.string.blocked_apps_no_play, countryName))
        }

        val rows = buildList {
            add(
                Row.Header(
                    countryName = countryName ?: getString(R.string.blocked_apps_country_unknown),
                    source = getString(
                        if (RegionUtils.chosenCountry(this@BlockedAppsActivity) != null) R.string.blocked_apps_country_chosen
                        else R.string.blocked_apps_country_detected
                    ),
                    notice = notices.joinToString("\n\n").ifEmpty { null },
                )
            )

            if (countryName != null) {
                add(Row.Section(getString(R.string.blocked_apps_section_country, countryName)))
                if (apps.blocked.isEmpty()) {
                    add(Row.Message(getString(R.string.blocked_apps_none, countryName)))
                }
                for ((app, status) in apps.blocked) {
                    add(Row.App(app, status, categoryLabel(app), app.id in installed, apps.noPlay))
                }
            } else {
                add(Row.Message(getString(R.string.blocked_apps_none_unknown)))
            }

            if (apps.tools.isNotEmpty()) {
                add(Row.Section(getString(R.string.blocked_apps_section_tools)))
                for (app in apps.tools) {
                    add(Row.App(app, null, toolDescription(app) ?: categoryLabel(app), app.id in installed, apps.noPlay))
                }
            }

            add(Row.Footer(getString(R.string.blocked_apps_footer, formatDate(catalog.updated))))
        }
        adapter.submitList(rows)
    }

    /** Removes finished downloads of installed apps and offers to route new apps through the VPN. */
    private fun onInstalledChanged(installed: Set<String>) {
        val before = knownInstalled
        knownInstalled = installed

        for (id in installed) {
            if (AppDownloads.states.value[id] is AppDownloads.State.Ready) AppDownloads.clear(this, id, deleteFile = true)
        }
        if (before == null) return

        val app = catalog.apps.firstOrNull { it.id in installed && it.id !in before } ?: return
        val packageName = ApkInstallUtils.installedPackage(this, app.packages) ?: return
        val prefs = getPreferences()
        val selected = prefs.getStringSet("selected_apps", emptySet()).orEmpty()
        val onlySelected = prefs.mode() == Mode.VPN && prefs.getStringNotNull("applist_type", "disable") == "whitelist"
        if (!onlySelected || packageName in selected) return

        Snackbar.make(binding.root, getString(R.string.app_not_in_vpn, app.name, getString(R.string.app_name)), Snackbar.LENGTH_LONG)
            .setAction(R.string.app_add_to_vpn) {
                prefs.edit { putStringSet("selected_apps", selected + packageName) }
                if (appStatus.first == AppStatus.Running && !TestService.isRunning) ServiceManager.restart(this, Mode.VPN)
                showMessage(getString(R.string.app_added_to_vpn, app.name, getString(R.string.app_name)))
            }
            .show()
    }

    private fun handleFinishedDownloads(states: Map<String, AppDownloads.State>) {
        for ((id, state) in states) {
            val app = catalog.apps.firstOrNull { it.id == id } ?: continue
            when (state) {
                is AppDownloads.State.Ready -> if (autoInstall.remove(id)) install(app, state)
                is AppDownloads.State.Failed -> {
                    autoInstall.remove(id)
                    AppDownloads.clear(this, id, deleteFile = true)
                    val message = getString(
                        if (state.wrongFile) R.string.app_download_wrong_file else R.string.app_download_failed,
                        app.name
                    )
                    val snackbar = Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
                    if (!state.wrongFile && appStatus.first != AppStatus.Running) {
                        snackbar.setAction(R.string.vpn_connect) { connect() }
                    }
                    snackbar.show()
                }
                else -> {}
            }
        }
    }

    // region Listener

    override fun onChangeCountry() {
        val collator = Collator.getInstance(currentLocale())
        val countries = (catalog.apps.flatMap { it.regions.keys } + catalog.noPlay)
            .distinct()
            .sortedWith(compareBy(collator) { RegionUtils.countryName(this, it) })
        val detected = RegionUtils.detectCountry(this)
        val automatic = detected?.let { getString(R.string.blocked_apps_country_auto, RegionUtils.countryName(this, it)) }
            ?: getString(R.string.blocked_apps_country_auto_unknown)
        val names = (listOf(automatic) + countries.map { RegionUtils.countryName(this, it) }).toTypedArray()
        val chosen = RegionUtils.chosenCountry(this)
        val checked = if (chosen == null) 0 else countries.indexOf(chosen) + 1

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.blocked_apps_country)
            .setSingleChoiceItems(names, checked) { dialog, which ->
                dialog.dismiss()
                RegionUtils.setChosenCountry(this, if (which == 0) null else countries[which - 1])
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onAction(app: BlockedApp, action: AppAction) {
        when (action) {
            AppAction.Open -> {
                val packageName = ApkInstallUtils.installedPackage(this, app.packages)
                if (packageName == null || !ApkInstallUtils.launch(this, packageName)) refresh()
            }
            AppAction.Install -> startDownload(app)
            AppAction.InstallDownloaded -> (AppDownloads.states.value[app.id] as? AppDownloads.State.Ready)?.let { install(app, it) }
            AppAction.Cancel -> {
                autoInstall.remove(app.id)
                AppDownloads.cancel(this, app.id)
            }
            AppAction.Play -> app.playPackage?.let {
                if (!ApkInstallUtils.openStorePage(this, it, aurora = false)) showMessage(getString(R.string.app_store_unavailable))
            }
            AppAction.Aurora -> app.playPackage?.let { openInAurora(it) }
            AppAction.Website -> app.website?.let {
                if (!ApkInstallUtils.openWebsite(this, it)) showMessage(getString(R.string.no_browser))
            }
        }
    }

    override fun onMore(app: BlockedApp, actions: List<AppAction>, anchor: View) {
        val popup = PopupMenu(this, anchor)
        actions.forEachIndexed { index, action ->
            popup.menu.add(0, index, index, BlockedAppAdapter.label(action))
        }
        popup.setOnMenuItemClickListener { item ->
            actions.getOrNull(item.itemId)?.let { onAction(app, it) }
            true
        }
        popup.show()
    }

    override fun onUpdateList() {
        if (updating) return
        updating = true
        lifecycleScope.launch {
            val result = AppCatalogUtils.update(this@BlockedAppsActivity)
            updating = false
            showMessage(
                getString(
                    when (result) {
                        AppCatalogUtils.UpdateResult.Updated -> R.string.blocked_apps_updated
                        AppCatalogUtils.UpdateResult.UpToDate -> R.string.blocked_apps_up_to_date
                        AppCatalogUtils.UpdateResult.Failed -> R.string.blocked_apps_update_failed
                    }
                )
            )
            refresh()
        }
    }

    // endregion

    private fun startDownload(app: BlockedApp) {
        if (TestService.isRunning) {
            showMessage(getString(R.string.test_unavailable))
            return
        }
        if (!ApkInstallUtils.canInstallApps(this)) {
            askInstallPermission(app)
            return
        }
        autoInstall.add(app.id)
        AppDownloads.start(this, app)
    }

    private fun askInstallPermission(app: BlockedApp) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.app_install_permission_title)
            .setMessage(getString(R.string.app_install_permission_text, getString(R.string.app_name)))
            .setPositiveButton(R.string.app_install_permission_open) { _, _ ->
                pendingInstall = app.id
                if (!ApkInstallUtils.openInstallPermissionSettings(this)) {
                    pendingInstall = null
                    showMessage(getString(R.string.app_install_failed))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun install(app: BlockedApp, state: AppDownloads.State.Ready) {
        if (!state.file.isFile) {
            AppDownloads.clear(this, app.id, deleteFile = false)
            return
        }
        if (!ApkInstallUtils.install(this, state.file)) showMessage(getString(R.string.app_install_failed))
    }

    private fun openInAurora(packageName: String) {
        if (ApkInstallUtils.openStorePage(this, packageName, aurora = true)) return
        val aurora = catalog.apps.firstOrNull { ApkInstallUtils.AURORA_STORE in it.packages }
        val snackbar = Snackbar.make(binding.root, R.string.app_aurora_needed, Snackbar.LENGTH_LONG)
        if (aurora != null) snackbar.setAction(R.string.app_action_install) { startDownload(aurora) }
        snackbar.show()
    }

    private fun connect() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_CONNECT, true)
        )
    }

    private fun categoryLabel(app: BlockedApp): String {
        val label = when (app.category) {
            "messenger" -> R.string.app_category_messenger
            "social" -> R.string.app_category_social
            "video" -> R.string.app_category_video
            "game" -> R.string.app_category_game
            "reading" -> R.string.app_category_reading
            "knowledge" -> R.string.app_category_knowledge
            "browser" -> R.string.app_category_browser
            "vpn" -> R.string.app_category_vpn
            "store" -> R.string.app_category_store
            else -> return ""
        }
        return getString(label)
    }

    private fun toolDescription(app: BlockedApp): String? {
        val description = when (app.id) {
            "psiphon" -> R.string.tool_psiphon
            "tor" -> R.string.tool_tor
            "protonvpn" -> R.string.tool_protonvpn
            "aurora" -> R.string.tool_aurora
            "fdroid" -> R.string.tool_fdroid
            else -> return null
        }
        return getString(description)
    }

    private fun formatDate(date: String): String = try {
        val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(date)
        if (parsed == null) date else DateFormat.getDateInstance(DateFormat.MEDIUM, currentLocale()).format(parsed)
    } catch (e: Exception) {
        date
    }

    private fun currentLocale(): Locale =
        androidx.core.os.ConfigurationCompat.getLocales(resources.configuration)[0] ?: Locale.getDefault()

    private fun showMessage(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }
}
