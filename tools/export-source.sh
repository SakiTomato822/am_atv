#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"
version_name=$(sed -n 's/^[[:space:]]*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts)
test -n "$version_name"
git diff --quiet
git diff --cached --quiet
mkdir -p dist
archive="$project_dir/dist/AMATV-$version_name-source.zip"
git archive --format=zip --prefix=AMATV/ --output="$archive" HEAD
printf '%s\n' "Source archive: $archive"
