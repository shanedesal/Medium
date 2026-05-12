---
name: add-feature
description: End-to-end workflow for adding features to the Medium Android app (MVVM + Clean layers, Kotlin, Firebase/Room). Use when the user asks to add, build, implement, or create a new feature, screen, or capability in this project.
disable-model-invocation: true
---

# Add Feature

## Pre-Flight: Always Read Rules First

Before writing any code, read and apply the project coding standards:

```
.cursor/rules/coding-standards.mdc
```

All code produced must comply — zero exceptions.

---

## Feature Addition Checklist

Copy and track:

```
- [ ] 1. Analyse scope & ripple effects
- [ ] 2. Plan layer structure
- [ ] 3. Implement Data layer
- [ ] 4. Implement ViewModel
- [ ] 5. Implement UI (Fragment / Adapter)
- [ ] 6. Wire navigation
- [ ] 7. Ripple-effect sweep
- [ ] 8. Optimisation pass
- [ ] 9. Lint pass
```

---

## Step 1 — Analyse Scope & Ripple Effects

Before touching any file, answer:

| Question | Why it matters |
|---|---|
| Which existing repositories / DAOs does this touch? | Prevents duplicate data-fetch logic |
| Which ViewModels share related state? | Avoids stale LiveData across screens |
| Does the feature write to Firestore **and** Room? | Ensures cache invalidation is handled |
| Does it affect the notification pipeline (`NotificationRepository`, `NotificationDao`)? | WorkManager jobs may need updating |
| Does it affect the post feed (`PostRepository`, `PostAdapter`, `HomeViewModel`)? | Pagination / DiffUtil callbacks may need updating |
| Does it add a new bottom sheet or dialog? | Check `CommentsBottomSheet` pattern for consistency |
| Does it add media (image / video)? | Cloudinary upload + ExoPlayer playback paths must be reviewed |

Identify every file that **will change** before writing the first line of code.

---

## Step 2 — Plan Layer Structure

Map the feature to the three layers. Confirm each piece belongs in exactly one layer:

```
Data layer
  data/model/         → new domain model (if required)
  data/local/entity/  → new Room entity (if persisted locally)
  data/local/dao/     → new DAO (if new entity) or extended existing DAO
  data/remote/        → new method on FirestoreDataSource / CloudinaryDataSource
  data/repository/    → new repository or extended existing one

ViewModel layer
  ui/.../FeatureViewModel.kt
  ui/.../FeatureViewModelFactory.kt

UI layer
  ui/.../FeatureFragment.kt
  ui/.../adapters/FeatureAdapter.kt  (if list)
  res/layout/fragment_feature.xml
  res/navigation/nav_main.xml        (add destination + action)
```

---

## Step 3 — Implement Data Layer

### Domain model
- Add to `data/model/`; use plain Kotlin `data class` with no Android imports.

### Room entity & DAO
- Annotate with `@Entity`, define `@PrimaryKey`.
- Add `@Dao` interface; prefer `Flow<List<T>>` return types for observable queries.
- Register entity in `AppDatabase` and increment `version`; provide a `Migration`.

### Remote data source
- Add a `suspend` function to `FirestoreDataSource` or `CloudinaryDataSource`.
- Map Firestore documents → domain model inside the data source.

### Repository
- Return `Resource<T>` from every `suspend` function.
- Decide cache strategy: network-first vs cache-first.
- Use `Dispatchers.IO` for all I/O; never dispatch from the Fragment.

```kotlin
// ✅ Pattern
suspend fun getFeatureData(id: String): Resource<FeatureModel> = try {
    val remote = dataSource.fetchFeature(id)
    dao.insert(remote.toEntity())
    Resource.Success(remote)
} catch (e: Exception) {
    val cached = dao.get(id)?.toDomain()
    if (cached != null) Resource.Success(cached)
    else Resource.Error(e.localizedMessage ?: "Unknown error")
}
```

---

## Step 4 — Implement ViewModel

