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
