#!/usr/bin/env python3
"""Package the Cameo SAF MCP Server plugin distribution zip from Make-built artifacts.

This is the Makefile equivalent of ci/build-plugin.py. It does NOT invoke Gradle;
it packages the already-staged output of `make dist`
(build/plugin-dist/com.haarer.saf.mcpserver) together with the Groovy scripts and
_data JSON files into a zip installable via Cameo's Resource Manager.

Usage:
    make plugin
    python ci/package-plugin.py [--plugin-version X.Y.Z] [--target 2026x|2024x]

Output:
    dist/cameo-saf-mcp-server.zip
"""

import argparse
import datetime
import os
import zipfile

RESOURCE_ID = "99001"
PLUGIN_ID = "com.haarer.saf.mcpserver"
PLUGIN_NAME = "Cameo SAF MCP Server"
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
    lines = [
        f'        <file from="{path}" to="{path}"/>'
        for path in sorted(files)
    ]
    return "\n".join(lines)


def _internal_version(version: str) -> str:
    clean = version.lstrip("v")
    parts = clean.split(".")[:3]
    major = int(parts[0]) if len(parts) >= 1 else 1
    minor = int(parts[1]) if len(parts) >= 2 else 0
    patch_str = parts[2] if len(parts) >= 3 else "0"
    patch = int(patch_str.split("-")[0].split("+")[0])
    return f"{major:d}{minor:02d}{patch:03d}"


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Package Cameo SAF MCP Server plugin zip from make dist output"
    )
    parser.add_argument(
        "--plugin-version",
        default="0.1.1",
        help="Plugin version (default: 0.1.1)",
    )
    parser.add_argument(
        "--target",
        default="2026x",
        choices=list(TARGETS.keys()),
        help="Cameo target version (default: 2026x)",
    )
    parser.add_argument(
        "--root",
        default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        help="Project root (default: derived from this script's location)",
    )
    args = parser.parse_args()

    plugin_version = args.plugin_version
    target_cfg = TARGETS[args.target]
    project_root = args.root

    plugin_dist = os.path.join(project_root, "build", "plugin-dist", PLUGIN_ID)
    scripts_src = os.path.join(project_root, "scripts")
    data_src = os.path.join(project_root, "_data")
    dist_dir = os.path.join(project_root, "dist")
    os.makedirs(dist_dir, exist_ok=True)

    if not os.path.isdir(plugin_dist):
        raise SystemExit(
            f"ERROR: {plugin_dist} not found. Run 'make dist' (or 'make plugin') first."
        )

    # JAR + plugin.xml from Make staging (skip directories; _data is added below)
    plugin_files = [
        f"{PLUGIN_DIR}/{item}"
        for item in sorted(os.listdir(plugin_dist))
        if os.path.isfile(os.path.join(plugin_dist, item))
    ]
    # Groovy scripts
    plugin_files += [
        f"{PLUGIN_DIR}/scripts/{entry}"
        for entry in sorted(os.listdir(scripts_src))
        if entry.endswith(".groovy")
    ]
    # _data JSON files
    plugin_files += [
        f"{PLUGIN_DIR}/_data/{entry}"
        for entry in sorted(os.listdir(data_src))
        if entry.endswith(".json")
    ]

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

    zip_path = os.path.join(dist_dir, "cameo-saf-mcp-server.zip")
    descriptor_arc = f"{DESCRIPTOR_DIR}/{DESCRIPTOR_FILENAME}"

    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr(descriptor_arc, descriptor_xml)
        print(f"  Added: {descriptor_arc}")

        for item in sorted(os.listdir(plugin_dist)):
            if not os.path.isfile(os.path.join(plugin_dist, item)):
                continue
            arc_path = f"{PLUGIN_DIR}/{item}"
            zf.write(os.path.join(plugin_dist, item), arc_path)
            print(f"  Added: {arc_path}")

        for entry in sorted(os.listdir(scripts_src)):
            if not entry.endswith(".groovy"):
                continue
            arc_path = f"{PLUGIN_DIR}/scripts/{entry}"
            zf.write(os.path.join(scripts_src, entry), arc_path)
            print(f"  Added: {arc_path}")

        for entry in sorted(os.listdir(data_src)):
            if not entry.endswith(".json"):
                continue
            arc_path = f"{PLUGIN_DIR}/_data/{entry}"
            zf.write(os.path.join(data_src, entry), arc_path)
            print(f"  Added: {arc_path}")

    with zipfile.ZipFile(zip_path, "r") as zf:
        entry_count = len(zf.infolist())
    size_kb = os.path.getsize(zip_path) / 1024
    print(f"\nCreated: {zip_path}")
    print(f"  {entry_count} files, {size_kb:.0f} KB")
    print(f"\nInstall via Cameo: File → Resource Manager → Install → {zip_path}")


if __name__ == "__main__":
    main()
