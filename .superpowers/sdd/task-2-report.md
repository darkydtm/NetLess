# Task 2 Report

## Status

Implemented Task 2 on `feat/multiprotocol-messenger`.

The route graph now performs bounded breadth-first traversal over non-expired,
acyclic hops, preserves transport data on each selected route, and the route
engine applies automatic, preferred, strict, and relay policy filtering before
delegating scoring to `RouteSelector`.

## Files

- `core/common/src/main/kotlin/com/netless/network/Route.kt`
- `core/common/src/main/kotlin/com/netless/network/RouteGraph.kt`
- `core/common/src/main/kotlin/com/netless/network/RouteEngine.kt`
- `core/common/src/main/kotlin/com/netless/transport/TransportContracts.kt`
- `core/common/src/test/kotlin/com/netless/network/RouteEngineTest.kt`
- `core/transport-api/src/main/kotlin/com/netless/transport/TransportApi.kt`
- `core/transport-api/src/main/kotlin/com/netless/transport/TransportPolicy.kt` was moved to the common transport contracts file to avoid the existing `core:common` and `core:transport-api` dependency cycle.

Unrelated untracked paths `.sign/`, `.superpowers/`, and `prototype/` were not staged or modified.

## Tests / Output

- TDD test file added first: `RouteEngineTest` covers mixed transports, strict rejection, expiry, and preferred selection.
- Focused command attempted: `./gradlew :core:common:test --tests com.netless.network.RouteEngineTest`
- Result: not runnable. The repository has no `gradlew` wrapper and no system `gradle` executable.
- `git diff --check`: passed with no output.
- Post-commit focused command was attempted again with the same missing-wrapper result.

## Commit

- `9e1429d add multiprotocol route engine`

## Concerns

- Gradle tests could not be run in this checkout because the build runner is unavailable.
- Route graph traversal uses the existing default `TransferPolicy.maxHops` value of 2 because the required `RouteGraph.routesTo(destination, nowMillis)` interface does not accept a policy argument.
- The transport contracts relocation is required for module acyclicity and should be checked against any external consumer assumptions about source-module ownership.

## Review Fix Report

### Fixes

- Kept the required `java.util.Collections` import in `Route` so immutable node and hop views compile.
- Added a caller-time overload to `RouteSelector`; `RouteEngine` passes `nowMillis` through and uses the graph hop limit for filtering.
- Changed graph traversal to consider active connected components, allowing valid routes after expired incoming hops and routes inside cyclic components while preventing repeated nodes per path.
- Validated every supplied route hop against its corresponding node edge during construction and copy.
- Added regressions for caller time, expiry fallback, cyclic traversal, hop limits, and node/hop consistency.

### Verification

```text
$ ./gradlew :core:common:test --tests com.netless.network.RouteEngineTest --tests com.netless.network.RouteTest
/usr/bin/bash: ./gradlew: No such file or directory
exit: 127

$ git diff --check
PASS
```

Focused Gradle tests could not run because this repository has no Gradle wrapper and no system `gradle` or `kotlinc` executable. Task 1/3/4 files and untracked user files were preserved.

### Self-review

- Route expiry is checked before expansion and again before selection.
- Cycles are bounded by the existing `TransferPolicy.maxHops` default and rejected per path by node membership.
- Existing routes without transport hops remain supported for Task 1 selector tests; routes carrying hops must have exact node-edge alignment.

### Fix Commit

- `10716ff fix route engine review regressions`
