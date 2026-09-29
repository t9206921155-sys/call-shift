# Gesture lifecycle correction after 0.8.19

Found in code review: the card reader could observe an opened profile and perform
Back before Android delivered dispatchGesture's completion. Cancelling the gesture
at that point could terminate a visually successful training run. A Boolean busy
flag also did not distinguish duplicate/late callbacks from another dispatch.

The service now uses a tokenized, single-flight gate shared with unit tests:
- no profile reading, Back, editor input or next gesture until Android completes;
- only the owning completion/cancellation/rejection releases the gate;
- a two-second missing-callback deadline stops the scenario, but DOES NOT pretend
  Android finished the physical action and does not permit a new gesture;
- stop, timeout and late completion cannot resume a terminated scenario;
- a new call, training, route probe or profile capture cannot overlap a pending gesture;
- an unresolved gesture is explicitly described on the main screen, rather than an
  endless generic “running” message. Reconnecting the service is recovery, not resend.

Unit regressions cover duplicate dispatch, foreign/duplicate/late callbacks, clock
rollback and timeout without retry. This is lifecycle logic validation, not a
claim of successful dispatchGesture or MAX delivery on the handset.

No changes to recipient proof, gesture consent, geometry, clone routing, SMS/rules
or existing rate limits. No new interaction model or settings screens.
