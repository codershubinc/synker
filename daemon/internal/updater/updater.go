package updater

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"syscall"
	"time"
)

const (
	GithubOwner      = "codershubinc"
	GithubRepo       = "synker"
	LatestReleaseURL = "https://api.github.com/repos/" + GithubOwner + "/" + GithubRepo + "/releases/latest"
)

type ReleaseInfo struct {
	HasUpdate      bool      `json:"has_update"`
	CurrentVersion string    `json:"current_version"`
	LatestVersion  string    `json:"latest_version"`
	ReleaseName    string    `json:"release_name"`
	ReleaseNotes   string    `json:"release_notes"`
	AssetURL       string    `json:"asset_url"`
	AssetName      string    `json:"asset_name"`
	HTMLURL        string    `json:"html_url"`
	PublishedAt    time.Time `json:"published_at"`
}

// CheckForUpdate queries GitHub for the latest synkerd release binary.
func CheckForUpdate(currentVersion string) (*ReleaseInfo, error) {
	client := &http.Client{Timeout: 10 * time.Second}
	req, err := http.NewRequest("GET", LatestReleaseURL, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/vnd.github.v3+json")
	req.Header.Set("User-Agent", "Synker-Daemon")

	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("failed to query GitHub API: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("GitHub API returned status HTTP %d", resp.StatusCode)
	}

	var data struct {
		TagName     string    `json:"tag_name"`
		Name        string    `json:"name"`
		Body        string    `json:"body"`
		HTMLURL     string    `json:"html_url"`
		PublishedAt time.Time `json:"published_at"`
		Assets      []struct {
			Name               string `json:"name"`
			BrowserDownloadURL string `json:"browser_download_url"`
		} `json:"assets"`
	}

	if err := json.NewDecoder(resp.Body).Decode(&data); err != nil {
		return nil, fmt.Errorf("failed to decode release JSON: %w", err)
	}

	cleanRemote := strings.TrimPrefix(data.TagName, "v")
	cleanCurrent := strings.TrimPrefix(currentVersion, "v")

	// Target asset name: synkerd-<os>-<arch>, e.g. synkerd-linux-amd64
	expectedAsset := fmt.Sprintf("synkerd-%s-%s", runtime.GOOS, runtime.GOARCH)

	var downloadURL, foundAsset string
	for _, asset := range data.Assets {
		if strings.EqualFold(asset.Name, expectedAsset) {
			downloadURL = asset.BrowserDownloadURL
			foundAsset = asset.Name
			break
		}
	}
	// Fallback to binary named "synkerd"
	if downloadURL == "" {
		for _, asset := range data.Assets {
			if strings.EqualFold(asset.Name, "synkerd") {
				downloadURL = asset.BrowserDownloadURL
				foundAsset = asset.Name
				break
			}
		}
	}

	hasUpdate := isNewerVersion(cleanCurrent, cleanRemote)

	return &ReleaseInfo{
		HasUpdate:      hasUpdate,
		CurrentVersion: currentVersion,
		LatestVersion:  cleanRemote,
		ReleaseName:    data.Name,
		ReleaseNotes:   data.Body,
		AssetURL:       downloadURL,
		AssetName:      foundAsset,
		HTMLURL:        data.HTMLURL,
		PublishedAt:    data.PublishedAt,
	}, nil
}

// DownloadAndApplyUpdate downloads the binary and atomically replaces the currently executing file.
func DownloadAndApplyUpdate(downloadURL string) error {
	if downloadURL == "" {
		return fmt.Errorf("no matching binary asset found for %s/%s", runtime.GOOS, runtime.GOARCH)
	}

	exePath, err := os.Executable()
	if err != nil {
		return fmt.Errorf("failed to get executable path: %w", err)
	}
	realExePath, err := filepath.EvalSymlinks(exePath)
	if err == nil {
		exePath = realExePath
	}

	targetDir := filepath.Dir(exePath)
	tempFile, err := os.CreateTemp(targetDir, "synkerd-update-*")
	if err != nil {
		// Target directory might not be writable, fallback to system temp
		tempFile, err = os.CreateTemp("", "synkerd-update-*")
		if err != nil {
			return fmt.Errorf("failed to create temporary file: %w", err)
		}
	}
	tempPath := tempFile.Name()
	defer os.Remove(tempPath)

	client := &http.Client{Timeout: 60 * time.Second}
	resp, err := client.Get(downloadURL)
	if err != nil {
		_ = tempFile.Close()
		return fmt.Errorf("failed to download update: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		_ = tempFile.Close()
		return fmt.Errorf("update download returned HTTP %d", resp.StatusCode)
	}

	_, err = io.Copy(tempFile, resp.Body)
	_ = tempFile.Close()
	if err != nil {
		return fmt.Errorf("failed to write update binary: %w", err)
	}

	if err := os.Chmod(tempPath, 0755); err != nil {
		return fmt.Errorf("failed to set executable permission: %w", err)
	}

	// Try atomic rename first
	if err := os.Rename(tempPath, exePath); err != nil {
		// Cross-device link or permission issue: copy bytes directly into destination file
		src, err := os.Open(tempPath)
		if err != nil {
			return fmt.Errorf("failed to open downloaded binary: %w", err)
		}
		defer src.Close()

		dst, err := os.OpenFile(exePath, os.O_WRONLY|os.O_TRUNC, 0755)
		if err != nil {
			return fmt.Errorf("failed to overwrite %s (check write permissions / sudo): %w", exePath, err)
		}
		defer dst.Close()

		if _, err := io.Copy(dst, src); err != nil {
			return fmt.Errorf("failed to copy binary to %s: %w", exePath, err)
		}
	}

	log.Printf("[Updater] Successfully updated %s with new binary", exePath)
	return nil
}

// RestartDaemon restarts the current process in-place using syscall.Exec.
func RestartDaemon() error {
	exePath, err := os.Executable()
	if err != nil {
		return err
	}
	realExePath, err := filepath.EvalSymlinks(exePath)
	if err == nil {
		exePath = realExePath
	}

	log.Printf("[Updater] Restarting synkerd process in-place: %s", exePath)
	return syscall.Exec(exePath, os.Args, os.Environ())
}

// isNewerVersion compares two semver strings (e.g. 0.0.1 vs 0.0.2)
func isNewerVersion(current, remote string) bool {
	if remote == "" {
		return false
	}
	if current == "0.0.1-dev" || current == "dev" {
		// Dev version allows updates if remote tag exists
		return true
	}

	cParts := splitVersion(current)
	rParts := splitVersion(remote)

	maxLen := len(cParts)
	if len(rParts) > maxLen {
		maxLen = len(rParts)
	}

	for i := 0; i < maxLen; i++ {
		var c, r int
		if i < len(cParts) {
			c = cParts[i]
		}
		if i < len(rParts) {
			r = rParts[i]
		}
		if r > c {
			return true
		}
		if r < c {
			return false
		}
	}
	return false
}

func splitVersion(v string) []int {
	v = strings.TrimPrefix(v, "v")
	v = strings.Split(v, "-")[0]
	parts := strings.Split(v, ".")
	var res []int
	for _, p := range parts {
		if n, err := strconv.Atoi(p); err == nil {
			res = append(res, n)
		}
	}
	return res
}
