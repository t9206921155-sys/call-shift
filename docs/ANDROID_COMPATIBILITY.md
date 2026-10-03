# Android compatibility — 0.7.3

Support target: Android 9/API 28 and newer cellular phones. This is NOT a
certification of every handset/OEM/future Android release. Local unit tests do
not replace real-device tests. No hidden permission/root bypass is introduced.

- API 28: first-run setup requests default dialer using Telecom's public intent;
  incoming calls can be evaluated by the existing InCallService fallback.
- API 29+: screening role or default dialer; outgoing screening callbacks must
  never be treated as incoming. Role availability depends on system firmware.
- API 28–29: SMS routing matches active subscriptions to the canonical saved
  PhoneAccount ID, not its old serialized handle representation.
- API 30+: public account-to-subscription mapping first, then active subscriptions.
- Unknown/ambiguous SIM remains a stop condition, never another-SIM fallback.
- Runtime readiness checks roles, telephony/SMS capability, phone/SMS permissions,
  detected SIMs and usable routes. This does not prove network balance/delivery.
- Runtime notification permission remains gated at API 33; edge-to-edge insets
  are explicitly applied after the asynchronously constructed SMS editor, too.
- Native Telegram libraries remain packaged for arm64-v8a, armeabi-v7a, x86 and
  x86_64. Newer devices' 16 KB page-size/native-loader compatibility has NOT been
  validated here. Do not advertise every Android 15/16 phone as tested.

## Additional usability

The focused editor has Work/Driving/Holiday text presets. They do not activate
or overwrite SIM/recipient selection. Choose an expiry (1/3 hours, day, week),
then save. Expiry uses the existing rule time gate, not an alarm or timed SMS.
The main screen shows the latest recorded call and separately the latest SMS
attempt, with timestamps. They are explicitly not assumed to be the same call.
Open the block to see the full log. A requested rejection is not labelled a
confirmed rejection. No record is not proof that a call never occurred.

## Owner-managed signing (still not provisioned)

In addition to ignored keystore.properties, builds accept environment variables:
CALLSHIFT_KEYSTORE (private file path), CALLSHIFT_STORE_PASSWORD,
CALLSHIFT_KEY_ALIAS, CALLSHIFT_KEY_PASSWORD. All four are required if any is set;
partial configuration fails. Existing local properties take precedence.
Use `-PrequireReleaseSigning=true` for production builds. Never print secrets,
put them on command lines, or upload a key into chat/git/artifacts/caches.
Owner must retain an encrypted backup of the same key for every future release.
Current integration cannot access repository Actions secrets (403), nor provision
workflow signing. Debug builds remain test-signed; release without key unsigned.

## Required device matrix (not yet executed)

API 28, 29, 30/31, 33, 34, 35/36: one and two SIM/eSIM, default dialer and
screening paths, contacts vs unknown, +7/8 equivalents, outgoing calls (no reply),
missing permissions/roles, incoming account absent, no network/airplane mode,
process kill, budget exhaustion, expiry, font scaling/rotation/keyboard/insets.
Samsung, Xiaomi/HyperOS, Huawei/Honor and Pixel should be independently checked.
Record device, Android/build, granted role, selected SIM and sanitized log result.
