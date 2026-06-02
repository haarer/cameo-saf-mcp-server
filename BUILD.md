# Build and Deployment

## Multi-Target Build

The project builds for Cameo 2026x (JDK 21) and Cameo 2024x (JDK 17) from a single codebase. All target-specific configuration lives in `build.gradle` and `scripts/build-plugin.py`.

### Target Configuration

| Property | 2026x | 2024x |
|---|---|---|
| JDK | 21 | 17 |
| Automaton version | `2026x` | `2024x Refresh3` |
| Descriptor `mdVersionMin` | `2026x` | `2024x` |
| Descriptor `internalMinVer` | `202600000` | `202400000` |

### Build Commands

```bash
# Build 2026x (default)
python scripts/build-plugin.py --version 1.0.0

# Build 2024x
python scripts/build-plugin.py --target 2024x --cameo-home /workspace/msosa2024 --version 1.0.0

# Build with custom JDK
python scripts/build-plugin.py --target 2024x --cameo-home /path/to/cameo \
  --version 1.0.0 -PjdkHome=/path/to/jdk17
```

Output: `dist/cameo-saf-mcp-server.zip`

### Deploy to Running Cameo

```bash
# Deploy 2026x
gradle deploy -Ptarget=2026x -PcameoHome=/workspace/MSOSA2026xHF2 -PpluginVersion=1.0.0

# Deploy 2024x
gradle deploy -Ptarget=2024x -PcameoHome=/workspace/msosa2024 -PpluginVersion=1.0.0
```

### Adding a New Target

Add an entry to the `targets` map in `build.gradle` and the `TARGETS` dict in `scripts/build-plugin.py`:

```groovy
// build.gradle
def targets = [
    '2026x': [ ... ],
    '2024x': [ ... ],
    '2028x': [
        cameoHome:      '/workspace/MSOSA2028x',
        jdkHome:        '/usr/lib/jvm/java-21-openjdk',
        release:        21,
        automatonVer:   '2028x',
        mdVersionMin:   '2028x',
        internalMinVer: '202800000',
    ]
]
```

```python
# scripts/build-plugin.py
TARGETS = {
    "2026x": { ... },
    "2024x": { ... },
    "2028x": {
        "mdVersionMin": "2028x",
        "internalMinVer": "202800000",
    },
}
```

### CI/CD

GitHub Actions builds both targets on every tag push and uploads two assets per release:
- `cameo-saf-mcp-server-2024x.zip`
- `cameo-saf-mcp-server-2026x.zip`

The `prepare-ci-libs.sh` script compiles stubs matching the active JDK. Override with `STUBS_RELEASE=17` or `STUBS_RELEASE=21` env var.

See `.github/workflows/ci.yml` for the matrix configuration.
