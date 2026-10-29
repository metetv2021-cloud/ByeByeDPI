package io.github.romanvht.byedpi.utility

import io.github.romanvht.byedpi.data.BlockedApp
import io.github.romanvht.byedpi.data.CatalogDownload
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AppActionsTest {

    private fun app(download: Boolean = false, play: Boolean = true, website: Boolean = true) = BlockedApp(
        id = "app",
        name = "App",
        category = "messenger",
        packages = listOf("com.example.app"),
        playPackage = if (play) "com.example.app" else null,
        website = if (website) "https://example.com" else null,
        tool = false,
        download = if (download) CatalogDownload(type = "apk", url = "https://telegram.org/app.apk") else null,
        regions = emptyMap(),
    )

    @Test
    fun officialDownloadComesFirst() {
        assertEquals(AppAction.Install, AppActions.primary(app(download = true), installed = false, state = null, noPlay = false))
    }

    @Test
    fun installedAppsOpen() {
        assertEquals(AppAction.Open, AppActions.primary(app(download = true), installed = true, state = null, noPlay = false))
    }

    @Test
    fun downloadStates() {
        val app = app(download = true)
        assertEquals(AppAction.Cancel, AppActions.primary(app, false, AppDownloads.State.Downloading(10, 100), false))
        assertEquals(AppAction.Cancel, AppActions.primary(app, false, AppDownloads.State.Preparing, false))
        assertEquals(AppAction.InstallDownloaded, AppActions.primary(app, false, AppDownloads.State.Ready(File("a.apk")), false))
        assertEquals(AppAction.Install, AppActions.primary(app, false, AppDownloads.State.Failed(wrongFile = false), false))
    }

    @Test
    fun withoutGooglePlayAuroraStoreIsUsed() {
        assertEquals(AppAction.Play, AppActions.primary(app(), installed = false, state = null, noPlay = false))
        assertEquals(AppAction.Aurora, AppActions.primary(app(), installed = false, state = null, noPlay = true))
        assertEquals(AppAction.Website, AppActions.primary(app(play = false), installed = false, state = null, noPlay = false))
    }

    @Test
    fun otherActionsSkipThePrimaryOne() {
        assertEquals(listOf(AppAction.Aurora, AppAction.Website), AppActions.others(app(), AppAction.Play))
        assertEquals(listOf(AppAction.Play, AppAction.Aurora, AppAction.Website), AppActions.others(app(download = true), AppAction.Install))
        assertEquals(emptyList<AppAction>(), AppActions.others(app(play = false), AppAction.Website))
    }
}
