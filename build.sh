#!/usr/bin/env bash
# ==============================================================================
# Synker - Unified Multi-Platform Build Pipeline
# Builds the Synker Linux Daemon (synkerd) and Android App (APK)
# Repository: https://github.com/codershubinc/synker
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DIST_DIR="${SCRIPT_DIR}/dist"
DAEMON_DIR="${SCRIPT_DIR}/daemon"
APP_DIR="${SCRIPT_DIR}/klient"

# Colors for terminal output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
MAGENTA='\033[0;35m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

# Build parameters & flags
BUILD_DAEMON=false
BUILD_APP=false
MULTIARCH=false
RELEASE_MODE=false
CLEAN_FIRST=false
INSTALL_AFTER=false
START_TIME=$(date +%s)

# Architecture normalization
detect_arch() {
    local raw_arch
    raw_arch="$(uname -m)"
    case "${raw_arch}" in
        x86_64|amd64) echo "amd64" ;;
        aarch64|arm64) echo "arm64" ;;
        armv7*|armhf) echo "armv7" ;;
        i386|i686) echo "386" ;;
        *) echo "${raw_arch}" ;;
    esac
}

HOST_ARCH="$(detect_arch)"
HOST_OS="linux"

# Read metadata from RELEASE files (industry standard single-source-of-truth)
read_release_prop() {
    local file="$1"
    local prop="$2"
    local fallback="$3"
    if [ -f "${file}" ]; then
        local val
        val="$(grep -E "^[[:space:]]*${prop}=" "${file}" | cut -d '=' -f2- | tr -d '"\r' | xargs || true)"
        if [ -n "${val}" ]; then
            echo "${val}"
            return 0
        fi
    fi
    echo "${fallback}"
}

DAEMON_RELEASE_FILE="${DAEMON_DIR}/RELEASE"
APP_RELEASE_FILE="${APP_DIR}/RELEASE"

DAEMON_VERSION="$(read_release_prop "${DAEMON_RELEASE_FILE}" "VERSION" "0.0.1")"
DAEMON_CHANNEL="$(read_release_prop "${DAEMON_RELEASE_FILE}" "CHANNEL" "beta")"
DAEMON_CODENAME="$(read_release_prop "${DAEMON_RELEASE_FILE}" "CODENAME" "aurora")"
DAEMON_FULL_VERSION="${DAEMON_VERSION}"
[ -n "${DAEMON_CHANNEL}" ] && DAEMON_FULL_VERSION="${DAEMON_VERSION}-${DAEMON_CHANNEL}"

APP_VERSION="$(read_release_prop "${APP_RELEASE_FILE}" "VERSION" "0.0.1")"
APP_CHANNEL="$(read_release_prop "${APP_RELEASE_FILE}" "CHANNEL" "beta")"
APP_CODENAME="$(read_release_prop "${APP_RELEASE_FILE}" "CODENAME" "liquid")"
APP_FULL_VERSION="${APP_VERSION}"
[ -n "${APP_CHANNEL}" ] && APP_FULL_VERSION="${APP_VERSION}-${APP_CHANNEL}"

# Global release version for pipeline
VERSION="${DAEMON_FULL_VERSION}"

COMMIT_HASH="unknown"
if command -v git >/dev/null 2>&1 && [ -d "${SCRIPT_DIR}/.git" ]; then
    COMMIT_HASH="$(git rev-parse --short HEAD 2>/dev/null || echo "unknown")"
fi
BUILD_DATE="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"

print_banner() {
    echo -e "${CYAN}${BOLD}"
    echo "  ================================================================"
    echo "     Synker - Unified Multi-Platform Build Pipeline"
    echo "  ================================================================"
    echo -e "${NC}"
    echo -e "  • Daemon Version : ${GREEN}v${DAEMON_FULL_VERSION}${NC} [${DAEMON_CODENAME}] (${COMMIT_HASH})"
    echo -e "  • App Version    : ${GREEN}v${APP_FULL_VERSION}${NC} [${APP_CODENAME}]"
    echo -e "  • Build Time     : ${BLUE}${BUILD_DATE}${NC}"
    echo -e "  • Host System    : ${YELLOW}${HOST_OS}/${HOST_ARCH}${NC} ($(uname -s -r))"
    echo -e "  • Artifacts      : ${MAGENTA}${DIST_DIR}${NC}"
    echo ""
}

