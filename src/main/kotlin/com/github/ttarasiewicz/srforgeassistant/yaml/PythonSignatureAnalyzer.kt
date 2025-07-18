package com.github.ttarasiewicz.srforgeassistant.yaml

import com.github.ttarasiewicz.srforgeassistant.settings.MyPluginSettings
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PyParameter
import com.jetbrains.python.psi.stubs.PyClassNameIndex
import com.jetbrains.python.sdk.PythonSdkUtil




object PythonSignatureAnalyzer {
    fun findPythonClass(project: Project, className: String): PyClass? {
        if (!isOnlyClassName(className)) return findFullyQualifiedClass(project, className)
//        PsiDocumentManager.getInstance(project).getPsiFile(event.document)
        // Lookup only registered classes in class_registry.json
        val registeredClasses = PyClassNameIndex.find(className, project, GlobalSearchScope.projectScope(project))
        if (registeredClasses.size == 1){
            return registeredClasses.firstOrNull()
        }
        else if (registeredClasses.size > 1){
            return null
        }
        return null
    }

    fun isOnlyClassName(className: String): Boolean {
        return !className.contains(".")
    }

    private fun findFullyQualifiedClass(project: Project, qualifiedName: String): PyClass? {
        val parts = qualifiedName.split(".")
        if (parts.isEmpty()) return null
        val actualClassName = parts.last()

        val allCandidates = PyClassNameIndex.find(actualClassName, project, GlobalSearchScope.allScope(project))
        if (allCandidates.isEmpty()) return null

        val (projectClasses, externalClasses) = allCandidates.partitionClasses(project)

        projectClasses.firstOrNull { it.qualifiedName == qualifiedName }?.let { return it }

        val rootDir = MyPluginSettings.getInstance(project).state.projectRootDir
        if (qualifiedName.startsWith("$rootDir.")) {
            val stripped = qualifiedName.removePrefix("$rootDir.")
            projectClasses.firstOrNull { it.qualifiedName == stripped }?.let { return it }
        }

        // 2) For external libs (like torch), we require at least 1 dot => done
        // We already know we have a dotted name, so let's match with a qualified name
        externalClasses.firstOrNull { it.qualifiedName == qualifiedName }?.let { return it }

        // fallback if you want:
        // return externalClasses.firstOrNull() // up to you
        return null
    }

    // For convenience, your method that partitions classes into (project vs external)
    private fun Collection<PyClass>.partitionClasses(project: Project): Pair<List<PyClass>, List<PyClass>> {
        val projectBasePath = project.basePath
        // Optionally, find the module's sdk so we can also detect "sdk-based" classes
        val module = ModuleManager.getInstance(project).modules.firstOrNull()
        val sdk = module?.let { PythonSdkUtil.findPythonSdk(it) }

        return this.partition { pyClass ->
            val path = pyClass.containingFile.virtualFile?.path ?: return@partition false
            // We'll call it a "project class" if it's under project base path
            // (and NOT under the python sdk? You can do advanced checks if needed.)
            if (projectBasePath != null && path.contains(projectBasePath)) {
                true
            } else {
                false
            }
        }
    }

    fun getFunctionParameters(pyClass: PyClass?, functionName: String): List<PyParameter> {
        pyClass ?: return emptyList()

        val function: PyFunction? = pyClass.findMethodByName(functionName, false, null)
        return function?.parameterList?.parameters?.toList()?.filter { it.name != "self" } ?: emptyList()
    }

    fun getFullyQuallfiedName(project: Project, pyClass: PyClass): String? {
        // 1) Check class_registry.json
        val registeredModule = ClassRegistry.getModuleForClass(project, pyClass.name ?: "")
        if (registeredModule != null) return "$registeredModule.${pyClass.name}"

        // 2) Attempt to find PyClass in code
        val foundClasses = PyClassNameIndex.find(pyClass.name, project, GlobalSearchScope.projectScope(project))
        if (foundClasses.contains(pyClass)) {
            val qName = pyClass.qualifiedName ?: return null
            val rootDir = MyPluginSettings.getInstance(project).state.projectRootDir
            if (!qName.startsWith("$rootDir.")){
                return "$rootDir.$qName"
            }
            return qName
        }
        return pyClass.qualifiedName
    }
}