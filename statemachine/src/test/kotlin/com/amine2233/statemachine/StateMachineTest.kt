package com.amine2233.statemachine

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

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

    @Test
    fun `an OnState handler can call allowedTransitions without deadlocking`() =
        runTest(timeout = 5.seconds) {
            val machine = machine()
            var seen: List<Transition>? = null

            machine.on(LifecycleEvent.OnState(published)) { seen = machine.allowedTransitions() }

            machine.fire(publish)

            assertEquals(listOf(archive), seen)
        }

    @Test
    fun `handlers read the old state before the move and the new state after it`() =
        runTest(timeout = 5.seconds) {
            val machine = machine()
            val reads = mutableListOf<String>()

            machine.on(LifecycleEvent.BeforeTransition(publish)) {
                reads += "before:${machine.canFire(publish)}:${machine.currentState}"
            }
            machine.on(LifecycleEvent.LeaveState(draft)) {
                reads += "leave:${machine.allowedTransitions()}"
            }
            machine.on(LifecycleEvent.OnState(published)) {
                reads += "onState:${machine.canFire(archive)}:${machine.currentState}"
            }
            machine.on(LifecycleEvent.AfterTransition(publish)) {
                reads += "after:${machine.canFire(archive)}"
            }

            machine.fire(publish)

            assertEquals(
                listOf(
                    "before:true:draft",
                    "leave:${listOf(publish)}",
                    "onState:true:published",
                    "after:true",
                ),
                reads,
            )
        }

    @Test
    fun `on and off from a handler apply from the next fire`() =
        runTest(timeout = 5.seconds) {
            val unpublish = Transition("unpublish", from = published, to = draft)
            val machine = StateMachine(initialState = draft, transitions = listOf(publish, unpublish))
            val calls = mutableListOf<String>()

            machine.on(LifecycleEvent.BeforeTransition(publish)) {
                calls += "before"
                machine.on(LifecycleEvent.OnTransition(publish)) { calls += "added" }
                machine.off(LifecycleEvent.OnState(published))
                machine.off(LifecycleEvent.BeforeTransition(publish))
            }
            machine.on(LifecycleEvent.OnState(published)) { calls += "removed" }

            machine.fire(publish)

            assertEquals(listOf("before", "removed"), calls)

            calls.clear()
            machine.fire(unpublish)
            machine.fire(publish)

            assertEquals(listOf("added"), calls)
        }

    @Test
    fun `fire from a handler of the same machine throws ReentrantFire`() =
        runTest(timeout = 5.seconds) {
            val machine = machine()
            var nested: TransitionError.ReentrantFire? = null

            machine.on(LifecycleEvent.OnState(published)) {
                nested = assertFailsWith<TransitionError.ReentrantFire> { machine.fire(archive) }
            }

            machine.fire(publish)

            assertEquals(archive, nested?.transition)
            assertEquals(published, machine.currentState)
        }

    @Test
    fun `an uncaught ReentrantFire propagates and leaves the machine usable`() =
        runTest(timeout = 5.seconds) {
            val machine = machine()

            machine.on(LifecycleEvent.OnState(published)) { machine.fire(archive) }

            assertFailsWith<TransitionError.ReentrantFire> { machine.fire(publish) }

            assertEquals(published, machine.currentState)
            assertEquals(listOf(archive), machine.allowedTransitions())
            machine.fire(archive)
            assertEquals(archived, machine.currentState)
        }

    @Test
    fun `fire from a handler of a different machine is not affected`() =
        runTest(timeout = 5.seconds) {
            val machine = machine()
            val other = machine()

            machine.on(LifecycleEvent.OnState(published)) { other.fire(publish) }

            machine.fire(publish)

            assertEquals(published, machine.currentState)
            assertEquals(published, other.currentState)
        }

    @Test
    fun `concurrent fires run one at a time`() =
        runTest(timeout = 5.seconds) {
            val machine = machine()
            val log = mutableListOf<String>()

            machine.on(LifecycleEvent.BeforeTransition(publish)) {
                delay(10)
                log += "publish:before"
            }
            machine.on(LifecycleEvent.LeaveState(draft)) {
                yield()
                log += "publish:leave"
            }
            machine.on(LifecycleEvent.OnState(published)) {
                delay(10)
                log += "publish:onState"
            }
            machine.on(LifecycleEvent.OnTransition(publish)) {
                yield()
                log += "publish:onTransition"
            }
            machine.on(LifecycleEvent.AfterTransition(publish)) {
                delay(10)
                log += "publish:after"
            }
            machine.on(LifecycleEvent.BeforeTransition(archive)) { log += "archive:before" }
            machine.on(LifecycleEvent.AfterTransition(archive)) { log += "archive:after" }

            val first = launch { machine.fire(publish) }
            val second = launch { machine.fire(archive) }
            first.join()
            second.join()

            assertEquals(
                listOf(
                    "publish:before",
                    "publish:leave",
                    "publish:onState",
                    "publish:onTransition",
                    "publish:after",
                    "archive:before",
                    "archive:after",
                ),
                log,
            )
            assertEquals(archived, machine.currentState)
        }

    @Test
    fun `an unconfined state collector can read the machine while fire moves the cursor`() =
        runTest(timeout = 5.seconds) {
            val machine = machine()
            val reads = mutableListOf<String>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                machine.state.collect { state ->
                    reads += "$state:${machine.allowedTransitions()}:${machine.canFire(archive)}"
                }
            }

            machine.fire(publish)

            assertEquals(
                listOf("draft:${listOf(publish)}:false", "published:${listOf(archive)}:true"),
                reads,
            )
        }

    @Test
    fun `an unconfined state collector can fire the machine without deadlocking`() =
        runTest(timeout = 5.seconds) {
            val machine = machine()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                machine.state.collect { state -> if (state == published) machine.fire(archive) }
            }

            machine.fire(publish)
            testScheduler.advanceUntilIdle()

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

    @Test
    fun `a guard evaluated by fire can read the machine without deadlocking`() =
        runTest(timeout = 5.seconds) {
            lateinit var machine: StateMachine
            var seenState: State? = null
            var seenAllowed: List<Transition>? = null
            var seenCanFireArchive: Boolean? = null
            machine =
                guardedMachine {
                    seenState = machine.currentState
                    seenAllowed = machine.allowedTransitions()
                    seenCanFireArchive = machine.canFire(archive)
                    true
                }

            machine.fire(publish)

            assertEquals(published, machine.currentState)
            assertEquals(draft, seenState)
            assertEquals(listOf(publish), seenAllowed)
            assertEquals(false, seenCanFireArchive)
        }

    @Test
    fun `a guard evaluated by canFire can read the machine without deadlocking`() =
        runTest(timeout = 5.seconds) {
            lateinit var machine: StateMachine
            var seenAllowed: List<Transition>? = null
            machine =
                guardedMachine {
                    seenAllowed = machine.allowedTransitions()
                    !machine.canFire(archive)
                }

            assertTrue(machine.canFire(publish))
            assertEquals(listOf(publish), seenAllowed)
        }

    @Test
    fun `a guard that fires the same machine throws ReentrantFire`() =
        runTest(timeout = 5.seconds) {
            lateinit var machine: StateMachine
            machine =
                guardedMachine {
                    machine.fire(publish)
                    true
                }

            assertFailsWith<TransitionError.ReentrantFire> { machine.fire(publish) }
            assertEquals(draft, machine.currentState)
        }

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
