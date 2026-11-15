package com.amine2233.statemachine

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StateMachineDslTest {
    private val draft = State("draft")
    private val published = State("published")
    private val archived = State("archived")

    private val publish = Transition("publish", from = draft, to = published)
    private val archive = Transition("archive", from = published, to = archived)

    private fun handBuilt() = StateMachine(initialState = draft, transitions = listOf(publish, archive))

    private fun dslBuilt() =
        stateMachine(initial = "draft") {
            state("draft") { on("publish") goesTo "published" }
            transition("archive", from = "published", to = "archived")
        }

    private suspend fun assertSameFailure(
        handBuilt: StateMachine,
        dslBuilt: StateMachine,
        fire: suspend StateMachine.() -> Unit,
    ): Throwable {
        val handState = handBuilt.currentState
        val dslState = dslBuilt.currentState
        val expected = runCatching { handBuilt.fire() }.exceptionOrNull()
        val actual = assertNotNull(runCatching { dslBuilt.fire() }.exceptionOrNull())
        assertTrue(expected is TransitionError, "hand-built machine threw $expected")
        assertEquals(expected::class, actual::class)
        assertEquals(expected.message, actual.message)
        assertEquals(handState, handBuilt.currentState)
        assertEquals(dslState, dslBuilt.currentState)
        return actual
    }

    @Test
    fun `stateMachine builds a machine that fires by name through referenced states`() =
        runTest {
            val machine = dslBuilt()

            machine.fire("publish")
            assertEquals(State("published"), machine.currentState)

            machine.fire("archive")
            assertEquals(State("archived"), machine.currentState)
        }

    @Test
    fun `fire with a transition value behaves like on a hand-built machine`() =
        runTest {
            val hand = handBuilt()
            val dsl = dslBuilt()

            hand.fire(publish)
            dsl.fire(publish)

            assertEquals(hand.currentState, dsl.currentState)
        }

    @Test
    fun `canFire allowedTransitions and transition named match a hand-built machine`() =
        runTest {
            val hand = handBuilt()
            val dsl = dslBuilt()

            assertEquals(hand.canFire(publish), dsl.canFire(publish))
            assertEquals(hand.canFire(archive), dsl.canFire(archive))
            assertEquals(hand.allowedTransitions(), dsl.allowedTransitions())
            assertEquals(hand.transition(named = "publish"), dsl.transition(named = "publish"))
            assertNull(dsl.transition(named = "archive"))

            hand.fire("publish")
            dsl.fire("publish")

            assertEquals(hand.allowedTransitions(), dsl.allowedTransitions())
            assertEquals(hand.transition(named = "archive"), dsl.transition(named = "archive"))
        }

    @Test
    fun `handlers run in the lifecycle order with the fired userInfo on both machines`() =
        runTest {
            val userInfo: UserInfo = mapOf("id" to "article-42")

            val handOrder = mutableListOf<String>()
            val handSeen = mutableListOf<UserInfo?>()
            val hand = handBuilt()
            hand.on(LifecycleEvent.BeforeTransition(publish)) {
                handOrder += "before"
                handSeen += it
            }
            hand.on(LifecycleEvent.LeaveState(draft)) {
                handOrder += "leave"
                handSeen += it
            }
            hand.on(LifecycleEvent.OnState(published)) {
                handOrder += "onState"
                handSeen += it
            }
            hand.on(LifecycleEvent.OnTransition(publish)) {
                handOrder += "onTransition"
                handSeen += it
            }
            hand.on(LifecycleEvent.AfterTransition(publish)) {
                handOrder += "after"
                handSeen += it
            }

            val dslOrder = mutableListOf<String>()
            val dslSeen = mutableListOf<UserInfo?>()
            val dsl =
                stateMachine(initial = "draft") {
                    afterTransition("publish") {
                        dslOrder += "after"
                        dslSeen += it
                    }
                    onTransition("publish") {
                        dslOrder += "onTransition"
                        dslSeen += it
                    }
                    beforeTransition("publish") {
                        dslOrder += "before"
                        dslSeen += it
                    }
                    state("published") {
                        onEnter {
                            dslOrder += "onState"
                            dslSeen += it
                        }
                    }
                    state("draft") {
                        on("publish") goesTo "published"
                        onLeave {
                            dslOrder += "leave"
                            dslSeen += it
                        }
                    }
                }

            hand.fire("publish", userInfo)
            dsl.fire("publish", userInfo)

            val expected = listOf("before", "leave", "onState", "onTransition", "after")
            assertEquals(expected, handOrder)
            assertEquals(expected, dslOrder)
            assertEquals(List<UserInfo?>(5) { userInfo }, handSeen)
            assertEquals(handSeen, dslSeen)
        }

    @Test
    fun `Unknown matches a hand-built machine`() =
        runTest {
            val foreign = Transition("foreign", from = draft, to = archived)

            assertSameFailure(handBuilt(), dslBuilt()) { fire(foreign) }
        }

    @Test
    fun `NotAllowed matches a hand-built machine`() =
        runTest {
            assertSameFailure(handBuilt(), dslBuilt()) { fire(archive) }
        }

    @Test
    fun `GuardRejected from a guard on on matches a hand-built machine`() =
        runTest {
            val hand =
                StateMachine(
                    initialState = draft,
                    transitions = listOf(Transition("publish", from = draft, to = published, guard = { false })),
                )
            val dsl = stateMachine(initial = "draft") { state("draft") { on("publish", guard = { false }) goesTo "published" } }

            assertSameFailure(hand, dsl) { fire("publish") }
            assertSameFailure(hand, dsl) { fire(publish) }
            assertEquals(hand.canFire(publish), dsl.canFire(publish))
        }

    @Test
    fun `GuardRejected from a guard on transition matches a hand-built machine`() =
        runTest {
            val guard: suspend (UserInfo?) -> Boolean = { it?.get("ok") == true }
            val hand =
                StateMachine(
                    initialState = draft,
                    transitions = listOf(Transition("publish", from = draft, to = published, guard = guard)),
                )
            val dsl = stateMachine(initial = "draft") { transition("publish", from = "draft", to = "published", guard = guard) }

            assertSameFailure(hand, dsl) { fire("publish") }

            dsl.fire("publish", mapOf("ok" to true))
            assertEquals(published, dsl.currentState)
        }

    @Test
    fun `UnknownName matches a hand-built machine`() =
        runTest {
            val thrown = assertSameFailure(handBuilt(), dslBuilt()) { fire("archive") }

            assertTrue(thrown is TransitionError.UnknownName)
        }

    @Test
    fun `ReentrantFire matches a hand-built machine`() =
        runTest {
            val hand = handBuilt()
            var handNested: Throwable? = null
            hand.on(LifecycleEvent.OnState(published)) { handNested = runCatching { hand.fire("archive") }.exceptionOrNull() }

            var dslNested: Throwable? = null
            lateinit var dsl: StateMachine
            dsl =
                stateMachine(initial = "draft") {
                    state("draft") { on("publish") goesTo "published" }
                    state("published") { onEnter { dslNested = runCatching { dsl.fire("archive") }.exceptionOrNull() } }
                    transition("archive", from = "published", to = "archived")
                }

            hand.fire(publish)
            dsl.fire(publish)

            assertTrue(handNested is TransitionError.ReentrantFire)
            assertEquals(handNested?.let { it::class }, dslNested?.let { it::class })
            assertEquals(handNested?.message, dslNested?.message)
            assertEquals(published, hand.currentState)
            assertEquals(published, dsl.currentState)
        }

    @Test
    fun `a by-name transition handler runs on every transition with that name`() =
        runTest {
            val fired = mutableListOf<State>()
            lateinit var machine: StateMachine
            machine =
                stateMachine(initial = "browsing") {
                    beforeTransition("cancel") { fired += machine.currentState }
                    state("browsing") {
                        on("checkout") goesTo "paying"
                        on("cancel") goesTo "cancelled"
                    }
                    state("paying") { on("cancel") goesTo "cancelled" }
                    transition("restart", from = "cancelled", to = "browsing")
                }

            machine.fire("cancel")
            machine.fire("restart")
            machine.fire("checkout")
            machine.fire("cancel")

            assertEquals(listOf(State("browsing"), State("paying")), fired)
        }

    @Test
    fun `allowedTransitions follows declaration order`() =
        runTest {
            val machine =
                stateMachine(initial = "inCart") {
                    state("inCart") { on("continueShopping") goesTo "browsing" }
                    transition("checkout", from = "inCart", to = "checkout")
                    state("inCart") { on("cancel") goesTo "cancelled" }
                }

            assertEquals(listOf("continueShopping", "checkout", "cancel"), machine.allowedTransitions().map { it.name })
        }

    @Test
    fun `on and off after construction replace or remove a seeded handler`() =
        runTest {
            val calls = mutableListOf<String>()
            val machine =
                stateMachine(initial = "draft") {
                    state("draft") { on("publish") goesTo "published" }
                    state("published") {
                        on("unpublish") goesTo "draft"
                        onEnter { calls += "seeded" }
                    }
                    beforeTransition("unpublish") { calls += "seeded unpublish" }
                }

            machine.on(LifecycleEvent.OnState(published)) { calls += "replaced" }
            machine.off(LifecycleEvent.BeforeTransition(Transition("unpublish", from = published, to = draft)))

            machine.fire("publish")
            machine.fire("unpublish")

            assertEquals(listOf("replaced"), calls)
        }

    @Test
    fun `state emits like a hand-built machine`() =
        runTest {
            val hand = handBuilt()
            val dsl = dslBuilt()
            val handSeen = mutableListOf<State>()
            val dslSeen = mutableListOf<State>()
            val handJob = launch(UnconfinedTestDispatcher(testScheduler)) { hand.state.toList(handSeen) }
            val dslJob = launch(UnconfinedTestDispatcher(testScheduler)) { dsl.state.toList(dslSeen) }

            hand.fire("publish")
            hand.fire("archive")
            dsl.fire("publish")
            dsl.fire("archive")

            assertEquals(listOf(draft, published, archived), handSeen)
            assertEquals(handSeen, dslSeen)
            assertEquals(dsl.currentState, dsl.state.value)
            handJob.cancel()
            dslJob.cancel()
        }

    @Test
    fun `an initial state outside the graph throws the constructor's error`() {
        val expected =
            assertFailsWith<IllegalArgumentException> {
                StateMachine(initialState = State("nowhere"), transitions = listOf(publish))
            }
        val actual =
            assertFailsWith<IllegalArgumentException> {
                stateMachine(initial = "nowhere") { state("draft") { on("publish") goesTo "published" } }
            }

        assertEquals(expected.message, actual.message)
        assertContains(actual.message.orEmpty(), "nowhere")
    }

    @Test
    fun `a duplicate name from one state throws the constructor's error`() {
        val expected =
            assertFailsWith<IllegalArgumentException> {
                StateMachine(
                    initialState = draft,
                    transitions = listOf(publish, Transition("publish", from = draft, to = archived)),
                )
            }
        val actual =
            assertFailsWith<IllegalArgumentException> {
                stateMachine(initial = "draft") {
                    state("draft") { on("publish") goesTo "published" }
                    transition("publish", from = "draft", to = "archived")
                }
            }

        assertEquals(expected.message, actual.message)
        assertContains(actual.message.orEmpty(), "publish")
    }

    @Test
    fun `two onEnter calls for one state across merged blocks throw`() {
        val thrown =
            assertFailsWith<IllegalArgumentException> {
                stateMachine(initial = "draft") {
                    state("draft") { on("publish") goesTo "published" }
                    state("published") { onEnter {} }
                    state("published") { onEnter {} }
                }
            }

        assertContains(thrown.message.orEmpty(), "published")
    }

    @Test
    fun `two beforeTransition calls for one name throw`() {
        val thrown =
            assertFailsWith<IllegalArgumentException> {
                stateMachine(initial = "draft") {
                    state("draft") { on("publish") goesTo "published" }
                    beforeTransition("publish") {}
                    beforeTransition("publish") {}
                }
            }

        assertContains(thrown.message.orEmpty(), "publish")
    }

    @Test
    fun `a transition handler for an undeclared name throws`() {
        val thrown =
            assertFailsWith<IllegalArgumentException> {
                stateMachine(initial = "draft") {
                    state("draft") { on("publish") goesTo "published" }
                    afterTransition("archive") {}
                }
            }

        assertContains(thrown.message.orEmpty(), "archive")
    }

    @Test
    fun `on without goesTo throws`() {
        val thrown =
            assertFailsWith<IllegalArgumentException> {
                stateMachine(initial = "draft") {
                    state("draft") {
                        on("publish") goesTo "published"
                        on("x")
                    }
                }
            }

        assertContains(thrown.message.orEmpty(), "'x'")
        assertContains(thrown.message.orEmpty(), "draft")
    }
}
