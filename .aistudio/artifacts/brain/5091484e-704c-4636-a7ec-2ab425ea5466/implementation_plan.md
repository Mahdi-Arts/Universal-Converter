# Universal Converter: Media Processing, Storage, and Lifecycle Robustness

Comprehensive engineering blueprint to resolve remaining media engine lifecycle, scoped storage validation, honest UI controls, keyframe splitting, two-pass target size budgeting, and reproducible verification in Universal Converter.

### User Review & Critical Decisions

> [!IMPORTANT]
> The following architectural decisions were confirmed in Phase 1 interactive clarification:
> - **Legacy Android 7–9 Storage**: Prioritize app-specific storage paired with a secure AndroidX `FileProvider` (`content://` URI), strictly preventing any insecure `file://` exposure to external apps.
> - **Fast Split Keyframe Boundary**: Snap to the previous sync frame (`SEEK_TO_PREVIOUS_SYNC`) and report the actual start timestamp and offset to the UI for honest transparency.
> - **Target Size Multi-Pass Limit**: Enforce a strict 2-pass maximum to balance output accuracy with device thermals and battery endurance.
> - **Zero AI Online Dependency**: Keep title generation strictly offline/heuristic, remove active API keys from `.env.example`, and do not misattribute local suggestions to online Gemini services.

---

### 1. Overview & Core Concept

Universal Converter is a production-grade, offline-first Android media utility for mobile content creators and power users. It delivers three core pillars:
1. **Media Converter & Compressor**: Video and audio transcoding with target size budgeting, custom resolution preservation, and rotation handling.
2. **Social Media Video Splitter**: High-performance keyframe-aligned video segmentation with per-part status tracking, partial success handling, and retry capability.
3. **Thumbnail Studio**: Frame extraction, contrast/saturation adjustment, and offline title suggestion without leaking online AI promises.

This phase guarantees complete operation ownership, clean cancellation without orphaned temporary files or dangling `IS_PENDING` MediaStore records, strict validation before publication, and verifiable test coverage.

---

### 2. User Experience & Visual Design

#### Key User Flows
1. **Compression & Conversion Flow**:
   - User picks media via Android Photo Picker or system picker.
   - Screen displays real duration, dimensions (accounting for rotation metadata), codec, and audio channels.
   - User chooses preset or Target Size (MB). Target size validates against minimum feasible bitrate budget (audio + video).
   - Real-time progress displays current pass (e.g., "Pass 1 of 2"), bytes processed, and estimated time.
   - User can cancel at any moment from the active screen or Queue screen; cancellation immediately terminates the underlying codec/muxer/transformer and deletes temporary files.
   - Upon completion, the result displays real output metrics (actual size, codec, resolution). Target achieved status is marked `true` only if `actualSizeBytes <= targetSizeBytes`.
2. **Video Splitter Flow**:
   - User selects split mode (Platform preset, Fixed duration, Equal parts).
   - App computes segments. For short videos, Equal parts validates against duration limits and prevents invalid zero-length splits.
   - Fast Split runs keyframe copy. Each segment generates a stable `SplitSegmentResult`.
   - If segment 1 succeeds but segment 2 fails or is cancelled, previously finalized segments remain accessible in Library, while incomplete segments are scrubbed. The overall job state transitions to `PARTIAL_SUCCESS` or `CANCELLED` with access to finished parts.
3. **Queue & Library Management**:
   - Waiting/Queued jobs feature an active Cancel button.
   - Completed queue items have an active "Open" button launching a secure `content://` intent via `FileProvider` or MediaStore URI.
   - Library query filters strictly to Universal Converter items across Video, Audio, and Images.

#### Visual Identity & Layout
- **Style**: Polished Material 3 design with dynamic color scheme, full edge-to-edge support, and responsive Persian/English RTL typography.
- **Honesty in UI**:
  - Unconnected features (Crop, Speed adjustment, unimplemented audio formats like MP3/OGG/FLAC, WebM/MOV/GIF) are clearly disabled or marked with an "Experimental / Coming Soon" badge.
  - Stream copy mode disables audio bitrate tweaking to prevent user confusion.
  - No misleading "50% smaller guaranteed" or "lossless" marketing claims for lossy codecs.

---

### 3. Key Product Decisions & Trade-Offs

- **Decision 1: Operation-Bound Transformer Lifecycle**:
  - *Chosen Approach*: Bind Media3 `Transformer` execution to the calling coroutine lifecycle. Transformer initialization and cancellation occur on the required Android Main Looper. Cancel calls `transformer.cancel()`, clears listener callbacks, cancels progress poller jobs, and executes cleanup in a bounded `withContext(NonCancellable)` block.
  - *Why*: Eliminates unmanaged background scopes and prevents late callbacks from flipping cancelled states to completed.
