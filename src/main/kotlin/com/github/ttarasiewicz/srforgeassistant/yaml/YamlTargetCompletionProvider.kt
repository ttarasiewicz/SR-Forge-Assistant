package com.github.ttarasiewicz.srforgeassistant.yaml

import com.github.ttarasiewicz.srforgeassistant.settings.MyPluginSettings
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.AutoCompletionPolicy
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.project.Project
import com.intellij.util.ProcessingContext
import com.jetbrains.python.psi.stubs.PyClassNameIndex
import com.jetbrains.python.psi.stubs.PyModuleNameIndex
import org.jetbrains.yaml.psi.YAMLKeyValue
import com.intellij.psi.search.GlobalSearchScope
import com.jetbrains.python.psi.PyClass
import com.intellij.icons.AllIcons

class YamlTargetCompletionProvider : CompletionProvider<CompletionParameters>() {

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        val position = parameters.position
        if (position.parent.parent !is YAMLKeyValue) return
        val project = position.project

        // 1) Get suggestions from class_registry.json
        val registrySuggestions = getRegistryClasses(project)
        for (pyClass in registrySuggestions) {
            val className = pyClass.name ?: continue
            val moduleName = findModuleFor(project, className)
            val lookup = LookupElementBuilder.create(pyClass)
                .withTypeText(moduleName, true)
                .withTailText(" (Registered)", true)  // Tail text to indicate it's registered
                .withIcon(AllIcons.Nodes.Plugin)       // Optionally use an icon for registered classes
            val prioritized = PrioritizedLookupElement.withPriority(lookup, 1000.0)
            result.addElement(prioritized)
        }

        // 2) Get suggestions from project classes (full qualified names)
        val projectSuggestions = getAllProjectClasses(project)
        for (pyClass in projectSuggestions) {
            val className = pyClass.name ?: continue
            val moduleName = findModuleFor(project, className) ?: continue
            val lookup = LookupElementBuilder.create(pyClass)
                .withTypeText(moduleName, true)
                .withTailText(" (Project)", true)
                .withIcon(AllIcons.Nodes.Class)
                .withInsertHandler { insertContext, _ ->
                    val fqn = "$moduleName.$className"
                    insertContext.document.replaceString(insertContext.startOffset, insertContext.tailOffset, fqn)
                }
            val prioritized = PrioritizedLookupElement.withPriority(lookup, 100.0)
            result.addElement(prioritized)

            val fullLookup = LookupElementBuilder.create(pyClass, "$moduleName.$className")
                .withTypeText(moduleName, true)
                .withTailText(" (Project)", true)
                .withIcon(AllIcons.Nodes.Class)
            val fullPrioritized = PrioritizedLookupElement.withPriority(fullLookup, 0.0)
            result.addElement(fullPrioritized)
        }
    }

    private fun findModuleFor(project: Project, className: String): String? {
        // 1) Check class_registry.json
        val registeredModule = ClassRegistry.getModuleForClass(project, className)
        if (registeredModule != null) return registeredModule

        // 2) Attempt to find PyClass in code
        val foundClasses = PyClassNameIndex.find(className, project, GlobalSearchScope.projectScope(project))
        val pyClass = foundClasses.firstOrNull() ?: return null
        val qName = pyClass.qualifiedName ?: return null
        val qNameModule = qName.substringBeforeLast('.', missingDelimiterValue = qName)
        val rootDir = MyPluginSettings.getInstance(project).state.projectRootDir
        if (!qNameModule.startsWith("$rootDir.")){
            return "$rootDir.$qNameModule"
        }

        return qNameModule
    }

    /**
     * All simple class names from `class_registry.json`.
     */
    private fun getRegistryClasses(project: Project): List<PyClass> {
        val keys = ClassRegistry.getAllClasses(project)
        // use PyClassNameIndex.find() to find PyClass instances
        val result = mutableListOf<PyClass>()
        for (key in keys) {
            val classes = PyClassNameIndex.find(key, project, GlobalSearchScope.projectScope(project))
            result += classes
        }
        return result
    }

    /**
     * All project classes from the code that sits in the 'core' module.
     */
    fun getAllProjectClasses(project: Project): List<PyClass> {
        val result = mutableListOf<PyClass>()
        val allNames = PyClassNameIndex.allKeys(project) // e.g. {"Preprocessor", "MyClass", "Adam", ...}

        // We'll search each class name in the project scope
        val projectScope = GlobalSearchScope.projectScope(project)

        for (name in allNames) {
            // Find all PyClass with this name in *projectScope*
            val classes = PyClassNameIndex.find(name, project, projectScope)
            // add them to result
            result += classes
        }
        return result
    }
}