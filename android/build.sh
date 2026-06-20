#!/bin/bash
# build.sh — Build and sideload Grünstahl Android APK
#
# Usage:
#   ./build.sh              # build debug APK
#   ./build.sh release      # build release APK (debug-signed, sideload-ready)
#   ./build.sh install      # build debug + adb install to connected phone

set -e
cd "$(dirname "$0")"

# ── Locate Android SDK ───────────────────────────────────────────────────────
if [ -z "$ANDROID_HOME" ] && [ -z "$ANDROID_SDK_ROOT" ]; then
    for candidate in \
        "/usr/lib/android-sdk" \
        "/usr/local/lib/android-sdk" \
        "$HOME/Android/Sdk" \
        "$HOME/android-sdk" \
        "/opt/android-sdk" \
        "$HOME/Library/Android/sdk"; do
        if [ -d "$candidate" ]; then
            export ANDROID_HOME="$candidate"
            echo "Found Android SDK at $ANDROID_HOME"
            break
        fi
    done
fi

if [ -z "$ANDROID_HOME" ] && [ -z "$ANDROID_SDK_ROOT" ]; then
    echo "ERROR: Android SDK not found."
    echo "  Set ANDROID_HOME, e.g.:"
    echo "    export ANDROID_HOME=/usr/lib/android-sdk     # apt install android-sdk"
    echo "    export ANDROID_HOME=\$HOME/Android/Sdk       # Android Studio"
    exit 1
fi

SDK="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"

