package ai.pushpilot.sdk.inapp

/** The message picked to show, its prepared-for-cache page, and its coach-mark target. */
internal class Choice(val message: InAppMessage, val html: String, val target: Frame?)

/**
 * The first message, in the fetch's order, that may show now (section 3): the right trigger, inside
 * its dates by the device clock, under its cap by the larger of [localShown] and the server's count,
 * a coach mark only with its view on screen ([target]), and its page cached ([page]).
 */
internal fun pick(
    messages: List<InAppMessage>,
    trigger: String,
    name: String?,
    now: Long,
    localShown: (String) -> Int,
    target: (String) -> Frame?,
    page: (InAppMessage) -> String?,
): Choice? {
    for (m in messages) {
        val triggered = if (trigger == APP_OPEN) m.trigger == APP_OPEN else m.trigger == SHOW_IN_APP && m.triggerName == name
        if (!triggered) continue
        if (m.startsAt != null && now < m.startsAt) continue
        if (m.endsAt != null && now >= m.endsAt) continue
        if (m.capPerDevice != null && maxOf(localShown(m.id), m.shownCount) >= m.capPerDevice) continue
        val frame = if (m.format == COACH_MARK) (m.coachTarget?.let(target) ?: continue) else null
        val html = page(m) ?: continue
        return Choice(m, html, frame)
    }
    return null
}

internal const val APP_OPEN = "app_open"
internal const val SHOW_IN_APP = "show_in_app"
internal const val COACH_MARK = "coach_mark"

/** One event to queue: `shown`, `clicked` (with its button) or `dismissed`, at its own time. */
internal data class InAppEvent(val messageId: String, val type: String, val buttonId: String?, val at: Long)

/** One showing of a message and the events it produces (section 7). */
internal class Showing(private val messageId: String, private val shownAt: Long) {
    private var clicks = 0
    private var closed = false

    /** The `shown` event, at the moment the message went on screen. */
    fun shown() = InAppEvent(messageId, "shown", null, shownAt)

    /** A `clicked` event for `track(buttonId)`; null after close, for an id the server would reject, or past the per-showing limit. */
    fun track(buttonId: String, at: Long): InAppEvent? {
        if (closed || !isButtonId(buttonId) || clicks >= MAX_CLICKS_PER_SHOWING) return null
        clicks++
        return InAppEvent(messageId, "clicked", buttonId, at)
    }

    /** Ends the showing: `dismissed` only when no `track` was recorded during it; null on a second close. */
    fun close(at: Long): InAppEvent? {
        if (closed) return null
        closed = true
        return if (clicks == 0) InAppEvent(messageId, "dismissed", null, at) else null
    }
}
