package com.github.ttarasiewicz.srforgeassistant

import com.intellij.codeInsight.documentation.DocumentationManagerProtocol
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.ProjectManager
import com.intellij.platform.backend.documentation.DocumentationLinkHandler
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.LinkResolveResult

class PyClassDocLinkHandler : DocumentationLinkHandler {

    override fun resolveLink(target: DocumentationTarget, url: String): LinkResolveResult? {
        if (!url.startsWith(DocumentationManagerProtocol.PSI_ELEMENT_PROTOCOL)) return null
        val payload = url.removePrefix(DocumentationManagerProtocol.PSI_ELEMENT_PROTOCOL)

        val project = (target as? PyClassDocTarget)?.element?.project
            ?: (target as? PyFunctionDocTarget)?.element?.project
            ?: ProjectManager.getInstance().openProjects.firstOrNull()
            ?: return null

        when {
            payload.startsWith("py:") -> {
                val fqn = payload.removePrefix("py:").trim().ifEmpty { return null }
                val cls = TargetUtils.resolveTargetClass(fqn, project)
                if (cls != null) return LinkResolveResult.resolvedTarget(PyClassDocTarget(cls))
                val func = TargetUtils.resolveTargetFunction(fqn, project) ?: return null
                return LinkResolveResult.resolvedTarget(PyFunctionDocTarget(func))
            }
            payload.startsWith("py-src:") -> {
                val fqn = payload.removePrefix("py-src:").trim().ifEmpty { return null }
                val resolved = TargetUtils.resolveTargetClass(fqn, project)
                    ?: TargetUtils.resolveTargetFunction(fqn, project)
                    ?: return null
                val vFile = resolved.containingFile?.virtualFile ?: return null
                val offset = resolved.textOffset
                return LinkResolveResult.asyncResult {
                    ApplicationManager.getApplication().invokeLater {
                        OpenFileDescriptor(resolved.project, vFile, offset).navigate(true)
                    }
                    null
                }
            }
        }
        return null
    }
}
