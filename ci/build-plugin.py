#!/usr/bin/env python3
"""Build the Cameo SAF MCP Server plugin distribution zip for Resource Manager installation.

Usage:
    python ci/build-plugin.py [--cameo-home PATH] [--target 2026x|2024x]

Steps:
    1. Run 'gradle assemblePlugin' to compile Java and produce the JAR + plugin.xml.
    2. Generate a resource descriptor XML listing all plugin files.
    3. Package descriptor + JAR + plugin.xml + Groovy scripts into a plugin zip
       that can be installed via Cameo's Resource Manager (File → Resource Manager → Install).

Output:
    dist/cameo-saf-mcp-server.zip
"""

import argparse
import datetime
import os
import subprocess
import zipfile

RESOURCE_ID = "99001"
PLUGIN_ID = "com.haarer.saf.mcpserver"
PLUGIN_NAME = "Cameo SAF MCP Server"
PLUGIN_VERSION_DEFAULT = "0.1.0-dev"
PROVIDER_NAME = "Alexander Haarer"

DESCRIPTOR_DIR = "data/resourcemanager"
DESCRIPTOR_FILENAME = f"MDR_Plugin_{PLUGIN_NAME}_{RESOURCE_ID}_descriptor.xml"
PLUGIN_DIR = f"plugins/{PLUGIN_ID}"

TARGETS = {
    "2026x": {
        "mdVersionMin": "2026x",
        "internalMinVer": "202600000",
    },
    "2024x": {
        "mdVersionMin": "2024x",
        "internalMinVer": "202400000",
    },
}

DESCRIPTOR_TEMPLATE = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<resourceDescriptor
    critical="false"
    date="{date}"
    description="MCP (Model Context Protocol) server plugin for Cameo Systems Modeler. Enables AI agents to interact with Cameo models via tools, resources, and prompts over HTTP."
    homePage="https://github.com/haarer/cameo-saf-mcp-server"
    id="{resource_id}"
    mdVersionMax="higher"
    mdVersionMin="{md_version_min}"
    name="{plugin_name}"
    product="{plugin_name}"
    restartMagicdraw="true"
    type="Plugin">
    <version human="{version}" internal="{internal_version}" resource="{resource_version}"/>
    <provider
        email=""
        homePage="https://github.com/haarer/cameo-saf-mcp-server"
        name="{provider_name}"/>
    <edition>Reader</edition>
    <edition>Community</edition>
    <edition>Standard</edition>
    <edition>Professional Java</edition>
    <edition>Professional C++</edition>
    <edition>Professional C#</edition>
    <edition>Professional</edition>
    <edition>Architect</edition>
    <edition>Enterprise</edition>
    <requiredResource id="1440" name="SysML v1">
        <minVersion internal="{internal_min_ver}" human="{md_version_min}"/>
    </requiredResource>
    <installation>
{installation_entries}
    </installation>
