#!/usr/bin/env bash
# ==============================================================================
# Synker Music Daemon - Linux Installer & systemd Auto-Start
# Repository: https://github.com/codershubinc/synker
# ==============================================================================
# Usage:
#   # Quick install via curl:
#   curl -sSL https://raw.githubusercontent.com/codershubinc/synker/main/install.sh | bash
#
#   # Local execution:
#   ./install.sh                     Install latest release & setup auto-start
#   ./install.sh -v daemon-v0.0.1-beta Install specific release version
#   ./install.sh -p 5050             Install with custom port (default: 4242)
#   ./install.sh --status            Check service status
#   ./install.sh --logs              Stream live service logs
#   ./install.sh --uninstall         Stop & remove service cleanly
# ==============================================================================

set -euo pipefail

GITHUB_REPO="codershubinc/synker"
DEFAULT_TAG="daemon-v0.0.2-beta"
TARGET_TAG="${DEFAULT_TAG}"
DEFAULT_PORT=4242
PORT="${DEFAULT_PORT}"
FORCE_RELEASE=false

BIN_DIR="${HOME}/.local/bin"
SERVICE_DIR="${HOME}/.config/systemd/user"
CONFIG_DIR="${HOME}/.config/synker"
LEGACY_CONFIG_DIR="${HOME}/.config/quazaar"
SERVICE_NAME="synkerd.service"
LEGACY_SERVICE_NAME="quazaard.service"
TARGET_BIN="${BIN_DIR}/synkerd"
LEGACY_BIN="${BIN_DIR}/quazaard"

# Colors for terminal formatting
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

print_banner() {
    echo -e "${CYAN}${BOLD}"
    echo "  ================================================================"
    echo "    Synker Music Daemon - Linux Auto-Start Installer"
    echo "    Repository: https://github.com/${GITHUB_REPO}"
    echo "  ================================================================"
    echo -e "${NC}"
}

detect_arch() {
    local raw_arch
    raw_arch="$(uname -m)"
    case "${raw_arch}" in
        x86_64|amd64)
            ARCH="amd64"
            ;;
        aarch64|arm64)
            ARCH="arm64"
            ;;
        armv7*|armhf)
            ARCH="armv7"
            ;;
        i386|i686)
            ARCH="386"
            ;;
        *)
            echo -e "${RED}[ERROR] Unsupported architecture: ${raw_arch}${NC}"
            exit 1
            ;;
    esac
    OS="linux"
}

check_prerequisites() {
    echo -e "${BLUE}[*] Checking system dependencies...${NC}"
    if ! command -v curl >/dev/null 2>&1 && ! command -v wget >/dev/null 2>&1; then
        echo -e "${RED}[ERROR] Neither curl nor wget was found. Please install curl or wget.${NC}"
        exit 1
    fi

    if ! command -v systemctl >/dev/null 2>&1; then
        echo -e "${RED}[ERROR] systemd is required to manage auto-start services.${NC}"
        exit 1
    fi
    echo -e "${GREEN}[✓] Prerequisites verified (systemd, ${OS}/${ARCH}).${NC}"
}

download_file() {
    local url="$1"
    local dest="$2"
    if command -v curl >/dev/null 2>&1; then
        curl -sSL --fail -o "${dest}" "${url}"
    else
        wget -q -O "${dest}" "${url}"
    fi
}

migrate_legacy_data() {
    # If legacy ~/.config/quazaar exists and ~/.config/synker does not, migrate seamlessly
    if [ -d "${LEGACY_CONFIG_DIR}" ] && [ ! -d "${CONFIG_DIR}" ] && [ ! -L "${CONFIG_DIR}" ]; then
        echo -e "${CYAN}[*] Migrating existing database from ${LEGACY_CONFIG_DIR} to ${CONFIG_DIR}...${NC}"
        mkdir -p "$(dirname "${CONFIG_DIR}")"
        cp -r "${LEGACY_CONFIG_DIR}" "${CONFIG_DIR}"
        echo -e "${GREEN}[✓] Synker data preserved in ${CONFIG_DIR}.${NC}"
    fi
    mkdir -p "${CONFIG_DIR}"

    # Stop legacy quazaard service if currently active
    if systemctl --user is-active --quiet "${LEGACY_SERVICE_NAME}" 2>/dev/null; then
        echo -e "${YELLOW}[!] Stopping legacy ${LEGACY_SERVICE_NAME}...${NC}"
        systemctl --user stop "${LEGACY_SERVICE_NAME}" 2>/dev/null || true
    fi
    if systemctl --user is-enabled --quiet "${LEGACY_SERVICE_NAME}" 2>/dev/null; then
        systemctl --user disable "${LEGACY_SERVICE_NAME}" 2>/dev/null || true
    fi
    rm -f "${SERVICE_DIR}/${LEGACY_SERVICE_NAME}"
}

