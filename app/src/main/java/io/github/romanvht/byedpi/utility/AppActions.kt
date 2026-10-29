package io.github.romanvht.byedpi.utility

import io.github.romanvht.byedpi.data.BlockedApp

/** What can be done with an app in the blocked apps list. */
enum class AppAction { Open, Install, InstallDownloaded, Cancel, Play, Aurora, Website }

object AppActions {

    /**
     * The main button. An official download is preferred, because it also works when the app is
     * missing from Google Play. Without Google Play in the country, Aurora Store gets the app instead.
     */
    fun primary(app: BlockedApp, installed: Boolean, state: AppDownloads.State?, noPlay: Boolean): AppAction? = when {
        state is AppDownloads.State.Ready -> AppAction.InstallDownloaded
        state != null && state !is AppDownloads.State.Failed -> AppAction.Cancel
        installed -> AppAction.Open
        app.download != null -> AppAction.Install
        app.playPackage != null -> if (noPlay) AppAction.Aurora else AppAction.Play
        app.website != null -> AppAction.Website
        else -> null
    }

    /** The other ways to get the app, for the menu next to the main button. */
    fun others(app: BlockedApp, primary: AppAction?): List<AppAction> = buildList {
        if (app.playPackage != null && primary != AppAction.Play) add(AppAction.Play)
        if (app.playPackage != null && primary != AppAction.Aurora) add(AppAction.Aurora)
        if (app.website != null && primary != AppAction.Website) add(AppAction.Website)
    }
}
