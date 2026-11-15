package com.amine2233.statemachine

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/*
 * Kotlin port of amine2233/StateMachine (https://github.com/amine2233/StateMachine).
 *
 * A small finite state machine. You describe the graph once with [State] and
 * [Transition] values, then let the machine decide what may happen next.
 * It holds no storage of its own beyond the graph and a cursor — persisting
 * or restoring a machine means persisting one string: [StateMachine.currentState]'s name.
 *
 * The Swift version is isolated by an `actor`; Kotlin has no actor keyword,
 * so the same "only one caller mutates state at a time" guarantee is
 * reproduced with a [Mutex] and suspend functions. The lock covers only
 * short reads and the handler map; lifecycle handlers and the cursor write run
 * outside it, so handlers and state collectors may call back into the machine.
 * A second, fire-only lock keeps `fire` calls, and so cursor writes, serialized.
 */

/** A named node in the graph. Two states are equal when their names match. */
data class State(
    val name: String,
) {
    override fun toString(): String = name
}

/**
 * A named edge: firing it moves the machine from [from] to [to].
 *
 * An optional [guard] can refuse the move at fire time. It takes no part in
 * equality, hashing or [toString]: two transitions with the same [name],
 * [from] and [to] are the same edge whatever their guards. The machine only
 * ever evaluates the guard of the instance registered in its graph, never the
 * one on the instance passed to [StateMachine.fire] or [StateMachine.canFire],
 * so an equal copy without a guard cannot get around it.
 *
 * A guard runs after the [TransitionError.Unknown] and
 * [TransitionError.NotAllowed] checks and before any handler, outside the
 * machine's state lock, like a lifecycle handler. It may read the machine
 * ([StateMachine.currentState], [StateMachine.canFire],
 * [StateMachine.allowedTransitions]) without deadlocking. A guard evaluated by
 * [StateMachine.fire] that calls `fire` on the same machine gets
 * [TransitionError.ReentrantFire]. An exception thrown by a guard reaches the
 * caller unchanged.
 */
data class Transition
    @JvmOverloads
    constructor(
        val name: String,
        val from: State,
        val to: State,
        val guard: (suspend (UserInfo?) -> Boolean)? = null,
    ) {
        override fun equals(other: Any?): Boolean = other is Transition && name == other.name && from == other.from && to == other.to

        override fun hashCode(): Int {
            var result = name.hashCode()
            result = 31 * result + from.hashCode()
            result = 31 * result + to.hashCode()
            return result
        }

        override fun toString(): String = "$name (${from.name} -> ${to.name})"
    }

/** Mirrors Swift's `TransitionError`. */
sealed class TransitionError(
    message: String,
) : Exception(message) {
    /** [transition] is not part of this machine's graph at all. */
    class Unknown(
        val transition: Transition,
    ) : TransitionError("Transition '${transition.name}' is unknown to this machine.")

    /** [transition] exists in the graph but doesn't start from [currentState]. */
    class NotAllowed(
        val transition: Transition,
        val currentState: State,
    ) : TransitionError(
            "Transition '${transition.name}' requires state '${transition.from.name}' " +
                "but the machine is currently in '${currentState.name}'.",
        )

    /** [transition] is allowed from the current state but its guard returned `false`. */
    class GuardRejected(
        val transition: Transition,
    ) : TransitionError("Transition '${transition.name}' was rejected by its guard.")

    /** `fire` was called from a lifecycle handler of the same machine while a transition was running. */
    class ReentrantFire(
        val transition: Transition,
    ) : TransitionError(
            "Transition '${transition.name}' was fired from a lifecycle handler of the same machine " +
                "while a transition was in progress.",
        )
}

/**
 * A point in a transition's life, in the fixed order they fire:
 * [BeforeTransition] -> [LeaveState] -> [OnState] -> [OnTransition] -> [AfterTransition].
 *
 * Each case carries the specific [State]/[Transition] it refers to, so — just
 * like the Swift original — registering an observer is scoped to one state or
 * one transition, and a second registration for the same [LifecycleEvent]
 * replaces the first.
 *
 * [BeforeTransition] and [LeaveState] run before the state moves, so a handler
 * for either can veto the transition by throwing. [OnState], [OnTransition] and
 * [AfterTransition] run after the move and cannot undo it. In both cases the
 * first handler that throws stops the remaining handlers of that lifecycle.
 */
