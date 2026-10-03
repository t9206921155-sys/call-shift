# Missing click-source recovery (not yet handset-verified)

Observed user screenshot: SOURCE_MISSING during card learning. Card visible, but
AccessibilityEvent.source absent. This does not mean the card itself is unreadable.

The learner now tries a narrowly bounded alternative before that refusal:
- MAX click event in the same window, pre-click snapshot no older than 1500 ms;
- exactly one candidate of the event's reported class, with a unique resource ID;
- resource ID must identify the saved title or avatar/profile/userpic, in MAX's
  namespace; no opaque button identifiers, toolbar container or composite control;
- still require labelled phone card, return to identical empty chat, one ACTION_CLICK
  replay, the same phone card and final return before saving anything;
- a source-less replay event is accepted once under the same window/class/uniqueness
  constraints, so the missing source does not simply cause a later EXTRA_CLICK;
- no coordinates, no button enumeration by trial clicks, no recipient binding,
  no messages during training. Live permission remains off.

Unit tests cover stale/missing/ambiguous/foreign-window/class/namespace/opaque-ID
rejection. These are policy tests, NOT evidence that this phone exports the required
class and ID. Existing stored SOURCE_MISSING markers are intentionally NOT reset;
this is a development change, not a new request for repeated handset tests. There
is no new signed release or claim of successful MAX sending with this patch.
