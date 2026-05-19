# Repository Guidelines

## Project Structure & Module Organization
- `app/src/main/java/com/example/rocketplan_android/` contains the main Kotlin source organized by feature (data, domain, ui, etc.).
- `app/src/main/res/` contains Android resources (layouts, values, drawables).
- `app/src/test/` and `app/src/androidTest/` hold unit and instrumentation tests.

## Build, Test, and Development Commands
- `./gradlew assembleDevStandardDebug` — build dev debug APK
- `./gradlew assembleDevFlirDebug` — build FLIR thermal device debug APK
- `./gradlew installDevStandardDebug` — build and install to connected standard device
- `./gradlew installDevFlirDebug` — build and install to connected FLIR device
- `./gradlew compileDevStandardDebugKotlin` — compile only (fast check)
- `./gradlew testDevStandardDebugUnitTest` — run unit tests
- Use `run_in_background: true` for all Gradle commands so the user can continue working.
- See `CLAUDE.md` for full build variant reference (Dev Standard, Dev FLIR, Staging, Production).

## Coding Style & Naming Conventions
- Language is Kotlin; architecture follows offline-first with Room database.
- Use PascalCase for types and camelCase for properties/functions.
- Keep UI logic in ViewModels, business logic in Services/Managers.
- Prefer Kotlin Coroutines + Flow for async operations.

## Testing Guidelines
- Unit tests live in `app/src/test/` with `*Test.kt` naming.
- Instrumented tests live in `app/src/androidTest/` with `*AndroidTest.kt` naming.
- Run targeted tests with `./gradlew testDevStandardDebugUnitTest`.

## Commit & Pull Request Guidelines
- Commit messages are short, imperative, and descriptive (e.g., "Fix upload retry edge cases").
- Include build/version bumps explicitly when relevant.
- PRs should describe the change, link related issues, and note test coverage.

## Configuration & Security Notes
- The app targets multiple environments (Dev/Stage/Prod); confirm the correct build variant.
- Do not commit secrets or API keys; use environment-specific config files instead.

## Documentation
All project docs live under `docs/`. See `docs/README.md` for the folder structure — use it to decide where to place a new doc or where to look for an existing one. If `docs/README.md` does not exist yet, follow the structure below:

- **Bug tracker**: `docs/BUG_TRACKER.md` is the single source of truth for bug status. Always check it first when investigating a bug. Update it at every lifecycle step (register, plan, fix, release).
- For bug/fix documentation flow, follow the "Bug doc lifecycle" section in `docs/README.md` (or the pattern from `docs/BUG_TRACKER.md` if README doesn't exist).
- When asked to perform a code review and save it, save the review under `docs/reviews/`.
- Use a filename in the form `code_review_<scope>_<YYYY-MM-DD>.md`.
- **Code review metadata**: Always record precise timestamp (YYYY-MM-DD HH:MM:SS) and uncommitted files (`git status --porcelain`) at the start of the review. Include this in the review doc header.
- When asked to write a test plan, save it under `docs/testing/TEST_PLAN_<feature>_<date>.md`.
- When the user refers to "console logs", check `adb logcat` output filtered by tag (e.g., `logcat -d -t 500 --pid=$(adb shell pidof -s com.rocketplantech.rocketplan)`).

## Agent Context
- If `.codex` exists, open and follow it at the start of a session to load shared context.
- Before changing offline/Room database code (`Offline*Service`, `SyncQueueManager`, sync job code, or ViewModels that call offline services), read relevant docs in `docs/architecture/` if they exist.
- For bug/fix work, always check `docs/BUG_TRACKER.md` first and update it at every lifecycle step.
