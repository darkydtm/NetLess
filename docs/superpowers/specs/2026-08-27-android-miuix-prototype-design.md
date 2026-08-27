# Android Miuix Prototype

## Scope

Implement the existing `prototype/web` client prototype as a native Android Compose UI using Miuix `0.9.4-rc01`. This phase is UI-only: use mock peers and local Compose state, and do not connect screens to the real runtime or delivery services.

## Screens

- Chats: search peers, show connection or delivery status, and open a conversation.
- Conversation: show messages and support local reply, edit, delete, and send actions.
- Profile: show the display name and persistent profile ID.
- Settings: support language, theme, route selection, transport, delivery priority, relay-hop limit, message lifetime, relay participation, store-and-forward, and notifications.

## Structure

Keep the existing Android `app` module, `MainActivity`, and `NetlessApp` entry point. Use one Miuix theme and one root `Scaffold`; manage prototype state in the prototype composable. Use Miuix components and icons instead of Material 3 components.

## Verification

Verify dependency/API usage against the pinned Miuix release, inspect the resulting diff, and run repository checks that do not require a local Android build. Android compilation remains a GitHub Actions responsibility under the repository instructions.
