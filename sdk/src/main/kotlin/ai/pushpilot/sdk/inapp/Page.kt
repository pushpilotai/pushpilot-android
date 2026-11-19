package ai.pushpilot.sdk.inapp

import org.json.JSONObject

/** A rectangle in CSS px, for `pushpilot.target`. */
internal data class Frame(val x: Double, val y: Double, val width: Double, val height: Double) {
    /** The `{x, y, width, height}` object the page reads. */
    fun json(): JSONObject = JSONObject().put("x", x).put("y", y).put("width", width).put("height", height)
}

/** Safe-area insets in CSS px, for `pushpilot.insets`. */
internal data class Insets(val top: Double, val right: Double, val bottom: Double, val left: Double) {
    /** The `{top, right, bottom, left}` object the page reads. */
    fun json(): JSONObject = JSONObject().put("top", top).put("right", right).put("bottom", bottom).put("left", left)
}

/** Converts a frame in device pixels to CSS px. */
internal fun cssFrame(x: Int, y: Int, width: Int, height: Int, density: Float): Frame =
    Frame(x / density.toDouble(), y / density.toDouble(), width / density.toDouble(), height / density.toDouble())

/** `pushpilot.posture` on Android: always `flat` (no Jetpack WindowManager), with the screen size in CSS px. */
internal fun posture(widthCss: Double, heightCss: Double): JSONObject =
    JSONObject().put("state", "flat").put("screen", JSONObject().put("width", widthCss).put("height", heightCss))

/** A downloaded file: its bytes and media type. */
internal class Download(val bytes: ByteArray, val contentType: String?)

/** Slow phone (I9, chosen during the build): Android's low-RAM flag, or under 3 GiB of total memory. */
internal fun isSlowPhone(lowRam: Boolean, totalMemoryBytes: Long): Boolean = lowRam || totalMemoryBytes < 3L * 1024 * 1024 * 1024

/** Whether this phone uses [message]'s light page: a slow phone and a light page to use. */
internal fun usesLight(message: InAppMessage, slow: Boolean) = slow && message.lightHtmlUrl != null

/** The page URL to download for [message]. */
internal fun pageUrl(message: InAppMessage, slow: Boolean) = if (usesLight(message, slow)) message.lightHtmlUrl!! else message.htmlUrl

/** The cache file name, keyed by message id and HTML version (and light), so a new version gets a new file. */
internal fun cacheName(message: InAppMessage, slow: Boolean) =
    "${message.id}-v${message.htmlVersion}${if (usesLight(message, slow)) "-light" else ""}.html"

/** A media type that is safe to write into a data URI: `type/subtype` in plain characters. */
private val MEDIA_TYPE = Regex("[a-z0-9.+-]+/[a-z0-9.+-]+")

/** File types the templates use, for servers that send no usable Content-Type. */
private val BY_EXTENSION = mapOf(
    "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif",
    "webp" to "image/webp", "svg" to "image/svg+xml", "avif" to "image/avif",
    "woff2" to "font/woff2", "woff" to "font/woff", "ttf" to "font/ttf", "otf" to "font/otf",
    "mp4" to "video/mp4", "webm" to "video/webm", "mp3" to "audio/mpeg", "json" to "application/json",
)

/** The media type of a download: its Content-Type without parameters, else guessed from the URL's extension. */
internal fun mediaType(contentType: String?, url: String): String {
    val header = contentType?.substringBefore(';')?.trim()?.lowercase()
    if (header != null && MEDIA_TYPE.matches(header) && header != "application/octet-stream") return header
    val extension = url.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('.', "").lowercase()
    return BY_EXTENSION[extension] ?: "application/octet-stream"
}

/**
 * Replaces every occurrence of each asset URL in [html] with a `data:` URI of its bytes. Longer URLs
 * go first, so one that starts with a shorter one is not cut in half. [base64] is the platform's encoder.
 */
internal fun inlineAssets(html: String, assets: Map<String, Download>, base64: (ByteArray) -> String): String =
    assets.keys.sortedByDescending { it.length }.fold(html) { page, url ->
        val asset = assets.getValue(url)
        page.replace(url, "data:${mediaType(asset.contentType, url)};base64,${base64(asset.bytes)}")
    }

/** An allowed origin as CSP may carry it: https, a host (optionally `*.`), a port; nothing that could end the policy. */
private val ORIGIN = Regex("""https://(\*\.)?[A-Za-z0-9.-]+(:[0-9]+)?/?""")

/**
 * The content security policy of section 4. `form-action 'none'` is added (chosen during the build):
 * on Android a form POST never reaches shouldOverrideUrlLoading, and form-action does not fall back to default-src.
 */
