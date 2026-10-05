# GIF Maker v1.0

Offline Android app that turns **any part of a video into an animated GIF**. Pick a video, choose the start and end (or just a length), set the frame rate and size, then tap **Create GIF**. The GIF is a standard GIF89a file that opens in Gallery/Photos, chat apps and browsers.

- Package: `com.vanu.gifmaker` · minSdk 26 (Android 8.0) / targetSdk 34
- Kotlin + Jetpack Compose, hardware decoding through `MediaCodec`, pure‑Kotlin GIF encoder: **no native code of its own, no ML models, about 1 MB**
- Fully offline: no network permission, no account

## Download

**[GifMaker-1.0-arm64.apk](https://github.com/vanukrishnans-source/gif-maker/releases/download/v1.0/GifMaker-1.0-arm64.apk)** (see the [v1.0 release](https://github.com/vanukrishnans-source/gif-maker/releases/tag/v1.0) for the size and SHA‑256)

This one universal file installs on any Android 8.0+ phone (arm64, 32‑bit ARM and x86). The only native code is a 10 KB AndroidX helper that Compose uses.

## Features

| | |
|---|---|
| **Trim** | Range slider for start/end, with **−/+** buttons that move each end by 0.1 s. The preview shows the exact frame at the handle you moved, and ▶ plays the selected range on a loop |
| **Length** | Quick‑length chips (1 s, 2 s, 3 s, 5 s, 10 s, 15 s, Whole video) start from the current start point. A GIF can be 0.2 s to 30 s long |
| **Frame rate** | 5, 8, 10 (default), 12, 15, 20 or 25 fps. Frames are sampled on an even 1/fps grid, and GIF delays are rounded cumulatively so playback timing doesn't drift |
| **Size** | Longest side of 240, 320, 480 (default), 640 or 800 px, keeping the aspect ratio. Portrait and rotated phone videos come out the right way up |
| **Speed** | 0.5×, 1×, 1.5× or 2× playback |
| **Quality** | *Best*, *Balanced* (default) or *Smaller file*, plus optional dithering for smooth skies and gradients, and *Loop forever* on/off |
| **Save / Share / Open** | **Save** writes to `Pictures/GifMaker/` through MediaStore (no storage permission needed on Android 10+). **Share** sends the GIF as `image/gif` to WhatsApp, Telegram, Gmail and other apps. **Open** shows the saved file in your gallery |
| **Share‑to** | Share a video from Gallery or Files to *GIF Maker* to open it directly |

## How to use

1. Install the APK. If Android asks, allow installs from your browser or file manager.
2. Open **GIF Maker** and tap **Pick a video**, or share a video to the app.
3. Drag the two handles to set the start and end. Fine‑tune with **−/+**, or tap a length chip such as **3s**. Tap ▶ to watch the selection.
4. Choose **Frame rate**, **Size**, **Speed** and **Quality**. The card shows the frame count and the output size in pixels.
5. Tap **Create GIF**. A progress bar counts the frames, and you can tap **Cancel** at any time.
6. The finished GIF plays in the app. Tap **Save**, which puts it in Gallery under *Pictures/GifMaker*, then **Share** or **Open** if you want.

Rough guide to file size: 5 s at 480 px and 10 fps is about 1–2 MB. Larger sizes and higher fps grow quickly. Use *Smaller file* or 320 px for chat apps.

## How the GIF encoder works (and why the GIFs open in Gallery)

`engine/GifEncoder.kt` is a streaming GIF89a writer that has no Android dependencies and is unit‑tested on the JVM.

- **LZW Clear code at the current code width.** When the 4096‑entry dictionary fills up, the Clear code is written at the current (12‑bit) width, and only *then* is the width reset. The GIF Face Swap 1.0 encoder reset the width first, which corrupted every frame whose dictionary filled up, and Gallery showed a broken image. That bug was fixed in GIF Face Swap 1.0.1, and the fix is carried over here. A unit test round‑trips every code size from 2 to 8 bits, up to 200,000 pixels, through an independent reference decoder and checks that the output is bit‑exact.
- **Global Color Table on frame 0.** Some gallery thumbnailers show a broken icon for GIFs that only have Local Color Tables.
- **Per‑frame palettes.** Each frame gets its own palette, built by a variance‑based median cut on a 15‑bit histogram that uses exact colour means. Floyd–Steinberg dithering is optional.
- **Changed‑rectangle frames.** After the first frame, each frame only stores the area that changed. Unchanged pixels are transparent over the previous frame (disposal = do not dispose). Static backgrounds stay steady and files get smaller.
- **Checked before saving.** The app parses every block structurally, decodes the first frame with Android's own decoder, and checks the size and `GIF89a` header again after the MediaStore copy. If any check fails, the placeholder entry is deleted so a broken file never reaches Gallery.

## Fragmented MP4s

Many web downloads and some editors produce *fragmented* MP4 files. When a timecode or data track sits in its own fragment, Android's `MediaExtractor` stops reading at that fragment, so a 16.4 s clip shows up as 10.4 s. GIF Maker detects this case and makes a cleaned copy in its cache. The copy contains the same bytes, minus the fragments that hold no video, so you can pick from the whole video.

## Build

```bash
./gradlew :app:testDebugUnitTest :app:assembleRelease
```

Signing is read from `keystore.properties`, which is not committed. See `keystore.properties.example`.