fetch_binary_from_release() {
    echo -e "${BLUE}[*] Resolving release binary from GitHub (${GITHUB_REPO})...${NC}"
    mkdir -p "${BIN_DIR}"
    local tmp_bin
    tmp_bin="$(mktemp)"

    local script_dir
    script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" 2>/dev/null && pwd || echo "")"
    local has_local_source=false
    if [ -d "${script_dir}/daemon" ] && command -v go >/dev/null 2>&1; then
        has_local_source=true
    fi

    # If running locally from repo and release download wasn't explicitly forced, build from local source
    if [ "${FORCE_RELEASE}" = false ] && [ "${has_local_source}" = true ]; then
        echo -e "${BLUE}[*] Local repository detected. Compiling synkerd from local source...${NC}"
        local cmd_pkg="./cmd/synkerd"
        if (cd "${script_dir}/daemon" && go build -trimpath -ldflags="-s -w" -o "${tmp_bin}" "${cmd_pkg}"); then
            download_success=true
            echo -e "${GREEN}[✓] Built synkerd successfully from local source.${NC}"
        fi
    fi

    # Strategy 1: Query GitHub API for matching release assets (when remote install or forced release)
    if [ "${download_success}" = false ]; then
        local api_url="https://api.github.com/repos/${GITHUB_REPO}/releases"
        local json_data=""
        if command -v curl >/dev/null 2>&1; then
            json_data="$(curl -sSL "${api_url}" 2>/dev/null || true)"
        fi

        if [ -n "${json_data}" ] && [ "${json_data}" != "[]" ]; then
            # Search for asset URLs matching architecture, synkerd, or daemon
            local asset_url
            asset_url="$(echo "${json_data}" | grep -oE "https://github.com/${GITHUB_REPO}/releases/download/[^\"]+(${ARCH}|synkerd|synker|quazaard|daemon)[^\"]*" | head -n 1 || true)"
            
            if [ -n "${asset_url}" ]; then
                echo -e "${CYAN}[*] Downloading from release asset: ${asset_url}${NC}"
                if download_file "${asset_url}" "${tmp_bin}"; then
                    download_success=true
                fi
            fi
        fi
    fi

    # Strategy 2: Direct release download fallback pattern
    if [ "${download_success}" = false ]; then
        local direct_urls=(
            "https://github.com/${GITHUB_REPO}/releases/download/${TARGET_TAG}/synkerd-linux-${ARCH}"
            "https://github.com/${GITHUB_REPO}/releases/download/${TARGET_TAG}/synkerd"
            "https://github.com/${GITHUB_REPO}/releases/download/${TARGET_TAG}/daemon-linux-${ARCH}"
            "https://github.com/${GITHUB_REPO}/releases/latest/download/synkerd-linux-${ARCH}"
            "https://github.com/${GITHUB_REPO}/releases/latest/download/synkerd"
        )

        for url in "${direct_urls[@]}"; do
            echo -e "${CYAN}[*] Trying direct release URL: ${url}${NC}"
            if download_file "${url}" "${tmp_bin}" 2>/dev/null; then
                if [ -s "${tmp_bin}" ]; then
                    download_success=true
                    break
                fi
            fi
        done
    fi

    # Strategy 3: Local build fallback if not already tried
    if [ "${download_success}" = false ] && [ "${has_local_source}" = true ]; then
        echo -e "${BLUE}[*] Compiling synkerd from local source using Go...${NC}"
        local cmd_pkg="./cmd/synkerd"
        (cd "${script_dir}/daemon" && go build -trimpath -ldflags="-s -w" -o "${tmp_bin}" "${cmd_pkg}")
        download_success=true
    fi

    if [ "${download_success}" = false ]; then
        echo -e "${RED}[ERROR] Could not fetch release binary for ${TARGET_TAG} (${ARCH}).${NC}"
        echo -e "Please ensure the release exists at: https://github.com/${GITHUB_REPO}/releases"
        rm -f "${tmp_bin}"
        exit 1
    fi

    mv "${tmp_bin}" "${TARGET_BIN}"
    chmod +x "${TARGET_BIN}"


    echo -e "${GREEN}[✓] Synker daemon binary installed to: ${TARGET_BIN}${NC}"
}

