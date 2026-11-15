# StateMachine (Kotlin)

A small finite state machine for Kotlin, ported from
[amine2233/StateMachine](https://github.com/amine2233/StateMachine) (Swift).

Describe the graph once with `State` and `Transition` values, then let the
machine decide what may happen next. It holds no storage of its own — a graph
plus a cursor — so persisting or restoring a machine means persisting one
string.

## Modules

- `statemachine` — the library (published to GitHub Packages).
- `sample-kiosk` — a runnable kiosk food-ordering example built on top of it.

## Requirements

- JDK 17+
- Kotlin 2.0 / kotlinx-coroutines 1.8

## Usage

```kotlin
import com.amine2233.statemachine.*
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val machine =
        stateMachine(initial = "draft") {
            state("draft") {
                on("publish") goesTo "review"
                onLeave { println("leaving draft") }
            }
            state("review") {
                on("approve", guard = { userInfo -> userInfo?.get("approved") == true }) goesTo "published"
                on("reject") goesTo "draft"
            }
            state("published") {
                onEnter { userInfo -> println("published! id=${userInfo?.get("id")}") }
            }
            transition("archive", from = "published", to = "archived")
            afterTransition("archive") { println("archived") }
        }

    machine.fire("publish")
    machine.fire("approve", userInfo = mapOf("approved" to true, "id" to "article-42"))
    machine.fire("archive")
    println(machine.currentState) // archived
}
```

`stateMachine(initial) { ... }` builds a plain `StateMachine`, so everything
below applies to it unchanged. It is not a suspend call.

- `state(name) { ... }` declares a state. Inside it, `on(name, guard) goesTo target`
  declares a transition out of that state, and `onEnter`/`onLeave` register the
  `OnState`/`LeaveState` handlers. Declaring the same state twice merges the blocks.
- `transition(name, from, to, guard)` declares a transition at the top level.
- `beforeTransition(name)`, `onTransition(name)` and `afterTransition(name)`
  register a handler on **every** transition with that name, so one
  `beforeTransition("cancel")` covers `cancel` from any state.
- States are plain strings. A state that is only a target or a `from`/`to`
  (`archived` above) needs no `state` block, and references may come before or
  after the state's block.
- Transitions keep the order they are written in, so `allowedTransitions()`
  lists them in that order.

`stateMachine(...)` throws `IllegalArgumentException`, naming the state or
transition, when:

- `initial` is not the `from` or `to` of any transition;
- two transitions have the same name and the same `from` state;
- a lifecycle event gets a second handler, such as two `onEnter` for one state
  (even across merged blocks) or two `beforeTransition("x")`;
- `beforeTransition`, `onTransition` or `afterTransition` names a transition
  that is not declared;
- an `on("x")` has no `goesTo`.

### The lower-level API

The DSL is a thin layer over `State`, `Transition` and the `StateMachine`
constructor, which you can also use directly:

```kotlin
import com.amine2233.statemachine.*
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val draft = State("draft")
    val published = State("published")
    val publish = Transition("publish", from = draft, to = published)

    val machine = StateMachine(initialState = draft, transitions = listOf(publish))

    machine.fire(publish)
    println(machine.currentState == published) // true
}
```

Firing a transition the machine does not know throws `TransitionError.Unknown`;
firing one that does not start from the current state throws
`TransitionError.NotAllowed`; firing one whose guard refuses it throws
`TransitionError.GuardRejected` (see [Guarded transitions](#guarded-transitions)).
Ask first with `canFire(transition, userInfo)` or `allowedTransitions()` — the latter is
what UI usually binds to, so buttons enable themselves from the graph.

The constructor checks the graph and throws `IllegalArgumentException` if:

- two transitions have the same name and the same `from` state. Names only need
  to be unique per source state, so one `cancel` may leave several states;
- `initialState` is not the `from` or `to` of any transition. An empty
  `transitions` list always fails this check.

`machine.state` is a `StateFlow<State>` of the same cursor as `currentState`,
for UI layers to collect.

### Firing by name

When all you have is a name — a deep link, a push notification or a server
event — fire it directly. The machine looks the name up among the transitions
leaving the current state:

```kotlin
machine.fire("publish", userInfo = mapOf("id" to "article-42"))
```

It goes through the same path as `fire(transition)`: the transition's guard,
the lifecycle order, `ReentrantFire` from a handler and the one-at-a-time
serialization all apply. If no transition with that name leaves the current
state, it throws `TransitionError.UnknownName`, which carries the `name` and the
`currentState`, and nothing moves or runs. To check first,
`transition(named = "publish")` returns the matching transition or `null`.

### Observing the lifecycle

One handler per lifecycle event. They run in a fixed order, and `fire` only
returns once they've all finished:

```kotlin
machine.on(LifecycleEvent.OnState(published)) { userInfo ->
    println("published! id=${userInfo?.get("id")}")
}

machine.fire(publish, userInfo = mapOf("id" to "article-42"))
```

`BeforeTransition -> LeaveState -> OnState -> OnTransition -> AfterTransition`.

If a handler throws, `fire` rethrows that same exception and skips the handlers
after it. A throw in `BeforeTransition` or `LeaveState` vetoes the transition:
`currentState` stays where it was. A throw in `OnState`, `OnTransition` or
`AfterTransition` happens after the move, so `currentState` is already the
target state and stays there. Either way the machine stays usable. A handler
that must not interrupt the lifecycle should catch its own exceptions.

#### What handlers may do

- Handlers may call `currentState`, `isCurrent`, `canFire`, `allowedTransitions`,
  `on` and `off` on the machine that runs them.
- Reads see the old state in `BeforeTransition` and `LeaveState`, and the new
  state from `OnState` onwards.
- `on`/`off` called from a handler apply from the next `fire`, never to the
  transition in progress.
- Calling `fire` on the same machine from a handler throws
  `TransitionError.ReentrantFire`.
- Concurrent `fire` calls from different coroutines still run one at a time.
- A `state` collector, even one on an unconfined dispatcher that runs inline
  when the cursor moves, may call back into the machine the same way.

### Guarded transitions

A transition can carry a `guard`: a suspending check that receives the same
`userInfo` passed to `fire` and decides whether the move may happen.

```kotlin
val checkout =
    Transition("checkout", from = browsing, to = paying, guard = { userInfo ->
        (userInfo?.get("cart") as? List<*>).orEmpty().isNotEmpty()
    })

val machine = StateMachine(initialState = browsing, transitions = listOf(checkout))

machine.canFire(checkout, userInfo = mapOf("cart" to emptyList<String>())) // false
machine.fire(checkout, userInfo = mapOf("cart" to listOf("burger")))       // moves to paying
```

`fire` checks `Unknown`, then `NotAllowed`, then the guard. A guard that
returns `false` makes `fire` throw `TransitionError.GuardRejected`: the state
does not change and no handlers run. A guard that throws has its exception
rethrown unchanged, also with no state change and no handlers.

The guard that runs is always the one on the transition registered in the
graph; guards take no part in equality, so an equal copy without a guard
cannot get around it.

`allowedTransitions()` stays structural and never evaluates guards;
`canFire(transition, userInfo)` does.

Guards run outside the state lock, like handlers, so a guard may read the
machine: `currentState`, `isCurrent`, `canFire` and `allowedTransitions` all
work from inside it. A guard evaluated by `fire` runs before any handler is
called, so reads see the old state, and calling `fire` on the same machine from
it throws `TransitionError.ReentrantFire`.

### Restoring a machine

The graph is static; only the cursor moves. Save `currentState.name`, and pass
it back as the `initialState` to carry on where you left off:

```kotlin
val machine = StateMachine(
    initialState = State(order.state),
    transitions = OrderWorkflow.transitions,
)
```

With the DSL, pass the saved name as `initial`:

```kotlin
val machine =
    stateMachine(initial = savedName) {
        state("draft") { on("publish") goesTo "published" }
        state("published") { on("archive") goesTo "archived" }
    }
```

The restored state must belong to the graph: if no transition starts or ends
there, construction throws `IllegalArgumentException`.

## Try the kiosk example

```
gradle :sample-kiosk:run
```

See `sample-kiosk/src/main/kotlin/.../kiosk/KioskFoodOrderExample.kt` for a
full ordering flow (`idle -> browsingMenu -> ... -> orderConfirmed`) that
collects the machine's `state` the way a Compose screen would.

## Development

```
mise run test   # run the library's unit tests and the ABI check
mise run lint   # ktlint
mise run build  # compile everything
```

The public API is guarded by explicit API mode and a committed ABI dump in
`statemachine/api/`. `mise run test` and `./gradlew build` fail when the dump
is stale. After an intentional API change, run
`./gradlew :statemachine:updateKotlinAbi` and commit the updated dump. An
incompatible change (a removed or changed signature) needs a `feat!:` commit or
a `BREAKING CHANGE:` footer so semantic-release bumps the major version.

## CI/CD

CI and releases come from [kotlin-ci-shared](https://github.com/amine2233/kotlin-ci-shared).
All logic lives in `mise.toml`:

- `mise run test` / `mise run lint` — what the `CI` workflow runs on pull requests.
- Merging a `feat:` / `fix:` commit into `main` runs semantic-release: it tags
  `vX.Y.Z`, updates `CHANGELOG.md`, publishes `com.amine2233.statemachine:statemachine`
  to GitHub Packages and creates the GitHub release with the jars attached.
- `mise run release --dry-run` previews the next version locally.

### Consuming the published package

From another Gradle project:

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/amine2233/kotlin-statemachine")
        credentials {
            username = providers.gradleProperty("gpr.user").getOrElse(System.getenv("GITHUB_ACTOR"))
            password = providers.gradleProperty("gpr.key").getOrElse(System.getenv("GITHUB_TOKEN"))
        }
    }
}

dependencies {
    implementation("com.amine2233.statemachine:statemachine:1.0.0")
}
```

GitHub Packages requires authentication even for public read access — the
consumer needs a personal access token with `read:packages` scope.

## License

MIT — see [LICENSE](LICENSE).
