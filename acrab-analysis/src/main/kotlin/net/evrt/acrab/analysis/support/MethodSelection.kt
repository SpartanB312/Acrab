package net.evrt.acrab.analysis.support

import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode

/** How a method request was matched against the class file. */
enum class MethodMatchKind {
    /** The caller supplied a descriptor that matched exactly one method. */
    DESCRIPTOR,

    /** The caller supplied no descriptor and the name is unambiguous. */
    NAME_ONLY,
}

/** One resolved method plus the proof of how it was addressed. */
data class ResolvedMethod(
    val className: String,
    val method: MethodNode,
    val matchKind: MethodMatchKind,
    val requestedDescriptor: String? = null,
) {
    val descriptor: String get() = method.desc

    /** True only when the caller's descriptor selected this exact method. */
    val exactDescriptorMatch: Boolean get() = matchKind == MethodMatchKind.DESCRIPTOR
}

/**
 * Raised when a method request cannot be resolved to exactly one method. Callers must fail rather
 * than fall back to "first method with this name", which silently produced wrong evidence.
 */
class MethodResolutionException(message: String) : IllegalArgumentException(message)

object MethodSelector {
    /**
     * Resolve `methodName` (+ optional `methodDescriptor`) against a parsed class.
     *
     * A descriptor that matches no method, or a name-only request that matches several overloads,
     * is a hard error. Resolution never falls back to the first candidate.
     */
    fun resolve(classNode: ClassNode, methodName: String, methodDescriptor: String?): ResolvedMethod {
        val displayName = classNode.name.replace('/', '.')
        val requested = methodDescriptor?.trim()?.takeIf { it.isNotEmpty() }
        val named = classNode.methods.filter { it.name == methodName }
        if (named.isEmpty()) {
            val known = classNode.methods.map { it.name }.distinct().sorted()
            val hint = if (known.isEmpty()) "" else " Known methods: ${known.joinToString()}."
            throw MethodResolutionException("Method not found in $displayName: $methodName$hint")
        }
        if (requested == null) {
            val descriptors = named.map { it.desc }.distinct()
            if (descriptors.size > 1) {
                throw MethodResolutionException(
                    "$displayName#$methodName is overloaded; supply methodDescriptor to select one. " +
                        "Available descriptors: ${descriptors.joinToString()}",
                )
            }
            return ResolvedMethod(displayName, named.single(), MethodMatchKind.NAME_ONLY)
        }
        val matched = named.filter { it.desc == requested }
        if (matched.isEmpty()) {
            val available = named.map { it.desc }.distinct()
            throw MethodResolutionException(
                "No method in $displayName matches $methodName$requested. " +
                    "Available descriptors: ${available.joinToString()}",
            )
        }
        check(matched.size == 1) {
            "$displayName#$methodName$requested resolved to ${matched.size} methods; the class file is malformed."
        }
        return ResolvedMethod(displayName, matched.single(), MethodMatchKind.DESCRIPTOR, requested)
    }
}
