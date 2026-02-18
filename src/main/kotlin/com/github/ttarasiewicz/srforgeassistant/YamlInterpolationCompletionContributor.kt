package com.github.ttarasiewicz.srforgeassistant

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.util.ProcessingContext

/**
 * Completion inside `${...}` and `%{...}` interpolation expressions in YAML files.
 * Suggests key paths from the current YAML document with segment-by-segment navigation.
 *
 * Uses text-based key scanning instead of PSI navigation so that suggestions work
 * even when the file has parse errors (which is common while the user is typing).
 *
 * Supports OmegaConf-style list indexing via both notations:
 * - Dot notation: `preprocessing.training.0._target`
 * - Bracket notation: `preprocessing.training[0]._target`
 */
class YamlInterpolationCompletionContributor : CompletionContributor() {

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.completionType != CompletionType.BASIC) return

        val vFile = parameters.originalFile.viewProvider.virtualFile
        val name = vFile.name
        if (!name.endsWith(".yaml") && !name.endsWith(".yml")) return

        val offset = parameters.offset
        val docText = parameters.editor.document.text
        val parentPath = findInterpolationParentPath(docText, offset) ?: return

        val prefix = findInterpolationPrefix(docText, offset)
        val adjusted = result.withPrefixMatcher(PlainPrefixMatcher(prefix))
        adjusted.stopHere()

        val children = collectDirectChildrenFromText(docText, parentPath)
        for (child in children) {
            val el = LookupElementBuilder.create(child.segment)
                .withTypeText(child.valuePreview ?: "", true)
                .withIcon(when {
                    child.hasChildren -> AllIcons.Json.Object
                    child.isSequence -> AllIcons.Json.Array
                    child.isIndex -> AllIcons.Nodes.Enum
                    else -> AllIcons.Nodes.Field
                })
                .withInsertHandler(
                    if (child.hasChildren || child.isSequence) DOT_INSERT_HANDLER else null
                )
                .withCaseSensitivity(false)
            adjusted.addElement(el)
        }
    }

    companion object {
        fun isInsideInterpolation(text: String, offset: Int): Boolean {
            var i = (offset - 1).coerceAtMost(text.length - 1)
            while (i >= 1) {
                val ch = text[i]
                if (ch == '}') return false
                if (ch == '{' && (text[i - 1] == '$' || text[i - 1] == '%')) return true
                i--
            }
            return false
        }

        fun findInterpolationParentPath(text: String, offset: Int): String? {
            var i = (offset - 1).coerceAtMost(text.length - 1)
            while (i >= 1) {
                val ch = text[i]
                if (ch == '}') return null
                if (ch == '{' && (text[i - 1] == '$' || text[i - 1] == '%')) {
                    val inside = text.substring(i + 1, offset)
                    return if (inside.contains('.')) inside.substringBeforeLast('.') else ""
                }
                i--
            }
            return null
        }

        fun findInterpolationPrefix(text: String, offset: Int): String {
            var i = offset - 1
            while (i >= 0) {
                val ch = text[i]
                if (ch == '.' || ch == '{') return text.substring(i + 1, offset)
                i--
            }
            return text.substring(0, offset)
        }

        private data class ChildInfo(
            val segment: String,
            val hasChildren: Boolean,
            val isSequence: Boolean,
            val isIndex: Boolean,
            val valuePreview: String?
        )

        private data class KeyLine(val lineIdx: Int, val indent: Int, val key: String, val rest: String)

        /**
         * Parse a path segment that may contain a bracket index, e.g. "tags[0]" → ("tags", 0).
         * Plain segments return null index. Pure integers also return null index (handled separately).
         */
        private fun parsePathSegment(segment: String): Pair<String, Int?> {
            val bracket = segment.indexOf('[')
            if (bracket < 0) return Pair(segment, null)
            val key = segment.substring(0, bracket)
            val closeBracket = segment.indexOf(']', bracket + 1)
            if (closeBracket < 0) return Pair(segment, null)
            val idxStr = segment.substring(bracket + 1, closeBracket)
            return Pair(key, idxStr.toIntOrNull())
        }

        /**
         * Extract the scalar value from a dash line like "- MagNAt" → "MagNAt".
         * Returns null if the dash line contains a key-value pair or is empty.
         */
        private fun extractDashScalar(lines: List<String>, lineIdx: Int): String? {
            val trimmed = lines[lineIdx].trimStart()
            if (!trimmed.startsWith('-')) return null
            val afterDash = trimmed.substring(1).trimStart()
            if (afterDash.isEmpty() || afterDash.startsWith('#')) return null
            // If it contains a colon, it's a key-value (like "- _target: Foo"), not a scalar
            if (afterDash.contains(':')) return null
            return afterDash
        }

        private fun isSequenceContent(lines: List<String>, startLineIdx: Int, endLineIdx: Int): Boolean {
            for (lineIdx in (startLineIdx + 1) until endLineIdx) {
                val t = lines[lineIdx].trimStart()
                if (t.isEmpty() || t.startsWith('#')) continue
                return t.startsWith('-')
            }
            return false
        }

        private fun countDashItems(lines: List<String>, startLineIdx: Int, endLineIdx: Int): Int {
            var dashIndent: Int? = null
            var count = 0
            for (lineIdx in (startLineIdx + 1) until endLineIdx) {
                val line = lines[lineIdx]
                val t = line.trimStart()
                if (!t.startsWith('-')) continue
                val ind = line.length - t.length
                if (dashIndent == null) dashIndent = ind
                if (ind == dashIndent) count++
            }
            return count
        }

        private fun findDashItemRanges(lines: List<String>, startLineIdx: Int, endLineIdx: Int): List<Pair<Int, Int>> {
            var dashIndent: Int? = null
            val dashStarts = ArrayList<Int>()
            for (lineIdx in (startLineIdx + 1) until endLineIdx) {
                val line = lines[lineIdx]
                val t = line.trimStart()
                if (!t.startsWith('-')) continue
                val ind = line.length - t.length
                if (dashIndent == null) dashIndent = ind
                if (ind == dashIndent) dashStarts.add(lineIdx)
            }
            return dashStarts.mapIndexed { idx, start ->
                val end = if (idx + 1 < dashStarts.size) dashStarts[idx + 1] else endLineIdx
                Pair(start, end)
            }
        }

        private fun parseKeysInDashItem(
            lines: List<String>,
            itemStartLine: Int,
            itemEndLine: Int
        ): List<KeyLine> {
            val result = ArrayList<KeyLine>()

            val dashLine = lines[itemStartLine]
            val dashTrimmed = dashLine.trimStart()
            val afterDash = dashTrimmed.substring(1).trimStart()
            if (afterDash.isNotEmpty() && !afterDash.startsWith('#')) {
                val colonIdx = afterDash.indexOf(':')
                if (colonIdx > 0) {
                    val key = afterDash.substring(0, colonIdx).trim()
                    if (key.isNotEmpty() && key.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }) {
                        val rest = afterDash.substring(colonIdx + 1).trim()
                        val contentIndent = dashLine.indexOf(afterDash[0])
                        result.add(KeyLine(itemStartLine, contentIndent, key, rest))
                    }
                }
            }

            for (lineIdx in (itemStartLine + 1) until itemEndLine) {
                val line = lines[lineIdx]
                val trimmed = line.trimStart()
                if (trimmed.isEmpty() || trimmed.startsWith('#') || trimmed.startsWith('-')) continue
                val colonIdx = trimmed.indexOf(':')
                if (colonIdx <= 0) continue
                val key = trimmed.substring(0, colonIdx).trim()
                if (key.isEmpty()) continue
                if (key.any { !it.isLetterOrDigit() && it != '_' && it != '-' && it != '.' }) continue
                val indent = line.length - trimmed.length
                val rest = trimmed.substring(colonIdx + 1).trim()
                result.add(KeyLine(lineIdx, indent, key, rest))
            }

            return result
        }

        /**
         * Scan the document text for YAML key lines and return direct children
         * of [parentPath]. Works even when the PSI tree is broken due to parse errors.
         *
         * Supports OmegaConf-style list indexing via both notations:
         * - Dot: `preprocessing.training.0._target`
         * - Bracket: `preprocessing.training[0]._target`
         */
        private fun collectDirectChildrenFromText(text: String, parentPath: String): List<ChildInfo> {
            val lines = text.lines()

            // Phase 1: Parse all non-dash key lines
            val keyLines = ArrayList<KeyLine>()
            for ((idx, line) in lines.withIndex()) {
                val trimmed = line.trimStart()
                if (trimmed.isEmpty() || trimmed.startsWith('#') || trimmed.startsWith('-')) continue
                val colonIdx = trimmed.indexOf(':')
                if (colonIdx <= 0) continue
                val key = trimmed.substring(0, colonIdx).trim()
                if (key.isEmpty()) continue
                if (key.any { !it.isLetterOrDigit() && it != '_' && it != '-' && it != '.' }) continue
                val indent = line.length - trimmed.length
                val rest = trimmed.substring(colonIdx + 1).trim()
                keyLines.add(KeyLine(idx, indent, key, rest))
            }

            // Phase 2: Navigate to target scope
            var rangeStart = 0
            var rangeEnd = keyLines.size
            var targetIndent = 0
            var scopeEndLineIdx = lines.size
            var dashItemKeys: List<KeyLine>? = null
            // Set when the current scope is a sequence — Phase 3 will show index suggestions
            var pendingSequenceLineIdx: Int? = null
            var pendingSequenceEndLineIdx = 0

            if (parentPath.isNotEmpty()) {
                for (segment in parentPath.split('.')) {
                    // Dot-notation list index: previous segment was a sequence, this is the index
                    if (pendingSequenceLineIdx != null) {
                        val listIndex = segment.toIntOrNull() ?: return emptyList()
                        val ranges = findDashItemRanges(lines, pendingSequenceLineIdx, pendingSequenceEndLineIdx)
                        if (listIndex >= ranges.size) return emptyList()

                        val (itemStart, itemEnd) = ranges[listIndex]
                        val itemKeys = parseKeysInDashItem(lines, itemStart, itemEnd)
                        if (itemKeys.isEmpty()) return emptyList()

                        dashItemKeys = itemKeys
                        rangeStart = 0
                        rangeEnd = itemKeys.size
                        targetIndent = itemKeys.minOf { it.indent }
                        scopeEndLineIdx = itemEnd
                        pendingSequenceLineIdx = null
                        continue
                    }

                    // Check for bracket notation: "tags[0]" → key "tags", index 0
                    val (keyName, bracketIndex) = parsePathSegment(segment)

                    val activeKeys = dashItemKeys ?: keyLines

                    val parentPos = (rangeStart until rangeEnd).firstOrNull {
                        activeKeys[it].indent == targetIndent && activeKeys[it].key == keyName
                    } ?: return emptyList()

                    val parentLineIdx = activeKeys[parentPos].lineIdx

                    val blockEnd = ((parentPos + 1) until rangeEnd).firstOrNull {
                        activeKeys[it].indent <= targetIndent
                    } ?: rangeEnd
                    val blockEndLineIdx = if (blockEnd < rangeEnd) activeKeys[blockEnd].lineIdx else scopeEndLineIdx

                    if (bracketIndex != null) {
                        // Bracket notation: navigate directly into list item
                        val ranges = findDashItemRanges(lines, parentLineIdx, blockEndLineIdx)
                        if (bracketIndex >= ranges.size) return emptyList()

                        val (itemStart, itemEnd) = ranges[bracketIndex]
                        val itemKeys = parseKeysInDashItem(lines, itemStart, itemEnd)
                        if (itemKeys.isEmpty()) return emptyList()

                        dashItemKeys = itemKeys
                        rangeStart = 0
                        rangeEnd = itemKeys.size
                        targetIndent = itemKeys.minOf { it.indent }
                        scopeEndLineIdx = itemEnd
                    } else if (isSequenceContent(lines, parentLineIdx, blockEndLineIdx)) {
                        // Dot notation: this key is a sequence — next segment is the index
                        pendingSequenceLineIdx = parentLineIdx
                        pendingSequenceEndLineIdx = blockEndLineIdx
                    } else {
                        // Regular mapping navigation
                        rangeStart = parentPos + 1
                        rangeEnd = blockEnd

                        val childIndent = (rangeStart until rangeEnd).minOfOrNull { activeKeys[it].indent }
                            ?: return emptyList()
                        targetIndent = childIndent
                        scopeEndLineIdx = blockEndLineIdx
                    }
                }
            }

            // Phase 3: Collect results
            if (pendingSequenceLineIdx != null) {
                // Current scope is a sequence — suggest list item indices
                val ranges = findDashItemRanges(lines, pendingSequenceLineIdx, pendingSequenceEndLineIdx)
                val result = mutableListOf<ChildInfo>()
                for ((i, range) in ranges.withIndex()) {
                    val (itemStart, itemEnd) = range
                    val itemKeys = parseKeysInDashItem(lines, itemStart, itemEnd)
                    val hasItemChildren = itemKeys.isNotEmpty()
                    // Show preview: first key-value for mappings, scalar value for plain items
                    val preview = if (itemKeys.isNotEmpty()) {
                        val first = itemKeys[0]
                        if (first.rest.isNotEmpty()) {
                            val v = if (first.rest.length > 30) first.rest.take(27) + "..." else first.rest
                            "${first.key}: $v"
                        } else "(mapping)"
                    } else {
                        // Plain scalar item (e.g. "- MagNAt")
                        extractDashScalar(lines, itemStart)
                    }
                    result.add(ChildInfo(i.toString(), hasItemChildren, isSequence = false, isIndex = true, preview))
                }
                return result
            }

            // Regular children at targetIndent within range
            val activeKeys = dashItemKeys ?: keyLines

            val result = mutableListOf<ChildInfo>()
            val seen = HashSet<String>()

            for (i in rangeStart until rangeEnd) {
                val kl = activeKeys[i]
                if (kl.indent != targetIndent) continue
                if (!seen.add(kl.key)) continue

                val nextSibling = ((i + 1) until rangeEnd).firstOrNull {
                    activeKeys[it].indent <= targetIndent
                } ?: rangeEnd
                val nextSiblingLineIdx = if (nextSibling < rangeEnd) activeKeys[nextSibling].lineIdx else scopeEndLineIdx

                val isSequence = when {
                    kl.rest.startsWith('[') -> true
                    kl.rest.isNotEmpty() -> false
                    else -> isSequenceContent(lines, kl.lineIdx, nextSiblingLineIdx)
                }

                val hasChildren = if (isSequence) false
                else ((i + 1) until nextSibling).any { activeKeys[it].indent > targetIndent }

                val preview = when {
                    isSequence -> {
                        val count = if (!kl.rest.startsWith('[')) {
                            countDashItems(lines, kl.lineIdx, nextSiblingLineIdx)
                        } else null
                        if (count != null && count > 0) "(list, $count items)" else "(list)"
                    }
                    hasChildren -> "(mapping)"
                    kl.rest.isEmpty() -> null
                    kl.rest.startsWith('{') -> "(inline)"
                    kl.rest.length > 40 -> kl.rest.take(37) + "..."
                    else -> kl.rest
                }

                result.add(ChildInfo(kl.key, hasChildren, isSequence, isIndex = false, preview))
            }

            return result.sortedBy { it.segment.lowercase() }
        }

        /**
         * Count elements in an inline YAML list like `[1, 2, 3]`.
         * Handles nested brackets/braces so commas inside them aren't counted.
         */
        private fun countInlineElements(s: String): Int {
            if (s.length < 2) return 0
            val content = s.substring(1, s.length - 1).trim()
            if (content.isEmpty()) return 0
            var depth = 0
            var count = 1
            for (ch in content) {
                when (ch) {
                    '[', '{' -> depth++
                    ']', '}' -> depth--
                    ',' -> if (depth == 0) count++
                }
            }
            return count
        }

        /**
         * Build a content-aware preview for a block sequence.
         * - Items with `_target`: show short class names, e.g. `[SetAttribute, ChooseImages, ...+3 more]`
         * - Scalar items: show values, e.g. `[MagNAt, ProbaV, ...+5 more]`
         * - Other mappings: fall back to `[N items]`
         */
        private fun buildSequencePreview(
            lines: List<String>,
            startLineIdx: Int,
            endLineIdx: Int
        ): String {
            val ranges = findDashItemRanges(lines, startLineIdx, endLineIdx)
            if (ranges.isEmpty()) return "[]"

            val maxPreview = 2
            val previews = ArrayList<String>()
            var hasTargets = false
            var hasScalars = false
            var hasMappings = false

            for ((i, range) in ranges.withIndex()) {
                if (i >= maxPreview) break
                val (itemStart, itemEnd) = range
                val itemKeys = parseKeysInDashItem(lines, itemStart, itemEnd)

                if (itemKeys.isEmpty()) {
                    hasScalars = true
                    val scalar = extractDashScalar(lines, itemStart)
                    if (scalar != null) previews.add(scalar)
                } else {
                    hasMappings = true
                    val targetKey = itemKeys.firstOrNull { it.key == "_target" }
                    if (targetKey != null && targetKey.rest.isNotEmpty()) {
                        hasTargets = true
                        previews.add(targetKey.rest.substringAfterLast('.'))
                    } else {
                        previews.add("{...}")
                    }
                }
            }

            // Fall back to plain count for mixed/unparseable content
            if (previews.isEmpty()) return "[${ranges.size} items]"

            val remaining = ranges.size - previews.size
            return if (remaining > 0) {
                "[${previews.joinToString(", ")}, ...+$remaining more]"
            } else {
                "[${previews.joinToString(", ")}]"
            }
        }

        private fun stripYamlQuotes(s: String): String {
            if (s.length >= 2) {
                if ((s.first() == '"' && s.last() == '"') ||
                    (s.first() == '\'' && s.last() == '\'')) {
                    return s.substring(1, s.length - 1)
                }
            }
            return s
        }

        private val SINGLE_INTERPOLATION = Regex("""^[$%]\{([^}]+)}$""")

        /**
         * Resolve the value at [path] within the YAML [text].
         * If the resolved value is itself an interpolation reference, resolves recursively.
         * Cycle detection prevents infinite loops.
         */
        fun resolveValueFromText(text: String, path: String): String? {
            return resolveValueRecursive(text, path, mutableSetOf())
        }

        private fun resolveValueRecursive(text: String, path: String, visited: MutableSet<String>): String? {
            if (!visited.add(path)) return null // cycle detected
            val value = resolveValueDirect(text, path) ?: return null
            val inner = SINGLE_INTERPOLATION.matchEntire(value)
            if (inner != null) {
                val innerPath = inner.groupValues[1].trim()
                return resolveValueRecursive(text, innerPath, visited) ?: value
            }
            return value
        }

        private fun resolveValueDirect(text: String, path: String): String? {
            if (path.isEmpty()) return null
            val lines = text.lines()

            // Phase 1: Parse all non-dash key lines
            val keyLines = ArrayList<KeyLine>()
            for ((idx, line) in lines.withIndex()) {
                val trimmed = line.trimStart()
                if (trimmed.isEmpty() || trimmed.startsWith('#') || trimmed.startsWith('-')) continue
                val colonIdx = trimmed.indexOf(':')
                if (colonIdx <= 0) continue
                val key = trimmed.substring(0, colonIdx).trim()
                if (key.isEmpty()) continue
                if (key.any { !it.isLetterOrDigit() && it != '_' && it != '-' && it != '.' }) continue
                val indent = line.length - trimmed.length
                val rest = trimmed.substring(colonIdx + 1).trim()
                keyLines.add(KeyLine(idx, indent, key, rest))
            }

            val segments = path.split('.')
            if (segments.isEmpty()) return null
            val parentSegments = segments.dropLast(1)
            val leafSegment = segments.last()

            // Phase 2: Navigate to parent scope
            var rangeStart = 0
            var rangeEnd = keyLines.size
            var targetIndent = 0
            var scopeEndLineIdx = lines.size
            var dashItemKeys: List<KeyLine>? = null
            var pendingSequenceLineIdx: Int? = null
            var pendingSequenceEndLineIdx = 0

            for (segment in parentSegments) {
                if (pendingSequenceLineIdx != null) {
                    val listIndex = segment.toIntOrNull() ?: return null
                    val ranges = findDashItemRanges(lines, pendingSequenceLineIdx, pendingSequenceEndLineIdx)
                    if (listIndex >= ranges.size) return null
                    val (itemStart, itemEnd) = ranges[listIndex]
                    val itemKeys = parseKeysInDashItem(lines, itemStart, itemEnd)
                    if (itemKeys.isEmpty()) return null
                    dashItemKeys = itemKeys
                    rangeStart = 0
                    rangeEnd = itemKeys.size
                    targetIndent = itemKeys.minOf { it.indent }
                    scopeEndLineIdx = itemEnd
                    pendingSequenceLineIdx = null
                    continue
                }

                val (keyName, bracketIndex) = parsePathSegment(segment)
                val activeKeys = dashItemKeys ?: keyLines
                val parentPos = (rangeStart until rangeEnd).firstOrNull {
                    activeKeys[it].indent == targetIndent && activeKeys[it].key == keyName
                } ?: return null
                val parentLineIdx = activeKeys[parentPos].lineIdx
                val blockEnd = ((parentPos + 1) until rangeEnd).firstOrNull {
                    activeKeys[it].indent <= targetIndent
                } ?: rangeEnd
                val blockEndLineIdx = if (blockEnd < rangeEnd) activeKeys[blockEnd].lineIdx else scopeEndLineIdx

                if (bracketIndex != null) {
                    val ranges = findDashItemRanges(lines, parentLineIdx, blockEndLineIdx)
                    if (bracketIndex >= ranges.size) return null
                    val (itemStart, itemEnd) = ranges[bracketIndex]
                    val itemKeys = parseKeysInDashItem(lines, itemStart, itemEnd)
                    if (itemKeys.isEmpty()) return null
                    dashItemKeys = itemKeys
                    rangeStart = 0
                    rangeEnd = itemKeys.size
                    targetIndent = itemKeys.minOf { it.indent }
                    scopeEndLineIdx = itemEnd
                } else if (isSequenceContent(lines, parentLineIdx, blockEndLineIdx)) {
                    pendingSequenceLineIdx = parentLineIdx
                    pendingSequenceEndLineIdx = blockEndLineIdx
                } else {
                    rangeStart = parentPos + 1
                    rangeEnd = blockEnd
                    val childIndent = (rangeStart until rangeEnd).minOfOrNull { activeKeys[it].indent }
                        ?: return null
                    targetIndent = childIndent
                    scopeEndLineIdx = blockEndLineIdx
                }
            }

            // Phase 3: Find leaf and extract value
            if (pendingSequenceLineIdx != null) {
                val listIndex = leafSegment.toIntOrNull() ?: return null
                val ranges = findDashItemRanges(lines, pendingSequenceLineIdx, pendingSequenceEndLineIdx)
                if (listIndex >= ranges.size) return null
                val (itemStart, itemEnd) = ranges[listIndex]
                val itemKeys = parseKeysInDashItem(lines, itemStart, itemEnd)
                return if (itemKeys.isEmpty()) {
                    extractDashScalar(lines, itemStart)
                } else {
                    val first = itemKeys[0]
                    if (first.key == "_target" && first.rest.isNotEmpty()) {
                        first.rest.substringAfterLast('.')
                    } else "{...}"
                }
            }

            val (leafKeyName, leafBracketIndex) = parsePathSegment(leafSegment)
            val activeKeys = dashItemKeys ?: keyLines
            val targetPos = (rangeStart until rangeEnd).firstOrNull {
                activeKeys[it].indent == targetIndent && activeKeys[it].key == leafKeyName
            } ?: return null

            val kl = activeKeys[targetPos]
            val nextSibling = ((targetPos + 1) until rangeEnd).firstOrNull {
                activeKeys[it].indent <= targetIndent
            } ?: rangeEnd
            val nextSiblingLineIdx = if (nextSibling < rangeEnd) activeKeys[nextSibling].lineIdx else scopeEndLineIdx

            if (leafBracketIndex != null) {
                val ranges = findDashItemRanges(lines, kl.lineIdx, nextSiblingLineIdx)
                if (leafBracketIndex >= ranges.size) return null
                val (itemStart, itemEnd) = ranges[leafBracketIndex]
                val itemKeys = parseKeysInDashItem(lines, itemStart, itemEnd)
                return if (itemKeys.isEmpty()) {
                    extractDashScalar(lines, itemStart)
                } else {
                    val first = itemKeys[0]
                    if (first.key == "_target" && first.rest.isNotEmpty()) {
                        first.rest.substringAfterLast('.')
                    } else "{...}"
                }
            }

            return when {
                kl.rest.isNotEmpty() && !kl.rest.startsWith('{') && !kl.rest.startsWith('[') -> {
                    val stripped = stripYamlQuotes(kl.rest)
                    if (stripped.length > 50) stripped.take(47) + "..." else stripped
                }
                kl.rest.startsWith('{') -> "{...}"
                kl.rest.startsWith('[') -> {
                    if (kl.rest.length <= 40) kl.rest
                    else "[${countInlineElements(kl.rest)} items]"
                }
                kl.rest.isEmpty() -> {
                    if (isSequenceContent(lines, kl.lineIdx, nextSiblingLineIdx)) {
                        buildSequencePreview(lines, kl.lineIdx, nextSiblingLineIdx)
                    } else {
                        val hasChildren = ((targetPos + 1) until nextSibling).any {
                            activeKeys[it].indent > targetIndent
                        }
                        if (hasChildren) {
                            val childIndent = ((targetPos + 1) until nextSibling)
                                .minOfOrNull { activeKeys[it].indent }
                            val targetChild = if (childIndent != null) {
                                ((targetPos + 1) until nextSibling).firstOrNull {
                                    activeKeys[it].indent == childIndent && activeKeys[it].key == "_target"
                                }
                            } else null
                            if (targetChild != null) {
                                val tv = stripYamlQuotes(activeKeys[targetChild].rest)
                                tv.substringAfterLast('.').ifEmpty { "{...}" }
                            } else "{...}"
                        } else null
                    }
                }
                else -> null
            }
        }

        private val DOT_INSERT_HANDLER = InsertHandler<LookupElement> { ctx, _ ->
            val doc = ctx.document
            val tail = ctx.editor.caretModel.offset
            if (tail >= doc.textLength || doc.charsSequence[tail] != '.') {
                doc.insertString(tail, ".")
                ctx.editor.caretModel.moveToOffset(tail + 1)
            }
            AutoPopupController.getInstance(ctx.project).scheduleAutoPopup(ctx.editor)
            ctx.setAddCompletionChar(false)
        }
    }
}
