Status: implemented
Commit: signed `chore: add github actions ci` at `HEAD`
Tests: Python CLI self-test passed; pytest smoke-test passed with 15 tests. Gradle/Android build was not run locally by request; GitHub Actions runs assembleDebug and all Gradle unit tests.
Concerns: no Gradle wrapper was added because creating and validating one would require a local Gradle invocation; the workflow uses pinned Gradle 8.9 instead. Android SDK license acceptance is non-fatal if the runner has already accepted the licenses.
Worktree: /tmp/opencode/netless-ci
