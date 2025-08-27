package com.github.ttarasiewicz.srforgeassistant

import com.intellij.codeInsight.documentation.DocumentationManagerProtocol
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.ProjectManager
import com.intellij.platform.backend.documentation.DocumentationLinkHandler
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.LinkResolveResult
import com.intellij.psi.search.GlobalSearchScope
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.stubs.PyClassNameIndex

class PyClassDocLinkHandler : DocumentationLinkHandler {

    override fun resolveLink(target: DocumentationTarget, url: String): LinkResolveResult? {
        // We only handle links that use the internal PSI protocol
        if (!url.startsWith(DocumentationManagerProtocol.PSI_ELEMENT_PROTOCOL)) return null
        val payload = url.removePrefix(DocumentationManagerProtocol.PSI_ELEMENT_PROTOCOL)

        fun resolveClass(fqn: String): PyClass? {
            val project =
                (target as? PyClassDocTarget)?.element?.project
                    ?: ProjectManager.getInstance().openProjects.firstOrNull()
                    ?: return null
            val scope = GlobalSearchScope.allScope(project)
            val simple = fqn.substringAfterLast('.')
            val cands = PyClassNameIndex.find(simple, project, scope)
            return cands.firstOrNull { it.qualifiedName == fqn }
        }

        when {
            payload.startsWith("py:") -> {
                val fqn = payload.removePrefix("py:").trim().ifEmpty { return null }
                val cls = resolveClass(fqn) ?: return null
                // Show documentation for the class
                return LinkResolveResult.resolvedTarget(PyClassDocTarget(cls))
            }
            payload.startsWith("py-src:") -> {
                val fqn = payload.removePrefix("py-src:").trim().ifEmpty { return null }
                val cls = resolveClass(fqn) ?: return null
                val vFile = cls.containingFile?.virtualFile ?: return null
                val offset = cls.textOffset
                // Jump to source
                return LinkResolveResult.asyncResult {
                    ApplicationManager.getApplication().invokeLater {
                        OpenFileDescriptor(cls.project, vFile, offset).navigate(true)
                    }
                    null
                }
            }
        }
        return null
    }
}