- **Decision 2: Atomic Bounded MediaStore Publication (`OutputStorageManager`)**:
  - *Chosen Approach*: On Android 10+ (API 29+), insert records with `IS_PENDING = 1`. Copy temp data to content stream using an 8KB bounded buffer while checking coroutine cancellation every iteration. Validate destination size and media track headers via `MediaMetadataRetriever` before publishing with `IS_PENDING = 0`. If cancelled or failed, delete the pending record and temp file.
  - *Fallback on Android 7–9 (API 24–28)*: Store in app-specific external files (`context.getExternalFilesDir()`) under subdirectories `Movies/Universal Converter`, `Music/Universal Converter`, and `Pictures/Universal Converter`. Share files exclusively via AndroidX `FileProvider` (`content://com.example.universalconverter.fileprovider/...`).
- **Decision 3: Independent Split Segment Contract**:
  - *Chosen Approach*: Replace raw `List<OutputMediaFile>` with structured `SplitResult` containing individual `SplitSegmentResult` models.
  - *Why*: Enables granular retry of failed segments, handles partial success gracefully, and ensures user effort is never lost.
- **Decision 4: Two-Pass Target Size Budgeting**:
  - *Chosen Approach*: Pass 1 encodes based on theoretical bitrate formula accounting for audio budget and container overhead. If output exceeds target size, Pass 2 applies a scaled bitrate constraint (capped at minimum viable encoder limits). If Pass 2 is still slightly over or fails, keep the closest valid output with an explicit warning banner in the UI.

---

### 4. Technical Architecture & Data Strategy

```
┌────────────────────────────────────────────────────────────────────────┐
│                          Jetpack Compose UI                            │
│   (CompressScreen, SplitScreen, EditScreen, QueueScreen, LibraryScreen)│
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ StateFlow / Events
┌───────────────────────────────────▼────────────────────────────────────┐
│                             MediaViewModel                             │
│       - Job Cancellation & Lifecycle Management                        │
│       - Queue State (PENDING, RUNNING, COMPLETED, PARTIAL, CANCELLED)  │
└───────────────────┬────────────────────────────────┬───────────────────┘
                    │                                │
┌───────────────────▼──────────────┐   ┌─────────────▼───────────────────┐
│           Media3Engine           │   │         MediaCodecEngine        │
│   - Transformer on MainLooper    │   │   - Keyframe Fast Demux/Mux     │
│   - Cancellation Propagation     │   │   - Segment Timestamp Math      │
│   - Progress Flow Ownership      │   │   - Partial Segment Recovery    │
└───────────────────┬──────────────┘   └─────────────┬───────────────────┘
                    │                                │
                    └───────────────┬────────────────┘
                                    │ Temp File Artifacts
┌───────────────────────────────────▼────────────────────────────────────┐
│                        OutputStorageManager                            │
│   - Android 10+: MediaStore + IS_PENDING Atomic Publishing             │
│   - Android 7-9: App-Specific Storage + FileProvider (content://)      │
│   - Header & Duration Validation (MediaMetadataRetriever)              │
│   - Safe Cleanup on Failure / Cancellation                             │
└────────────────────────────────────────────────────────────────────────┘
```

#### Core Data Contracts
```kotlin
data class SplitSegmentResult(
    val segmentId: String,
    val partNumber: Int,
    val requestedStartMs: Long,
    val requestedEndMs: Long,
    val actualStartMs: Long,
    val actualEndMs: Long,
    val status: SegmentStatus, // PENDING, PROCESSING, COMPLETED, FAILED, CANCELLED
    val outputFile: OutputMediaFile? = null,
    val errorMessage: String? = null
)

data class SplitJobResult(
    val segments: List<SplitSegmentResult>,
    val overallStatus: JobStatus // COMPLETED, PARTIAL_SUCCESS, FAILED, CANCELLED
)
```

---

### 5. Verification & Testing Strategy

- **Robolectric & Unit Tests**:
  - `Media3EngineTest`: Verify transformer cancellation on coroutine abort, ensuring progress poller terminates and temp files are cleaned up.
  - `OutputStorageManagerTest`: Verify `IS_PENDING` lifecycle, stream copy with cancellation, failed copy cleanup, and legacy Android FileProvider path resolution.
  - `MediaCodecEngineTest`: Verify segment calculation, keyframe sync boundary clamping, and non-empty valid duration validation.
  - `TargetSizeCalculatorTest`: Test two-pass estimation, minimum feasible size rejection, and audio-less budget calculations.
  - `ViewModelJobTest`: Test queue cancellation of waiting jobs, ensuring cancelled jobs cannot transition to completed via late events.
- **Verification Commands**:
  - `gradle :app:testDebugUnitTest --rerun-tasks`
  - `gradle :app:lintDebug`
  - `gradle :app:assembleDebug`
