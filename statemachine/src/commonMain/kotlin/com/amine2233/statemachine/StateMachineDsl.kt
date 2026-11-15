package com.amine2233.statemachine

/**
 * Marks the [stateMachine] builder scopes, so a block can only call the
 * functions of its own scope: `state(...)` or `transition(...)` can't be
 * called from inside a `state` block without an explicit receiver.
 */
@DslMarker
public annotation class StateMachineDsl

/**
 * Builds a [StateMachine] from a declarative block, starting in the state
 * called [initial].
 *
 * ```
 * val machine =
 *     stateMachine(initial = "draft") {
 *         state("draft") {
 *             on("publish") goesTo "published"
 *             onLeave { println("leaving draft") }
 *         }
 *         transition("archive", from = "published", to = "archived")
 *         afterTransition("archive") { println("archived") }
 *     }
 *
 * machine.fire("publish")
 * ```
 *
 * Rules:
 * - Names are plain strings and become [State] values. A state that is only
 *   referenced, as a `goesTo` target or as the `from`/`to` of
 *   [StateMachineBuilder.transition], needs no `state` block, and a reference
 *   may come before or after the referenced state's block.
 * - Transitions keep the order the calls run in the block, across both
 *   `on ... goesTo` and [StateMachineBuilder.transition], so
 *   [StateMachine.allowedTransitions] lists them in the order they were written.
 * - The result is a plain [StateMachine] built through its constructor, so the
 *   constructor's validation applies unchanged: an [initial] that is in no
 *   transition, or the same transition name declared twice from one state,
 *   throws [IllegalArgumentException].
 * - The builder adds its own checks, each throwing [IllegalArgumentException]
 *   with a message naming the state or transition: a second handler for the
 *   same [LifecycleEvent] (even across merged `state` blocks), a transition
 *   handler for a name no transition has, and an `on(name)` without `goesTo`.
 * - Handlers are seeded when the machine is built, without suspending. The
 *   built machine behaves exactly like a hand-built one: [StateMachine.on]
 *   and [StateMachine.off] replace or remove seeded handlers like any other.
 */
public fun stateMachine(
    initial: String,
    block: StateMachineBuilder.() -> Unit,
): StateMachine = StateMachineBuilder().apply(block).build(initial)

/** The scope of a [stateMachine] block. */
@StateMachineDsl
public class StateMachineBuilder internal constructor() {
    private val transitions = mutableListOf<Transition>()

    private val handlers = mutableMapOf<LifecycleEvent, LifecycleHandler>()

    private val beforeHandlers = mutableMapOf<String, LifecycleHandler>()

    private val onTransitionHandlers = mutableMapOf<String, LifecycleHandler>()

    private val afterHandlers = mutableMapOf<String, LifecycleHandler>()

    /**
     * Declares the state called [name] and runs [block] in its scope.
     * Declaring the same name again merges the blocks; a second `onEnter` or
     * `onLeave` for the state still throws [IllegalArgumentException].
     */
    public fun state(
        name: String,
        block: StateBuilder.() -> Unit = {},
    ) {
        StateBuilder(State(name), this).apply(block).requireTargets()
    }

    /**
     * Declares `Transition(name, from, to, guard)`. [from] and [to] need no
     * `state` block of their own.
     */
    public fun transition(
        name: String,
        from: String,
        to: String,
        guard: (suspend (UserInfo?) -> Boolean)? = null,
    ) {
        transitions += Transition(name, State(from), State(to), guard)
    }

    /**
     * Registers [handler] for [LifecycleEvent.BeforeTransition] on every
     * transition called [name], wherever it is declared in the block.
     */
    public fun beforeTransition(
        name: String,
        handler: LifecycleHandler,
    ) {
        addByName(beforeHandlers, "beforeTransition", name, handler)
    }

