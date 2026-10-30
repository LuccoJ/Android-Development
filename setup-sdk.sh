#!/bin/bash
# Assembles an Android SDK from openly licensed parts only, so that Gradle/AGP can build apps
# without anyone accepting Google's Android SDK License Agreement.
#
#   platforms/android-<API>[.0]/android.jar
#       AOSP framework as built by Robolectric (org.robolectric:android-all, Apache-2.0)
#       + the java.* API of the local OpenJDK (GPLv2 + Classpath Exception),
#       reduced to public API by tools/StubJar.java
#   build-tools/<version>/
#       Debian/Ubuntu binaries (aidl, aapt, aapt2, zipalign, apksigner; Apache-2.0)
#
# It also installs a Gradle init script that fetches Maven Central through Google's public mirror.
#
# Usage: setup-sdk.sh [--sdk DIR] [--api N]... [--build-tools X.Y.Z]...
#   --sdk          where to create the SDK (default: ~/oss-android-sdk)
#   --api          API level to provide, repeatable (default: 37)
#   --build-tools  build-tools version AGP asks for, repeatable (default: 36.0.0)
# Afterwards: export ANDROID_HOME=<sdk dir> and run ./gradlew as usual.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SDK="$HOME/oss-android-sdk"
APIS=()
BUILD_TOOLS=()
while [ $# -gt 0 ]; do
    case "$1" in
        --sdk) SDK="$2"; shift 2;;
        --api) APIS+=("$2"); shift 2;;
        --build-tools) BUILD_TOOLS+=("$2"); shift 2;;
        -h|--help) sed -n '2,20p' "$0"; exit 0;;
        *) echo "Unknown option: $1" >&2; exit 2;;
    esac
done
[ ${#APIS[@]} -gt 0 ] || APIS=(37)
[ ${#BUILD_TOOLS[@]} -gt 0 ] || BUILD_TOOLS=(36.0.0)
mkdir -p "$SDK"
SDK="$(cd "$SDK" && pwd)"

MIRROR="https://maven-central.storage-download.googleapis.com/maven2"
ASM="9.9"
CACHE="$SDK/.cache"
mkdir -p "$CACHE"

# Robolectric names android-all releases by Android version, not API level
release_for_api() {
    case "$1" in
        21) echo 5.0.2_r3;; 22) echo 5.1.1_r9;; 23) echo 6.0.1_r3;; 24) echo 7.0.0_r1;;
        25) echo 7.1.0_r7;; 26) echo 8.0.0_r4;; 27) echo 8.1.0;; 28) echo 9;; 29) echo 10;;
        30) echo 11;; 31) echo 12;; 32) echo 12.1;; 33) echo 13;; 34) echo 14;; 35) echo 15;;
        36) echo 16;; 37) echo 17;;
        *) echo "No known Robolectric release for API $1 (add it to release_for_api)" >&2; return 1;;
    esac
}

fetch() {  # url -> path in cache
    local out="$CACHE/$(basename "$1")"
    if [ ! -s "$out" ]; then
        curl -sSfL -o "$out.part" "$1" || { echo "Download failed: $1" >&2; return 1; }
        mv -f "$out.part" "$out"
    fi
    echo "$out"
}

echo "== Debian/Ubuntu packages"
if ! dpkg -s aidl android-sdk-build-tools >/dev/null 2>&1; then
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq aidl android-sdk-build-tools >/dev/null
fi

echo "== Gradle: Maven Central through Google's mirror"
GRADLE_HOME_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}"
mkdir -p "$GRADLE_HOME_DIR/init.d"
cp "$HERE/gradle/central-mirror.gradle" "$GRADLE_HOME_DIR/init.d/"

echo "== Stub tool"
ASM_CP="$(fetch "$MIRROR/org/ow2/asm/asm/$ASM/asm-$ASM.jar"):$(fetch "$MIRROR/org/ow2/asm/asm-tree/$ASM/asm-tree-$ASM.jar")"
mkdir -p "$CACHE/classes"
javac -d "$CACHE/classes" -cp "$ASM_CP" "$HERE/tools/StubJar.java"

