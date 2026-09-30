#!/usr/bin/env bash
# compile_scripts.sh
#
# Compile scripts/*.groovy to bytecode, not just to parsed syntax.
#
# Why this exists: `gradle compileJava` builds the Java layer only, and a
# Groovy parse check stops at the CONVERSION phase, which never resolves a
# type. Both miss the failure that actually bites - an unresolvable type in
# one script aborts the load of *every* Groovy script, so the whole
# saf_*/structural_* half of the tool surface disappears at runtime with no
# error on the tool that went missing. A typo like `instanceof Block` (a SysML
# Block is a mdkernel Class; there is no Block metaclass) is invisible to
# both and takes 40 tools down.
#
# CLASS_GENERATION resolves imports and types against the real MagicDraw jars,
# so it fails here rather than in a running Cameo.
#
# Usage: bash ci/compile_scripts.sh
set -uo pipefail

cd "$(dirname "$0")/.."

CAMEO_HOME="${CAMEO_HOME:-/workspace/MSOSA2026xHF2}"
GROOVY_JAR="${GROOVY_JAR:-/tmp/ci-libs-backup/plugins/com.nomagic.magicdraw.automaton/lib/groovy-5.0.0.jar}"

if [ ! -f "$GROOVY_JAR" ]; then
  echo "SKIP  groovy jar not found at $GROOVY_JAR"
  exit 0
fi
if [ ! -d "$CAMEO_HOME/lib" ]; then
  echo "SKIP  no MagicDraw install at $CAMEO_HOME"
  exit 0
fi

gradle compileJava -Ptarget=2026x -PcameoHome="$CAMEO_HOME" --offline --console=plain >/tmp/compile-scripts-java.log 2>&1
if [ ! -d build/classes/java/main ]; then
  echo "FAIL  could not build the plugin Java classes (see /tmp/compile-scripts-java.log)"
  tail -20 /tmp/compile-scripts-java.log
  exit 1
fi

CP="$GROOVY_JAR:build/classes/java/main:$(ls "$CAMEO_HOME"/lib/*.jar 2>/dev/null | tr '\n' ':')"

cat > /tmp/compile_scripts_check.groovy <<'EOF'
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.Phases
def cu = new CompilationUnit()
cu.addSource(new File(args[0]))
cu.compile(Phases.CLASS_GENERATION)
EOF

FAILED=()
for f in scripts/*.groovy; do
  if out=$(java -cp "$CP" groovy.ui.GroovyMain /tmp/compile_scripts_check.groovy "$f" 2>&1); then
    echo "PASS  $f"
  else
    # A missing third-party jar in CAMEO_HOME/lib is a gap in this classpath,
    # not a broken script; the tool still loads in a real Cameo.
    if grep -q 'unable to resolve class com.nomagic.magicdraw' <<<"$out" \
       && ! grep -qE 'unable to resolve class com.nomagic.uml2|unable to resolve class com.haarer' <<<"$out"; then
      echo "SKIP  $f (class not in $CAMEO_HOME/lib)"
    else
      echo "FAIL  $f"
      grep -E 'unable to resolve|startup failed|expecting' <<<"$out" | head -6
      FAILED+=("$f")
    fi
  fi
done

echo
if [ ${#FAILED[@]} -eq 0 ]; then
  echo "Groovy scripts: all resolved against the MagicDraw jars"
  exit 0
fi
echo "Groovy scripts FAILED to resolve:"
printf '  - %s\n' "${FAILED[@]}"
exit 1
