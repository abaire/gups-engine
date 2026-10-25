package gups.harness

import gups.script.Ctx
import gups.script.DocExamples
import gups.script.ScriptApi
import gups.script.findBuiltinClasses
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HarnessApiTest {
    @Test
    fun `the model covers every harness builtin with docs`() {
        val harness = findBuiltinClasses(TestCtx::class.java)
        assertEquals(HarnessApi.BUILTIN_CLASSES.toSet(), harness.toSet(), "HarnessApi is out of date")
        val model = ScriptApi.model(HarnessApi.specs())
        val problems = ScriptApi.validate(model, findBuiltinClasses(Ctx::class.java) + harness)
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
        val exampleProblems = DocExamples.problems(model)
        assertTrue(exampleProblems.isEmpty(), exampleProblems.joinToString("\n"))
        assertEquals(listOf("diagnostic", "p", "t", "w"), model.harness.map { it.name })
    }
}
