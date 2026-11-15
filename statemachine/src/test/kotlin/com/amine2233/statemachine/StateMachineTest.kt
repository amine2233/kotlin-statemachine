package com.amine2233.statemachine

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

    private fun guardedMachine(guard: suspend (UserInfo?) -> Boolean) =
        StateMachine(
            initialState = draft,
            transitions = listOf(Transition("publish", from = draft, to = published, guard = guard), archive),
        )

    @Test
    fun `a passing guard lets the transition fire and run every handler`() =
        runTest {
            val machine = guardedMachine { true }
            val order = mutableListOf<String>()
            machine.recordLifecycle(order)

            machine.fire(publish)

            assertEquals(published, machine.currentState)
            assertEquals(listOf("before", "leave", "onState", "onTransition", "after"), order)
        }

    @Test
    fun `a rejecting guard throws GuardRejected without moving or running handlers`() =
        runTest {
            val machine = guardedMachine { false }
            val order = mutableListOf<String>()
            machine.recordLifecycle(order)

            val thrown = assertFailsWith<TransitionError.GuardRejected> { machine.fire(publish) }

            assertEquals(publish, thrown.transition)
            assertEquals(draft, machine.currentState)
            assertEquals(emptyList(), order)
        }

    @Test
    fun `canFire evaluates the guard`() =
        runTest {
            assertTrue(guardedMachine { true }.canFire(publish))
            assertFalse(guardedMachine { false }.canFire(publish))
        }

    @Test
    fun `a guard can decide from userInfo`() =
        runTest {
            val machine = guardedMachine { userInfo -> (userInfo?.get("cart") as? List<*>).orEmpty().isNotEmpty() }
            val emptyCart = mapOf("cart" to emptyList<String>())
            val fullCart = mapOf("cart" to listOf("burger"))

            assertFalse(machine.canFire(publish, emptyCart))
            assertTrue(machine.canFire(publish, fullCart))

            assertFailsWith<TransitionError.GuardRejected> { machine.fire(publish, emptyCart) }
            assertEquals(draft, machine.currentState)

            machine.fire(publish, fullCart)
            assertEquals(published, machine.currentState)
        }

    @Test
    fun `the guard receives the exact userInfo passed to fire`() =
        runTest {
            val received = mutableListOf<UserInfo?>()
            val machine =
                guardedMachine { userInfo ->
                    received += userInfo
                    true
                }
            val userInfo = mapOf("actor" to "editor")

            machine.fire(publish, userInfo)

            assertEquals(1, received.size)
            assertSame(userInfo, received.single())
        }

    @Test
    fun `transitions differing only in their guard are equal`() {
        val guarded = Transition("publish", from = draft, to = published, guard = { false })

        assertEquals(publish, guarded)
        assertEquals(publish.hashCode(), guarded.hashCode())
        assertEquals(publish.toString(), guarded.toString())
    }

    @Test
    fun `an equal transition without a guard cannot bypass the registered guard`() =
        runTest {
            val machine = guardedMachine { false }
            val unguarded = Transition("publish", from = draft, to = published)

            assertFailsWith<TransitionError.GuardRejected> { machine.fire(unguarded) }
            assertFalse(machine.canFire(unguarded))
            assertEquals(draft, machine.currentState)
        }

    @Test
    fun `only the registered guard runs, never the caller's copy`() =
        runTest {
            var callerGuardCalls = 0
            val machine = guardedMachine { true }
            val callersCopy =
                Transition("publish", from = draft, to = published, guard = {
                    callerGuardCalls++
                    false
                })

            assertTrue(machine.canFire(callersCopy))
            machine.fire(callersCopy)

            assertEquals(published, machine.currentState)
            assertEquals(0, callerGuardCalls)
        }

    @Test
    fun `a throwing guard propagates unchanged and leaves the machine usable`() =
        runTest {
            val failure = HandlerFailure()
            var shouldThrow = true
            val machine =
                guardedMachine {
                    if (shouldThrow) throw failure
                    true
                }
            val order = mutableListOf<String>()
            machine.recordLifecycle(order)

            assertSame(failure, assertFailsWith<HandlerFailure> { machine.fire(publish) })
            assertSame(failure, assertFailsWith<HandlerFailure> { machine.canFire(publish) })
            assertEquals(draft, machine.currentState)
            assertEquals(emptyList(), order)

            shouldThrow = false
            machine.fire(publish)

            assertEquals(published, machine.currentState)
        }

    @Test
    fun `NotAllowed is thrown before the guard is evaluated`() =
        runTest {
            var guardCalls = 0
            val guardedArchive =
                Transition("archive", from = published, to = archived, guard = {
                    guardCalls++
                    true
                })
            val machine = StateMachine(initialState = draft, transitions = listOf(publish, guardedArchive))

            assertFailsWith<TransitionError.NotAllowed> { machine.fire(guardedArchive) }
            assertFalse(machine.canFire(guardedArchive))
            assertEquals(0, guardCalls)
        }
}
