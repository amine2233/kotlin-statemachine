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
Ask first with `canFire(transition)` or `allowedTransitions()` — the latter is
what UI usually binds to, so buttons enable themselves from the graph.

`machine.state` is a `StateFlow<State>` of the same cursor as `currentState`,
for UI layers to collect.

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
`canFire(transition, userInfo)` does. Guards run while the machine holds its
lock, so a guard must not call back into the same machine (`fire`, `canFire`,
`allowedTransitions`, `on`, `off`). Reading `currentState` is fine.

### Restoring a machine

The graph is static; only the cursor moves. Save `currentState.name`, and pass
it back as the `initialState` to carry on where you left off:

```kotlin
val machine = StateMachine(
    initialState = State(order.state),
    transitions = OrderWorkflow.transitions,
)
```

## Try the kiosk example

```
gradle :sample-kiosk:run
```

See `sample-kiosk/src/main/kotlin/.../kiosk/KioskFoodOrderExample.kt` for a
full ordering flow (`idle -> browsingMenu -> ... -> orderConfirmed`) that
collects the machine's `state` the way a Compose screen would.

## Development

```
mise run test   # run the library's unit tests
mise run lint   # ktlint
mise run build  # compile everything
```

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
