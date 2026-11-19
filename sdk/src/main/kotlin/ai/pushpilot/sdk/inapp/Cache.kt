package ai.pushpilot.sdk.inapp

import ai.pushpilot.sdk.log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The page cache (section 2): one file per message and HTML version, holding the page with every
 * asset already inlined as a `data:` URI. A file exists only once the page and all its assets
 * downloaded, so its presence is what makes a message ready. Used on the SDK thread, read on main.
 */
internal class InAppCache(
    private val dir: File,
    private val slow: Boolean,
    private val download: (String) -> Download?,
    private val base64: (ByteArray) -> String,
) {
    /** Drops files of messages no longer listed (or of an older version), then caches what is missing. */
    fun update(messages: List<InAppMessage>) {
        dir.mkdirs()
        val keep = messages.map { cacheName(it, slow) }.toSet()
        dir.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
        for (m in messages) if (!File(dir, cacheName(m, slow)).exists()) cache(m)
    }

    /** Downloads one message's page and assets and writes the inlined page; on any failure writes nothing, so the next fetch retries. */
    private fun cache(m: InAppMessage) {
        val page = download(pageUrl(m, slow)) ?: return log("in-app page not downloaded; retried on the next fetch")
        val assets = HashMap<String, Download>()
        for (url in m.assets.distinct()) {
            assets[url] = download(url) ?: return log("in-app asset not downloaded; retried on the next fetch")
        }
        val html = inlineAssets(String(page.bytes, Charsets.UTF_8), assets, base64)
        val file = File(dir, cacheName(m, slow))
        val temp = File(dir, file.name + ".tmp")
        try {
            temp.writeText(html)
            if (!temp.renameTo(file)) throw IOException("rename failed")
        } catch (e: IOException) {
            temp.delete()
            log("in-app page not cached: ${e.javaClass.simpleName}")
        }
    }

    /** The cached page of [m], or null when it is not cached (or the system cleared the cache). */
    fun read(m: InAppMessage): String? = try {
        File(dir, cacheName(m, slow)).takeIf { it.exists() }?.readText()
    } catch (e: IOException) {
        null
    }

    /** Deletes every cached page. */
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }
}

/** Largest page or asset the cache downloads (chosen during the build). */
internal const val MAX_DOWNLOAD_BYTES = 10 * 1024 * 1024

/**
 * GETs [url] without the SDK key (pages and assets live on the content host, not the API); null
 * unless it is http(s) and answers 200 within the size cap. Cleartext follows the app's own policy.
 */
internal fun httpDownload(url: String): Download? = try {
    if (!url.startsWith("https://") && !url.startsWith("http://")) {
        null
    } else {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            if (connection.responseCode != 200 || connection.contentLength > MAX_DOWNLOAD_BYTES) {
                null
            } else {
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                connection.inputStream.use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        if (out.size() > MAX_DOWNLOAD_BYTES) throw IOException("too large")
                    }
                }
                Download(out.toByteArray(), connection.contentType)
            }
        } finally {
            connection.disconnect()
        }
    }
} catch (e: Exception) {
    log("in-app download failed: ${e.javaClass.simpleName}")
    null
}