setup_systemd_service() {
    echo -e "${BLUE}[*] Configuring systemd user service for auto-start...${NC}"
    mkdir -p "${SERVICE_DIR}"
    mkdir -p "${CONFIG_DIR}"

    cat <<EOF > "${SERVICE_DIR}/${SERVICE_NAME}"
[Unit]
Description=Synker Music Daemon (Apple Music & MPRIS Sync)
Documentation=https://github.com/${GITHUB_REPO}
After=network.target sound.target
PartOf=graphical-session.target

[Service]
Type=simple
ExecStart=${TARGET_BIN} -port ${PORT} -config ${CONFIG_DIR}
Restart=always
RestartSec=3s
Environment=PORT=${PORT}
Environment=CONFIG_DIR=${CONFIG_DIR}
LimitNOFILE=65535

[Install]
WantedBy=default.target
EOF

    echo -e "${GREEN}[✓] Service configuration written to: ${SERVICE_DIR}/${SERVICE_NAME}${NC}"

    systemctl --user daemon-reload
    systemctl --user enable "${SERVICE_NAME}"
    systemctl --user restart "${SERVICE_NAME}"

    # Enable lingering so systemd starts user service on system boot before desktop login
    if command -v loginctl >/dev/null 2>&1; then
        loginctl enable-linger "${USER}" 2>/dev/null || true
    fi

    echo -e "${GREEN}[✓] Service enabled and running (configured for auto-start on boot).${NC}"
}

show_status() {
    echo -e "${BLUE}[*] Synker daemon service status:${NC}"
    systemctl --user status "${SERVICE_NAME}" --no-pager || true
}

show_logs() {
    echo -e "${BLUE}[*] Streaming logs for ${SERVICE_NAME} (Ctrl+C to exit):${NC}"
    journalctl --user -u "${SERVICE_NAME}" -f
}

uninstall_service() {
    echo -e "${YELLOW}[!] Removing Synker daemon service...${NC}"
    
    # Stop and disable synkerd
    if systemctl --user is-active --quiet "${SERVICE_NAME}" 2>/dev/null; then
        systemctl --user stop "${SERVICE_NAME}"
    fi
    if systemctl --user is-enabled --quiet "${SERVICE_NAME}" 2>/dev/null; then
        systemctl --user disable "${SERVICE_NAME}"
    fi
    rm -f "${SERVICE_DIR}/${SERVICE_NAME}"

    # Clean legacy quazaard service if present
    if systemctl --user is-active --quiet "${LEGACY_SERVICE_NAME}" 2>/dev/null; then
        systemctl --user stop "${LEGACY_SERVICE_NAME}"
    fi
    if systemctl --user is-enabled --quiet "${LEGACY_SERVICE_NAME}" 2>/dev/null; then
        systemctl --user disable "${LEGACY_SERVICE_NAME}"
    fi
    rm -f "${SERVICE_DIR}/${LEGACY_SERVICE_NAME}"

    systemctl --user daemon-reload 2>/dev/null || true

    if [ -f "${TARGET_BIN}" ]; then
        rm -f "${TARGET_BIN}"
        echo -e "${GREEN}[✓] Removed binary: ${TARGET_BIN}${NC}"
    fi
    if [ -L "${LEGACY_BIN}" ] || [ -f "${LEGACY_BIN}" ]; then
        rm -f "${LEGACY_BIN}"
        echo -e "${GREEN}[✓] Removed legacy binary alias: ${LEGACY_BIN}${NC}"
    fi

    echo -e "${GREEN}[✓] Synker service successfully uninstalled.${NC}"
    echo -e "${CYAN}Note: Your listening database and artwork in ${CONFIG_DIR} were preserved.${NC}"
}

