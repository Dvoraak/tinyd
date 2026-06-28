# Green Compressor

An Android video compressor focused on **bulk phone archival**. Fork of
[JoshAtticus/Compressor](https://github.com/JoshAtticus/Compressor) (MIT) — see
[`NOTICE.md`](./NOTICE.md) and [`LICENSE`](./LICENSE) for full attribution.

Same Media3/MediaCodec hardware pipeline as upstream, with three additions:

1. **Metadata preservation.** `DATE_TAKEN`, GPS (`udta/©xyz`), rotation, and
   HDR are carried from the source video to the compressed copy. Google
   Photos shows the correct date and location on the archive.
2. **Batch compression.** Pick up to 30 videos in one go, set a preset once,
   zero per-file interaction. Per-item progress + a batch summary screen.
3. **Optional in-place overwrite.** Toggle "Replace originals"; after every
   item is verified-saved, a single Android system dialog confirms deletion
   of all sources at once.

`applicationId` is `io.github.dvoraak.greencompressor` — distinct from
upstream's `compress.joshattic.us`, so the two apps install side-by-side
without signature conflicts.

## What changed vs. upstream

| Area | Upstream Compressor | Green Compressor |
|------|---------------------|------------------|
| Video picker | `PickVisualMedia` (1 file) | `PickMultipleVisualMedia` (≤30) |
| `DATE_TAKEN` | not written | written to MediaStore + patched into mvhd |
| GPS | stripped | read with `ACCESS_MEDIA_LOCATION`, written to `udta/©xyz` |
| Save flow | manual per file | auto-save + auto-advance in batch mode |
| Originals | untouched | optional `MediaStore.createDeleteRequest` at batch end |
| `applicationId` | `compress.joshattic.us` | `io.github.dvoraak.greencompressor` |
| Launcher icon | upstream colors | green (#2E7D32) |
| `targetSdk` | 37 (preview) | 36 (stable) |

The metadata patcher lives in
`app/src/main/java/io/github/dvoraak/greencompressor/Mp4MetadataPatcher.kt` —
a self-contained ISO BMFF box editor that appends `©xyz` to `moov/udta` and
rewrites `mvhd`/`tkhd` `creation_time`. No new dependencies.

## Install via Obtainium

1. Open Obtainium → **+** → paste the repo URL:

   ```
   https://github.com/Dvoraak/green-compressor
   ```

2. Obtainium auto-detects the GitHub source and picks up the latest
   release. Tap **Add**. First install will prompt the "unknown source"
   dialog — allow once.
3. Grant *Photos and videos / Allow access to all photos* on first launch.
   That's what `ACCESS_MEDIA_LOCATION` needs to read un-redacted GPS.

If you previously added an Obtainium entry for this repo while v1.6.0 was
still using upstream's `applicationId`, **remove that entry first** and re-add
the URL. Obtainium now sees a new `applicationId`
(`io.github.dvoraak.greencompressor`) and installs Green Compressor cleanly
alongside any existing upstream Compressor.

## Install via USB-C + adb (faster for one-off testing)

```bash
export PATH=/opt/homebrew/share/android-commandlinetools/platform-tools:$PATH
adb install GreenCompressor-v1.0.0.apk
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
release-mode APK signed with the debug keystore (stable across builds so
Android accepts upgrades), and attaches it to a GitHub Release as
`GreenCompressor-v<TAG>.apk`. To cut a new version:

```bash
# Bump versionCode and versionName in app/build.gradle.kts
git commit -am "Release v1.0.1"
git tag v1.0.1 && git push origin v1.0.1
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
  Android 10 the request is silently no-op'd — fine for the Pixel target.

## License & credits

MIT, same as upstream. The original `Compressor` is © Josh Wardle
(JoshAtticus) and contributors; this fork preserves both the `LICENSE`
file and the in-app attribution in the info dialog. See
[`NOTICE.md`](./NOTICE.md).
