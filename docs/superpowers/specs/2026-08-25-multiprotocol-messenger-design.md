# Netless Multiprotocol Messenger Design

## Goal

Turn the current Android/Jetpack Compose MVP into a familiar messenger for non-technical users while preserving an expert mode for transport and routing control. Messages must be able to cross a route where every hop uses its own supported transport.

## Product Behavior

- Users address persistent contacts by `ProfileId`, independently of current connectivity.
- The default network mode automatically chooses the best supported route.
- A manual transport policy supports `Preferred` and `Strict` modes.
- `Preferred` tries the selected transport first and falls back when unavailable.
- `Strict` disables fallback and requires an explicit warning before activation.
- Relay through any compatible node is enabled by default.
- End-to-end content encryption prevents relay nodes from reading message content.
- Relay nodes store encrypted undelivered packets for up to 24 hours, remove them after delivery confirmation, and expire them afterward.
- The simple UI shows human-readable connection states. Expert views expose route hops, transport types, metrics, and policy decisions.

## Architecture

### Control Plane

The control plane owns discovery, capability exchange, signed topology advertisements, route quality, and route calculation. A transport registry exposes supported discovery and data adapters without making routing depend on a concrete protocol.

Each route hop records its own transport, endpoint, node identity, metrics, and expiry. The route engine filters incompatible or expired hops and selects a route according to automatic or user policy.

### Data Plane

The data plane carries one packet format across all transports. A packet has a stable packet identity, final recipient, hop forwarding metadata, expiration, encrypted content, sender signature, and delivery state. A relay validates packet integrity and lifetime, deduplicates it, persists it before forwarding, selects the next hop, and deletes the stored copy after acknowledgement.

The existing `ForwardingEnvelope`, `ContentEnvelope`, and `RouteSelector` are the starting point. The current peer message frame must not remain the application-level delivery format because it is tied to one Wi-Fi Direct session and carries plaintext message fields.

### Transport Adapters

All transports implement the common data/discovery contract. Existing Wi-Fi Direct data transport is migrated first. BLE discovery remains useful, but BLE data delivery requires a GATT channel before it can be advertised as a usable data hop. Wi-Fi Aware and Local Hotspot are registered as capability-aware adapters and must report unavailable until their Android implementations exist.

### Persistence

Conversation storage keeps contacts, conversations, messages, delivery state, packet identifiers, and route summaries. Relay storage keeps encrypted packets, expiry timestamps, next-hop state, and deduplication records. Expired relay data is deleted without requiring message decryption.

## UI/UX

- Use a Telegram-like information architecture without copying Telegram branding or proprietary assets.
- Main navigation contains `Chats`, `Contacts`, and `Settings`.
- The phone layout uses a chat list followed by a separate conversation screen.
- Wide layouts may show chat list and conversation side by side.
- Chats include search, unread counts, timestamps, avatars, message bubbles, attachment/send controls, and delivery status.
- Contact screens expose persistent identity and QR/code sharing.
- Network details are collapsed by default behind the connection status.
- The network settings screen exposes automatic mode, transport preferences, preferred/strict mode, relay permission, and the 24-hour store-and-forward policy.
- The expert route screen shows every hop, protocol, endpoint class, quality metrics, fallback decisions, and delivery acknowledgements.

## Delivery States

The default UI maps internal states to simple labels such as `Connected`, `Relayed`, `Waiting for delivery`, `Delivered`, and `Expired`. Expert mode may expose detailed failure causes, but user-facing errors must explain the next action rather than expose protocol exceptions.

## Security Requirements

- Keep message content end-to-end encrypted.
- Authenticate peers before accepting forwarding or content packets.
- Validate packet signatures, destination, expiration, hop limits, and duplicate identifiers.
- Do not expose plaintext content to relay storage or routing code.
- Treat strict transport mode as an availability tradeoff and warn before enabling it.

## Verification

- Unit-test transport registration, capability filtering, preferred selection, strict rejection, per-hop transport routes, route expiry, and fallback.
- Unit-test packet authentication, deduplication, relay persistence, acknowledgement deletion, and 24-hour expiry.
- Add integration tests for mixed BLE/Wi-Fi Direct route plans using fake transport adapters.
- Test migration from the current Wi-Fi Direct message path to packet routing.
- Add Compose tests for chat navigation, simple delivery states, strict-mode warning, and expert route details.

## Phases

1. Build transport registry, capability model, route graph, and policy selection.
2. Replace the Wi-Fi Direct message frame with authenticated packet routing and relay persistence.
3. Add and register data adapters for BLE, Wi-Fi Direct, Wi-Fi Aware, and Local Hotspot as implementations become available.
4. Rebuild the Compose UI around chats, contacts, conversations, settings, and progressive disclosure of network details.
