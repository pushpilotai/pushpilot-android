package ai.pushpilot.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android compiles regexes with ICU, which is stricter than the desktop JVM these tests run on: a
 * bare `}` or `]`, or a brace inside `[...]`, compiles here and throws on a phone. The 2.1.0 live
 * token pattern crashed every live push that way. This scans every regex literal in the SDK source
 * and holds it to ICU's rules, so the next one fails here instead of on a device.
 */
class IcuRegexTest {
    /** The pattern that crashed on Android ("Syntax error in regexp pattern near index 38"). */
    private val crashed = """\{\{\s*([a-z][a-z0-9_]*)\s*(\|[^}]*)?}}"""

    @Test
    fun theCheckerRejectsThePatternThatCrashed() {
        assertTrue(icuProblems(crashed).isNotEmpty())
    }

    @Test
    fun theCheckerAcceptsWhatIcuAccepts() {
        for (ok in listOf("""\{\{\s*([a-z][a-z0-9_]*)\s*(\|[^\}]*)?\}\}""", "[A-Za-z0-9._~%-]+", "a{2}b{1,}c{0,3}", """[\]\[]""", "x\\}"))
            assertEquals(ok, emptyList<String>(), icuProblems(ok))
    }

    @Test
    fun everyRegexInTheSdkIsIcuSafe() {
        val literals = File("src/main").walk().filter { it.extension == "kt" || it.extension == "java" }
            .flatMap { file -> regexLiterals(file.readText()).map { file.name to it } }.toList()
        assertTrue("no regex literals found; is the working directory the sdk module?", literals.any { it.second.contains("""\{\{""") })
        for ((file, pattern) in literals) {
            Regex(pattern)
            assertEquals("$file: $pattern", emptyList<String>(), icuProblems(pattern))
        }
    }

    /** Patterns passed to `Regex(...)` or `.toRegex()` as string literals, with Kotlin escapes of plain strings undone. */
    private fun regexLiterals(source: String): List<String> {
        val raw = Regex("\"\"\"(.*?)\"\"\"", RegexOption.DOT_MATCHES_ALL)
        val plain = Regex("\"((?:[^\"\\\\\\n]|\\\\.)*)\"")
        val call = Regex("Regex\\(\\s*(\"\"\"|\")")
        val toRegex = Regex("(\"\"\".*?\"\"\"|\"(?:[^\"\\\\\\n]|\\\\.)*\")\\s*\\.toRegex\\(", RegexOption.DOT_MATCHES_ALL)
        val starts = call.findAll(source).map { it.range.last - it.groupValues[1].length + 1 } +
            toRegex.findAll(source).map { it.range.first }
        return starts.mapNotNull { start ->
            raw.matchAt(source, start)?.groupValues?.get(1)
                ?: plain.matchAt(source, start)?.groupValues?.get(1)?.replace(Regex("\\\\(.)")) { it.groupValues[1] }
        }.toList()
    }

    /** What ICU would reject in [pattern]: bare `}` or `]`, a `{` that is not a counted repeat, a brace inside a set. */
    private fun icuProblems(pattern: String): List<String> {
        val problems = mutableListOf<String>()
        var depth = 0
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> i++
                depth > 0 && c == '[' -> depth++
                depth > 0 && c == ']' -> depth--
                depth > 0 && (c == '{' || c == '}') -> problems += "unescaped '$c' in a set at $i"
                c == '[' -> {
                    depth = 1
                    if (pattern.startsWith("^", i + 1)) i++
                    if (pattern.startsWith("]", i + 1)) problems += "set starting with ']' at $i"
                }
                c == ']' -> problems += "unescaped ']' at $i"
                c == '}' -> problems += "unescaped '}' at $i"
                c == '{' -> {
                    val repeat = Regex("""\{\d+(,\d*)?\}""").matchAt(pattern, i)
                    if (repeat == null) problems += "unescaped '{' at $i" else i = repeat.range.last
                }
            }
            i++
        }
        if (depth > 0) problems += "unclosed set"
        return problems
    }
}
