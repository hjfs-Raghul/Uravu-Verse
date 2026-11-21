# Uravu Verse

Offline-first family tree for Android that tells you **how any two people are related** and **what to call them in Tamil, Malayalam and English**.
The tree looks like a family sketched on one big white sheet: pencil-drawn paper stamps on an infinite canvas.

* Kotlin, Jetpack Compose, Room, MVVM. No network, no accounts, no Firebase.
* `domain/` is pure Kotlin (no Android imports) and unit-tested: data model, relationship rules, relationship engine, terminology, layout, backup format.
* `data/` Room + repository (single transaction per change, undo snapshots, photo store, backup/restore).
* `ui/` Compose screens and the canvas renderer. `work/` optional "family in this city" alerts.

## Build the APK (no local Android Studio needed)

Push to `main`/`master` or run the **Build debug APK** workflow manually (Actions tab → *Run workflow*).
It runs the unit tests, builds `:app:assembleDebug` and uploads
`app/build/outputs/apk/debug/app-debug.apk` as the artifact **UravuVerse-debug-apk**.

Locally (needs JDK 17 + Android SDK): `./gradlew :app:testDebugUnitTest :app:assembleDebug`

## Design notes

* **Relationships are data, lines are generated.** `FamilyData` = persons + parent links (biological / adoptive / step) + marriages (current / previous / divorced / widowed). Divorce only changes a marriage record; parent-child links are never rewritten. Siblings, half-siblings and derived step-parents come from parentage and marriages.
* **Engine** (`RelationshipEngine`): graph search over parent/child/spouse/sibling edges → kinship code (e.g. `FeB` = father's elder brother) → `Terminology` table (English / Tamil / Malayalam). Unknown age order yields **both** outcomes. Terms are data in `DefaultTerminology.kt` and can be overridden per family from Settings › Terminology; missing terms are shown as "not configured" rather than invented. **+ Add new term** offers quick picks for relationships in your own tree that lack terms, and a step-by-step builder for any other relationship.
* **Drawings**: 16 categories (baby, kid, boy/girl, teen, adult, family, 50s–60s, old × male/female), 12 distinct pencil drawings each (`StickerLooks`, unit-tested for distinctness).
* **Birth order**: each person has an optional `birthOrder` among all siblings (full and half). Drag-and-drop permutes the existing order slots so relative position to half-siblings is preserved. Explicit order beats dates of birth, which beat the "older/younger than Raghul" hint.
* **Backup**: ZIP with `manifest.json` (format + `backupVersion`), `data.json`, `photos/`. Validated (versions, references, cycles, unsafe names) before anything is replaced.
* **Alerts** are off by default; see the in-app explanation of the Android background-location limits.

## Free arrangement and composed terms
* **Arrange freely:** press and hold a sticker, then drag it anywhere. Positions are saved (and included in backups); **Reset layout** returns to the automatic arrangement. See `domain/ManualLayout.kt`.
* **Relations without a term** are split into the fewest, largest parts that each have a term (e.g. mother's brother's son + son) and joined (`Terminology.compose`, `Genitive`). Composed terms are marked "Built from: ..." and still show up as suggestions under Add new term.
