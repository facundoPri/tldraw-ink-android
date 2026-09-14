# Releasing Ink Share

## Development prerelease

Version 0.3.1 is published as a GitHub prerelease with `ink-share-0.3.1-dev.apk`, `SHA256SUMS`, and `build-info.json`. It is the debug APK built from the tagged main commit, with the SDK in development mode and no SDK key. Android/WebView debugging is enabled. The artifact is for development testing; its prerelease label does not change the SDK license terms.

To reproduce it, check out the release tag, install the locked web dependencies with `npm ci --prefix web`, and run `./scripts/build.sh debug`. The output is `android/app/build/outputs/apk/debug/app-debug.apk`. A locally generated debug certificate will differ from the published artifact's certificate; Android will not accept it as an update to that installation unless the signing identity matches.

For later development prereleases, increment the Android and web versions, push the source to main, build the debug APK, verify its signature, and attach it with a SHA-256 checksum and source commit metadata. Mark the GitHub release as a prerelease and identify the build as development mode in its name and notes. Keep signing keys private.

## Production release requirement

A production APK is pending a tldraw SDK license. Publishing source or a development artifact does not grant SDK production rights. Do not relabel the development-mode APK as a production build.

Request a suitable license through [tldraw](https://tldraw.dev/community/license). The packaged editor uses `https://appassets.androidplatform.net`; ensure the license covers this Android/WebView distribution and origin. The key is passed to `<Tldraw licenseKey={...}>` at build time. SDK attribution is preserved.

## Signing setup

Use JDK 21, Android SDK 37, and `npm ci --prefix web`. Create a release key once, outside source control:

```sh
mkdir -p .private
chmod 700 .private
keytool -genkeypair -keystore .private/ink-share.jks \
  -alias ink-share -keyalg RSA -keysize 4096 -validity 10000
chmod 600 .private/ink-share.jks
```

Let `keytool` prompt for passwords. Back up the key and its passwords securely; Android requires the same signing identity for future updates. The `.private/` directory is ignored by Git.

Provide the following environment variables using a local secret manager or a shell session. Never commit real values or paste signing passwords into issue reports:

| Variable | Value |
| --- | --- |
| `JAVA_HOME` | JDK 21 directory |
| `ANDROID_HOME` | Android SDK directory |
| `VITE_TLDRAW_LICENSE_KEY` | Active SDK key covering the packaged app |
| `SIGNING_STORE_FILE` | Absolute path to the release keystore |
| `SIGNING_STORE_PASSWORD` | Keystore password |
| `SIGNING_KEY_ALIAS` | `ink-share`, or your chosen alias |
| `SIGNING_KEY_PASSWORD` | Key password |

The SDK key is embedded in the frontend and is client-visible by design. The signing key and its passwords must remain private.

## Build from published main

1. Update `versionName` and `versionCode` in `android/app/build.gradle.kts`, plus the version in `web/package.json` and its lockfile. Write release notes in `CHANGELOG.md`.
2. Run the relevant checks from [TESTING.md](TESTING.md), commit, and push to `main`.
3. Run:

   ```sh
   node scripts/package-release.mjs
   ```

The packager requires a clean `main` matching `origin/main`, builds the frontend in production mode, builds the signed APK, verifies its signature, and writes these assets under the ignored `artifacts/release/`:

- `ink-share-<version>.apk`
- `SHA256SUMS`
- `build-info.json` containing the source commit

It does not publish automatically. Install and smoke-test the signed APK, including the SDK license on the real packaged origin, Share connection, drawing, reconnection, and Android update behavior. A successful build alone does not establish license validity. A debug installation has a different signature; use a separate test device or preserve its invitations before replacing it.

## Publish the release

Once licensing and device checks are complete, create a new production release targeting its tested main commit. Attach the signed production APK, checksum, and build metadata. Keep the existing development prerelease labeled as such rather than replacing its APK with a different signing identity.

Once published, the repository's Releases page provides the APK download. Retain the APK, checksum, source commit, and signing-key backup for each release.
