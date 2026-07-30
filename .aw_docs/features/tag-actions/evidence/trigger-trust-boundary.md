# Trigger trust boundary — what was wrong, what is proven

Third review of this codebase, third time the worst finding sat in the layer deciding *when* logic runs
rather than in the logic. Recorded here because the pattern is now consistent enough to be a property of
the code rather than a coincidence.

## The defect

`TagActionDispatch.shouldAct` took three conditions and the test suite swept all eight combinations of
them. But at the only call site the activity did this:

```kotlin
val uid = tag?.id?.let(ByteBlock::copyOf)
if (uid == null) { finish(); return }
...
shouldAct(intentAction = intent?.action, hasTagExtra = true, assignment = assignment)
```

`hasTagExtra = true` restated the null-check immediately above it. It could never be `false`, so of the
three "necessary, none sufficient" conditions only two could vary in production — and the sweep gave
full confidence over a parameter that carried no information. The KDoc claimed it was "the part a
hostile caller cannot fake"; that claim was wrong.

## Reachability, measured

Not theoretical. From an ordinary third-party uid:

```
$ adb shell am start -n dev.shivam.nfcexplorer/.TagActionActivity -a android.nfc.action.TECH_DISCOVERED
Starting: Intent { act=android.nfc.action.TECH_DISCOVERED cmp=dev.shivam.nfcexplorer/.TagActionActivity }

ActivityTaskManager: START u0 {act=android.nfc.action.TECH_DISCOVERED
  cmp=dev.shivam.nfcexplorer/.TagActionActivity} with LAUNCH_MULTIPLE
  from uid 2000 (com.android.shell) (BAL_ALLOW_PERMISSION) result code=0

W/NfcExplorer: [4] trigger: trigger invoked without a tag; ignoring
  {action=android.nfc.action.TECH_DISCOVERED}
```

The activity started, from a caller that is not the NFC stack, with a spoofed action string. The only
thing that stopped it was the absence of a `Tag` — and `android.nfc.Tag` is a `Parcelable` with a public
`CREATOR`, so a caller can build one carrying any UID it likes.

So the guard reduced to: *does the caller hold a UID the user has configured?* A UID is broadcast in the
clear by every tag to every reader, and UID-rewritable cards are commodity items.

## The fix

`TagPresence.check` opens a connection to the tag and reports whether it answered. The tag handle inside
a `Tag` is issued by the NFC service for a live discovery session; a handle that was never issued is not
in that table, so a forged parcel cannot be connected to. That is the one signal in the intent which
cannot be fabricated in software.

Two supporting changes, because fixing the instance is not the same as removing the class:

- `shouldAct` now takes a `TagPresence.Answer`, not a `Boolean`. `true` no longer compiles there. A
  hand-written `Answer.Live` is a visible claim rather than a plausible-looking flag — which matters
  because the activity itself has no unit test, so nothing else would catch the regression.
- The answer carries the *cause* of a refusal. The trigger has no UI, so a tap that silently does
  nothing must be explainable from the log afterwards: "left the field" points at how the card was
  waved, "no technology this app can open" points somewhere else entirely.

## What is still open, deliberately

**A cloned UID passes.** A clone is a genuinely live tag; presence cannot tell it from the original.
This is inherent to identifying tags by UID — the same property MacroDroid and Tasker rely on — and is
not fixable at this layer. The bound on the damage: an attacker can only replay an action the user
configured themselves. They cannot introduce a new one.

**Cost on the happy path.** A connection attempt now runs on every tap. A card whipped away in the same
instant it was discovered will fail it and the action will not run. Accepted: the failure mode is
"nothing happened", it is logged with the cause, and a second tap fixes it. The alternative was a guard
that could not tell a tap from a hostile intent.

---

# The crash this exposed — `Theme.NoDisplay`

Reported from the field on the first real tap of an assigned tag: a buzz, then "NFC Explorer keeps
stopping".

```
java.lang.RuntimeException: Unable to resume activity {dev.shivam.nfcexplorer/.TagActionActivity}
Caused by: java.lang.IllegalStateException: Activity {…TagActionActivity}
    did not call finish() prior to onResume() completing
    at android.app.Activity.performResume(Activity.java:9516)
```

`Theme.NoDisplay` is not merely "draw nothing" — it is a contract that `finish()` happens before
`onResume()` returns, and the platform enforces it by throwing. The trigger has to read the assignment
store before it knows whether to act, which suspends, so `finish()` always landed after `onResume`.

**This was latent from the moment the activity was written.** The presence check did not cause it; it
widened the pre-suspension window enough to make it fire every time. The reason nobody saw it earlier is
that every test of this activity used a launch with *no* tag, which returns and finishes synchronously —
the one path that satisfies the contract. Phase 5, the tap on a real assigned tag, had never been run.

Fixed by `Theme.NfcExplorer.Trigger`, a translucent theme: same invisibility, no such contract.

Verified: the trigger launched over the app drawer draws no frame at all — the drawer stays fully
visible, no NFC Explorer UI, and the crash buffer is empty afterwards. Not yet verified: the async
finish itself, because reproducing it needs a real tag in the field. That is the tap this file is
waiting on.

Also moved the tag exchange to `Dispatchers.IO`. It was running on the main thread, which is wrong for
a blocking NFC exchange regardless of the crash.

## The log line to look for next

A refused tap and an unassigned tap used to log the same way, so a failed tap could not be told from a
tap on the wrong card. Presence failing on an assigned tag now logs at WARN:

```
refused a tag that has an assignment  {uid=…, label=…, presence=did not answer: …}
```

That is the signal that decides whether the presence check is affordable on real hardware. If it appears
on ordinary taps, the gate is too strict and the trade-off recorded above has to be revisited.
