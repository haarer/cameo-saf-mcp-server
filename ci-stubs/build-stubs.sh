#!/usr/bin/env bash
# Builds the compile-only stub jars CI needs. See README.md.
#
# These stubs exist ONLY so `gradle compileJava` can run on a machine that
# has no MagicDraw installation. They are never packaged into the plugin and
# never loaded at runtime - at runtime MagicDraw supplies the real classes.
#
# Output goes to ci-libs/lib/ with names that match the `include` patterns in
# build.gradle, so no build script change is needed to use them.
set -euo pipefail

cd "$(dirname "$0")/.."
OUT=ci-libs/lib
BUILD=ci-stubs/build
rm -rf "$BUILD"
mkdir -p "$OUT"

# Each stub set becomes one jar. The jar name must match a glob in the
# `compileOnly fileTree(include: [...])` list in build.gradle:
#   core-*.jar                  -> core-ui-stubs.jar
#   jide-dock-*.jar              -> jide-dock-stubs.jar
#   lucene-core-*.jar            -> lucene-core-stubs.jar
#   lucene-analysis-common-*.jar -> lucene-analysis-common-stubs.jar
# JAVAC_RELEASE is set by ci/prepare-ci-libs.sh so the stubs are built for the
# same Java release the plugin is compiled against (17 for 2024x, 21 for 2026x).
RELEASE_FLAG=${JAVAC_RELEASE:+--release "$JAVAC_RELEASE"}

build() {
    local set=$1 jarbase=$2 src=$3 cp=${4:-}
    local classes="$BUILD/$set"
    mkdir -p "$classes"
    # shellcheck disable=SC2086
    javac -nowarn -proc:none $RELEASE_FLAG ${cp:+-cp "$cp"} -d "$classes" $(find "$src" -name '*.java')
    jar --create --file "$OUT/$jarbase.jar" -C "$classes" .
    printf '  %-32s %3d classes\n' "$jarbase.jar" "$(find "$classes" -name '*.class' | wc -l)"
}

echo "building stub jars -> $OUT"
# lucene-core references org.apache.lucene.analysis.Analyzer, so the
# analysis stubs are built first and passed as its compile classpath.
# md-ui references com.nomagic.magicdraw.core.Project, which the
# pre-existing core-stubs.jar already provides.
build lucene-analysis-common lucene-analysis-common-stubs ci-stubs/lucene-analysis-common
build lucene-core            lucene-core-stubs            ci-stubs/lucene-core "$BUILD/lucene-analysis-common"
build jide-dock              jide-dock-stubs              ci-stubs/jide-dock
build core-ui                core-ui-stubs                ci-stubs/md-ui "$OUT/core-stubs.jar"

rm -rf "$BUILD"
echo "done."