internal fun contentSecurityPolicy(origins: List<String>): String {
    val connect = origins.filter { ORIGIN.matches(it) }.joinToString(" ").ifEmpty { "'none'" }
    return "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; " +
        "font-src data:; media-src data: blob:; connect-src $connect; form-action 'none'"
}

/** The bridge script of section 5, with the SDK's values filled in; posts `{type, value}` to `PushPilotNative`. */
internal fun bridgeScript(insets: Insets, target: Frame?, light: Boolean, posture: JSONObject): String {
    val values = JSONObject()
        .put("insets", insets.json())
        .put("target", target?.json() ?: JSONObject.NULL)
        .put("light", light)
        .put("posture", posture)
    // org.json escapes "/", so no value can close the script element.
    return "<script>(function(){var v=$values,p=v.posture,f=[];" +
        "function s(t,x){try{PushPilotNative.postMessage(JSON.stringify({type:t,value:x==null?\"\":String(x)}))}catch(e){}}" +
        "Object.defineProperty(window,\"pushpilot\",{enumerable:true,value:Object.freeze({" +
        "close:function(){s(\"close\")},open:function(u){s(\"open\",u)},haptic:function(k){s(\"haptic\",k)}," +
        "track:function(b){s(\"track\",b)},insets:Object.freeze(v.insets),island:null," +
        "target:v.target&&Object.freeze(v.target),platform:\"android\",light:v.light," +
        "get posture(){return p}," +
        "onPosture:function(fn){if(typeof fn==\"function\"){f.push(fn);try{fn(p)}catch(e){}}}," +
        "_posture:function(n){p=n;f.forEach(function(fn){try{fn(n)}catch(e){}});" +
        "try{window.dispatchEvent(new CustomEvent(\"pushpilotposture\",{detail:n}))}catch(e){}}" +
        "})})})();</script>"
}

/** The JavaScript the SDK runs on a size or configuration change. */
internal fun postureUpdate(posture: JSONObject) = "window.pushpilot&&window.pushpilot._posture($posture)"

/** A doctype and an `<html>` start tag at the top of the page, with the whitespace around them. */
private val LEAD = Regex("""^\uFEFF?\s*(<!doctype[^>]*>\s*)?(<html(\s[^>]*)?>\s*)?""", RegexOption.IGNORE_CASE)

/** A `<head>` start tag, with or without attributes, but not `<header>`. */
private val HEAD = Regex("""<head(\s[^>]*)?>""", RegexOption.IGNORE_CASE)

/**
 * Inserts the CSP meta and the bridge script right after `<head>`. Only a `<head>` that follows the
 * doctype and `<html>` directly counts, so one inside a comment or after a script cannot push them
 * below page code. Without one they go after the doctype and `<html>` (chosen during the build: the
 * very start would come before the doctype and switch the page to quirks mode).
 */
internal fun preparePage(html: String, origins: List<String>, bridge: String): String {
    val lead = LEAD.find(html)?.range?.let { it.last + 1 } ?: 0
    val at = HEAD.matchAt(html, lead)?.let { it.range.last + 1 } ?: lead
    val meta = "<meta http-equiv=\"Content-Security-Policy\" content=\"${contentSecurityPolicy(origins)}\">"
    return html.substring(0, at) + meta + bridge + html.substring(at)
}

/** URL schemes `pushpilot.open` refuses (section 5, plus `vbscript:`, which the backend refuses too). */
private val REFUSED_SCHEMES = setOf("javascript", "data", "file", "blob", "vbscript")

/** A URL scheme: a letter, then letters, digits, `+`, `-` or `.`. */
private val SCHEME = Regex("[A-Za-z][A-Za-z0-9+.-]*")

/** True when `pushpilot.open` may hand [url] to the app: it has a scheme, and not a refused one. */
internal fun isOpenable(url: String): Boolean {
    // Browsers ignore control characters and spaces in a scheme ("java\tscript:"), so they are ignored here too.
    val scheme = url.filter { it > ' ' }.substringBefore(':', "")
    return SCHEME.matches(scheme) && scheme.lowercase() !in REFUSED_SCHEMES
}

/** Most `track` calls kept per showing (chosen during the build), so a page in a loop cannot flood the queue. */
internal const val MAX_CLICKS_PER_SHOWING = 50

/** A `track` button id the server accepts: 1 to 64 bytes of UTF-8, as the backend measures it. */
internal fun isButtonId(id: String) = id.isNotEmpty() && id.toByteArray(Charsets.UTF_8).size <= 64
