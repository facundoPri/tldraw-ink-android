# Third-party notices

The repository's MIT license covers original application code and artwork. It does not replace the licenses of bundled dependencies.

- **tldraw SDK and related packages:** [tldraw license](https://github.com/tldraw/tldraw/blob/main/LICENSE.md). Default terms permit development use; production requires a valid trial, commercial, or hobby license. SDK notices and attribution remain intact. Dependencies are pinned in `web/package-lock.json`.
- **AndroidX / Jetpack Ink:** Apache License 2.0. Native versions are listed in `android/app/build.gradle.kts`.
- **Google ML Kit Digital Ink Recognition:** Google SDK and model terms apply; see https://developers.google.com/ml-kit/terms. The app downloads language models on demand and performs handwriting recognition on-device.
- **React, Vite, TypeScript and associated web dependencies:** retain their respective package licenses. Consult the installed packages and lockfile for exact versions and transitive dependencies.
- **Gradle wrapper:** Apache License 2.0; supplied to make the Android build reproducible.

This project is independent of tldraw and does not use the desktop application's license key or branding assets. Before distributing a binary, retain the notices required by its bundled dependencies and verify the SDK license covers the app's packaged origin.
