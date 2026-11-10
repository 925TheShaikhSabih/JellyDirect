# JellyDirect

A small Jellyfin client for Android phones, tablets and Android TV that always plays "direct" and decodes on the device with libVLC (like Kodi/JellyCon). The server never transcodes.

## How playback works
1. The app asks Jellyfin for the item's `PlaybackInfo` (no transcoding allowed).
2. **Direct mode (default):** the URL stored in your `.strm` file is handed straight to libVLC, so video bytes never touch your server.
3. If that link fails to open, it falls back to Jellyfin's raw `static=true` stream (still no transcoding). *Settings → Streaming* can force the server route.
4. Movies, episodes and Live TV all use the same path. Position, watched state and now-playing are reported back to Jellyfin.

## Build the APK (GitHub Actions)
1. Create a GitHub repo and push this folder to `main`.
2. Open the **Actions** tab → *Build APK* → wait for it to finish (or press *Run workflow*).
3. Download the `JellyDirect-apks` artifact. Install `app-universal-release.apk`, or the smaller one for your CPU (`arm64-v8a` for almost every phone and most TV boxes).

Or locally with Android Studio: open this folder and run *Build → Build APK(s)* (release is signed with the debug key so it installs directly).

## Notes
- Written without being able to compile in the authoring environment. If the first CI run fails, send me the error log and I'll fix it.
- Cleartext `http://` servers are allowed. Self-signed HTTPS certificates are not supported yet.
- Targets Jellyfin 10.9+.
