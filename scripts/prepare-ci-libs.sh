#!/usr/bin/env bash
set -euo pipefail

# prepare-ci-libs.sh
# Builds ci-libs/ with:
#   - Real Jackson jars (open source, from Maven Central)
#   - Real Groovy jar   (open source, from Maven Central)
#   - Stub jar for Cameo proprietary classes (Plugin, Application, GUILog)
#
# Usage:
#   ./scripts/prepare-ci-libs.sh
#
# Output: ci-libs/  (suitable for -PcameoHome=ci-libs)

CI_LIBS="$(cd "$(dirname "$0")/.." && pwd)/ci-libs"
STUBS_SRC=$(mktemp -d)
trap 'rm -rf "$STUBS_SRC"' EXIT

# Stubs release version — auto-detect from JDK on PATH, or override via env var
STUBS_RELEASE="${STUBS_RELEASE:-}"
if [ -z "$STUBS_RELEASE" ]; then
  STUBS_RELEASE=$(java -version 2>&1 | head -1 | sed -n 's/.*version "\(.*\)".*/\1/p' | cut -d. -f1)
fi

JACKSON_VERSION="2.19.1"
GROOVY_VERSION="5.0.0"

MAVEN_BASE="https://repo1.maven.org/maven2"

JACKSON_ARTIFACTS=(
  "com/fasterxml/jackson/core/jackson-core/${JACKSON_VERSION}/jackson-core-${JACKSON_VERSION}.jar"
  "com/fasterxml/jackson/core/jackson-databind/${JACKSON_VERSION}/jackson-databind-${JACKSON_VERSION}.jar"
  "com/fasterxml/jackson/core/jackson-annotations/${JACKSON_VERSION}/jackson-annotations-${JACKSON_VERSION}.jar"
)

GROOVY_ARTIFACTS=(
  "org/apache/groovy/groovy/${GROOVY_VERSION}/groovy-${GROOVY_VERSION}.jar"
)

echo "=== Preparing ci-libs/ ==="
rm -rf "$CI_LIBS"
mkdir -p "$CI_LIBS/lib"
mkdir -p "$CI_LIBS/plugins/com.nomagic.magicdraw.automaton/lib"

# -------------------------------------------------------
# 1. Download Jackson jars from Maven Central
# -------------------------------------------------------
echo "--- Downloading Jackson jars ---"
for artifact in "${JACKSON_ARTIFACTS[@]}"; do
  url="${MAVEN_BASE}/${artifact}"
  jar_name=$(basename "$artifact")
  if [ ! -f "$CI_LIBS/lib/$jar_name" ]; then
    echo "  Downloading $jar_name ..."
    curl -sfL "$url" -o "$CI_LIBS/lib/$jar_name" || {
      echo "ERROR: Failed to download $url" >&2
      exit 1
    }
  else
    echo "  Already cached: $jar_name"
  fi
done

# -------------------------------------------------------
# 2. Download Groovy jar from Maven Central
# -------------------------------------------------------
echo "--- Downloading Groovy jar ---"
for artifact in "${GROOVY_ARTIFACTS[@]}"; do
  url="${MAVEN_BASE}/${artifact}"
  jar_name=$(basename "$artifact")
  if [ ! -f "$CI_LIBS/plugins/com.nomagic.magicdraw.automaton/lib/$jar_name" ]; then
    echo "  Downloading $jar_name ..."
    curl -sfL "$url" -o "$CI_LIBS/plugins/com.nomagic.magicdraw.automaton/lib/$jar_name" || {
      echo "ERROR: Failed to download $url" >&2
      exit 1
    }
  else
    echo "  Already cached: $jar_name"
  fi
done

# -------------------------------------------------------
# 3. Create Cameo SDK stubs (compile-only)
# -------------------------------------------------------
echo "--- Creating Cameo SDK stubs ---"

# Package: com.nomagic.magicdraw.plugins
mkdir -p "$STUBS_SRC/com/nomagic/magicdraw/plugins"
cat > "$STUBS_SRC/com/nomagic/magicdraw/plugins/Plugin.java" << 'JAVA'
package com.nomagic.magicdraw.plugins;

public abstract class Plugin {
    public void init() {}
    public boolean close() { return true; }
    public boolean isSupported() { return true; }
}
JAVA

# Package: com.nomagic.magicdraw.core
mkdir -p "$STUBS_SRC/com/nomagic/magicdraw/core"
cat > "$STUBS_SRC/com/nomagic/magicdraw/core/Application.java" << 'JAVA'
package com.nomagic.magicdraw.core;

public class Application {
    private static final Application INSTANCE = new Application();
    public static Application getInstance() { return INSTANCE; }
    public GUILog getGUILog() { return new GUILog(); }
    public Project getProject() { return new Project(); }
}
JAVA

cat > "$STUBS_SRC/com/nomagic/magicdraw/core/GUILog.java" << 'JAVA'
package com.nomagic.magicdraw.core;

public class GUILog {
    public void log(String msg) {}
    public void showError(String msg) {}
}
JAVA

cat > "$STUBS_SRC/com/nomagic/magicdraw/core/Project.java" << 'JAVA'
package com.nomagic.magicdraw.core;

public class Project {
    public OptionsSet getOptions() { return new OptionsSet(); }
}
JAVA

cat > "$STUBS_SRC/com/nomagic/magicdraw/core/OptionsSet.java" << 'JAVA'
package com.nomagic.magicdraw.core;

public class OptionsSet {
    public String getCategoryName() { return "stub"; }
}
JAVA

# Compile stubs
echo "--- Compiling stubs ---"
javac --release "$STUBS_RELEASE" -d "$STUBS_SRC/classes" \
  "$STUBS_SRC/com/nomagic/magicdraw/plugins/Plugin.java" \
  "$STUBS_SRC/com/nomagic/magicdraw/core/Application.java" \
  "$STUBS_SRC/com/nomagic/magicdraw/core/GUILog.java" \
  "$STUBS_SRC/com/nomagic/magicdraw/core/Project.java" \
  "$STUBS_SRC/com/nomagic/magicdraw/core/OptionsSet.java"

# Package into a jar that matches one of the build.gradle glob patterns
# e.g. core-*.jar  matches  core-stubs.jar
jar --create --file "$CI_LIBS/lib/core-stubs.jar" -C "$STUBS_SRC/classes" .

echo "=== ci-libs/ ready ==="
ls -la "$CI_LIBS/lib/"
ls -la "$CI_LIBS/plugins/com.nomagic.magicdraw.automaton/lib/"
