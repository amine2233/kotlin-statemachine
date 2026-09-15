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
`TransitionError.NotAllowed`. Ask first with `canFire(transition)` or
`allowedTransitions()` — the latter is what UI usually binds to, so buttons
enable themselves from the graph.

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
full ordering flow (`idle -> browsingMenu -> ... -> orderConfirmed`), wired to
`StateFlow` the way a Compose screen would collect it.

## Development

```
gradle test    # run the library's unit tests
gradle build   # compile everything
```

> This repo doesn't check in a Gradle wrapper. If you'd like one locally, run
> `gradle wrapper --gradle-version 8.9` once and commit the generated
> `gradlew`, `gradlew.bat`, and `gradle/wrapper/` files. CI doesn't need it —
> the workflows install Gradle 8.9 directly via `gradle/actions/setup-gradle`.

## CI/CD

- **`.github/workflows/ci.yml`** — runs `gradle test` on every push and pull
  request to `main`, and uploads the test report as a build artifact.
- **`.github/workflows/publish.yml`** — publishes the `statemachine` module to
  GitHub Packages whenever a GitHub Release is published (the release's tag,
  e.g. `v1.2.0`, becomes the artifact version), or on demand via
  "Run workflow" with a version input.

No extra secrets to configure — both workflows use the automatically
provisioned `GITHUB_TOKEN`, which has `packages: write` for this repo.

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
