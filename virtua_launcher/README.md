# Virtua Launcher

Mobile game launcher shell for **Feature Set 1 / 1b**:

- **VPR** — virtual sub-pixel lattice upscale
- **CompactFlow** — motion + confidence (real block-matching now; see below)
- **FSRCNN** — spatial prior, converted and bundled automatically by CI
- **Virtual HDR+** — local tone / bloom
- **Neural Depth** — real MiDaS v2.1-small inference, fetched and bundled automatically by CI
- **Virtual Ray-Cast** — god rays + light SSR

## No model binaries live in this repo — CI fetches/builds them

Both `.tflite` models are produced by `.github/workflows/android-ci.yml`
at build time, not committed to git:

- `midas_small.tflite` (66MB) — **downloaded** straight from the official
  `isl-org/MiDaS` release.
- `fsrcnn_div2k.tflite` — **converted** from `suxrobGM/fsrcnn`'s checkpoint.

Why: the MiDaS file alone is 66MB, which clears GitHub's 25MB web-upload
limit and is almost certainly why Spck's push was failing too (git itself
handles 66MB fine via a real `git push`, but a mobile client doing that over
a phone connection is a different story). Rather than find a way to shrink
or chunk the transfer, CI just gets the file itself — nobody has to push a
66MB binary from a phone, ever. Same reasoning already applied to FSRCNN.

`app/src/main/assets/models/` is empty in this zip except for a `.gitkeep`
explaining this. Push what's here as-is; CI fills the rest in on every run.

## Real status per component (checked / built Oct 2026)

| Component | Status | What's actually there |
|---|---|---|
| **MediaProjection capture** | Real, working | `capture/CaptureService.kt` — real `MediaProjectionManager` → `VirtualDisplay` → `ImageReader` chain, foreground service, exposes latest/previous frame as `Bitmap`. `capture/CaptureManager.kt` handles the permission request via the Activity Result API. Tap **Start Capture** in the HUD. Captures the composited screen output (the portable approach — true pre-compositor capture needs an OEM hook, not available off-OEM). |
| **MiDaS (depth)** | Real, fetched by CI | `ml/DepthEstimator.kt` runs real inference against the official isl-org/MiDaS v2.1-small release. CI's "Fetch MiDaS model" step downloads it directly from `github.com/isl-org/MiDaS/releases/download/v2_1/model_opt.tflite` and verifies it against a known-good SHA-256 (`93d871071edff1218973ce25ee27ce95ccd20450c70a55e1b89efa3f5a772cdd` — computed from the real file, not guessed) before the build proceeds. Input/output tensor shapes `[1,256,256,3]`/`[1,256,256,1]` confirmed by parsing the model directly. Wired into Apply-pipeline when "depth" is enabled. |
| **CompactFlow (motion)** | Real substitute, not CompactFlowNet | CompactFlowNet (arXiv 2412.13273, Samsung/Picsart, Dec 2024) still has no public code or weights anywhere — confirmed again, nothing to stream. `core/MotionEstimator.kt` + a new native `nativeComputeMotionField` in `virtua_core.cpp` run real SAD block-matching motion estimation instead — CPU-only, far less accurate than a trained net, but a genuine working motion field today. Swap for a trained CompactFlow once weights exist. |
| **FSRCNN** | Real, converted by CI | The workflow clones `suxrobGM/fsrcnn`, exports its real pretrained checkpoint to ONNX (`tools/convert_fsrcnn_to_tflite.py`), converts ONNX→TFLite with `onnx2tf`, and copies the result to `assets/models/fsrcnn_div2k.tflite` **before** the Gradle build. `ml/SuperResEstimator.kt` loads it (bundled asset first, then a streamed install at `files/features/fsrcnn/`) and handles the YCbCr conversion FSRCNN needs (Y-channel only net). |

## Build

```bash
./gradlew :app:assembleDebug
```

Builds locally too, but `assets/models/` is empty without the CI steps, so
depth/FSRCNN report "model not available" in the HUD instead of crashing.
Capture and motion work fully offline either way.

The GitHub Actions workflow runs two model steps before the Gradle build —
"Fetch MiDaS model" (a plain download + checksum check, a few seconds) and
"Convert FSRCNN checkpoint to TFLite" (pip installs + a tiny conversion, a
few minutes, cached via `actions/cache` on `~/.cache/pip`).

## Stream delivery (no app rebuild per feature)

1. Host `update_manifest.json` + per-ABI `.so`/`.tflite` files.
2. App downloads manifest → SHA-256 check → install under `files/features/<id>/`.
3. `replaces: ["old_id"]` auto-deletes old modules.
4. Version compare skips up-to-date modules.

See `app/src/main/assets/features/update_manifest.sample.json`.

## ABI / API

- `minSdk 21` … `compileSdk/targetSdk 35` (matches AGP 8.7.2's tested range — compileSdk 36 needs AGP 9.0+)
- `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`

## Native

- `libvirtua_core.so` — JNI bridge + pipeline stub + real block-matching motion estimator (`nativeComputeMotionField`).
- MiDaS and FSRCNN both run through `org.tensorflow:tensorflow-lite` (Java API), not through the native bridge — simpler, no custom JNI needed for either.
- `.tflite` assets are excluded from AAPT compression (`androidResources.noCompress += "tflite"`) — required for `AssetManager.openFd()`/mmap to work; without it the app throws at runtime trying to load either model.

## HUD

- Portrait: vertical feature list + Start Capture + Apply / Check updates
- Landscape (`layout-land`): left rail (profile / cool-down / perf / Start Capture) + feature list

## License

Project scaffold for integration; third-party model weights retain their own licenses (FSRCNN, CompactFlowNet, MiDaS, etc.). `midas_small.tflite` is isl-org/MiDaS's official v2.1-small release; `fsrcnn_div2k.tflite` derives from suxrobGM/fsrcnn's checkpoint — check both repos' license terms before shipping.
