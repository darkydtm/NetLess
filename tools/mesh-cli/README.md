# Mesh CLI

The Python CLI is a platform-independent smoke-test for mesh delivery. It exercises in-memory direct routes, mesh routes, TTL, hop limits, duplicate suppression, and relay storage without Android or radio APIs.

The shared routing implementation lives in Kotlin at `core/common/src/main/kotlin/com/netless/network/RouteSelector.kt`. Android and later platform adapters should use that implementation and the `Route`, `RouteMetrics`, `NodeId`, and `TransferPolicy` contracts from `core/common`.

Run the Python smoke-test with:

```text
python tools/mesh-cli/mesh_cli.py self-test
python -m pytest tools/mesh-cli/test_mesh_cli.py -q
```

The Python code remains an external smoke-test, not a second shared routing implementation. Kotlin unit tests belong to `core/common` and cover deterministic route selection, metrics, expiry filtering, cycles, and configurable hop limits.

Balanced selection uses fixed normalized weights: bandwidth `0.35`, latency `0.25`, energy cost `0.15`, availability `0.20`, and hop penalty `0.05`. These constants keep selection deterministic across callers.
