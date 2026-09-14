package com.amine2233.statemachine.kiosk

import com.amine2233.statemachine.LifecycleEvent
import com.amine2233.statemachine.State
import com.amine2233.statemachine.StateMachine
import com.amine2233.statemachine.Transition
import com.amine2233.statemachine.TransitionError
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

    // States — the graph is static data, exactly like Swift's `OrderWorkflow.transitions`.
    val idle = State("idle")
    val browsingMenu = State("browsingMenu")
    val itemSelected = State("itemSelected")
    val customizing = State("customizing")
    val inCart = State("inCart")
    val checkout = State("checkout")
    val paying = State("paying")
    val orderConfirmed = State("orderConfirmed")
    val cancelled = State("cancelled")

    // Transitions
    val startOrder = Transition("startOrder", from = idle, to = browsingMenu)
    val selectItem = Transition("selectItem", from = browsingMenu, to = itemSelected)
    val customize = Transition("customize", from = itemSelected, to = customizing)
    val addToCartDirect = Transition("addToCart", from = itemSelected, to = inCart)
    val addToCartCustomized = Transition("addToCart", from = customizing, to = inCart)
    val continueShopping = Transition("continueShopping", from = inCart, to = browsingMenu)
    val goToCheckout = Transition("checkout", from = inCart, to = checkout)
    val pay = Transition("pay", from = checkout, to = paying)
    val confirmPayment = Transition("confirmPayment", from = paying, to = orderConfirmed)
    val newOrder = Transition("newOrder", from = orderConfirmed, to = idle)

    // "cancel" is reachable from every screen where a customer might walk away.
    val cancelFromBrowsing = Transition("cancel", from = browsingMenu, to = cancelled)
    val cancelFromItemSelected = Transition("cancel", from = itemSelected, to = cancelled)
    val cancelFromCustomizing = Transition("cancel", from = customizing, to = cancelled)
    val cancelFromCart = Transition("cancel", from = inCart, to = cancelled)
    val cancelFromCheckout = Transition("cancel", from = checkout, to = cancelled)
    val restart = Transition("restart", from = cancelled, to = idle)

    val transitions: List<Transition> = listOf(
        startOrder, selectItem, customize, addToCartDirect, addToCartCustomized,
        continueShopping, goToCheckout, pay, confirmPayment, newOrder,
        cancelFromBrowsing, cancelFromItemSelected, cancelFromCustomizing,
        cancelFromCart, cancelFromCheckout, restart,
    )
}

/**
 * Wraps a [StateMachine] with kiosk-specific vocabulary and a [StateFlow] so
 * a Compose screen (or any Android UI layer) can collect the current state
 * and the buttons it should show, without touching [StateMachine] directly.
 *
 * In a real app this would extend `androidx.lifecycle.ViewModel`; it's kept
 * plain here so the example runs with no Android dependency.
 */
class KioskOrderSession(
    orderId: String,
    restoredStateName: String? = null,
) {
    // "Restoring a machine" (see the Swift README): the graph never changes,
    // only the cursor does — so resuming an order is just handing back its
    // last known state name.
    private val machine = StateMachine(
        initialState = restoredStateName?.let { State(it) } ?: KioskOrderWorkflow.idle,
        transitions = KioskOrderWorkflow.transitions,
    )

    private val _screen = MutableStateFlow(machine.currentState)
    val screen: StateFlow<State> = _screen.asStateFlow()

    private val _availableActions = MutableStateFlow<List<Transition>>(emptyList())
    val availableActions: StateFlow<List<Transition>> = _availableActions.asStateFlow()

    init {
        runBlocking { refreshAvailableActions() }
    }

    /** Call once, wherever you set up the session (e.g. `ViewModel.init`). */
    suspend fun observeLifecycle(onLog: (String) -> Unit) {
        // beforeTransition -> leaveState -> onState -> onTransition -> afterTransition,
        // same fixed order as the Swift machine.
        machine.on(LifecycleEvent.OnState(KioskOrderWorkflow.orderConfirmed)) { userInfo ->
            onLog("Order $orderId confirmed. Ticket #${userInfo?.get("ticketNumber")}")
        }
        machine.on(LifecycleEvent.OnState(KioskOrderWorkflow.cancelled)) {
            onLog("Order $orderId cancelled.")
        }
        machine.on(LifecycleEvent.LeaveState(KioskOrderWorkflow.paying)) {
            onLog("Payment step finished for $orderId.")
        }
    }

    private suspend fun refreshAvailableActions() {
        _availableActions.value = machine.allowedTransitions()
    }

    /**
     * Fires [transition] and republishes the resulting screen + button set.
     * Returns false (without throwing) if the button shouldn't have been
     * tappable in the first place — useful for a UI layer that just wants a
     * boolean rather than a caught exception.
     */
    suspend fun perform(transition: Transition, userInfo: Map<String, Any?>? = null): Boolean {
        return try {
            machine.fire(transition, userInfo)
            _screen.value = machine.currentState
            refreshAvailableActions()
            true
        } catch (e: TransitionError.NotAllowed) {
            false
        } catch (e: TransitionError.Unknown) {
            false
        }
    }
}

/**
 * Simulates one customer session end-to-end, plus a couple of edge cases:
 * an invalid transition, and resuming an order from persisted state.
 */
fun main() = runBlocking {
    val session = KioskOrderSession(orderId = "K-1024")
    session.observeLifecycle { message -> println("[log] $message") }

    suspend fun show(label: String) {
        println("$label -> screen=${session.screen.value}, actions=${session.availableActions.value.map { it.name }}")
    }

    show("start")

    session.perform(KioskOrderWorkflow.startOrder)
    show("after startOrder")

    session.perform(KioskOrderWorkflow.selectItem)
    show("after selectItem")

    session.perform(KioskOrderWorkflow.customize)
    show("after customize (e.g. 'no onions')")

    session.perform(KioskOrderWorkflow.addToCartCustomized)
    show("after addToCart")

    // Trying to pay before checking out: the transition exists in the graph
    // but doesn't start from the current state, so it's rejected — this is
    // exactly what a "Pay" button's enabled state should be driven by.
    val rejected = session.perform(KioskOrderWorkflow.pay)
    println("pay before checkout accepted? $rejected")

    session.perform(KioskOrderWorkflow.goToCheckout)
    session.perform(KioskOrderWorkflow.pay)
    session.perform(KioskOrderWorkflow.confirmPayment, userInfo = mapOf("ticketNumber" to 42))
    show("after confirmPayment")

    session.perform(KioskOrderWorkflow.newOrder)
    show("after newOrder (back to idle)")

    // Resuming a different order that was persisted mid-checkout —
    // only the state name needs to have been saved.
    val resumed = KioskOrderSession(orderId = "K-1025", restoredStateName = "checkout")
    println(
        "resumed order -> screen=${resumed.screen.value}, " +
            "actions=${resumed.availableActions.value.map { it.name }}"
    )
}
