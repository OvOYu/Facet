#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/classes
javac --release 8 -encoding UTF-8 -d build/classes \
  src/FacetCodec.java src/FacetTool.java src/Correctness.java