print_help() {
    print_banner
    echo "Usage: ./build.sh [options]"
    echo ""
    echo "Build Targets:"
    echo "  (no args)             Build both Linux daemon (synkerd) and Android app (debug APK)"
    echo "  -d, --daemon          Build only the Linux daemon (synkerd)"
    echo "  -a, --app             Build only the Android app APK"
    echo "  -A, --all             Build daemon (multi-architecture) and Android app"
    echo ""
    echo "Options:"
    echo "  -r, --release         Build in release mode (optimized daemon, release APK)"
    echo "  -m, --multiarch       Cross-compile daemon for linux/amd64 and linux/arm64"
    echo "  -c, --clean           Clean previous build artifacts (dist/, bin/, gradle cache)"
    echo "  -i, --install         Install compiled synkerd locally to ~/.local/bin and restart"
    echo "  -h, --help            Show this help menu"
    echo ""
    echo "Output Naming Conventions in dist/:"
    echo "  • Daemon Binary       : dist/synkerd, dist/synkerd-linux-<arch>"
    echo "  • Android Debug APK   : dist/synker-v<version>-debug.apk (synker-debug.apk)"
    echo "  • Android Release APK : dist/synker-v<version>-release.apk (synker-release.apk)"
    echo "  • SHA-256 Checksums   : dist/SHA256SUMS"
    echo ""
}

clean_artifacts() {
    echo -e "${YELLOW}[*] Cleaning build artifacts...${NC}"
    rm -rf "${DIST_DIR}"
    rm -rf "${DAEMON_DIR}/bin"
    if [ -f "${APP_DIR}/gradlew" ]; then
        (cd "${APP_DIR}" && ./gradlew clean --quiet 2>/dev/null || true)
    fi
    echo -e "${GREEN}[✓] Build workspace cleaned.${NC}"
}

format_bytes() {
    local bytes="$1"
    if [ "${bytes}" -ge 1048576 ]; then
        awk "BEGIN {printf \"%.2f MB\", ${bytes}/1048576}"
    elif [ "${bytes}" -ge 1024 ]; then
        awk "BEGIN {printf \"%.1f KB\", ${bytes}/1024}"
    else
        echo "${bytes} B"
    fi
}

build_daemon_binary() {
    local target_os="$1"
    local target_arch="$2"
    local output_name="$3"
    local target_file="${DIST_DIR}/${output_name}"

    echo -e "${BLUE}[*] Compiling ${output_name} (${target_os}/${target_arch})...${NC}"

    local ldflags="-s -w"
    ldflags="${ldflags} -X main.version=v${VERSION} -X main.commit=${COMMIT_HASH} -X main.buildDate=${BUILD_DATE}"

    local cmd_pkg="./cmd/synkerd"
    if [ ! -d "${DAEMON_DIR}/cmd/synkerd" ] && [ ! -L "${DAEMON_DIR}/cmd/synkerd" ]; then
        cmd_pkg="./cmd/quazaard"
    fi

    (
        cd "${DAEMON_DIR}"
        GOOS="${target_os}" GOARCH="${target_arch}" CGO_ENABLED=0 \
            go build -trimpath -ldflags="${ldflags}" -o "${target_file}" "${cmd_pkg}"
    )

    chmod +x "${target_file}"

    # Also sync to daemon/bin/ for local run convenience
    mkdir -p "${DAEMON_DIR}/bin"
    if [ "${target_os}" = "${HOST_OS}" ] && [ "${target_arch}" = "${HOST_ARCH}" ]; then
        cp "${target_file}" "${DAEMON_DIR}/bin/synkerd"
        cp "${target_file}" "${DAEMON_DIR}/bin/quazaard"
        # Primary un-arch'd binary symlink in dist/
        (cd "${DIST_DIR}" && ln -sf "${output_name}" "synkerd")
    fi

    local fsize
    fsize="$(stat -c%s "${target_file}" 2>/dev/null || stat -f%z "${target_file}" 2>/dev/null || echo 0)"
    echo -e "${GREEN}[✓] Compiled: ${output_name} ($(format_bytes "${fsize}"))${NC}"
}