- Extend `ViewModel` (or `AndroidViewModel` only when `Context` is unavoidable).
- Expose state as `private val _state = MutableLiveData<Resource<T>>()` / `val state: LiveData<Resource<T>> = _state`.
- Launch coroutines from `viewModelScope`; cancel previous jobs where applicable.
- Instantiate via a `FeatureViewModelFactory`; pass the repository as a constructor param.

```kotlin
class FeatureViewModel(private val repo: FeatureRepository) : ViewModel() {
    private val _state = MutableLiveData<Resource<FeatureModel>>()
    val state: LiveData<Resource<FeatureModel>> = _state

    private var job: Job? = null

    fun load(id: String) {
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = Resource.Loading()
            _state.value = repo.getFeatureData(id)
        }
    }
}
```

---

## Step 5 — Implement UI

- Fragment follows the nullable binding pattern — **no exceptions**.
- Observe LiveData with `viewLifecycleOwner`; never use the Fragment itself as owner.
- Handle all three `Resource` states (`Loading`, `Success`, `Error`) — show shimmer/progress on loading, show error snackbar/toast on failure.
- Use Safe Args for navigation arguments; avoid raw `Bundle`.

```kotlin
vm.state.observe(viewLifecycleOwner) { resource ->
    when (resource) {
        is Resource.Loading -> showLoading(true)
        is Resource.Success -> { showLoading(false); render(resource.data) }
        is Resource.Error   -> { showLoading(false); showError(resource.message) }
    }
}
```

- RecyclerView adapters extend `ListAdapter<T, VH>` with `DiffUtil.ItemCallback`.
- All click listeners set in `onViewCreated`, cleared in `onDestroyView` if retaining refs.

---

## Step 6 — Wire Navigation

- Add a `<fragment>` destination to `res/navigation/nav_main.xml`.
- Define `<action>` elements with Safe Args `<argument>` tags for any typed params.
- Navigate via `findNavController().navigate(FeatureFragmentDirections.actionX())`.
- Set `popUpTo` / `popUpToInclusive` correctly to avoid back-stack buildup.

---

## Step 7 — Ripple-Effect Sweep

After implementation, re-visit every file identified in Step 1:

- **Shared ViewModels** (`HomeViewModel`, `ProfileViewModel`, etc.) — does new data invalidate their cached state? If yes, add a `refresh()` trigger or use `SharedFlow` to broadcast.
- **Adapters** — if a domain model changed, update `DiffUtil.ItemCallback` predicates.
- **Firestore security rules** — does the new collection/document need read/write rules?
- **WorkManager** — if background sync is involved, register the new `Worker` in `ConnectApplication`.
- **`AppDatabase` version** — confirm migration is registered; run a cold-start test mentally.
- **Constants** — add any new Firestore collection names, field keys, or limits to `utils/Constants.kt`.

---

## Step 8 — Optimisation Pass

Review each new or modified file for:

| Area | Check |
|---|---|
| **Coroutines** | No `runBlocking` on main thread; `Dispatchers.IO` for I/O |
| **Room queries** | Indexed columns used in `WHERE`; no `SELECT *` in high-frequency queries |
| **Firestore** | Queries use `limit()`; pagination via `startAfter()` for lists |
| **Images** | Glide loads use `.diskCacheStrategy(DiskCacheStrategy.ALL)` and appropriate `.override()` size |
| **RecyclerView** | `setHasFixedSize(true)` where applicable; no layout inflation in `onBindViewHolder` |
| **LiveData / Flow** | No duplicate observers; `distinctUntilChanged()` applied where state is noisy |
| **Memory** | No static references to `Context`, `Fragment`, or `View` |

---

## Step 9 — Lint Pass

Run `ReadLints` on every file you created or modified. Fix all `error`-level issues. For any `@SuppressLint` usage, add an inline comment explaining why.

Naming quick-reference:

| Kind | Rule |
|---|---|
| Layout files | `fragment_*`, `item_*`, `activity_*` |
| LiveData backing field | `_name` (private) + `name` (public) |
| Constants | `SCREAMING_SNAKE_CASE` in `companion object` or `Constants.kt` |
| Icons / drawables | `ic_*`, `bg_*`, `img_*` |
