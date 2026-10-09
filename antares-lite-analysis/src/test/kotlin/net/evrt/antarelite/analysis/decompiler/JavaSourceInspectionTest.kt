package net.evrt.antarelite.analysis.decompiler

import kotlin.test.*

class JavaSourceInspectionTest {
    @Test
    fun `complete signatures distinguish same arity overloads and ignore brace text`() {
        val source = """
            import java.util.List;
            class Example {
                Example() {}
                private void a(Example value) { String braces = "{}"; /* } */ }
                private Example a(long value) { return null; }
                int pick(byte[][] bytes) { return bytes.length; }
                long pick(char c) { return c; }
                <T extends Number> T bounded(T value) { return value; }
                List<String> generic(List<Integer> values) { return null; }
                static class Nested { void a(long value) {} }
            }
        """.trimIndent()
        val inspection = inspectJavaSource(source, "Example")
        assertTrue(inspection.failures.isEmpty(), inspection.failures.toString())
        assertEquals(setOf("(LExample;)V", "(J)LExample;"),
            inspection.methods.filter { it.name == "a" }.map { it.descriptor }.toSet())
        assertEquals("()V", inspection.methods.single { it.name == "<init>" }.descriptor)
        assertTrue(inspection.methods.any { it.descriptor == "([[B)I" })
        assertTrue(inspection.methods.any { it.descriptor == "(C)J" })
        assertEquals("(Ljava/lang/Number;)Ljava/lang/Number;",
            inspection.methods.single { it.name == "bounded" }.descriptor)
        assertEquals("(Ljava/util/List;)Ljava/util/List;",
            inspection.methods.single { it.name == "generic" }.descriptor)
        assertEquals("(J)V", inspectJavaSource(source, "Example\$Nested").methods.single().descriptor)
    }

    @Test
    fun `reports CFR and Vineflower failures and malformed Java`() {
        val cfr = inspectJavaSource("""
            class Broken {
                // Exception decompiling
                void missing() { throw new IllegalStateException("Decompilation failed"); }
                void flow() { ** GOTO label; }
            }
        """.trimIndent(), "Broken")
        assertTrue(cfr.failures.any { it.contains("Exception decompiling") })
        assertTrue(cfr.failures.any { it.contains("syntax error") })
        val vf = inspectJavaSource("""
            class Broken {
                // $${"VF"}: Couldn't be decompiled
                void missing() {}
            }
        """.trimIndent(), "Broken")
        assertTrue(vf.failures.any { it.contains("Couldn't be decompiled") })
    }

    @Test
    fun `nested imported array types resolve to binary descriptors and user strings are not failures`() {
        val inspection = inspectJavaSource("""
            import java.util.Map.Entry;
            class Owner {
                Entry[] pairs(Entry... values) {
                    String text = "Exception decompiling ** GOTO";
                    throw new IllegalStateException("Decompilation failed");
                }
            }
        """.trimIndent(), "Owner")
        assertTrue(inspection.failures.isEmpty(), inspection.failures.toString())
        assertEquals("([Ljava/util/Map\$Entry;)[Ljava/util/Map\$Entry;", inspection.methods.single().descriptor)
    }

    @Test
    fun `does not confuse methods declared in a nested class with the owner`() {
        val inspection = inspectJavaSource("""
            package sample;
            class Owner {
                static class Child { long a(long x) { return x; } }
                int a(int x) { return x; }
            }
        """.trimIndent(), "sample.Owner")
        assertEquals(listOf("(I)I"), inspection.methods.map { it.descriptor })
    }
}
