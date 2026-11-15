package com.amine2233.statemachine

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
}
