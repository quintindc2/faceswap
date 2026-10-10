# FaceSwap (offline Android app)

Pick a face image and a video from your phone, tap **Swap face**, and the app writes a new MP4
to `Movies/FaceSwap`. Fully offline: no INTERNET permission, and the face-detection model is
bundled inside the APK.

## Build
1. Open this folder in Android Studio (Koala or newer, JDK 17).
2. Let Gradle sync. Android Studio generates the Gradle wrapper if it is missing.
3. Run on a device with Android 10 (API 29) or newer, or use Build > Build APK(s).

## How it works
- `FaceSwapper`: ML Kit face contours on the source photo and on each frame; a least-squares
  similarity transform (scale, rotation, translation) aligns the photo's face to the frame's
  face; a feathered mask of the target face outline, plus per-channel colour matching,
  blends it in.
- `VideoProcessor`: reads frames at 15 fps (longest side capped at 720 px), swaps each one,
  encodes H.264 through a MediaCodec input surface, and copies the original audio track.

## Limitations (this is the basic version)
- The swap is a rigid paste: the borrowed face doesn't change expression, and it looks
  best with a front-facing photo and a mostly front-facing face in the video.
- Only the largest face per frame is swapped. Frames with no detected face pass through unchanged.
- Processing runs on the CPU, so expect roughly 0.1-0.5 s per frame depending on the phone.
- Output is 15 fps and at most 720 px on the long side.
- Audio is copied as-is; unusual audio codecs that MP4 can't hold are dropped.
- Untested on a device: it was written without an Android SDK to build against.

Only use faces you have permission to use.
