package com.github.ttarasiewicz.srforgeassistant

/**
 * Shared utilities for interpolation reference handling across folding,
 * completion, and annotation.
 *
 * Valid interpolation syntaxes:
 * - `${path.to.key}` — OmegaConf native interpolation
 * - `${ref:path.to.key}` — SR-Forge custom reference resolver
 * - `%{path.to.key}` — SR-Forge ConfigResolver reference
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
}
