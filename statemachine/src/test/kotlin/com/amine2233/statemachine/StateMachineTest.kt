package com.amine2233.statemachine

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StateMachineTest {
    private val draft = State("draft")
    private val published = State("published")
    private val archived = State("archived")

    private val publish = Transition("publish", from = draft, to = published)
    private val archive = Transition("archive", from = published, to = archived)

    private fun machine() =
        StateMachine(
            initialState = draft,
            transitions = listOf(publish, archive),
        )

    private class HandlerFailure : RuntimeException("handler failed")

    private suspend fun StateMachine.recordLifecycle(order: MutableList<String>) {
        on(LifecycleEvent.BeforeTransition(publish)) { order += "before" }
        on(LifecycleEvent.LeaveState(draft)) { order += "leave" }
        on(LifecycleEvent.OnState(published)) { order += "onState" }
        on(LifecycleEvent.OnTransition(publish)) { order += "onTransition" }
        on(LifecycleEvent.AfterTransition(publish)) { order += "after" }
    }

    @Test
    fun `fire moves the cursor along a known transition`() =
        runTest {
            val machine = machine()

            machine.fire(publish)

            assertEquals(published, machine.currentState)
        }

    @Test
    fun `firing a transition unknown to the graph throws Unknown`() =
        runTest {
            val machine = machine()
            val foreign = Transition("foreign", from = draft, to = archived)

            assertFailsWith<TransitionError.Unknown> {
                machine.fire(foreign)
            }
        }

    @Test
    fun `firing a transition that does not start from the current state throws NotAllowed`() =
        runTest {
            val machine = machine()

            // archive requires "published", but we're still in "draft".
            assertFailsWith<TransitionError.NotAllowed> {
                machine.fire(archive)
            }
        }

    @Test
    fun `allowedTransitions reflects only the current state`() =
        runTest {
            val machine = machine()

            assertEquals(listOf(publish), machine.allowedTransitions())

            machine.fire(publish)

            assertEquals(listOf(archive), machine.allowedTransitions())
        }

    @Test
    fun `canFire mirrors allowedTransitions without mutating state`() =
        runTest {
            val machine = machine()

            assertTrue(machine.canFire(publish))
            assertFalse(machine.canFire(archive))
            assertEquals(draft, machine.currentState) // canFire never moves the cursor
        }

    @Test
    fun `observers run in the fixed lifecycle order and see the fired userInfo`() =
        runTest {
            val machine = machine()
            val order = mutableListOf<String>()

            machine.on(LifecycleEvent.BeforeTransition(publish)) { order += "before" }
            machine.on(LifecycleEvent.LeaveState(draft)) { order += "leave" }
            machine.on(LifecycleEvent.OnState(published)) { userInfo ->
                order += "onState:${userInfo?.get("actor")}"
            }
            machine.on(LifecycleEvent.OnTransition(publish)) { order += "onTransition" }
            machine.on(LifecycleEvent.AfterTransition(publish)) { order += "after" }

            machine.fire(publish, userInfo = mapOf("actor" to "editor"))

            assertEquals(
                listOf("before", "leave", "onState:editor", "onTransition", "after"),
                order,
            )
        }

    @Test
    fun `registering a second handler for the same event replaces the first`() =
        runTest {
            val machine = machine()
            val calls = mutableListOf<String>()

            machine.on(LifecycleEvent.OnState(published)) { calls += "first" }
            machine.on(LifecycleEvent.OnState(published)) { calls += "second" }

            machine.fire(publish)

            assertEquals(listOf("second"), calls)
        }

    @Test
    fun `a machine can be resumed from a persisted state name`() =
        runTest {
            val resumed =
                StateMachine(
                    initialState = State("published"),
                    transitions = listOf(publish, archive),
                )

            assertEquals(listOf(archive), resumed.allowedTransitions())
        }

    // StateFlow is conflated: these ordering checks hold because the collector
    // runs on an unconfined dispatcher and so keeps up with every emission.
    @Test
    fun `state emits every state in order to a collector that keeps up`() =
        runTest {
            val machine = machine()
            val seen = mutableListOf<State>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { machine.state.toList(seen) }

            machine.fire(publish)
            machine.fire(archive)

            assertEquals(listOf(draft, published, archived), seen)
        }

    @Test
    fun `state value always agrees with currentState`() =
        runTest {
            val machine = machine()

            assertEquals(machine.currentState, machine.state.value)
            machine.fire(publish)
            assertEquals(machine.currentState, machine.state.value)
            machine.fire(archive)
            assertEquals(machine.currentState, machine.state.value)
        }

    @Test
    fun `rejected transitions emit nothing to a collector that keeps up`() =
        runTest {
            val machine = machine()
            val seen = mutableListOf<State>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { machine.state.toList(seen) }
            val foreign = Transition("foreign", from = draft, to = archived)

            assertFailsWith<TransitionError.NotAllowed> { machine.fire(archive) }
            assertFailsWith<TransitionError.Unknown> { machine.fire(foreign) }

            assertEquals(listOf(draft), seen)
            assertEquals(draft, machine.state.value)
        }

    @Test
    fun `state still holds the old state in LeaveState and the new one in OnState`() =
        runTest {
            val machine = machine()
            var duringLeave: State? = null
            var duringOnState: State? = null

            machine.on(LifecycleEvent.LeaveState(draft)) { duringLeave = machine.state.value }
            machine.on(LifecycleEvent.OnState(published)) { duringOnState = machine.state.value }

            machine.fire(publish)

            assertEquals(draft, duringLeave)
            assertEquals(published, duringOnState)
        }

    @Test
    fun `a throw in BeforeTransition vetoes the transition and skips every later handler`() =
        runTest {
            val machine = machine()
            val order = mutableListOf<String>()
            val failure = HandlerFailure()
            machine.recordLifecycle(order)
            machine.on(LifecycleEvent.BeforeTransition(publish)) { throw failure }

            val thrown = assertFailsWith<HandlerFailure> { machine.fire(publish) }

            assertSame(failure, thrown)
            assertEquals(draft, machine.currentState)
            assertEquals(emptyList(), order)
        }

    @Test
    fun `a throw in LeaveState vetoes the transition and skips the post-move handlers`() =
        runTest {
            val machine = machine()
            val order = mutableListOf<String>()
            val failure = HandlerFailure()
            machine.recordLifecycle(order)
            machine.on(LifecycleEvent.LeaveState(draft)) { throw failure }

            val thrown = assertFailsWith<HandlerFailure> { machine.fire(publish) }

            assertSame(failure, thrown)
            assertEquals(draft, machine.currentState)
            assertEquals(listOf("before"), order)
        }

    @Test
    fun `a throw in OnState keeps the move and skips the remaining handlers`() =
        runTest {
            val machine = machine()
            val order = mutableListOf<String>()
            val failure = HandlerFailure()
            machine.recordLifecycle(order)
            machine.on(LifecycleEvent.OnState(published)) { throw failure }

            val thrown = assertFailsWith<HandlerFailure> { machine.fire(publish) }

            assertSame(failure, thrown)
            assertEquals(published, machine.currentState)
            assertEquals(listOf("before", "leave"), order)
        }

    @Test
    fun `a vetoed transition can be fired again once the throwing handler is removed`() =
        runTest {
            val machine = machine()
            machine.on(LifecycleEvent.LeaveState(draft)) { throw HandlerFailure() }
            assertFailsWith<HandlerFailure> { machine.fire(publish) }

            assertTrue(machine.canFire(publish))
            assertEquals(listOf(publish), machine.allowedTransitions())

            machine.off(LifecycleEvent.LeaveState(draft))
            machine.fire(publish)

            assertEquals(published, machine.currentState)
        }

    @Test
    fun `transitions out of the new state can be fired after a post-move throw`() =
        runTest {
            val machine = machine()
            machine.on(LifecycleEvent.OnState(published)) { throw HandlerFailure() }
            assertFailsWith<HandlerFailure> { machine.fire(publish) }

            assertEquals(listOf(archive), machine.allowedTransitions())

            machine.fire(archive)

            assertEquals(archived, machine.currentState)
        }
}
