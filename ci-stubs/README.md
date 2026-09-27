# ci-stubs

Compile-only stubs for the third-party APIs the plugin compiles against but
must not ship.

**None of this is packaged into the plugin, and none of it is loaded at
runtime.** On a real MagicDraw install, MagicDraw supplies the real classes and
these jars are absent entirely. They exist so `gradle compileJava` can run on a
CI runner that has no MagicDraw installation.

## Why not just commit the real jars

Three of the four dependency families are proprietary or oversized:

| family | why not |
| --- | --- |
| `com.nomagic.magicdraw.*`, `com.jidesoft.*` | proprietary, not redistributable |
| `com.nomagic.uml2.*` | proprietary, not redistributable |
| `org.apache.lucene.*` | open source (Apache-2.0) but ~4 MB of jar for 26 stub classes |

Jackson and Groovy are the exception: they are genuinely open source, small to
fetch, and fetched from Maven Central at CI time rather than vendored here.

## How it is wired

`ci/prepare-ci-libs.sh` calls `build-stubs.sh` after building the core
`core-stubs.jar`. Every jar name below matches one of the `include` globs in
the `compileOnly fileTree(...)` block in `build.gradle`, so **no build script
change is needed** for them to be picked up:

| jar | glob it matches | source set |
| --- | --- | --- |
| `core-ui-stubs.jar` | `core-*.jar` | `md-ui/` |
| `jide-dock-stubs.jar` | `jide-dock-*.jar` | `jide-dock/` |
| `lucene-core-stubs.jar` | `lucene-core-*.jar` | `lucene-core/` |
| `lucene-analysis-common-stubs.jar` | `lucene-analysis-common-*.jar` | `lucene-analysis-common/` |

`ci-libs/` itself is gitignored and is regenerated from scratch on every CI run
(`rm -rf "$CI_LIBS"`), so nothing in it is committed — these sources are.

## Build

```bash
./ci-stubs/build-stubs.sh          # writes ci-libs/lib/*-stubs.jar
JAVAC_RELEASE=21 ./ci-stubs/build-stubs.sh   # build for a specific Java release
```

Then the CI compile is exactly:

```bash
gradle compileJava -Ptarget=2026x -PcameoHome="$PWD/ci-libs" --no-daemon
```

## Keeping a stub in sync with the real API

A stub that drifts from the real class will **not** fail here — it will fail in
a real MagicDraw install, at runtime, where the actual class is present. That is
the one real risk in this directory, so:

1. Signatures were copied from the real jars with
   `javap -classpath <MSOSA>/lib/*.jar <fqcn>`, not written from memory.
2. When you add a call to a third-party API, add the matching member here in
   the same change. A missing stub member shows up as a CI compile error; a
   *wrong* one does not.
3. Only stub what is actually used. Unused members are dead weight that can
   drift silently.

Method bodies are deliberately empty or no-ops. A stub is a compile-time
contract, not an implementation — if you find yourself writing logic in one,
that logic belongs in the plugin or the dependency is not really compileOnly.
