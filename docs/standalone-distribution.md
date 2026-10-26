# APK and source distribution

```sh
tools/build-standalone.sh
tools/build-standalone-gecko.sh
tools/export-source.sh
```

Outputs are written to `dist/`. The source archive contains the committed source
tree and licenses, without Git history, credentials, Apple binaries or local data.

The standalone APK contains a signed, expiring developer credential and uses no
token endpoint. Each recipient signs in on their own TV. Rebuild before the
credential expires; see [setup](musickit-setup.md).

Keep the APK signing key so updates can preserve existing installations. The
local standalone configuration uses the existing development signing identity;
configure your own release signing identity for your distribution.

`AMATV-<version>.apk` uses the system WebView for sign-in.
`AMATV-<version>-gecko.apk` bundles GeckoView for sign-in only, for ARM32 and
ARM64 TVs. The Gecko browser runs in a separate login process; music playback
continues to use the native MusicKit SDK. Both APKs have the same application ID
and signing identity, so installing one replaces the other and preserves app data.

Gecko native libraries are compressed in the APK and extracted for the device's
architecture during installation. To omit the other architecture entirely:

```sh
AMATV_GECKO_ABI=armeabi-v7a tools/build-standalone-gecko.sh # -gecko-arm32.apk
AMATV_GECKO_ABI=arm64-v8a tools/build-standalone-gecko.sh   # -gecko-arm64.apk
```

Both login screens keep the diagnostic console hidden by default. Press the
remote's MENU key to show or hide it. The panel does not take focus or intercept input.
Diagnostics are bounded in memory and redact credentials and URL parameters.
