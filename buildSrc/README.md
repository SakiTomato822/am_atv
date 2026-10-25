# MusicKit SDK compatibility

The build supports the official MusicKit Android playback SDK 1.1.1. It validates
the supplied AAR and applies a narrowly scoped 64-bit catalog-ID compatibility
adjustment during compilation. The original SDK file remains unchanged and is
not included in source control.

After downloading the SDK, compatibility tests can be run with:

```sh
./gradlew -p buildSrc test --tests dev.amatv.build.MusicKitSongIdPatchTest
```