sealed class LifecycleEvent {
    data class BeforeTransition(
        val transition: Transition,
    ) : LifecycleEvent()

    data class LeaveState(
        val state: State,
    ) : LifecycleEvent()

    data class OnState(
        val state: State,
    ) : LifecycleEvent()

    data class OnTransition(
        val transition: Transition,
    ) : LifecycleEvent()

    data class AfterTransition(
        val transition: Transition,
    ) : LifecycleEvent()
}

/** Free-form payload passed through `fire` to observers, like Swift's `userInfo`. */
typealias UserInfo = Map<String, Any?>

/** An observer callback. May suspend, exactly like the `async` closures in Swift. */
typealias LifecycleHandler = suspend (UserInfo?) -> Unit

/**
 * The machine itself. Construct it with the full graph; from then on it only
 * exposes what the current state allows.
 *
 * ```
 * val draft = State("draft")
 * val published = State("published")
 * val publish = Transition("publish", from = draft, to = published)
 *
 * val machine = StateMachine(initialState = draft, transitions = listOf(publish))
 *
 * machine.fire(publish)
 * machine.currentState == published // true
 * ```
 */
class StateMachine(
    initialState: State,
    private val transitions: List<Transition>,
) {
    private val mutex = Mutex()

    private val fireMutex = Mutex()

    private val firing = FiringElement()

    private val _state = MutableStateFlow(initialState)

    private val observers = mutableMapOf<LifecycleEvent, LifecycleHandler>()

    /**
     * The machine's cursor as a [StateFlow], for UI layers to collect.
     *
     * Its initial value is the `initialState` passed to the constructor. It is
     * updated by [fire] under the fire-only lock but outside [mutex], after the
     * [LifecycleEvent.LeaveState] handlers and before the [LifecycleEvent.OnState]
     * handlers run. A collector that runs inline on that write (for example on
     * an unconfined dispatcher) may call back into the machine: reads work
     * immediately, and a [fire] waits until the running one has returned.
     *
     * Like any [StateFlow] it is conflated: a collector that keeps up (for
     * example one on an unconfined dispatcher) sees every state in order, but a
     * slow collector may miss intermediate states. A transition into a state
     * equal to the current one emits nothing.
     */
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * The machine's cursor, the current value of [state]. Safe to read from any
     * thread without suspending — writes only ever happen inside [fire], one at
     * a time, so this is always one of the graph's states, never a torn value.
     */
    val currentState: State
        get() = _state.value

    /** Equivalent to `currentState == state`, spelled out for readability at call sites. */
    fun isCurrent(state: State): Boolean = _state.value == state

    /**
     * Whether [transition] exists, starts from [currentState] and, if the
     * registered instance has a guard, that guard returns `true` for [userInfo].
     *
     * The checks run in the same order as [fire], and the guard evaluated is
     * the one on the transition registered in the graph, not the one on
     * [transition]. A guard exception reaches the caller unchanged. The guard
     * runs after the state lock is released, so it may read the machine
     * ([currentState], [canFire], [allowedTransitions]) without deadlocking.
     * Never moves the cursor and never runs a handler.
     */
    @JvmOverloads
    suspend fun canFire(
        transition: Transition,
        userInfo: UserInfo? = null,
    ): Boolean {
        val allowed = mutex.withLock { transitions.contains(transition) && transition.from == _state.value }
        if (!allowed) return false
        return registered(transition).guard?.invoke(userInfo) ?: true
    }

    /**
     * Transitions that could fire right now. This is what UI usually binds
     * to — buttons enable themselves from the graph.
     *
     * The answer is structural: guards are not evaluated, so a listed
     * transition may still be rejected by its guard. Use
     * [canFire] (`canFire(transition, userInfo)`) for the guarded answer.
     */
    suspend fun allowedTransitions(): List<Transition> =
        mutex.withLock {
            transitions.filter { it.from == _state.value }
        }

    /**
     * Registers [handler] for [event]. Only one handler per [LifecycleEvent]
     * is kept — calling this again with an event equal to one already
     * registered replaces the previous handler.
     *
     * When called during a [fire] (from a handler, for example), the change
     * applies from the next [fire], never to the transition in progress.
     */
    suspend fun on(
        event: LifecycleEvent,
        handler: LifecycleHandler,
    ) {
        mutex.withLock { observers[event] = handler }
    }

    /**
     * Removes any handler registered for [event], if one exists. When called
     * during a [fire], the change applies from the next [fire].
     */
    suspend fun off(event: LifecycleEvent) {
        mutex.withLock { observers.remove(event) }
    }

    /**
     * Attempts to fire [transition].
     *
     * Checks, in order: throws [TransitionError.Unknown] if [transition]
     * isn't part of the graph, [TransitionError.NotAllowed] if it doesn't
     * start from [currentState], then [TransitionError.GuardRejected] if the
     * registered instance's guard returns `false` for [userInfo]. The guard
     * evaluated is the one on the transition registered in the graph, never
     * the one on [transition]. A rejected or throwing guard leaves
     * [currentState] unchanged and runs no handler; a guard's exception
     * reaches the caller unchanged.
     *
     * The guard runs under the fire-only lock but outside the state lock,
     * before any handler, exactly like a handler would: it may read the
     * machine ([currentState], [canFire], [allowedTransitions]) and sees the
     * old state, and calling `fire` on the same machine from it throws
     * [TransitionError.ReentrantFire].
     *
     * Otherwise every matching observer runs — in the fixed lifecycle
     * order — and this call only returns once they've all finished, so the
     * machine's state is settled by the time the caller continues.
     *
     * Calls are serialized: a `fire` from another coroutine waits until this
     * one has run all its handlers and returned (or thrown). The handlers for
     * this transition are snapshotted before any of them runs, so [on]/[off]
     * calls made meanwhile apply from the next `fire`. Handlers run outside
     * the state lock and may read the machine or call [on]/[off]; calling
     * `fire` on the same machine from a handler throws
     * [TransitionError.ReentrantFire].
     *
     * If an observer throws, the remaining observers are skipped and the
     * exception reaches the caller unchanged:
     * - a throw in [LifecycleEvent.BeforeTransition] or [LifecycleEvent.LeaveState]
     *   vetoes the transition, so [currentState] stays where it was;
     * - a throw in [LifecycleEvent.OnState], [LifecycleEvent.OnTransition] or
     *   [LifecycleEvent.AfterTransition] happens after the move, so
     *   [currentState] is already [Transition.to] and stays there.
     *
     * Either way both locks are released, so the machine stays usable: a vetoed
     * transition can be fired again, and after a post-move throw the
     * transitions out of the new state can be fired.
     */
    suspend fun fire(
        transition: Transition,
        userInfo: UserInfo? = null,
    ) {
        if (currentCoroutineContext()[firing] != null) {
            throw TransitionError.ReentrantFire(transition)
        }

        fireMutex.withLock {
            val handlers =
                mutex.withLock {
                    if (!transitions.contains(transition)) {
                        throw TransitionError.Unknown(transition)
                    }
                    if (transition.from != _state.value) {
                        throw TransitionError.NotAllowed(transition, _state.value)
                    }

                    TransitionHandlers(
                        before = observers[LifecycleEvent.BeforeTransition(transition)],
                        leave = observers[LifecycleEvent.LeaveState(transition.from)],
                        onState = observers[LifecycleEvent.OnState(transition.to)],
                        onTransition = observers[LifecycleEvent.OnTransition(transition)],
                        after = observers[LifecycleEvent.AfterTransition(transition)],
                    )
                }

            withContext(firing) {
                val guard = registered(transition).guard
                if (guard != null && !guard(userInfo)) {
                    throw TransitionError.GuardRejected(transition)
                }

                handlers.before?.invoke(userInfo)
                handlers.leave?.invoke(userInfo)

                _state.value = transition.to

                handlers.onState?.invoke(userInfo)
                handlers.onTransition?.invoke(userInfo)
                handlers.after?.invoke(userInfo)
            }
        }
    }

    private fun registered(transition: Transition): Transition = transitions.first { it == transition }

    private class TransitionHandlers(
        val before: LifecycleHandler?,
        val leave: LifecycleHandler?,
        val onState: LifecycleHandler?,
        val onTransition: LifecycleHandler?,
        val after: LifecycleHandler?,
    )

    /** Marks a coroutine context as running this machine's handlers; its own key, so it is per instance. */
    private class FiringElement :
        CoroutineContext.Element,
        CoroutineContext.Key<FiringElement> {
        override val key: CoroutineContext.Key<*> get() = this
    }
}
