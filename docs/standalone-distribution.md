# APK and source distribution

```sh
tools/build-standalone.sh
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
