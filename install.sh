#!/bin/bash
set -e

# Default CAMEO_HOME if not provided as environment variable
CAMEO_HOME=${CAMEO_HOME:-"/workspace/MSOSA2026xHF2"}

echo "Installing Cameo SAF MCP Server Plugin..."
echo "CAMEO_HOME: $CAMEO_HOME"

# 1. Define target directory
TARGET_DIR="$CAMEO_HOME/plugins/com.haarer.saf.mcpserver"
BUILD_DIST="build/plugin-dist/com.haarer.saf.mcpserver"

# 2. Create target directory if it doesn't exist
mkdir -p "$TARGET_DIR"

# 3. Copy plugin JAR and plugin.xml
echo "Deploying files to $TARGET_DIR..."
cp -r "$BUILD_DIST"/* "$TARGET_DIR/"

# 4. Copy scripts
echo "Deploying scripts to $TARGET_DIR/scripts..."
mkdir -p "$TARGET_DIR/scripts"
cp -r scripts/* "$TARGET_DIR/scripts/"

# 5. Copy _data (hot-reloadable alongside scripts)
echo "Deploying _data to $TARGET_DIR/_data..."
mkdir -p "$TARGET_DIR/_data"
cp -r _data/* "$TARGET_DIR/_data/"

echo "Installation successful!"
echo "Please restart Cameo to load the plugin."