    /**
     * Registers [handler] for [LifecycleEvent.OnTransition] on every
     * transition called [name], wherever it is declared in the block.
     */
    public fun onTransition(
        name: String,
        handler: LifecycleHandler,
    ) {
        addByName(onTransitionHandlers, "onTransition", name, handler)
    }

    /**
     * Registers [handler] for [LifecycleEvent.AfterTransition] on every
     * transition called [name], wherever it is declared in the block.
     */
    public fun afterTransition(
        name: String,
        handler: LifecycleHandler,
    ) {
        addByName(afterHandlers, "afterTransition", name, handler)
    }

    internal fun addHandler(
        event: LifecycleEvent,
        kind: String,
        subject: String,
        handler: LifecycleHandler,
    ) {
        require(event !in handlers) { "Duplicate $kind handler for $subject." }
        handlers[event] = handler
    }

    private fun addByName(
        byName: MutableMap<String, LifecycleHandler>,
        kind: String,
        name: String,
        handler: LifecycleHandler,
    ) {
        require(name !in byName) { "Duplicate $kind handler for transition '$name'." }
        byName[name] = handler
    }

    internal fun build(initial: String): StateMachine {
        val seeded = handlers.toMutableMap()
        seedByName(seeded, beforeHandlers, "beforeTransition") { LifecycleEvent.BeforeTransition(it) }
        seedByName(seeded, onTransitionHandlers, "onTransition") { LifecycleEvent.OnTransition(it) }
        seedByName(seeded, afterHandlers, "afterTransition") { LifecycleEvent.AfterTransition(it) }
        return StateMachine(State(initial), transitions.toList(), seeded)
    }

    private fun seedByName(
        seeded: MutableMap<LifecycleEvent, LifecycleHandler>,
        byName: Map<String, LifecycleHandler>,
        kind: String,
        event: (Transition) -> LifecycleEvent,
    ) {
        for ((name, handler) in byName) {
            val matching = transitions.filter { it.name == name }
            require(matching.isNotEmpty()) { "$kind handler for undeclared transition '$name'." }
            matching.forEach { seeded[event(it)] = handler }
        }
    }
}

/** The scope of a [StateMachineBuilder.state] block, for the state it declares. */
@StateMachineDsl
public class StateBuilder internal constructor(
    private val state: State,
    private val machine: StateMachineBuilder,
) {
    private val pending = mutableListOf<TransitionTarget>()

    /**
     * Starts declaring a transition called [name] out of this state; finish it
     * with [TransitionTarget.goesTo]. An `on(name)` left without `goesTo`
     * throws [IllegalArgumentException] when the block ends.
     */
    public fun on(
        name: String,
        guard: (suspend (UserInfo?) -> Boolean)? = null,
    ): TransitionTarget = TransitionTarget(name, state, guard, machine).also { pending += it }

    /** Registers [handler] for [LifecycleEvent.OnState] of this state. */
    public fun onEnter(handler: LifecycleHandler) {
        machine.addHandler(LifecycleEvent.OnState(state), "onEnter", "state '${state.name}'", handler)
    }

    /** Registers [handler] for [LifecycleEvent.LeaveState] of this state. */
    public fun onLeave(handler: LifecycleHandler) {
        machine.addHandler(LifecycleEvent.LeaveState(state), "onLeave", "state '${state.name}'", handler)
    }

    internal fun requireTargets() {
        pending.firstOrNull { !it.declared }?.let {
            throw IllegalArgumentException("Transition '${it.name}' from state '${state.name}' has no goesTo target.")
        }
    }
}

/** A transition started with [StateBuilder.on], waiting for its target. */
@StateMachineDsl
public class TransitionTarget internal constructor(
    internal val name: String,
    private val from: State,
    private val guard: (suspend (UserInfo?) -> Boolean)?,
    private val machine: StateMachineBuilder,
) {
    internal var declared = false
        private set

    /** Declares `Transition(name, from = this state, to = target, guard)`. */
    public infix fun goesTo(target: String) {
        machine.transition(name, from.name, target, guard)
        declared = true
    }
}
