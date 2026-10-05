# Treasure for iOS

SwiftUI (iOS 17+) client for finance-engine. It talks to the engine through the identity router at
`<router>/finance/api/v1/operations/<name>`, signed in with the same Clerk instance as AutoTelemetry.

```sh
brew install xcodegen          # once
cd clients/ios && xcodegen generate
open Treasure.xcodeproj        # or: xcodebuild -scheme Treasure -destination 'id=<simulator>' test
```

Router address and Clerk publishable key live in `project.yml`.

## What it does

Home (this month, recent), Spends (day-grouped, search, swipe to delete with Undo), Add/Edit sheet with a register-style
keypad, Insights (trend and category charts, compared with the earlier period), and More (categories, tags and merchants,
CSV export, Face ID lock).

## Rules that come from the engine

- Amounts are positive integer minor units; `kind` carries direction. Aggregates arrive as integer strings.
- `occurred_on` is a calendar date with no timezone. It is converted only at the edge, by `CalendarDate`.
- Every write carries an `idempotency_key`. Updates replace all editable fields, so edits resend every field they don't show.
- Spends created offline wait in a local outbox and are sent in order, with the same key, when the connection returns.
- Currencies are never combined.

## Not built yet

Offline edits and deletes, tags and evidence in the editor, split editing, refund linking, a tag breakdown, and the
router's `/finance` route (a deployment task).

## Importing receipts and statements

Import button next to "+": scan with the camera (VisionKit), choose photos, or import a file (PDF, image, CSV). Everything up
to the final save runs on the device: PDFKit's own text layer (Vision OCR for scanned pages), then rule-based parsing in
`Treasure/Import` (receipt = one spend, statement = many, CSV by header names), merchant and category suggestions learned from
your own history, and duplicate flags. You review every row, then spends go in as `spends_bulk_create` batches with
`source = "import"` and a stable fingerprint as `source_record_id`; the file is recorded once as evidence (hash and name only).
