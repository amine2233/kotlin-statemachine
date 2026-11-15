package com.amine2233.statemachine

import kotlinx.coroutines.test.runTest
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.test.Test
import kotlin.test.assertEquals

class StateMachineJvmTest {
    private val draft = State("draft")
    private val published = State("published")
    private val archived = State("archived")

    private val publish = Transition("publish", from = draft, to = published)
    private val archive = Transition("archive", from = published, to = archived)

    private fun guardedMachine(guard: suspend (UserInfo?) -> Boolean) =
        StateMachine(
            initialState = draft,
            transitions = listOf(Transition("publish", from = draft, to = published, guard = guard), archive),
        )

    @Test
    fun `the 1_0_0 three-argument Transition constructor still exists on the JVM`() {
        val constructor =
            Transition::class.java.getConstructor(String::class.java, State::class.java, State::class.java)

        val transition = constructor.newInstance("publish", draft, published)

        assertEquals(publish, transition)
        assertEquals(null, transition.guard)
    }

    @Test
    fun `the 1_0_0 one-argument canFire still exists on the JVM`() =
        runTest {
            val canFire =
                StateMachine::class.java.getMethod("canFire", Transition::class.java, Continuation::class.java)
            val machine = guardedMachine { userInfo -> userInfo != null }

            val result =
                suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
                    canFire.invoke(machine, publish, continuation)
                }

            assertEquals(false, result)
        }
}