build_daemon() {
    echo -e "${BOLD}${CYAN}────────────────────────────────────────────────────────────────${NC}"
    echo -e "${BOLD}${CYAN}  1. Building Synker Music Daemon (Go)                          ${NC}"
    echo -e "${BOLD}${CYAN}────────────────────────────────────────────────────────────────${NC}"

    if ! command -v go >/dev/null 2>&1; then
        echo -e "${RED}[ERROR] Go compiler not found in PATH. Please install Go 1.22+.${NC}"
        exit 1
    fi

    local go_ver
    go_ver="$(go version | awk '{print $3}')"
    echo -e "  • Go Compiler : ${YELLOW}${go_ver}${NC}"

    mkdir -p "${DIST_DIR}"

    # Primary host build
    local host_bin_name="synkerd-${HOST_OS}-${HOST_ARCH}"
    build_daemon_binary "${HOST_OS}" "${HOST_ARCH}" "${host_bin_name}"

    # Multi-architecture builds if requested
    if [ "${MULTIARCH}" = true ]; then
        if [ "${HOST_ARCH}" != "amd64" ]; then
            build_daemon_binary "linux" "amd64" "synkerd-linux-amd64"
        fi
        if [ "${HOST_ARCH}" != "arm64" ]; then
            build_daemon_binary "linux" "arm64" "synkerd-linux-arm64"
        fi
    fi
}

build_app() {
    echo ""
    echo -e "${BOLD}${CYAN}────────────────────────────────────────────────────────────────${NC}"
    echo -e "${BOLD}${CYAN}  2. Building Synker Android App (Gradle)                       ${NC}"
    echo -e "${BOLD}${CYAN}────────────────────────────────────────────────────────────────${NC}"

    if [ ! -f "${APP_DIR}/gradlew" ]; then
        echo -e "${RED}[ERROR] gradlew not found in ${APP_DIR}.${NC}"
        exit 1
    fi

    local build_task="assembleDebug"
    local build_type="debug"
    if [ "${RELEASE_MODE}" = true ]; then
        build_task="assembleRelease"
        build_type="release"
    fi

    echo -e "  • Gradle Task : ${YELLOW}:${build_task}${NC}"
    echo -e "  • Build Mode  : ${YELLOW}${build_type}${NC}"

    (
        cd "${APP_DIR}"
        ./gradlew "${build_task}" --no-daemon
    )

    # Locate generated APK
    local src_apk=""
    if [ "${build_type}" = "release" ]; then
        src_apk="$(find "${APP_DIR}/app/build/outputs/apk/release" -name "*.apk" 2>/dev/null | head -n 1 || true)"
    else
        src_apk="$(find "${APP_DIR}/app/build/outputs/apk/debug" -name "*.apk" 2>/dev/null | head -n 1 || true)"
    fi

    if [ -n "${src_apk}" ] && [ -f "${src_apk}" ]; then
        local versioned_apk_name="synker-v${APP_FULL_VERSION}-${build_type}.apk"
        local generic_apk_name="synker-${build_type}.apk"

        cp "${src_apk}" "${DIST_DIR}/${versioned_apk_name}"
        (cd "${DIST_DIR}" && ln -sf "${versioned_apk_name}" "${generic_apk_name}")

        local apk_size
        apk_size="$(stat -c%s "${DIST_DIR}/${versioned_apk_name}" 2>/dev/null || stat -f%z "${DIST_DIR}/${versioned_apk_name}" 2>/dev/null || echo 0)"
        echo -e "${GREEN}[✓] Generated APK: ${versioned_apk_name} ($(format_bytes "${apk_size}"))${NC}"
    else
        echo -e "${RED}[ERROR] Could not locate generated APK output.${NC}"
        exit 1
    fi
}

generate_checksums() {
    echo ""
    echo -e "${BLUE}[*] Calculating SHA-256 artifact checksums...${NC}"
    if command -v sha256sum >/dev/null 2>&1; then
        (
            cd "${DIST_DIR}"
            rm -f SHA256SUMS
            # Checksum only regular files (skip symlinks)
            find . -maxdepth 1 -type f ! -name "SHA256SUMS" -printf "%P\n" | sort | while read -r file; do
                sha256sum "${file}"
            done > SHA256SUMS
        )
        echo -e "${GREEN}[✓] Checksums saved to: ${DIST_DIR}/SHA256SUMS${NC}"
    fi
}

