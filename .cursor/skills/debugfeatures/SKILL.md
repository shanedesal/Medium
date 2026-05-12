---
name: debugfeatures
description: Investigate, debug, and fix bugs in this Android Kotlin MVVM project (Medium/Connect app). Guides the agent through systematic root-cause analysis across the MVVM layers (Fragment → ViewModel → Repository → DataSource/Room/Firestore), produces a structured impact-and-ripple plan before touching any code, and presents it to the user for approval. Use when the user reports a bug, unexpected behavior, crash, or asks to debug a feature.
disable-model-invocation: true
---

# debugfeatures — Bug Investigation & Fix Skill

## Project Quick Reference

| Layer | Key files |
|---|---|
| **View** | `ui/auth/`, `ui/main/fragments/`, `ui/main/adapters/` |
| **ViewModel** | `ui/*/…ViewModel.kt` + `…ViewModelFactory.kt` |
| **Repository** | `data/repository/` — `AuthRepository`, `PostRepository`, `UserRepository`, `NotificationRepository` |
| **Remote source** | `data/remote/FirestoreDataSource.kt`, `CloudinaryDataSource.kt` |
| **Local source** | `data/local/` — `AppDatabase`, DAOs, entities, `Converters.kt` |
| **Utils** | `utils/Resource.kt`, `Constants.kt`, `ThemePreferences.kt`, `Extensions.kt` |
| **Entry points** | `AuthActivity` (launcher) → `MainActivity` (nav host) |
| **Navigation** | `res/navigation/nav_auth.xml`, `nav_main.xml` |
| **DI** | Manual — ViewModels built in `*ViewModelFactory`; no Hilt |
| **Async contract** | All async results must flow as `Resource<T>` |

---

## Phase 1 — Investigation

Work through each step before touching any code.

### 1.1 Reproduce & Characterise

- Identify the exact symptom: crash (exception + stack trace), wrong data, missing UI update, nav error, etc.
- Note the entry point: which Fragment / Activity / ViewModel triggers the issue.
- Confirm whether it is deterministic or intermittent.

### 1.2 Layer Triage

Trace the call chain bottom-up through the MVVM stack:

1. **View layer** — Is the Fragment observing the correct `LiveData`? Is binding null-safe (`_binding!!` pattern)? Is a wrong `viewLifecycleOwner` being used?
2. **ViewModel** — Is `viewModelScope.launch` used? Is `Resource.Loading` emitted before the call? Is a stale `Job` not cancelled before a new one starts?
3. **Repository** — Does the method return `Resource.Success` / `Resource.Error` correctly? Is there a missing `try/catch`? Is Room accessed on `Dispatchers.IO`?
4. **Data sources** — Firestore (`FirestoreDataSource`): listener leaks, missing snapshots? Room DAO: wrong query, missing entity field? Cloudinary: upload callback not on main thread?
5. **Data models** — Is a `@TypeConverter` missing or broken in `Converters.kt`? Is a Firestore document field name mismatched?
6. **Navigation** — Is a Safe Args argument missing or the wrong type? Is `popBackStack` called on a fragment that was never added?

### 1.3 Identify Root Cause

State the single root cause clearly:
> *"The bug is in `[File.kt]` at `[function/line]` because `[reason]`."*

Search for related usages (`Grep` / `SemanticSearch`) before concluding — the same pattern may repeat elsewhere.

---

## Phase 2 — Impact & Ripple-Effect Plan

**Do not write any fix code yet.** Produce a written plan and present it to the user.

### Plan format

```
## Bug Fix Plan — [Short title]

### Root cause
[One sentence]

### Files to change
| File | Change summary | Risk |
|------|----------------|------|
| `path/to/File.kt` | [What changes] | Low / Medium / High |

### Ripple-effect analysis
- **[File/feature A]**: [How this change affects it]
- **[File/feature B]**: [How this change affects it]
- No changes needed in: [list files explicitly checked and ruled out]

### Coding-standards compliance
- [ ] Layer boundaries respected (no Fragment → Repo direct call)
- [ ] `Resource<T>` wrapper used for async results
- [ ] View Binding null-safety pattern preserved
- [ ] `viewModelScope` / `Dispatchers.IO` correct
- [ ] No `!!` outside binding getter
- [ ] TAG constant present for any new log calls
- [ ] No magic numbers — constants declared

### Proposed changes (pseudocode / description)
[Describe what will change in plain language or pseudocode — no real code yet]

### Impact on other features
[Describe any side effects on Home, Profile, Search, Create, Notifications, Comments, Auth flows]
```

Present this plan and **wait for user confirmation** before proceeding to Phase 3.

---

## Phase 3 — Implementation

Proceed only after the user approves the plan.

1. **Make the minimal change** — touch only the files listed in the plan.
2. **Follow all coding standards** (see `.cursor/rules/coding-standards.mdc`).
3. **After each file edit** run `ReadLints` on that file and fix any introduced errors immediately.
4. **Check ripple files** — open every file listed in the ripple-effect analysis and verify nothing broke.
5. **Update the plan checklist** as items are completed.

### Common fix patterns for this project

**Missing `Resource.Error` catch in Repository:**
```kotlin
suspend fun getData(): Resource<T> = try {
    Resource.Success(remote.fetch())
} catch (e: Exception) {
    Resource.Error(e.localizedMessage ?: "Unknown error")
}
```

**Stale coroutine Job not cancelled:**
```kotlin
private var fetchJob: Job? = null
fun load() {
    fetchJob?.cancel()
    fetchJob = viewModelScope.launch { ... }
}
```

**Null binding access after `onDestroyView`:**
```kotlin
private var _binding: FragmentXBinding? = null
private val binding get() = _binding!!
override fun onDestroyView() { super.onDestroyView(); _binding = null }
```

**Firestore listener leak:**
```kotlin
private var listener: ListenerRegistration? = null
fun startListening() { listener = firestore.collection(...).addSnapshotListener { ... } }
fun stopListening() { listener?.remove(); listener = null }
```

---

## Phase 4 — Verification Summary

After all edits are complete, produce a short summary:

```
## Fix Summary — [Short title]

### What was changed
- [File]: [one-line description]

### What was ruled out / not changed
- [File]: checked, no changes required

### Residual risks
- [Any known edge cases not addressed and why]
```

---

## Ripple-Effect Hotspots (project-specific)

When changing these files, always check the listed dependants:

| Changed file | Must also check |
|---|---|
| `data/model/Post.kt` | `PostRepository`, `FirestoreDataSource`, `HomeFragment`, `ProfileFragment`, `CreatePostFragment`, Room entity + DAO, `Converters.kt` |
| `data/model/User.kt` | `AuthRepository`, `UserRepository`, `FirestoreDataSource`, `ProfileFragment`, `EditProfileFragment`, `SettingsFragment` |
| `data/local/AppDatabase.kt` | All DAOs, all entities, migration version number in build |
| `utils/Resource.kt` | Every ViewModel (`_post.value = Resource.Loading()` pattern), every Fragment `when (result)` block |
| `utils/Constants.kt` | Any file that references the changed constant |
| `nav_main.xml` | Every `findNavController().navigate(...)` call in `ui/main/` |
| `nav_auth.xml` | Every `findNavController().navigate(...)` call in `ui/auth/` |
| `FirestoreDataSource.kt` | All four repositories |
| `AuthRepository.kt` | `AuthViewModel`, `AuthActivity`, login/register fragments |
