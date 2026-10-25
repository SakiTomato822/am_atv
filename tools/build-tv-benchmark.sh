#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"
./gradlew :app:assembleBenchmark :benchmark:assembleBenchmark "$@"
