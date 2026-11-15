package com.amine2233.statemachine.kiosk

import com.amine2233.statemachine.State
import com.amine2233.statemachine.StateMachine
import com.amine2233.statemachine.Transition
import com.amine2233.statemachine.TransitionError
import com.amine2233.statemachine.stateMachine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

/**
 * A worked example: a fast-food kiosk order screen, built on the
 * `statemachine` module (a Kotlin port of https://github.com/amine2233/StateMachine).
 *
 * Flow:
 *
 * ```
 *              startOrder            selectItem            addToCart
 * idle ────────────────────▶ browsingMenu ────────▶ itemSelected ────────▶ inCart
 *   ▲                             ▲   │                    │  ▲                │
 *   │                             │   │ cancel             │  │ customize      │ checkout
 *   │                     continueShopping  ────┐          │  ▼                ▼
 *   │                             │              ▼          customizing──▶ inCart      checkout
 *   │                             └────────── cancelled ◀───(cancel from any screen)
 *   │ newOrder                                                                  │
 *   └───────────────────── orderConfirmed ◀── confirmPayment ── paying ◀── pay ─┘
 * ```
 */
object KioskOrderWorkflow {
    /**
     * Builds the kiosk machine, starting at [initial]. The graph is static
     * data, exactly like Swift's `OrderWorkflow.transitions`; only the cursor
     * and the [onLog] handlers for order [orderId] differ per session.
     */
    fun machine(
        initial: String,
        orderId: String,
        onLog: (String) -> Unit,
    ): StateMachine =
        stateMachine(initial) {
            // beforeTransition -> leaveState -> onState -> onTransition -> afterTransition,
            // same fixed order as the Swift machine.
            state("idle") { on("startOrder") goesTo "browsingMenu" }
            state("browsingMenu") {
                on("selectItem") goesTo "itemSelected"
                // "cancel" is reachable from every screen where a customer might walk away.
                on("cancel") goesTo "cancelled"
            }
            state("itemSelected") {
                on("customize") goesTo "customizing"
                on("addToCart") goesTo "inCart"
                on("cancel") goesTo "cancelled"
            }
            state("customizing") {
                on("addToCart") goesTo "inCart"
                on("cancel") goesTo "cancelled"
            }
            state("inCart") {
                on("continueShopping") goesTo "browsingMenu"
                on("checkout") goesTo "checkout"
                on("cancel") goesTo "cancelled"
            }
            state("checkout") {
                on("pay") goesTo "paying"
                on("cancel") goesTo "cancelled"
            }
            state("paying") {
                on("confirmPayment") goesTo "orderConfirmed"
                onLeave { onLog("Payment step finished for $orderId.") }
            }
            state("orderConfirmed") {
                on("newOrder") goesTo "idle"
                onEnter { userInfo -> onLog("Order $orderId confirmed. Ticket #${userInfo?.get("ticketNumber")}") }
            }
            state("cancelled") {
                on("restart") goesTo "idle"
                onEnter { onLog("Order $orderId cancelled.") }
            }
        }
}

/**
 * Wraps a [StateMachine] with kiosk-specific vocabulary, exposing the
 * machine's [StateMachine.state] plus a [StateFlow] of actions so
 * a Compose screen (or any Android UI layer) can collect the current state
 * and the buttons it should show, without touching [StateMachine] directly.
 *
 * In a real app this would extend `androidx.lifecycle.ViewModel`; it's kept
 * plain here so the example runs with no Android dependency.
 */
class KioskOrderSession(
    val orderId: String,
    restoredStateName: String? = null,
    onLog: (String) -> Unit,
) {
    // "Restoring a machine" (see the Swift README): the graph never changes,
    // only the cursor does — so resuming an order is just handing back its
    // last known state name.
    private val machine = KioskOrderWorkflow.machine(restoredStateName ?: "idle", orderId, onLog)

    val screen: StateFlow<State> = machine.state

    private val _availableActions = MutableStateFlow<List<Transition>>(emptyList())
    val availableActions: StateFlow<List<Transition>> = _availableActions.asStateFlow()

    init {
        runBlocking { refreshAvailableActions() }
    }

    private suspend fun refreshAvailableActions() {
        _availableActions.value = machine.allowedTransitions()
    }

    /**
     * Fires the transition called [name] and republishes the resulting screen + button set.
     * Returns false (without throwing) if the button shouldn't have been
     * tappable in the first place — useful for a UI layer that just wants a
     * boolean rather than a caught exception.
     */
    suspend fun perform(
        name: String,
        userInfo: Map<String, Any?>? = null,
    ): Boolean =
        try {
            machine.fire(name, userInfo)
            refreshAvailableActions()
            true
        } catch (e: TransitionError.NotAllowed) {
            false
        } catch (e: TransitionError.Unknown) {
            false
        } catch (e: TransitionError.UnknownName) {
            false
        }
}

/**
 * Simulates one customer session end-to-end, plus a couple of edge cases:
 * an invalid transition, and resuming an order from persisted state.
 */
fun main() =
    runBlocking {
        val log: (String) -> Unit = { message -> println("[log] $message") }
        val session = KioskOrderSession(orderId = "K-1024", onLog = log)

        suspend fun show(label: String) {
            println("$label -> screen=${session.screen.value}, actions=${session.availableActions.value.map { it.name }}")
        }

        show("start")

        session.perform("startOrder")
        show("after startOrder")

        session.perform("selectItem")
        show("after selectItem")

        session.perform("customize")
        show("after customize (e.g. 'no onions')")

        session.perform("addToCart")
        show("after addToCart")

        // Trying to pay before checking out: the transition exists in the graph
        // but doesn't start from the current state, so it's rejected — this is
        // exactly what a "Pay" button's enabled state should be driven by.
        val rejected = session.perform("pay")
        println("pay before checkout accepted? $rejected")

        session.perform("checkout")
        session.perform("pay")
        session.perform("confirmPayment", userInfo = mapOf("ticketNumber" to 42))
        show("after confirmPayment")

        session.perform("newOrder")
        show("after newOrder (back to idle)")

        // Resuming a different order that was persisted mid-checkout —
        // only the state name needs to have been saved.
        val resumed = KioskOrderSession(orderId = "K-1025", restoredStateName = "checkout", onLog = log)
        println(
            "resumed order -> screen=${resumed.screen.value}, " +
                "actions=${resumed.availableActions.value.map { it.name }}",
        )
    }