</resourceDescriptor>'''


def _build_installation_entries(files: list[str]) -> str:
    lines = []
    for path in sorted(files):
        lines.append(f'        <file from="{path}" to="{path}"/>')
    return "\n".join(lines)


def _internal_version(version: str) -> str:
    """Build a numeric internal version from semver.
    1.0.0 → 100000, 1.2.3 → 102003, v2.0.0-rc1 → 200000, etc.
    Strips leading 'v' and pre-release suffix.
    """
    clean = version.lstrip("v")
    parts = clean.split(".")[:3]
    major = int(parts[0]) if len(parts) >= 1 else 1
    minor = int(parts[1]) if len(parts) >= 2 else 0
    patch_str = parts[2] if len(parts) >= 3 else "0"
    patch = int(patch_str.split("-")[0].split("+")[0])
    return f"{major:d}{minor:02d}{patch:03d}"


def _detect_version() -> str:
    """Detect version from git tag, or return default."""
    try:
        tag = subprocess.run(
            ["git", "describe", "--tags", "--exact-match", "HEAD"],
            capture_output=True, text=True, timeout=5
        )
        if tag.returncode == 0 and tag.stdout.strip():
            return tag.stdout.strip()
    except Exception:
        pass
    return PLUGIN_VERSION_DEFAULT


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Build Cameo SAF MCP Server plugin zip for Resource Manager"
    )
    parser.add_argument(
        "--cameo-home",
        default=None,
        help="Path to Cameo installation (sets -PcameoHome for Gradle)",
    )
    parser.add_argument(
        "--target",
        default="2026x",
        choices=list(TARGETS.keys()),
        help="Cameo target version (default: 2026x)",
    )
    parser.add_argument(
        "--version",
        default=None,
        help="Plugin version (default: auto-detected from git tag, or 1.0.0-dev). "
             "In CI, use github.ref_name (the git tag).",
    )
    args = parser.parse_args()
    plugin_version = args.version or _detect_version()
    target_cfg = TARGETS[args.target]
    project_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

    # ------------------------------------------------------------------
    # Step 1: Build JAR + plugin.xml via Gradle
    # ------------------------------------------------------------------
    print(f"--- Step 1: Gradle assemblePlugin (target={args.target}, version={plugin_version}) ---")
    gradle_cmd = ["gradle", "assemblePlugin", "--no-daemon", f"-PpluginVersion={plugin_version}", f"-Ptarget={args.target}"]
    if args.cameo_home:
        gradle_cmd.append(f"-PcameoHome={args.cameo_home}")
    subprocess.run(gradle_cmd, cwd=project_root, check=True)

    # ------------------------------------------------------------------
    # Step 2: Collect all plugin files and generate descriptor XML
    # ------------------------------------------------------------------
    print("--- Step 2: Generate resource descriptor ---")
    plugin_dist = os.path.join(
        project_root, "build", "plugin-dist", PLUGIN_ID
    )
    scripts_src = os.path.join(project_root, "scripts")
    data_src = os.path.join(project_root, "_data")
    dist_dir = os.path.join(project_root, "dist")
    os.makedirs(dist_dir, exist_ok=True)

    # Collect files that go into the plugin zip
    plugin_files: list[str] = []

    # JAR + plugin.xml
    for item in sorted(os.listdir(plugin_dist)):
        plugin_files.append(f"{PLUGIN_DIR}/{item}")

    # Groovy scripts
    for entry in sorted(os.listdir(scripts_src)):
        if not entry.endswith(".groovy"):
            continue
        plugin_files.append(f"{PLUGIN_DIR}/scripts/{entry}")

    # _data JSON files
    for entry in sorted(os.listdir(data_src)):
        if not entry.endswith(".json"):
            continue
        plugin_files.append(f"{PLUGIN_DIR}/_data/{entry}")

    # Build descriptor XML
    today = datetime.date.today().strftime("%Y-%m-%d")
    internal_ver = _internal_version(plugin_version)
    descriptor_xml = DESCRIPTOR_TEMPLATE.format(
        date=today,
        resource_id=RESOURCE_ID,
        plugin_name=PLUGIN_NAME,
        version=plugin_version,
        internal_version=internal_ver,
        resource_version=f"{internal_ver}0",
        provider_name=PROVIDER_NAME,
        md_version_min=target_cfg["mdVersionMin"],
        internal_min_ver=target_cfg["internalMinVer"],
        installation_entries=_build_installation_entries(plugin_files),
    )

    # ------------------------------------------------------------------
    # Step 3: Package plugin zip
    # ------------------------------------------------------------------
    print("--- Step 3: Package plugin zip ---")
    zip_path = os.path.join(dist_dir, "cameo-saf-mcp-server.zip")

    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
        # Descriptor XML
        descriptor_arc = f"{DESCRIPTOR_DIR}/{DESCRIPTOR_FILENAME}"
        zf.writestr(descriptor_arc, descriptor_xml)
        print(f"  Added: {descriptor_arc}")

        # Plugin files (JAR, plugin.xml, scripts)
        for item in sorted(os.listdir(plugin_dist)):
            item_path = os.path.join(plugin_dist, item)
            arc_path = f"{PLUGIN_DIR}/{item}"
            zf.write(item_path, arc_path)
            print(f"  Added: {arc_path}")

        for entry in sorted(os.listdir(scripts_src)):
            if not entry.endswith(".groovy"):
                continue
            entry_path = os.path.join(scripts_src, entry)
            arc_path = f"{PLUGIN_DIR}/scripts/{entry}"
            zf.write(entry_path, arc_path)
            print(f"  Added: {arc_path}")

        # _data JSON files
        for entry in sorted(os.listdir(data_src)):
            if not entry.endswith(".json"):
                continue
            entry_path = os.path.join(data_src, entry)
            arc_path = f"{PLUGIN_DIR}/_data/{entry}"
            zf.write(entry_path, arc_path)
            print(f"  Added: {arc_path}")

    print(f"\nCreated: {zip_path}")
    with zipfile.ZipFile(zip_path, "r") as zf:
        total = len(zf.infolist())
        size = os.path.getsize(zip_path)
    print(f"  {total} files, {size / 1024:.0f} KB")
    print(f"\nInstall via Cameo: File → Resource Manager → Install → {zip_path}")


if __name__ == "__main__":
    main()