# Parse command line options
ACTION="install"
while [[ $# -gt 0 ]]; do
    case "$1" in
        -v|--version|--tag)
            TARGET_TAG="$2"
            shift 2
            ;;
        -p|--port)
            PORT="$2"
            shift 2
            ;;
        -r|--release)
            FORCE_RELEASE=true
            shift
            ;;
        -b|--build|--local)
            FORCE_RELEASE=false
            shift
            ;;
        -s|--status)
            ACTION="status"
            shift
            ;;
        -l|--logs)
            ACTION="logs"
            shift
            ;;
        -u|--uninstall)
            ACTION="uninstall"
            shift
            ;;
        -h|--help)
            print_banner
            echo "Usage: ./install.sh [options]"
            echo ""
            echo "Options:"
            echo "  (no args)               Install synkerd & setup systemd auto-start"
            echo "  -b, --build, --local    Build & install from local repository source using Go"
            echo "  -r, --release           Force download precompiled release binary from GitHub"
            echo "  -v, --version <tag>     Install specific release tag (default: ${DEFAULT_TAG})"
            echo "  -p, --port <port>       Daemon Web UI / REST port (default: ${DEFAULT_PORT})"
            echo "  -s, --status            Inspect systemd service status"
            echo "  -l, --logs              Follow live systemd service logs"
            echo "  -u, --uninstall         Stop & remove service from auto-start"
            echo "  -h, --help              Show this help menu"
            exit 0
            ;;
        *)
            echo -e "${RED}[ERROR] Unknown argument: $1${NC}"
            echo "Run ./install.sh --help for available options."
            exit 1
            ;;
    esac
done

case "${ACTION}" in
    status)
        show_status
        ;;
    logs)
        show_logs
        ;;
    uninstall)
        uninstall_service
        ;;
    install)
        print_banner
        detect_arch
        check_prerequisites
        migrate_legacy_data
        fetch_binary_from_release
        setup_systemd_service

        echo ""
        echo -e "${GREEN}${BOLD}================================================================${NC}"
        echo -e "${GREEN}${BOLD}  Synker Daemon (synkerd) is active & configured to auto-start! ${NC}"
        echo -e "${GREEN}${BOLD}================================================================${NC}"
        echo -e "  • Web Dashboard & API : ${CYAN}http://localhost:${PORT}${NC}"
        echo -e "  • REST Current Song   : ${CYAN}http://localhost:${PORT}/api/v1/current${NC}"
        echo -e "  • Dynamic SVG Badge   : ${CYAN}http://localhost:${PORT}/api/v1/current.svg${NC}"
        echo -e "  • WebSocket Stream    : ${CYAN}ws://localhost:${PORT}/ws/v1/live${NC}"
        echo -e "  • Database & Artworks : ${CYAN}${CONFIG_DIR}${NC}"
        echo ""
        echo -e "${BOLD}Helpful Commands:${NC}"
        echo -e "  Status  : ${YELLOW}synkerd status${NC}   or  ${YELLOW}systemctl --user status ${SERVICE_NAME}${NC}"
        echo -e "  Logs    : ${YELLOW}synkerd logs${NC}     or  ${YELLOW}journalctl --user -u ${SERVICE_NAME} -f${NC}"
        echo -e "  Restart : ${YELLOW}synkerd restart${NC}  or  ${YELLOW}systemctl --user restart ${SERVICE_NAME}${NC}"
        echo -e "  Stop    : ${YELLOW}synkerd stop${NC}     or  ${YELLOW}systemctl --user stop ${SERVICE_NAME}${NC}"
        echo -e "  Remove  : ${YELLOW}synkerd uninstall${NC}"
        echo ""
        ;;
esac
