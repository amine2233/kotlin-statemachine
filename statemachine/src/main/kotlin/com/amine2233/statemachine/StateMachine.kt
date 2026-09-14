package com.amine2233.statemachine

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Kotlin port of amine2233/StateMachine (https://github.com/amine2233/StateMachine).
 *
 * A small finite state machine. You describe the graph once with [State] and
 * [Transition] values, then let the machine decide what may happen next.
 * It holds no storage of its own beyond the graph and a cursor — persisting
 * or restoring a machine means persisting one string: [StateMachine.currentState]'s name.
 *
 * The Swift version is isolated by an `actor`; Kotlin has no actor keyword,
 * so the same "only one caller mutates state at a time" guarantee is
 * reproduced with a [Mutex] and suspend functions.
 */

/** A named node in the graph. Two states are equal when their names match. */
data class State(val name: String) {
    override fun toString(): String = name
}

/** A named edge: firing it moves the machine from [from] to [to]. */
data class Transition(val name: String, val from: State, val to: State) {
    override fun toString(): String = "$name (${from.name} -> ${to.name})"
}

/** Mirrors Swift's `TransitionError`. */
sealed class TransitionError(message: String) : Exception(message) {

    /** [transition] is not part of this machine's graph at all. */
    class Unknown(val transition: Transition) :
        TransitionError("Transition '${transition.name}' is unknown to this machine.")

    /** [transition] exists in the graph but doesn't start from [currentState]. */
    class NotAllowed(val transition: Transition, val currentState: State) :
        TransitionError(
            "Transition '${transition.name}' requires state '${transition.from.name}' " +
                "but the machine is currently in '${currentState.name}'."
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
 */
sealed class LifecycleEvent {
    data class BeforeTransition(val transition: Transition) : LifecycleEvent()
    data class LeaveState(val state: State) : LifecycleEvent()
    data class OnState(val state: State) : LifecycleEvent()
    data class OnTransition(val transition: Transition) : LifecycleEvent()
    data class AfterTransition(val transition: Transition) : LifecycleEvent()
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

    @Volatile
    private var current: State = initialState

    private val observers = mutableMapOf<LifecycleEvent, LifecycleHandler>()

    /**
     * The machine's cursor. Safe to read from any thread without suspending —
     * writes only ever happen while [mutex] is held, so this is always one of
     * the graph's states, never a torn value.
     */
    val currentState: State
        get() = current

    /** Equivalent to `currentState == state`, spelled out for readability at call sites. */
    fun isCurrent(state: State): Boolean = current == state

    /** Whether [transition] both exists and starts from [currentState]. */
    suspend fun canFire(transition: Transition): Boolean = mutex.withLock {
        transitions.contains(transition) && transition.from == current
    }

    /**
     * Transitions that could fire right now. This is what UI usually binds
     * to — buttons enable themselves from the graph.
     */
    suspend fun allowedTransitions(): List<Transition> = mutex.withLock {
        transitions.filter { it.from == current }
    }

    /**
     * Registers [handler] for [event]. Only one handler per [LifecycleEvent]
     * is kept — calling this again with an event equal to one already
     * registered replaces the previous handler.
     */
    suspend fun on(event: LifecycleEvent, handler: LifecycleHandler) {
        mutex.withLock { observers[event] = handler }
    }

    /** Removes any handler registered for [event], if one exists. */
    suspend fun off(event: LifecycleEvent) {
        mutex.withLock { observers.remove(event) }
    }

    /**
     * Attempts to fire [transition].
     *
     * Throws [TransitionError.Unknown] if [transition] isn't part of the
     * graph, or [TransitionError.NotAllowed] if it doesn't start from
     * [currentState]. On success, every matching observer runs — in the
     * fixed lifecycle order — and this call only returns once they've all
     * finished, so the machine's state is settled by the time the caller
     * continues.
     */
    suspend fun fire(transition: Transition, userInfo: UserInfo? = null) {
        mutex.withLock {
            if (!transitions.contains(transition)) {
                throw TransitionError.Unknown(transition)
            }
            if (transition.from != current) {
                throw TransitionError.NotAllowed(transition, current)
            }

            val leavingState = current

            observers[LifecycleEvent.BeforeTransition(transition)]?.invoke(userInfo)
            observers[LifecycleEvent.LeaveState(leavingState)]?.invoke(userInfo)

            current = transition.to

            observers[LifecycleEvent.OnState(current)]?.invoke(userInfo)
            observers[LifecycleEvent.OnTransition(transition)]?.invoke(userInfo)
            observers[LifecycleEvent.AfterTransition(transition)]?.invoke(userInfo)
        }
    }
}
