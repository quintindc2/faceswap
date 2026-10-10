# Offline Face Swap (Android)

Pick a face image and a video from your phone, choose a start/end point, and the app replaces
the face in the video with the face from the image. Everything runs on-device; the app has no
INTERNET permission.

* Face detection: bundled ML Kit Face Mesh (468 landmarks), works offline.
* Swap: the image face is split into triangles, warped onto the video face, colour-matched and
  feathered into the frame.
* Expression slider: 0% = the swapped face copies the video's expression, 100% = it keeps the
  expression from your image, values in between mix the two.
* Start/end: drag the range slider, or press play and use "Start = now" / "End = now".
* Output: H.264 MP4 (max 720p side, 24 fps) with the original audio, saved to `Movies/FaceSwap`.
* Requires Android 10 (API 29) or newer.

## Build the APK online (GitHub Actions)

1. Create a new GitHub repository and upload everything in this folder (keep the
   `.github/workflows/build-apk.yml` file).
2. Open the repo's **Actions** tab. The *Build APK* workflow runs on every push
   (or press **Run workflow**).
3. When it finishes, open the run and download the **OfflineFaceSwap-debug-apk** artifact.
   Unzip it and install `app-debug.apk` on your phone (allow "install unknown apps").

## Build locally

Open the folder in Android Studio and run, or: `gradle assembleDebug`
(JDK 17, Android SDK 34). The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

## Notes / limits

* Works best with a clear, front-facing face in both image and video. Extreme side angles,
  masks or heavy occlusion will degrade or skip the swap for those frames.
* Processing is on the CPU and takes roughly 0.2–0.6 s per frame, so keep clips short and keep
  the app open (the screen stays on while processing).
* Only use faces you have permission to use.
