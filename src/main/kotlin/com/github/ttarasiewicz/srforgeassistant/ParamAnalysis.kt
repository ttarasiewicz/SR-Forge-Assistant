package com.github.ttarasiewicz.srforgeassistant

import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.psi.*
import com.jetbrains.python.psi.types.TypeEvalContext

data class ParamInfo(
    val name: String,
    val typeText: String = "Any",
    val defaultText: String? = null
)

data class ForwardBag(
    val passAll: Boolean,
    val names: Set<String>
) {
    fun isEmpty() = !passAll && names.isEmpty()
}

/** Build propagated __init__ params grouped by class (own + forwarded parent params). */
fun buildPropagatedParams(cls: PyClass): LinkedHashMap<PyClass, LinkedHashMap<String, ParamInfo>>? {
    val ctx = TypeEvalContextCompat.codeAnalysis(cls.project, cls.containingFile)
    val ownInit = cls.findInitOrNew(false, ctx) ?: cls.findInitOrNew(true, ctx) ?: return null

    val sections = LinkedHashMap<PyClass, LinkedHashMap<String, ParamInfo>>()
    val seenNames = LinkedHashSet<String>()

    fun sectionFor(klass: PyClass) = sections.getOrPut(klass) { LinkedHashMap() }

    // Own params (excluding self/*args/**kwargs)
    ownInit.parameterList.parameters
        .filterIsInstance<PyNamedParameter>()
        .filter { !it.isSelf && !it.isPositionalContainer && !it.isKeywordContainer }
        .forEach { p ->
            val info = p.toParamInfo()
            if (info.name.isNotBlank() && seenNames.add(info.name)) {
                sectionFor(cls)[info.name] = info
            }
        }

    // Walk MRO while something is being forwarded
    var bag = analyzeSuperForwarding(ownInit)
    var current: PyClass? = cls
    while (!bag.isEmpty()) {
        val parent = nextClassWithInit(current, ctx) ?: break
        val parentInit = parent.findInitOrNew(false, ctx) ?: parent.findInitOrNew(true, ctx) ?: break

        val parentNamedParams = parentInit.parameterList.parameters
            .filterIsInstance<PyNamedParameter>()
            .filter { !it.isSelf && !it.isPositionalContainer && !it.isKeywordContainer }

        val targetSection = sectionFor(parent)

        if (bag.passAll) {
            for (p in parentNamedParams) {
                val name = p.name ?: continue
                if (seenNames.add(name)) targetSection[name] = p.toParamInfo()
            }
        } else if (bag.names.isNotEmpty()) {
            val needed = bag.names
            for (p in parentNamedParams) {
                val name = p.name ?: continue
                if (name in needed && seenNames.add(name)) targetSection[name] = p.toParamInfo()
            }
        } else {
            break
        }

        val consumed = parentNamedParams.mapNotNull { it.name }.toSet()
        val parentForward = analyzeSuperForwarding(parentInit)
        val remainingNames = (bag.names - consumed) + parentForward.names
        bag = ForwardBag(bag.passAll && parentForward.passAll, remainingNames)
        current = parent
    }

    return if (sections.isEmpty()) null else sections
}

/** Analyze super().__init__(...) calls to determine which names are forwarded upward. */
fun analyzeSuperForwarding(func: PyFunction): ForwardBag {
    var passAll = false
    val names = LinkedHashSet<String>()

    val calls = PsiTreeUtil.findChildrenOfType(func, PyCallExpression::class.java)
    for (call in calls) {
        val calleeQ = call.callee as? PyQualifiedExpression ?: continue
        if (calleeQ.referencedName != "__init__") continue
        val qual = calleeQ.qualifier as? PyCallExpression ?: continue
        val superCallee = (qual.callee as? PyReferenceExpression)?.referencedName ?: continue
        if (superCallee != "super") continue

        for (arg in call.argumentList?.arguments.orEmpty()) {
            when (arg) {
                is PyKeywordArgument -> {
                    val kwName = arg.keyword
                    if (!kwName.isNullOrBlank()) names.add(kwName)
                }
                is PyStarArgument -> {
                    if (arg.isKeyword) passAll = true
                }
                else -> {
                    if (arg.text.trim().startsWith("**")) passAll = true
                }
            }
        }
    }

    return ForwardBag(passAll, names)
}

/** Find next superclass in MRO (excluding 'object') that defines __init__. */
fun nextClassWithInit(cls: PyClass?, ctx: TypeEvalContext): PyClass? {
    for (sup in cls?.getSuperClasses(ctx).orEmpty()) {
        if ((sup.findInitOrNew(false, ctx) ?: sup.findInitOrNew(true, ctx)) != null) return sup
    }
    return null
}

/** Build a flat param list for a standalone [PyFunction] (no MRO / inheritance). */
fun buildFunctionParams(func: PyFunction): LinkedHashMap<String, ParamInfo>? {
    val params = func.parameterList.parameters
        .filterIsInstance<PyNamedParameter>()
        .filter { !it.isSelf && !it.isPositionalContainer && !it.isKeywordContainer }

    if (params.isEmpty()) return null

    val map = LinkedHashMap<String, ParamInfo>()
    for (p in params) {
        val info = p.toParamInfo()
        if (info.name.isNotBlank()) map[info.name] = info
    }
    return if (map.isEmpty()) null else map
}

/** Convert a [PyNamedParameter] to [ParamInfo] using annotation/default from code text. */
fun PyNamedParameter.toParamInfo(): ParamInfo {
    val name = this.name ?: "<param>"
    val raw = this.text.trim()
    val colonIdx = raw.indexOf(':')
    val eqIdx = raw.indexOf('=')
    val typeText = when {
        colonIdx != -1 -> raw.substring(colonIdx + 1, if (eqIdx != -1) eqIdx else raw.length).trim().ifBlank { "Any" }
        else -> "Any"
    }
    val defaultText = if (eqIdx != -1) raw.substring(eqIdx + 1).trim().ifBlank { null } else null
    return ParamInfo(name, typeText, defaultText)
}
