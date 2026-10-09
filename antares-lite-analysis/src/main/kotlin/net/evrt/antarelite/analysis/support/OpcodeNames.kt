package net.evrt.antarelite.analysis.support

import org.objectweb.asm.util.Printer

/**
 * Complete JVM mnemonic table. The previous partial `when` left roughly 80 percent of
 * instructions reported as `OPCODE_nnn`, which forced every consumer to embed a second decoder.
 */
object OpcodeNames {
    private val names: Array<String> = Array(Printer.OPCODES.size) { index ->
        Printer.OPCODES[index]?.takeIf { it.isNotBlank() } ?: "OPCODE_$index"
    }

    fun of(opcode: Int): String = names.getOrElse(opcode) { "OPCODE_$opcode" }
}
