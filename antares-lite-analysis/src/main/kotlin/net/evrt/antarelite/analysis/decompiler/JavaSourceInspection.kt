package net.evrt.antarelite.analysis.decompiler

import com.sun.source.tree.*
import com.sun.source.util.JavacTask
import com.sun.source.util.Trees
import java.net.URI
import java.util.Locale
import javax.tools.Diagnostic
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.ToolProvider

internal data class SourceMethod(
    val name: String,
    val descriptor: String?,
    val declaration: String,
    val parameterCount: Int,
    val source: String,
)

internal data class JavaSourceInspection(
    val methods: List<SourceMethod>,
    val failures: List<String>,
)

/** Parse declarations without compiling, linking or loading any target classes. */
internal fun inspectJavaSource(
    source: String,
    className: String,
    knownClassNames: Set<String> = emptySet(),
): JavaSourceInspection {
    val compiler = ToolProvider.getSystemJavaCompiler()
        ?: return JavaSourceInspection(emptyList(), listOf("Java source inspection requires a JDK compiler."))
    val diagnostics = DiagnosticCollector<JavaFileObject>()
    val file = object : SimpleJavaFileObject(URI.create("string:///Reconstruction.java"), JavaFileObject.Kind.SOURCE) {
        override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = source
    }
    val methods = mutableListOf<SourceMethod>()
    val failures = mutableListOf<String>()
    compiler.getStandardFileManager(diagnostics, Locale.ROOT, Charsets.UTF_8).use { manager ->
        try {
            val task = compiler.getTask(null, manager, diagnostics, listOf("-proc:none"), null, listOf(file)) as JavacTask
            val units = task.parse().toList()
            val positions = Trees.instance(task).sourcePositions
            for (unit in units) {
                val packageName = unit.packageName?.toString().orEmpty()
                val imports = unit.imports.filterNot { it.isStatic }.map { it.qualifiedIdentifier.toString() }
                val expectedName = className.replace('/', '.').substringAfterLast('.')
                val declaredNames = mutableSetOf<String>()
                fun collectTypes(tree: ClassTree, owner: String) {
                    val name = owner + tree.simpleName
                    declaredNames += name
                    tree.members.filterIsInstance<ClassTree>().forEach { collectTypes(it, "$name\$") }
                }
                unit.typeDecls.filterIsInstance<ClassTree>().forEach {
                    collectTypes(it, if (packageName.isEmpty()) "" else "$packageName.")
                }
                val knownNames = knownClassNames + declaredNames
                fun binaryType(name: String): String? {
                    knownNames.singleOrNull { it.replace('$', '.') == name }?.let { return it }
                    var candidate = name
                    while (true) {
                        if (ClassLoader.getSystemResource(candidate.replace('.', '/') + ".class") != null) return candidate
                        val dot = candidate.lastIndexOf('.')
                        if (dot < 0) return null
                        candidate = candidate.substring(0, dot) + '$' + candidate.substring(dot + 1)
                    }
                }
                fun visitClass(tree: ClassTree, binaryName: String, inheritedBounds: Map<String, Tree?>) {
                    val bounds = inheritedBounds + tree.typeParameters.associate { parameter ->
                        parameter.name.toString() to parameter.bounds.firstOrNull()
                    }
                    if (binaryName == expectedName) {
                        for (method in tree.members.filterIsInstance<MethodTree>()) {
                            val start = positions.getStartPosition(unit, method).toInt()
                            val end = positions.getEndPosition(unit, method).toInt()
                            if (start < 0 || end !in (start + 1)..source.length) continue
                            val methodBounds = bounds + method.typeParameters.associate { parameter ->
                                parameter.name.toString() to parameter.bounds.firstOrNull()
                            }
                            fun descriptor(type: Tree?, resolving: Set<String> = emptySet()): String? {
                                if (type == null) return "V"
                                return when (type) {
                                    is PrimitiveTypeTree -> when (type.primitiveTypeKind.name) {
                                        "BOOLEAN" -> "Z"; "BYTE" -> "B"; "CHAR" -> "C"; "SHORT" -> "S"
                                        "INT" -> "I"; "LONG" -> "J"; "FLOAT" -> "F"; "DOUBLE" -> "D"; "VOID" -> "V"
                                        else -> null
                                    }
                                    is ArrayTypeTree -> descriptor(type.type, resolving)?.let { "[$it" }
                                    is ParameterizedTypeTree -> descriptor(type.type, resolving)
                                    is AnnotatedTypeTree -> descriptor(type.underlyingType, resolving)
                                    else -> {
                                        val name = type.toString()
                                        if (name in methodBounds) {
                                            if (name in resolving) null else methodBounds[name]?.let {
                                                descriptor(it, resolving + name)
                                            } ?: "Ljava/lang/Object;"
                                        } else {
                                            val imported = imports.firstOrNull { it.substringAfterLast('.') == name }
                                            val local = if (packageName.isEmpty()) name else "$packageName.$name"
                                            val javaLang = "java.lang.$name"
                                            val candidates = when {
                                                imported != null -> listOf(imported)
                                                '.' in name -> {
                                                    val first = name.substringBefore('.')
                                                    val owner = imports.firstOrNull { it.substringAfterLast('.') == first }
                                                    listOf(owner?.let { it + name.removePrefix(first) } ?: name)
                                                }
                                                name == tree.simpleName.toString() -> listOf(className.replace('/', '.'))
                                                else -> listOf(local, javaLang) + imports.filter { it.endsWith(".*") }
                                                    .map { it.removeSuffix("*") + name }
                                            }
                                            // A wildcard is only resolved when a JDK resource proves its type.
                                            val nested = declaredNames.filter {
                                                it.substringAfterLast('$').substringAfterLast('.') == name
                                            }.singleOrNull()
                                            val resolved = if (imported != null || '.' in name || name == tree.simpleName.toString()) {
                                                binaryType(candidates.single()) ?: candidates.single()
                                            } else if (nested != null) {
                                                nested
                                            } else {
                                                binaryType(local) ?: candidates.drop(1).mapNotNull(::binaryType).distinct()
                                                    .singleOrNull() ?: local.takeIf { imports.none { item -> item.endsWith(".*") } }
                                            }
                                            resolved?.let { "L${it.replace('.', '/')};" }
                                        }
                                    }
                                }
                            }
                            val arguments = method.parameters.map { descriptor(it.type) }
                            val returnType = descriptor(method.returnType)
                            val signature = if (arguments.all { it != null } && returnType != null) {
                                "(${arguments.joinToString("")})$returnType"
                            } else null
                            val bodyStart = method.body?.let { positions.getStartPosition(unit, it).toInt() } ?: end
                            methods += SourceMethod(
                                method.name.toString(), signature,
                                source.substring(start, bodyStart).trim(), method.parameters.size,
                                source.substring(start, end),
                            )
                        }
                    }
                    tree.members.filterIsInstance<ClassTree>().forEach {
                        visitClass(it, "$binaryName\$${it.simpleName}", bounds)
                    }
                }
                unit.typeDecls.filterIsInstance<ClassTree>().forEach {
                    visitClass(it, it.simpleName.toString(), emptyMap())
                }
            }
        } catch (failure: Exception) {
            failures += "Java source parsing failed: ${failure.message}"
        }
    }
    failures += diagnostics.diagnostics.filter { it.kind == Diagnostic.Kind.ERROR }.take(20).map {
        "Java syntax error at line ${it.lineNumber}, column ${it.columnNumber}: ${it.getMessage(Locale.ROOT)}"
    }
    val marker = Regex("^\\s*(?://|/\\*|\\*)\\s*(?:\\\$VF:\\s*)?" +
        "(?:This method has failed to decompile|Exception decompiling|Couldn't be decompiled|" +
        "Could not be decompiled|Unable to fully structure code)", RegexOption.IGNORE_CASE)
    source.lineSequence().forEachIndexed { line, text ->
        if (marker.containsMatchIn(text) || (failures.isNotEmpty() && text.contains("** GOTO"))) {
            failures += "Incomplete reconstruction at line ${line + 1}: ${text.trim().take(200)}"
        }
    }
    return JavaSourceInspection(methods, failures.distinct())
}
