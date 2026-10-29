package io.github.romanvht.byedpi.data

/**
 * The list of apps that are blocked in some countries, read from assets/blocked_apps.json
 * (or a newer copy downloaded from the project repository).
 *
 * Optional fields are nullable because the list is read with Gson, which does not use
 * Kotlin default values.
 */
data class AppCatalog(
    val version: Int = 0,
    /** Date of the last update, yyyy-MM-dd. */
    val updated: String? = null,
    /** Countries (ISO 3166 codes) where Google Play is not available. */
    val noPlay: List<String>? = null,
    val apps: List<CatalogApp>? = null,
)

data class CatalogApp(
    val id: String? = null,
    val name: String? = null,
    val category: String? = null,
    /** Every package name the app is known by. The first one is the one its own download has. */
    val packages: List<String>? = null,
    /** Package name on Google Play, or null when the app is not there. */
    val play: String? = null,
    val website: String? = null,
    /** Listed for every country, under the helpful tools. */
    val tool: Boolean? = null,
    val download: CatalogDownload? = null,
    /** Country code to a status: "blocked" or "restricted" (slowed down or partly blocked). */
    val regions: Map<String, String>? = null,
)

data class CatalogDownload(
    /** "apk" (direct link), "json" (Signal-style update file), "fdroid" or "page" (link found on a web page). */
    val type: String? = null,
    val url: String? = null,
    /** For "page": a regular expression that finds the APK link on the page. */
    val pattern: String? = null,
)

/** A checked catalog entry with all the required fields. */
data class BlockedApp(
    val id: String,
    val name: String,
    val category: String,
    val packages: List<String>,
    val playPackage: String?,
    val website: String?,
    val tool: Boolean,
    val download: CatalogDownload?,
    val regions: Map<String, String>,
)