display_summary() {
    local end_time
    end_time=$(date +%s)
    local duration=$((end_time - START_TIME))

    echo ""
    echo -e "${GREEN}${BOLD}================================================================${NC}"
    echo -e "${GREEN}${BOLD}  Build Completed Successfully in ${duration}s!                            ${NC}"
    echo -e "${GREEN}${BOLD}================================================================${NC}"
    echo ""
    echo -e "${BOLD}Artifacts in ${MAGENTA}${DIST_DIR}/${NC}:"
    echo -e "────────────────────────────────────────────────────────────────"

    if [ -d "${DIST_DIR}" ]; then
        for f in "${DIST_DIR}"/*; do
            if [ -f "${f}" ] && [ "$(basename "${f}")" != "SHA256SUMS" ]; then
                local bname
                bname="$(basename "${f}")"
                local sz
                sz="$(stat -c%s "${f}" 2>/dev/null || stat -f%z "${f}" 2>/dev/null || echo 0)"
                local h="-"
                if command -v sha256sum >/dev/null 2>&1; then
                    h="$(sha256sum "${f}" | awk '{print substr($1,1,16)"..."}')"
                fi
                printf "  %-32s %-12s %s\n" "${bname}" "($(format_bytes "${sz}"))" "sha256:${h}"
            fi
        done
    fi

    echo -e "────────────────────────────────────────────────────────────────"
    echo ""
    echo -e "${BOLD}Quick Actions:${NC}"
    if [ -f "${DIST_DIR}/synkerd" ]; then
        echo -e "  • Run Daemon Locally  : ${CYAN}./dist/synkerd${NC}"
        echo -e "  • Install to systemd  : ${CYAN}./install.sh${NC}"
    fi
    if [ -f "${DIST_DIR}/synker-debug.apk" ] || [ -f "${DIST_DIR}/synker-release.apk" ]; then
        local apk_file="dist/synker-debug.apk"
        [ -f "${DIST_DIR}/synker-release.apk" ] && apk_file="dist/synker-release.apk"
        echo -e "  • Install App via ADB : ${CYAN}adb install -r ${apk_file}${NC}"
    fi
    echo ""
}

install_daemon_locally() {
    echo -e "${BLUE}[*] Installing synkerd binary locally (~/.local/bin)...${NC}"
    mkdir -p "${HOME}/.local/bin"
    if [ -f "${DIST_DIR}/synkerd" ]; then
        # Use install with temporary file or stop service first to avoid 'Text file busy'
        local was_running=false
        if systemctl --user is-active --quiet synkerd.service 2>/dev/null; then
            was_running=true
            systemctl --user stop synkerd.service || true
        fi

        install -m 755 "${DIST_DIR}/synkerd" "${HOME}/.local/bin/synkerd"
        ln -sf "synkerd" "${HOME}/.local/bin/quazaard"
        echo -e "${GREEN}[✓] Installed to: ${HOME}/.local/bin/synkerd${NC}"

        if [ "${was_running}" = true ]; then
            echo -e "${BLUE}[*] Restarting running synkerd.service...${NC}"
            systemctl --user start synkerd.service || true
            echo -e "${GREEN}[✓] synkerd.service reloaded with new build.${NC}"
        fi
    fi
}

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case "$1" in
        -d|--daemon)
            BUILD_DAEMON=true
            shift
            ;;
        -a|--app)
            BUILD_APP=true
            shift
            ;;
        -A|--all)
            BUILD_DAEMON=true
            BUILD_APP=true
            MULTIARCH=true
            shift
            ;;
        -m|--multiarch)
            MULTIARCH=true
            shift
            ;;
        -r|--release)
            RELEASE_MODE=true
            shift
            ;;
        -c|--clean)
            CLEAN_FIRST=true
            shift
            ;;
        -i|--install)
            INSTALL_AFTER=true
            shift
            ;;
        -h|--help)
            print_help
            exit 0
            ;;
        *)
            echo -e "${RED}[ERROR] Unknown argument: $1${NC}"
            echo "Run ./build.sh --help for available options."
            exit 1
            ;;
    esac
done

# Default to building both if neither is explicitly selected
if [ "${BUILD_DAEMON}" = false ] && [ "${BUILD_APP}" = false ]; then
    BUILD_DAEMON=true
    BUILD_APP=true
fi

print_banner

if [ "${CLEAN_FIRST}" = true ]; then
    clean_artifacts
fi

mkdir -p "${DIST_DIR}"

if [ "${BUILD_DAEMON}" = true ]; then
    build_daemon
fi

if [ "${BUILD_APP}" = true ]; then
    build_app
fi

generate_checksums

if [ "${INSTALL_AFTER}" = true ] && [ "${BUILD_DAEMON}" = true ]; then
    install_daemon_locally
fi

display_summary
