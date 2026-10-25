#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"
./gradlew :app:assembleStandalone "$@"
mkdir -p dist
version_name=$(sed -n 's/^[[:space:]]*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts)
test -n "$version_name"
cp app/build/outputs/apk/standalone/app-standalone.apk "dist/AMATV-$version_name.apk"
printf '%s\n' "Shareable APK: $project_dir/dist/AMATV-$version_name.apk"
