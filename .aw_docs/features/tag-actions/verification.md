# tag-actions — verification

## AC status

| AC | What it requires | Status |
|----|------------------|--------|
| AC1 | Assignment survives app restart and device reboot | **Partial.** Survived repeated app restarts and reinstalls — the store was read back off the device between builds. Reboot not yet tested |
| AC2 | Tapping an assigned tag with the app closed acts, shows no UI | **Met on hardware** |
| AC3 | Tapping an unassigned tag does nothing, shows no UI | Not yet tested (card A) |
| AC4 | Each action type produces the correct `Intent` | Met by unit tests on `IntentSpecMapper` |
| AC5 | An action naming an absent app fails clearly, no crash | Not yet tested |
| AC6 | The trigger ignores any launch that is not a genuine NFC dispatch | Met, and strengthened after the third review found the guard's key condition was a tautology. See `evidence/trigger-trust-boundary.md` |
| AC7 | Create from last scan, edit, delete, test from the UI | Met; create/edit/delete/test exercised on device |
| AC8 | `testDebugUnitTest` and `detekt` green | Met — 367 tests, 0 failures |

## AC2, on hardware

Card B `04 1C 4E 52 CE 7C 80`, assigned through the new app picker, NFC Explorer closed:

```
I/ActivityTaskManager: START u0 {act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER]
  flg=0x10000000 cmp=in.amazon.mShop.android.shopping/com.amazon.mShop.home.HomeActivity}
  with LAUNCH_MULTIPLE from uid 10481 (dev.shivam.nfcexplorer) result code=2

I/ActivityTaskManager: Displayed dev.shivam.nfcexplorer/.TagActionActivity for user 0: +243ms
```

Stored assignment, read back off the device:

```json
{"version":1,"assignments":[{"uidHex":"041c4e52ce7c80","label":"Amazon",
  "action":{"type":"launchApp","packageName":"in.amazon.mShop.android.shopping"}}]}
```

Three things this confirms beyond AC2:

- **The presence check is affordable.** No `refused a tag that has an assignment` warning on a normal
  tap, which was the open question — the connection attempt added in front of every action does not
  spoil an ordinary tap. The trade-off recorded in `evidence/trigger-trust-boundary.md` stands rather
  than needing to be revisited.
- **The picker's label pre-fill works.** The label reads `Amazon`, which was never typed — it came from
  the chosen app.
- **The trigger is invisible.** `Displayed … +243ms` with no frame: the translucent theme replacing
  `Theme.NoDisplay` draws nothing while still allowing the async work that theme forbade.

## Remaining

- Reboot, then tap (AC1 in full)
- Tap card A, unassigned (AC3)
- Assign an absent package and tap (AC5)
- Note whether a MacroDroid chooser appears if MacroDroid is also configured
- README: the feature, the MacroDroid collision, and that no permission was added (Task 5.2)
