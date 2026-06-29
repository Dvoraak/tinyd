# Tinyd

An Android video compressor focused on **bulk phone archival**. Fork of
[JoshAtticus/Compressor](https://github.com/JoshAtticus/Compressor) (MIT) — see
[`NOTICE.md`](./NOTICE.md) and [`LICENSE`](./LICENSE) for full attribution.

Same Media3/MediaCodec hardware pipeline as upstream, plus:

1. **Metadata preservation.** `DATE_TAKEN`, GPS (`udta/©xyz`), rotation, and
   HDR are carried from the source video to the compressed copy. Google
   Photos shows the correct date and location on the archive.
2. **Batch compression.** Pick up to 30 videos in one go, set a preset once,
   zero per-file interaction. Per-item progress + a batch summary screen.
3. **Seamless replace-in-place.** Toggle "Replace originals" (on by default);
   the compressed copy is saved into the original's folder under the
   original's exact filename, and a single Android system dialog at the end
   confirms deletion of all sources at once.
4. **Foreground service** with progress notification + Cancel button, so
   batches run safely with the screen off / app backgrounded — no Doze
   suspension mid-encode.

`applicationId` is `io.github.dvoraak.tinyd` — distinct from upstream's
`compress.joshattic.us` (and from earlier `io.github.dvoraak.greencompressor`
1.x builds), so the apps install side-by-side without signature conflicts.

## What changed vs. upstream

| Area | Upstream Compressor | Tinyd |
|------|---------------------|-------|
| Video picker | `PickVisualMedia` (1 file) | `PickMultipleVisualMedia` (≤30) |
| Share intent | single | `ACTION_SEND` + `ACTION_SEND_MULTIPLE` |
| `DATE_TAKEN` | not written | written to MediaStore + patched into mvhd |
| GPS | stripped | read with `ACCESS_MEDIA_LOCATION`, written to `udta/©xyz` |
| Save flow | manual per file | auto-save + auto-advance in batch mode |
| Originals | untouched | seamless replace via `MediaStore.createDeleteRequest` |
| Background safety | none | foreground service (`mediaProcessing`) + cancel notif |
| Default preset | High | Low (archive workflow) |
| `applicationId` | `compress.joshattic.us` | `io.github.dvoraak.tinyd` |
| Launcher icon | upstream colors | green (#2E7D32) |
| `targetSdk` | 37 (preview) | 36 (stable) |

The metadata patcher lives in
`app/src/main/java/io/github/dvoraak/tinyd/Mp4MetadataPatcher.kt` —
a self-contained ISO BMFF box editor that appends `©xyz` to `moov/udta` and
rewrites `mvhd`/`tkhd` `creation_time`. No new dependencies.

## Install via Obtainium

1. Open Obtainium → **+** → paste the repo URL:

   ```
   https://github.com/Dvoraak/tinyd
   ```

2. Obtainium auto-detects the GitHub source and picks up the latest
   release. Tap **Add**. First install will prompt the "unknown source"
   dialog — allow once.
3. Grant *Photos and videos* + *Notifications* + media-location permissions
   on first launch. Without them, GPS preservation and the in-place delete
   dialog silently no-op.

If you had a 1.x GreenCompressor build installed via Obtainium, the
v2.0.0 Tinyd build is a **separate app** (new applicationId). Add it as a
new entry; uninstall the old GreenCompressor whenever you're ready.

## Install via USB-C + adb (faster for development)

```bash
export PATH=/opt/homebrew/share/android-commandlinetools/platform-tools:$PATH
adb install Tinyd-v2.0.0.apk
```

Requires USB debugging in Developer Options.

## Build locally

```bash
brew install openjdk@21 android-commandlinetools
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleRelease
```

APK lands at `app/build/outputs/apk/release/app-release.apk`.

## Release flow

The `.github/workflows/release.yml` action fires on every `v*` tag, builds a
release-mode APK signed with `app/release.keystore` (a committed keystore so
every build on every machine produces the same signature, and Android
accepts in-place upgrades), and attaches it to a GitHub Release as
`Tinyd-v<TAG>.apk`. To cut a new version:

```bash
# Bump versionCode and versionName in app/build.gradle.kts
git commit -am "Release v2.0.1"
git tag v2.0.1 && git push origin v2.0.1
```

## Known limitations

- **HDR pipeline on Pixel 10 H264/H265**: inherited from upstream — the
  workaround tone-maps HDR→SDR. To preserve 10-bit HDR, set the codec to AV1
  in the Video tab; the Pixel 10 special-case in
  `CompressorViewModel.startCompression` doesn't apply for AV1.
- **GPS write requires moov-after-mdat layout.** Media3 1.5.0 produces this
  by default. `Mp4MetadataPatcher` detects the inverse layout and degrades
  to date-only patching rather than corrupting mdat offsets.
- **In-place delete** uses `MediaStore.createDeleteRequest` (API 30+). On
  Android 10 the request silently no-ops — fine for the Pixel target.

## License & credits

MIT, same as upstream. The original `Compressor` is © Josh Wardle
(JoshAtticus) and contributors; this fork preserves both the `LICENSE`
file and the in-app attribution in the info dialog. See
[`NOTICE.md`](./NOTICE.md).