for API in "${APIS[@]}"; do
    REL="$(release_for_api "$API")"
    echo "== Platform API $API (Android $REL)"
    VERSION="$(curl -sSfL "$MIRROR/org/robolectric/android-all/maven-metadata.xml" \
        | grep -oE "<version>$REL-robolectric-[0-9]+</version>" | sed -E 's#</?version>##g' | sort -V | tail -1)"
    [ -n "$VERSION" ] || { echo "No android-all $REL release found" >&2; exit 1; }
    FRAMEWORK="$(fetch "$MIRROR/org/robolectric/android-all/$VERSION/android-all-$VERSION.jar")"
    if ! unzip -l "$FRAMEWORK" resources.arsc >/dev/null 2>&1; then
        echo "android-all $VERSION has no compiled framework resources (resources.arsc); unusable for aapt2" >&2
        exit 1
    fi

    # AGP asks for platforms;android-N, or platforms;android-N.0 when compileSdk sets a minor API level
    P="$SDK/platforms/android-$API"
    mkdir -p "$P"
    java -Xmx6g -cp "$ASM_CP:$CACHE/classes" StubJar "$P/android.jar" "$FRAMEWORK" "$P/framework.aidl"
    rm -rf "$CACHE/cfsm" && mkdir "$CACHE/cfsm"
    (cd "$CACHE/cfsm" && unzip -q "$P/android.jar" 'java/*' 'javax/*' 'org/w3c/*' 'org/xml/*' \
        && jar cf "$P/core-for-system-modules.jar" .)
    rm -rf "$CACHE/cfsm"
    printf "ro.build.version.sdk=%s\nro.build.version.codename=REL\n" "$API" > "$P/build.prop"
    printf "AndroidVersion.ApiLevel=%s\nAndroidVersion.CodeName=REL\nPkg.Revision=1\nPkg.LicenseRef=Apache-2.0\nLayoutlib.Api=15\n" "$API" > "$P/source.properties"

    P_MINOR="$SDK/platforms/android-$API.0"
    rm -rf "$P_MINOR" && mkdir -p "$P_MINOR"
    for f in android.jar core-for-system-modules.jar framework.aidl build.prop source.properties; do
        ln -s "../android-$API/$f" "$P_MINOR/$f"
    done

    for dir in "android-$API:$API" "android-$API.0:$API.0"; do
        name="${dir%%:*}"; level="${dir##*:}"
        cat > "$SDK/platforms/$name/package.xml" <<EOF
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<ns2:repository xmlns:ns2="http://schemas.android.com/repository/android/common/02"
                xmlns:ns6="http://schemas.android.com/sdk/android/repo/repository2/04"
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <license id="apache-2.0" type="text">Please refer to Apache v2.0 license</license>
  <localPackage path="platforms;$name" obsolete="false">
    <type-details xsi:type="ns6:platformDetailsType">
      <api-level>$level</api-level>
      <base-extension>true</base-extension>
      <layoutlib api="15"/>
    </type-details>
    <revision>
      <major>1</major>
    </revision>
    <display-name>Android API $API platform (AOSP $VERSION via Robolectric)</display-name>
    <uses-license ref="apache-2.0"/>
  </localPackage>
</ns2:repository>
EOF
    done
done

DEB_BT=/usr/lib/android-sdk/build-tools/debian
for BTV in "${BUILD_TOOLS[@]}"; do
    echo "== Build tools $BTV (Debian binaries)"
    BT="$SDK/build-tools/$BTV"
    rm -rf "$BT" && mkdir -p "$BT"
    for f in aapt aapt2 aidl apksigner apksigner.jar split-select zipalign; do ln -s "$DEB_BT/$f" "$BT/$f"; done
    # AGP checks these exist but does not use them to build an APK
    printf '#!/bin/sh\necho "placeholder: dexdump is not part of this SDK" >&2\nexit 1\n' > "$BT/dexdump"
    chmod +x "$BT/dexdump"
    rm -rf "$CACHE/empty" && mkdir -p "$CACHE/empty/META-INF" && echo "Manifest-Version: 1.0" > "$CACHE/empty/META-INF/MANIFEST.MF"
    jar cfm "$BT/core-lambda-stubs.jar" "$CACHE/empty/META-INF/MANIFEST.MF"
    IFS=. read -r MAJ MIN MIC <<< "$BTV"
    sed -e "s/build-tools;29.0.3/build-tools;$BTV/; s#<major>29</major>#<major>$MAJ</major>#; s#<minor>0</minor>#<minor>${MIN:-0}</minor>#; s#<micro>3</micro>#<micro>${MIC:-0}</micro>#; s/Build-Tools 29.0.3/Build-Tools $BTV (Debian binaries)/" \
        "$DEB_BT/package.xml" > "$BT/package.xml"
    printf "Pkg.Revision=%s\nPkg.Path=build-tools;%s\n" "$BTV" "$BTV" > "$BT/source.properties"
done

echo "== Done. Use it with: export ANDROID_HOME=$SDK"
