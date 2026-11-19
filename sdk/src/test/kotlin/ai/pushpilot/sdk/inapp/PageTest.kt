package ai.pushpilot.sdk.inapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PageTest {
    private val base64: (ByteArray) -> String = { Base64.getEncoder().encodeToString(it) }

    @Test
    fun assetsBecomeDataUrisEverywhereLongestFirst() {
        val html = """<img src="https://c.example/a.png"><div style="background:url(https://c.example/a.png)"></div>""" +
            """<img src="https://c.example/a.png.webp"><style>@font-face{src:url(https://c.example/f.woff2)}</style>"""
        val out = inlineAssets(
            html,
            mapOf(
                "https://c.example/a.png" to Download(byteArrayOf(1, 2, 3), "image/png; charset=binary"),
                "https://c.example/a.png.webp" to Download(byteArrayOf(4), null),
                "https://c.example/f.woff2" to Download(byteArrayOf(5), "application/octet-stream"),
            ),
            base64,
        )
        assertFalse(out.contains("https://"))
        assertEquals(2, Regex("data:image/png;base64,AQID").findAll(out).count())
        assertTrue(out.contains("data:image/webp;base64,BA=="))
        assertTrue(out.contains("data:font/woff2;base64,BQ=="))
    }

    @Test
    fun mediaTypesRefuseAnythingThatCouldBreakTheAttribute() {
        assertEquals("image/png", mediaType("""image/png"onload="x""", "https://c/x.png"))
        assertEquals("image/svg+xml", mediaType(null, "https://c/x.SVG?v=2"))
        assertEquals("application/octet-stream", mediaType(null, "https://c/x"))
    }

    @Test
    fun cacheKeysFollowIdVersionAndLight() {
        val m = message()
        assertEquals("${m.id}-v3.html", cacheName(m, slow = false))
        assertEquals("${m.id}-v3.html", cacheName(m, slow = true))
        val light = m.copy(lightHtmlUrl = "https://c/v3.light.html")
        assertEquals("${m.id}-v3-light.html", cacheName(light, slow = true))
        assertEquals("${m.id}-v4.html", cacheName(m.copy(htmlVersion = 4), slow = false))
        assertEquals("https://c/v3.light.html", pageUrl(light, slow = true))
        assertEquals(m.htmlUrl, pageUrl(light, slow = false))
    }

    @Test
    fun slowPhoneIsLowRamOrUnderThreeGib() {
        val gib = 1024L * 1024 * 1024
        assertTrue(isSlowPhone(lowRam = true, totalMemoryBytes = 8 * gib))
        assertTrue(isSlowPhone(lowRam = false, totalMemoryBytes = 3 * gib - 1))
        assertFalse(isSlowPhone(lowRam = false, totalMemoryBytes = 3 * gib))
    }

    @Test
    fun cspListsOnlyCleanHttpsOrigins() {
        assertTrue(contentSecurityPolicy(emptyList()).contains("connect-src 'none';"))
        val csp = contentSecurityPolicy(listOf("https://api.example.com", "https://*.cdn.example:8443/", "http://x.example", "https://a.example; script-src *", "https://q\".example"))
        assertTrue(csp.contains("connect-src https://api.example.com https://*.cdn.example:8443/;"))
        assertFalse(csp.contains("http://"))
        assertFalse(csp.contains("\""))
        assertTrue(csp.startsWith("default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; font-src data:; media-src data: blob:;"))
        assertTrue(csp.endsWith("form-action 'none'"))
    }

    @Test
    fun cspAndBridgeGoRightAfterHeadOrAfterTheDoctype() {
        val bridge = "<script>B</script>"
        /** Where the meta starts in the prepared page. */
        fun at(html: String) = preparePage(html, emptyList(), bridge).let { it.indexOf("<meta http-equiv") to it }
        val (i1, p1) = at("<!DOCTYPE html><html lang=\"en\"><HEAD data-x><meta charset=\"utf-8\"><script>page()</script>")
        assertEquals("<!DOCTYPE html><html lang=\"en\"><HEAD data-x>".length, i1)
        assertTrue(p1.indexOf("<script>B</script>") < p1.indexOf("page()"))
        assertEquals(0, at("<div>no head</div>").first)
        assertEquals("<!doctype html>\n".length, at("<!doctype html>\n<body><header>x</header>").first)
        // A <head> hidden in a comment, or after a script, must not pull the policy below page code.
        val (i2, p2) = at("<!doctype html><!-- <head> --><script>page()</script><head>")
        assertEquals("<!doctype html>".length, i2)
        assertTrue(p2.indexOf("Content-Security-Policy") < p2.indexOf("page()"))
    }

    @Test
    fun bridgeCarriesTheSdkValues() {
        val script = bridgeScript(Insets(24.0, 0.0, 48.0, 0.0), Frame(10.0, 20.0, 30.0, 40.0), light = true, posture(411.0, 914.0))
        assertTrue(script.startsWith("<script>") && script.endsWith("</script>"))
        val values = JSONObject(script.substringAfter("var v=").substringBefore(",p=v.posture"))
        assertEquals(24.0, values.getJSONObject("insets").getDouble("top"), 0.0)
        assertEquals(48.0, values.getJSONObject("insets").getDouble("bottom"), 0.0)
        assertEquals(30.0, values.getJSONObject("target").getDouble("width"), 0.0)
        assertTrue(values.getBoolean("light"))
        assertEquals("flat", values.getJSONObject("posture").getString("state"))
        assertEquals(914.0, values.getJSONObject("posture").getJSONObject("screen").getDouble("height"), 0.0)
        for (member in listOf("close:", "open:", "haptic:", "track:", "island:null", "platform:\"android\"", "get posture()", "onPosture:", "_posture:", "pushpilotposture", "PushPilotNative.postMessage"))
            assertTrue(member, script.contains(member))
        val none = bridgeScript(Insets(0.0, 0.0, 0.0, 0.0), null, light = false, posture(1.0, 1.0))
        assertTrue(JSONObject(none.substringAfter("var v=").substringBefore(",p=v.posture")).isNull("target"))
        val update = postureUpdate(posture(2.5, 3.0))
        assertTrue(update.startsWith("window.pushpilot&&window.pushpilot._posture({"))
        val sent = JSONObject(update.substringAfter("_posture(").removeSuffix(")"))
        assertEquals("flat", sent.getString("state"))
        assertEquals(2.5, sent.getJSONObject("screen").getDouble("width"), 0.0)
    }

    @Test
    fun cssFramesDivideByDensity() {
        assertEquals(Frame(10.0, 20.0, 50.0, 24.8), cssFrame(25, 50, 125, 62, 2.5f))
    }

    @Test
    fun openRefusesScriptAndLocalSchemes() {
        for (ok in listOf("https://example.com/x", "myapp://checkout?id=1", "mailto:a@b.c", "tel:123"))
            assertTrue(ok, isOpenable(ok))
        for (bad in listOf("javascript:alert(1)", " JavaScript:alert(1)", "java\tscript:alert(1)", "data:text/html,x", "file:///sdcard", "blob:https://x/1", "vbscript:x", "relative/path", "", "1http://x"))
            assertFalse(bad, isOpenable(bad))
    }

    @Test
    fun hapticKindsMapAndUnknownKindsDoNothing() {
        for (kind in listOf("light", "medium", "heavy", "success", "warning", "error", "selection")) {
            assertTrue(kind, hapticConstant(kind, 23) != null)
            assertTrue(kind, hapticConstant(kind, 34) != null)
        }
        assertEquals(null, hapticConstant("explode", 34))
        assertTrue(hapticConstant("success", 30) != hapticConstant("success", 29))
    }
}

/** A plain modal message for tests, with [id] as its id. */
internal fun message(id: String = "0b3e8d8e-6a4f-4d55-9f7a-2b8a1c6a9f10") = InAppMessage(
    id = id, format = "modal", trigger = APP_OPEN, triggerName = null,
    htmlUrl = "https://c/v3.html", lightHtmlUrl = null, htmlVersion = 3, assets = emptyList(),
    capPerDevice = null, shownCount = 0, coachTarget = null, startsAt = null, endsAt = null,
)
