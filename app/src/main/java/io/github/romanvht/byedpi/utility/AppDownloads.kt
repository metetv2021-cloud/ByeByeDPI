package io.github.romanvht.byedpi.utility

import android.content.Context
import android.util.Log
import com.google.gson.JsonParser
import io.github.romanvht.byedpi.data.BlockedApp
import io.github.romanvht.byedpi.data.CatalogDownload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Downloads apps from their official sources and checks them before they are installed.
 * Downloads keep running when the screen is closed or rotated.
 */
object AppDownloads {
    private const val TAG = "AppDownloads"
    private const val ATTEMPTS = 4

    sealed interface State {
        data object Preparing : State
        data class Downloading(val bytes: Long, val total: Long) : State
        data object Checking : State
        data class Ready(val file: File) : State
        data class Failed(val wrongFile: Boolean) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()
    private val mutableStates = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = mutableStates.asStateFlow()

    private class Source(val url: URL, val sha256: String?)

    fun start(context: Context, app: BlockedApp) {
        val download = app.download ?: return
        val appContext = context.applicationContext
        synchronized(jobs) {
            if (jobs[app.id]?.isActive == true) return
            setState(app.id, State.Preparing)
            jobs[app.id] = scope.launch {
                val state = try {
                    val file = download(appContext, app, download)
                    State.Ready(file)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: WrongFileException) {
                    Log.w(TAG, "Wrong file for ${app.id}: ${e.message}")
                    State.Failed(wrongFile = true)
                } catch (e: Exception) {
                    Log.w(TAG, "Download of ${app.id} failed", e)
                    State.Failed(wrongFile = false)
                }
                setState(app.id, state)
            }
        }
    }

    fun cancel(context: Context, appId: String) {
        synchronized(jobs) { jobs.remove(appId)?.cancel() }
        apkFile(context, appId)?.delete()
        setState(appId, null)
    }

    /** Forgets a finished download, for example after the app was installed. */
    fun clear(context: Context, appId: String, deleteFile: Boolean) {
        synchronized(jobs) {
            if (jobs[appId]?.isActive == true) return
            jobs.remove(appId)
        }
        if (deleteFile) apkFile(context, appId)?.delete()
        setState(appId, null)
    }

    /** Deletes old downloaded files that are not in use. */
    fun cleanUp(context: Context) {
        val dir = ApkInstallUtils.apkDir(context) ?: return
        val busy = states.value.keys
        val dayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        dir.listFiles()?.forEach { file ->
            if (file.nameWithoutExtension !in busy && file.lastModified() < dayAgo) file.delete()
        }
    }

    private fun setState(appId: String, state: State?) {
        mutableStates.update { if (state == null) it - appId else it + (appId to state) }
    }

    private fun apkFile(context: Context, appId: String): File? =
        ApkInstallUtils.apkDir(context)?.let { File(it, "$appId.apk") }

    private suspend fun download(context: Context, app: BlockedApp, download: CatalogDownload): File {
        val file = apkFile(context, app.id) ?: throw IOException("No storage for downloads")
        file.parentFile?.mkdirs()
        file.delete()

        val source = resolve(context, app, download)
        transfer(context, app.id, source.url, file)

        setState(app.id, State.Checking)
        if (source.sha256 != null && !sha256(file).equals(source.sha256, ignoreCase = true)) {
            file.delete()
            throw WrongFileException("checksum does not match")
        }
        val packageName = ApkInstallUtils.packageNameOf(context, file)
        if (packageName == null || packageName !in app.packages) {
            file.delete()
            throw WrongFileException("package $packageName")
        }
        return file
    }

    /** Finds the link to the APK file. */
    private fun resolve(context: Context, app: BlockedApp, download: CatalogDownload): Source {
        return when (download.type) {
            AppCatalogUtils.DOWNLOAD_APK -> Source(URL(download.url), null)

            AppCatalogUtils.DOWNLOAD_JSON -> {
                // Signal's update file: {"url": "...apk", "sha256sum": "..."}
                val json = JsonParser.parseString(NetUtils.readText(context, URL(download.url), 64 * 1024)).asJsonObject
                val url = json.get("url")?.asString
                if (!AppCatalogUtils.isTrustedUrl(url)) throw IOException("Untrusted link in the update file")
                Source(URL(url), json.get("sha256sum")?.asString?.takeIf { it.length == 64 })
            }

            AppCatalogUtils.DOWNLOAD_FDROID -> {
                val packageName = app.packages.first()
                val json = JsonParser.parseString(
                    NetUtils.readText(context, URL("https://f-droid.org/api/v1/packages/$packageName"), 64 * 1024)
                ).asJsonObject
                val versionCode = json.get("suggestedVersionCode")?.asLong ?: throw IOException("No version on F-Droid")
                Source(URL("https://f-droid.org/repo/${packageName}_$versionCode.apk"), null)
            }

            AppCatalogUtils.DOWNLOAD_PAGE -> {
                // The link changes with every version, so it is read from the official download page.
                val page = NetUtils.readText(context, URL(download.url), 2 * 1024 * 1024)
                val link = Regex(download.pattern.orEmpty()).find(page)?.value
                    ?.replace("&amp;", "&")
                    ?: throw IOException("No download link on the page")
                if (!AppCatalogUtils.isTrustedUrl(link)) throw IOException("Untrusted link on the page")
                Source(URL(link), null)
            }

            else -> throw IOException("Unknown download type ${download.type}")
        }
    }

    /** Downloads the file. Interrupted downloads continue where they stopped when the server allows it. */
    private suspend fun transfer(context: Context, appId: String, url: URL, file: File) {
        // Retries use the address after redirects, so that they continue the same file.
        var source = url
        var attempt = 0
        while (true) {
            try {
                transferOnce(context, appId, source, file) { source = it }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                attempt++
                if (attempt >= ATTEMPTS) throw e
                Log.w(TAG, "Download interrupted, trying again ($attempt)", e)
                delay(2000L * attempt)
            }
        }
    }

    private suspend fun transferOnce(context: Context, appId: String, url: URL, file: File, onRedirect: (URL) -> Unit) {
        val existing = if (file.exists()) file.length() else 0L
        val connection = NetUtils.open(context, url)
        try {
            if (existing > 0) connection.setRequestProperty("Range", "bytes=$existing-")
            val code = connection.responseCode
            onRedirect(connection.url)
            // The whole file was already there; the checks after the download find a broken one.
            if (code == 416 && existing > 0) return
            if (code != 200 && code != 206) throw IOException("HTTP $code")

            val resumed = code == 206 && existing > 0
            val length = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            val total = if (length < 0) -1L else if (resumed) existing + length else length
            var bytes = if (resumed) existing else 0L

            connection.inputStream.use { input ->
                FileOutputStream(file, resumed).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        bytes += read
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 250) {
                            lastReport = now
                            setState(appId, State.Downloading(bytes, total))
                        }
                    }
                }
            }

            if (total > 0 && bytes != total) throw IOException("Download incomplete: $bytes of $total")
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class WrongFileException(message: String) : Exception(message)
}
