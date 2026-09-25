# 0.7.2 — SMS-focused mode

New rules open in “Сброс + SMS”: SIM, contacts/unknown/number mask, text,
weekly time window, repeat interval. Complex existing rules keep the full editor;
that editor is also available under “Дополнительно”. Saved enabled state, priority
and absolute dates remain unchanged when editing. AutoReply priority is visible
on the main screen and editors, with an explicit disable action.

“SMS: защита и копия” provides:
- default 20 billable segments per rolling 24 hours, configurable 1–1000;
- global counter for automatic, manual and test SMS across all SIMs;
- durable reservation before SmsManager, guarded by one process-wide mutex;
- multipart accounting, no automatic retry, failed/unknown attempts keep their
  reservation for 24 hours. A storage error blocks sending, rather than bypassing
  the limit. This is a safety counter, not the operator's billing statement;
- per-SIM SMS cooldown by default, optional common history. Prior common history
  is honored until it expires. Changing the mode does not clear history;
- JSON export/import via Android's file picker; rules, AutoReply, whitelist,
  urgent-repeat settings, SMS safety and UI number masking. Not a complete device
  backup: roles/permissions, default routing policy, theme, logs, budget/cooldown
  history, credentials, Telegram sessions and endpoint tokens are not transferred.

JSON contains private numbers and messages in plaintext. Keep it private. Export
strips endpoints. Import validates size/version/limits before changes. Rules and
AutoReply import disabled, master OFF, default policy PASS. Verify SIM on the target
phone, then explicitly enable only needed rules. Import does not reset the current
budget. Already started calls/sends cannot be cancelled by importing. Multi-store
restore is best-effort with rollback, not a transactional database; master is
persistently disabled before replacing rules. Rules use Android AtomicFile.

“Статус” lists roles, permissions, SIM SMS routing, priority and exceptions, with a
paid test SMS requiring SIM, recipient and confirmation. A detected SIM does not
prove network coverage, balance or delivery. The same safety budget applies.

## Signing and updates

Release no longer silently falls back to a disposable debug key. Without private
signing configuration CI produces an **unsigned release**, not installable as-is.
The debug artifact remains installable for tests, but its key may change between
CI workers. Do not promise an in-place upgrade from earlier test APKs.

The owner must create/retain a private production key OUTSIDE the repository,
using Android Studio's signed APK wizard or keytool (interactive passwords; never
put passwords on a command line). Back it up encrypted in two owner-controlled
locations. Do not upload keys into chat, git, artifacts or public caches.

Create ignored `keystore.properties` locally (restrict permissions):

```
storeFile=/absolute/private/path/callshift.jks
storePassword=<private>
keyAlias=<private alias>
keyPassword=<private>
```

Then run:

```
./gradlew :app:assembleStandardRelease -PrequireReleaseSigning=true
```

Missing/incomplete configuration fails instead of using another key. Verify the
APK with Android SDK `apksigner verify --print-certs`. Keep the same package and
certificate for all subsequent releases and increase versionCode. Current package
and SHA-256 certificate are displayed in “SMS: защита и копия”. A configured key
alone cannot prove that its owner retained a backup.

For CI the repository owner must provide secure secrets and a private ephemeral
keystore provisioning step; current workflow is intentionally not modified by
this integration. Permanent production signing is **not configured by this change**.
The debug package (`.debug`) differs from release. Export before any migration;
never uninstall an existing installation blindly to bypass signature mismatch.

## Validation

Unit tests cover exact cap, multipart counts, 24-hour boundary, clock rollback,
serialized reservation contention, per-SIM key separation, backup roundtrip,
disabled restore, rejected versions/limits/size/time/IDs, endpoint stripping and
complex-rule editor gating. Device checks still required: both SIMs, +7/8 matching,
cap exhaustion, no retries after airplane mode/errors, process kill, permissions,
SAF restore and signed in-place upgrade. Android role/OEM restrictions still apply;
identical behavior on every Android build cannot be guaranteed.