# ── Patch build-tools version ────────────────────────────────────────────────
# The apt package ships build-tools with a non-semver name (e.g. "debian").
# Find the highest properly-versioned (X.Y.Z) build-tools directory.
if [ -d "$SDK/build-tools" ]; then
    # List only dirs that look like semver (digits and dots only)
    VALID_VERSIONS=$(ls "$SDK/build-tools/" 2>/dev/null \
        | grep -E '^[0-9]+\.[0-9]+(\.[0-9]+)?$' \
        | sort -t. -k1,1n -k2,2n -k3,3n)

    echo "Build-tools found in $SDK/build-tools/:"
    ls "$SDK/build-tools/" 2>/dev/null | sed 's/^/  /'

    if [ -n "$VALID_VERSIONS" ]; then
        LATEST=$(echo "$VALID_VERSIONS" | tail -1)
        CURRENT=$(grep 'buildToolsVersion' app/build.gradle | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' || true)
        if [ "$CURRENT" != "$LATEST" ]; then
            echo "Patching buildToolsVersion: $CURRENT -> $LATEST"
            sed -i "s/buildToolsVersion \"[^\"]*\"/buildToolsVersion \"$LATEST\"/" app/build.gradle
        fi
    else
        echo "WARNING: No properly-versioned build-tools found."
        echo "  The apt package may only include a 'debian' build-tools stub."
        echo "  Install proper build tools:"
        echo "    sudo apt install google-android-build-tools-installer"
        echo "  Or download Android Studio for a full SDK."
        echo ""
        echo "  Removing buildToolsVersion from build.gradle and hoping for the best..."
        sed -i '/buildToolsVersion/d' app/build.gradle
    fi
fi

# ── Patch compileSdk / targetSdk to match installed platform ─────────────────
if [ -d "$SDK/platforms" ]; then
    PLATFORMS=$(ls "$SDK/platforms/" 2>/dev/null \
        | grep -oE '[0-9]+' \
        | sort -n)

    echo "Platforms found: $(echo $PLATFORMS | tr '\n' ' ')"

    HIGHEST_PLATFORM=$(echo "$PLATFORMS" | tail -1)
    CURRENT_COMPILE=$(grep 'compileSdk' app/build.gradle | grep -oE '[0-9]+' | head -1 || true)

    if [ -n "$HIGHEST_PLATFORM" ] && [ "$CURRENT_COMPILE" != "$HIGHEST_PLATFORM" ]; then
        echo "Patching compileSdk/targetSdk: $CURRENT_COMPILE -> $HIGHEST_PLATFORM"
        sed -i "s/compileSdk [0-9]*/compileSdk $HIGHEST_PLATFORM/" app/build.gradle
        sed -i "s/targetSdk [0-9]*/targetSdk $HIGHEST_PLATFORM/" app/build.gradle
        # Also patch minSdk down if needed
        if [ "$HIGHEST_PLATFORM" -lt 26 ]; then
            echo "Patching minSdk down to $HIGHEST_PLATFORM"
            sed -i "s/minSdk [0-9]*/minSdk $HIGHEST_PLATFORM/" app/build.gradle
        fi
    fi
fi

# ── Accept SDK licenses ───────────────────────────────────────────────────────
# The license hashes are stable across SDK versions.
echo "==> Accepting Android SDK licenses..."
if mkdir -p "$SDK/licenses" 2>/dev/null && [ -w "$SDK/licenses" ]; then
    printf '\n8933bad161af4178b1185d1a37fbf41ea5269c55\n\nd56f5187479451eabf01fb78af6dfcb131a6481e\n24333f8a63b6825ea9c5514f83c2829b004d1fee\n' \
        > "$SDK/licenses/android-sdk-license"
    printf '\n84831b9409646a918e30573bab4c9c91346d8abd\n' \
        > "$SDK/licenses/android-sdk-preview-license"
else
    echo "WARNING: Cannot write to $SDK/licenses — trying with sudo..."
    sudo mkdir -p "$SDK/licenses" 2>/dev/null || true
    printf '\n8933bad161af4178b1185d1a37fbf41ea5269c55\n\nd56f5187479451eabf01fb78af6dfcb131a6481e\n24333f8a63b6825ea9c5514f83c2829b004d1fee\n' \
        | sudo tee "$SDK/licenses/android-sdk-license" > /dev/null || true
    printf '\n84831b9409646a918e30573bab4c9c91346d8abd\n' \
        | sudo tee "$SDK/licenses/android-sdk-preview-license" > /dev/null || true
fi

# Try sdkmanager --licenses too if available (more thorough)
for SDKMANAGER in \
    "$SDK/cmdline-tools/latest/bin/sdkmanager" \
    "$SDK/cmdline-tools/bin/sdkmanager" \
    "$SDK/tools/bin/sdkmanager"; do
    if [ -f "$SDKMANAGER" ]; then
        yes | "$SDKMANAGER" --licenses > /dev/null 2>&1 || true
        break
    fi
done

# ── Bootstrap Gradle ─────────────────────────────────────────────────────────
GRADLE_VERSION="8.4"
GRADLE_DIST_DIR="$HOME/.gradle/wrapper/dists/gradle-${GRADLE_VERSION}-bin"

find_gradle() {
    find "$GRADLE_DIST_DIR" -name "gradle" -path "*/bin/gradle" 2>/dev/null | head -1
}

GRADLE_BIN="$(find_gradle)"

if [ -z "$GRADLE_BIN" ]; then
    echo "==> Downloading Gradle ${GRADLE_VERSION}…"
    mkdir -p "$GRADLE_DIST_DIR"
    GRADLE_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
    GRADLE_ZIP="/tmp/gradle-${GRADLE_VERSION}-bin.zip"
    if command -v curl >/dev/null 2>&1; then
        curl -L -o "$GRADLE_ZIP" "$GRADLE_URL"
    elif command -v wget >/dev/null 2>&1; then
        wget -O "$GRADLE_ZIP" "$GRADLE_URL"
    else
        echo "ERROR: Neither curl nor wget found. Please install curl."
        exit 1
    fi
    echo "==> Extracting Gradle…"
    unzip -q "$GRADLE_ZIP" -d "$GRADLE_DIST_DIR/"
    rm -f "$GRADLE_ZIP"
    GRADLE_BIN="$(find_gradle)"
fi

if [ -z "$GRADLE_BIN" ]; then
    echo "ERROR: Could not locate gradle binary after extraction."
    echo "  Searched in: $GRADLE_DIST_DIR"
    find "$GRADLE_DIST_DIR" -maxdepth 4 2>/dev/null || true
    exit 1
fi

chmod +x "$GRADLE_BIN"
echo "Using Gradle: $GRADLE_BIN"

# ── Build ─────────────────────────────────────────────────────────────────────
VARIANT=${1:-debug}

case "$VARIANT" in
    install)
        echo "==> Building debug APK…"
        "$GRADLE_BIN" assembleDebug
        APK="app/build/outputs/apk/debug/app-debug.apk"
        echo "==> Installing via adb…"
        adb install -r "$APK"
        echo "==> Done. Launch 'Grünstahl' on your phone."
        ;;
    release)
        echo "==> Building release APK (debug-signed)…"
        "$GRADLE_BIN" assembleRelease
        APK="app/build/outputs/apk/release/app-release.apk"
        echo ""
        echo "==> APK ready: $APK"
        echo "    To install: adb install -r $APK"
        ;;
    debug)
        echo "==> Building debug APK…"
        "$GRADLE_BIN" assembleDebug
        APK="app/build/outputs/apk/debug/app-debug.apk"
        echo ""
        echo "==> APK ready: $APK"
        echo "    To install: adb install -r $APK"
        echo "    Or:         ./build.sh install"
        ;;
    *)
        echo "Usage: $0 [debug|release|install]"
        exit 1
        ;;
esac
