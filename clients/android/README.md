# Treasure for Android

Jetpack Compose (Material 3, min SDK 26) client for finance-engine. It talks to the engine through the identity router at
`<router>/finance/api/v1/operations/<name>`, signed in with the same Clerk instance as AutoTelemetry. It mirrors `clients/ios`.

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # Gradle 9 needs JDK 17+
cd clients/android
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Router address and Clerk key come from `local.properties` (`ROUTER_BASE_URL`, `CLERK_PUBLISHABLE_KEY`) or
`-PROUTER_BASE_URL=...`; defaults point at the shared dev instance.

## Layout

| Path | Responsibility |
| --- | --- |
| `net/` | `Api` result type, `FinanceApi` (one POST per operation), `Engine` (token + 401 retry) |
| `data/` | Models, money and date rules, period/trend math, and the stores (spends, directory, outbox, organize) |
| `auth/` | Clerk sign-in and the biometric app lock |
| `ui/` | Theme tokens, shared components, and one file per screen |
| `Session.kt` | Everything that belongs to one signed-in user; dropped on sign-out |

Stores depend on the `EngineApi` interface, so the logic tests (`app/src/test`) run on the JVM with no emulator.

## Rules that come from the engine

- Amounts are positive integer minor units; `kind` carries direction. Aggregates arrive as integer strings.
- `occurred_on` is a calendar date with no timezone (`java.time.LocalDate`, never an `Instant`).
- Every write carries an `idempotency_key`. Updates replace all editable fields, so edits resend every field they don't show.
- Spends created offline wait in the outbox (app-private files, never purged) and are sent in order, with the same key.
- Currencies are never combined.

## Not built yet

Offline edits and deletes, tags and evidence in the editor, split editing, refund linking, a tag breakdown, bar-by-bar
TalkBack reading of the trend chart (it announces a summary), and an instrumented UI test.

## Importing receipts and statements

Import button next to "+": scan with camera (ML Kit Document Scanner), choose photos, or import a file (PDF, image, CSV).
Everything up to the final save runs on the device: ML Kit's bundled text recognizer reads photos, PDFs are drawn page by page
with `PdfRenderer` and read the same way, and `ingest/` parses the text with rules (receipt = one spend, statement = many,
CSV by header names), learns merchants and categories from your own history, and flags duplicates. You review every row,
then spends go in as `spends_bulk_create` batches with `source = "import"` and a stable fingerprint as `source_record_id`,
and the file is recorded once as evidence (hash and name only). `ingest/Parsing.kt` and `Enrich.kt` mirror
`clients/ios/Treasure/Import`; keep their tests in step.

`./gradlew :app:connectedDebugAndroidTest` (emulator or device) runs the real OCR and PDF path on drawn documents.
