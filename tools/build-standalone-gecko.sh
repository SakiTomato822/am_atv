#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"
gecko_abi=${AMATV_GECKO_ABI:-}
case "$gecko_abi" in
    "") apk_suffix="" ;;
    armeabi-v7a) apk_suffix="-arm32" ;;
    arm64-v8a) apk_suffix="-arm64" ;;
    *) printf '%s\n' "AMATV_GECKO_ABI must be armeabi-v7a or arm64-v8a" >&2; exit 1 ;;
esac
if [ -n "$gecko_abi" ]; then
    ./gradlew :app:assembleStandaloneGecko "-Pamatv.geckoAbi=$gecko_abi" "$@"
else
    ./gradlew :app:assembleStandaloneGecko "$@"
fi
mkdir -p dist
version_name=$(sed -n 's/^[[:space:]]*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts)
test -n "$version_name"
cp app/build/outputs/apk/standaloneGecko/app-standaloneGecko.apk "dist/AMATV-$version_name-gecko$apk_suffix.apk"
printf '%s\n' "Shareable APK: $project_dir/dist/AMATV-$version_name-gecko$apk_suffix.apk"
