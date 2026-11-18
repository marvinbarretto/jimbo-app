package dev.marvinbarretto.jimbo.share

/** What a share sheet hands us, shaped for POST /api/door (source "url"). */
data class SharedItem(val title: String, val body: String?)

/**
 * Pure Kotlin, no Android: the first http(s) URL in the shared text becomes the
 * door title (bare, because the door dedupes on title for source url). The full
 * shared text plus subject rides along as the body. X/Twitter tracking
 * query (?s=..&t=..) is stripped; other hosts keep their query.
 */
object ShareLink {
    private val urlRegex = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private const val MAX_TITLE = 500

    fun extract(text: String?, subject: String? = null): SharedItem? {
        val t = text?.trim().orEmpty()
        val s = subject?.trim().orEmpty()
        if (t.isEmpty() && s.isEmpty()) return null
        val url = urlRegex.find(t)?.value?.trimEnd('.', ',', ';', ')', ']', '!', '?', ':')
            ?: urlRegex.find(s)?.value?.trimEnd('.', ',', ';', ')', ']', '!', '?', ':')
        val body = listOf(s, t).filter { it.isNotEmpty() }.distinct().joinToString("\n").ifEmpty { null }
        val title = if (url != null) clean(url) else (t.ifEmpty { s }).take(MAX_TITLE)
        return SharedItem(title, body)
    }

    fun clean(url: String): String {
        val q = url.indexOf('?')
        if (q < 0) return url
        val hash = url.indexOf('#')
        val end = if (hash in 0 until q) return url else if (hash >= 0) hash else url.length
        val host = Regex("""^https?://([^/?#]+)""", RegexOption.IGNORE_CASE).find(url)
            ?.groupValues?.get(1)?.lowercase()?.substringAfterLast('@')?.substringBefore(':') ?: return url
        val tracked = host == "x.com" || host == "twitter.com" || host.endsWith(".x.com") || host.endsWith(".twitter.com")
        return if (tracked) url.substring(0, q) + url.substring(end) else url
    }

    /** Hand-rolled JSON: org.json is a stub in JVM unit tests. */
    fun doorJson(item: SharedItem): String {
        val body = item.body?.let { "\"${escape(it)}\"" } ?: "null"
        return """{"source":"url","source_ids":["${escape(item.title)}"],"title":"${escape(item.title)}","body":$body}"""
    }

    private fun escape(s: String): String = buildString {
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }
}
