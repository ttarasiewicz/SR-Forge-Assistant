package com.github.ttarasiewicz.srforgeassistant

/**
 * Shared utilities for interpolation reference handling across folding,
 * completion, and annotation.
 *
 * Valid interpolation syntaxes:
 * - `${path.to.key}` — OmegaConf native interpolation
 * - `${ref:path.to.key}` — SR-Forge custom reference resolver
 * - `%{path.to.key}` — SR-Forge ConfigResolver reference
 *
 * Method chain syntax (after closing brace):
 * - `${ref:model}.trainable_params()` — no-arg method call
 * - `${ref:model}.foo().bar()` — chained no-arg calls
 */
object InterpolationUtils {

    /** Regex matching `${...}` and `%{...}` interpolation expressions. */
    val INTERPOLATION_REGEX = Regex("""[$%]\{([^}]+)}""")

    /**
     * Extract a resolvable YAML path from interpolation content, or null
     * if the content uses an unrecognized resolver prefix.
     *
     * - `"preprocessing.training"` → `"preprocessing.training"`
     * - `"ref:preprocessing.training"` → `"preprocessing.training"`
     * - `"oc.env:HOME"` → `null` (known OmegaConf built-in, can't resolve)
     * - `"ef:preprocessing.training"` → `null` (unknown resolver)
     */
    fun extractResolvablePath(content: String): String? {
        val colonIdx = content.indexOf(':')
        if (colonIdx < 0) return content.trim().ifEmpty { null }

        val prefix = content.substring(0, colonIdx).trim()
        if (prefix == "ref") {
            return content.substring(colonIdx + 1).trim().ifEmpty { null }
        }
        // Any other prefix (oc.env, typos, etc.) — not resolvable
        return null
    }

    /**
     * Check whether a resolver prefix is recognized.
     * Returns `true` for known resolvers (`ref`, `oc.*`), `false` for unknown prefixes.
     */
    fun isKnownResolverPrefix(prefix: String): Boolean {
        if (prefix == "ref") return true
        if (prefix.startsWith("oc.")) return true
        return false
    }

    // ── Post-interpolation method chain detection ────────────────────

    /**
     * Describes a cursor position after a closing interpolation brace,
     * where the user is typing a method name.
     *
     * Example: `${ref:model}.train▌`
     *   - referencePath = "model", methodPrefix = "train", chainDepth = 0
     *
     * Example: `%{model}.get_data().▌`
     *   - referencePath = "model", methodPrefix = "", chainDepth = 1
     */
    data class PostInterpolationContext(
        val referencePath: String,
        val methodPrefix: String,
        val chainDepth: Int
    )

    /**
     * Regex matching an interpolation expression followed by an optional method
     * chain and a final `.` with a partial method name (the completion prefix).
     *
     * Applied to text from line-start to cursor offset.
     *
     * Group 1: interpolation content (inside braces)
     * Group 2: prior method chain (e.g. `.method1().method2()`) — may be empty
     * Group 3: partial method name being typed — may be empty
     */
    private val POST_INTERPOLATION_REGEX = Regex(
        """[$%]\{([^}]+)\}((?:\.\w+\(\))*)\.([\w]*)$"""
    )

    /**
     * Check if the text up to [offset] ends at a post-interpolation method position.
     * Returns a [PostInterpolationContext] if so, or null otherwise.
     */
    fun findPostInterpolationContext(text: String, offset: Int): PostInterpolationContext? {
        // Find line start
        var lineStart = offset - 1
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--

        val segment = text.substring(lineStart, offset)
        val match = POST_INTERPOLATION_REGEX.find(segment) ?: return null

        val content = match.groupValues[1].trim()
        val chainPart = match.groupValues[2]
        val prefix = match.groupValues[3]

        val path = extractResolvablePath(content) ?: return null

        // Count completed .method() calls in the chain
        val chainDepth = if (chainPart.isEmpty()) 0
        else Regex("""\.\w+\(\)""").findAll(chainPart).count()

        return PostInterpolationContext(path, prefix, chainDepth)
    }

    /**
     * Returns true if a `.` just typed at [dotOffset] is in a post-interpolation
     * method chain position. Used by the typed handler to trigger auto-popup.
     *
     * [dotOffset] is the caret offset AFTER the dot was inserted.
     */
    fun isAfterInterpolationClose(text: String, dotOffset: Int): Boolean {
        if (dotOffset < 3) return false
        val beforeDot = text[dotOffset - 2]

        if (beforeDot == '}') {
            // Quick check: scan backward for ${ or %{
            var i = dotOffset - 3
            while (i >= 0) {
                if (text[i] == '{' && i >= 1 && (text[i - 1] == '$' || text[i - 1] == '%')) return true
                if (text[i] == '\n') return false
                i--
            }
            return false
        }

        if (beforeDot == ')') {
            // Could be end of a method chain: ${...}.method().
            return findPostInterpolationContext(text, dotOffset) != null
        }

        return false
    }
}
