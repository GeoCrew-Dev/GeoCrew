// Package update checks for new server releases and installs them in place.
//
// A release is a GitHub-style release whose assets include one binary per
// platform, named by AssetName, and a SHA256SUMS file listing their hashes.
// Install never trusts a download it cannot check against that list.
//
// Installing replaces the running program file and records a State in the
// data directory. The next start of the new program is a trial: if it fails
// before it answers its own health check, it puts the old program and the
// database snapshot back and starts the old program again (Recover). A
// program that keeps crashing during its trial is rolled back on the start
// after that, so a supervisor such as systemd ends up on the old version.
package update

import (
	"bufio"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"time"
)

// DefaultURL is the release feed of the reference server.
const DefaultURL = "https://api.github.com/repos/GeoCrew-Dev/GeoCrew-Server/releases/latest"

// SumsName is the asset that lists "sha256  name" for every other asset.
const SumsName = "SHA256SUMS"

const (
	maxFeed   = 1 << 20
	maxSums   = 64 << 10
	maxBinary = 256 << 20
)

// Release is one published version.
type Release struct {
	Version     string    `json:"version"` // without a leading v
	Tag         string    `json:"tag"`
	Name        string    `json:"name,omitempty"`
	PublishedAt time.Time `json:"published_at"`
	Notes       string    `json:"notes"`
	URL         string    `json:"url,omitempty"`
	// Assets maps file names to download URLs.
	Assets map[string]string `json:"assets"`
}

// AssetName is the binary asset for a platform, e.g.
// geocrew-server-linux-amd64 (with .exe on Windows).
func AssetName(goos, goarch string) string {
	n := "geocrew-server-" + goos + "-" + goarch
	if goos == "windows" {
		n += ".exe"
	}
	return n
}

// Checker reads the release feed.
type Checker struct {
	// URL returns the latest release as GitHub's API does; empty uses DefaultURL.
	URL    string
	Client *http.Client
}

func (c *Checker) client() *http.Client {
	if c.Client != nil {
		return c.Client
	}
	return &http.Client{Timeout: 30 * time.Second}
}

// Latest fetches the newest release.
func (c *Checker) Latest(ctx context.Context) (Release, error) {
	url := c.URL
	if url == "" {
		url = DefaultURL
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return Release{}, err
	}
	req.Header.Set("Accept", "application/vnd.github+json")
	req.Header.Set("User-Agent", "geocrew-server-update")
	resp, err := c.client().Do(req)
	if err != nil {
		return Release{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotFound {
		return Release{}, ErrNoRelease
	}
	if resp.StatusCode != http.StatusOK {
		return Release{}, fmt.Errorf("release feed returned %d", resp.StatusCode)
	}
	var raw struct {
		Tag         string    `json:"tag_name"`
		Name        string    `json:"name"`
		Body        string    `json:"body"`
		HTMLURL     string    `json:"html_url"`
		PublishedAt time.Time `json:"published_at"`
		Draft       bool      `json:"draft"`
		Prerelease  bool      `json:"prerelease"`
		Assets      []struct {
			Name string `json:"name"`
			URL  string `json:"browser_download_url"`
		} `json:"assets"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, maxFeed)).Decode(&raw); err != nil {
		return Release{}, fmt.Errorf("release feed: %w", err)
	}
	if raw.Tag == "" || raw.Draft {
		return Release{}, ErrNoRelease
	}
	r := Release{
		Version: strings.TrimPrefix(raw.Tag, "v"), Tag: raw.Tag, Name: raw.Name,
		PublishedAt: raw.PublishedAt, Notes: raw.Body, URL: raw.HTMLURL, Assets: map[string]string{},
	}
	for _, a := range raw.Assets {
		r.Assets[a.Name] = a.URL
	}
	return r, nil
}

// ErrNoRelease means the feed has no published release yet.
var ErrNoRelease = errors.New("no release published yet")

// Compare orders two versions like 1.2.3, 1.2.3-rc.1 or v1.2: -1, 0 or 1.
// A pre-release sorts before its release. A version that does not parse
// (a development build) sorts before every one that does.
func Compare(a, b string) int {
	pa, oka := parse(a)
	pb, okb := parse(b)
	switch {
	case !oka && !okb:
		return 0
	case !oka:
		return -1
	case !okb:
		return 1
	}
	for i := 0; i < 3; i++ {
		if pa.n[i] != pb.n[i] {
			if pa.n[i] < pb.n[i] {
				return -1
			}
			return 1
		}
	}
	switch {
	case pa.pre == pb.pre:
		return 0
	case pa.pre == "":
		return 1
	case pb.pre == "":
		return -1
	case pa.pre < pb.pre:
		return -1
	}
	return 1
}

// Newer reports whether latest is a newer release than current. A
// development build ("dev" or a commit hash) is never offered an update,
// since replacing it would throw away whatever it was built from.
func Newer(latest, current string) bool {
	if _, ok := parse(current); !ok {
		return false
	}
	return Compare(latest, current) > 0
}

// IsRelease reports whether v is a release version rather than a
// development build.
func IsRelease(v string) bool {
	_, ok := parse(v)
	return ok
}

type ver struct {
	n   [3]int
	pre string
}

func parse(s string) (ver, bool) {
	s = strings.TrimPrefix(strings.TrimSpace(s), "v")
	var v ver
	if i := strings.IndexByte(s, '+'); i >= 0 {
		s = s[:i]
	}
	if i := strings.IndexByte(s, '-'); i >= 0 {
		v.pre, s = s[i+1:], s[:i]
	}
	parts := strings.Split(s, ".")
	if len(parts) == 0 || len(parts) > 3 {
		return v, false
	}
	for i, p := range parts {
		n, err := strconv.Atoi(p)
		if err != nil || n < 0 {
			return v, false
		}
		v.n[i] = n
	}
	return v, true
}

// Download fetches the platform's binary of r next to dest, checks its hash
// against the release's SHA256SUMS and that it runs and reports r's
// version, and returns the checked file's path. The caller installs or
// removes it.
func Download(ctx context.Context, client *http.Client, r Release, goos, goarch, dest string) (string, error) {
	if client == nil {
		client = &http.Client{Timeout: 10 * time.Minute}
	}
	name := AssetName(goos, goarch)
	binURL, ok := r.Assets[name]
	if !ok {
		return "", fmt.Errorf("版本 %s 没有 %s 的程序（%s）", r.Version, goos+"/"+goarch, name)
	}
	sumsURL, ok := r.Assets[SumsName]
	if !ok {
		return "", fmt.Errorf("版本 %s 没有附 %s，无法校验，不安装", r.Version, SumsName)
	}
	sums, err := fetch(ctx, client, sumsURL, maxSums)
	if err != nil {
		return "", fmt.Errorf("%s: %w", SumsName, err)
	}
	want, err := SumFor(sums, name)
	if err != nil {
		return "", err
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, binURL, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("User-Agent", "geocrew-server-update")
	resp, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("下载 %s 返回 %d", name, resp.StatusCode)
	}
	tmp := filepath.Join(filepath.Dir(dest), "."+filepath.Base(dest)+".new")
	f, err := os.OpenFile(tmp, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o755)
	if err != nil {
		return "", fmt.Errorf("程序所在目录不可写：%w", err)
	}
	h := sha256.New()
	n, err := io.Copy(io.MultiWriter(f, h), io.LimitReader(resp.Body, maxBinary+1))
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	if err == nil && n > maxBinary {
		err = errors.New("下载的程序过大")
	}
	if err == nil {
		if got := hex.EncodeToString(h.Sum(nil)); got != want {
			err = fmt.Errorf("校验值不符（%s 应为 %s，实际 %s），已丢弃", name, want, got)
		}
	}
	if err == nil && goos == runtime.GOOS && goarch == runtime.GOARCH {
		err = Probe(ctx, tmp, r.Version)
	}
	if err != nil {
		os.Remove(tmp)
		return "", err
	}
	return tmp, nil
}

// SumFor finds name's hash in a SHA256SUMS listing.
func SumFor(sums []byte, name string) (string, error) {
	sc := bufio.NewScanner(strings.NewReader(string(sums)))
	for sc.Scan() {
		f := strings.Fields(sc.Text())
		if len(f) == 2 && strings.TrimPrefix(f[1], "*") == name {
			if len(f[0]) != 64 {
				break
			}
			if _, err := hex.DecodeString(f[0]); err != nil {
				break
			}
			return strings.ToLower(f[0]), nil
		}
	}
	return "", fmt.Errorf("%s 里没有 %s 的有效校验值", SumsName, name)
}

// Probe runs bin -version and checks that it names version.
func Probe(ctx context.Context, bin, version string) error {
	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	out, err := exec.CommandContext(ctx, bin, "-version").CombinedOutput()
	if err != nil {
		return fmt.Errorf("新程序无法运行：%v %s", err, strings.TrimSpace(string(out)))
	}
	if !strings.Contains(string(out), version) {
		return fmt.Errorf("新程序报告的版本是 %q，不是 %s", strings.TrimSpace(string(out)), version)
	}
	return nil
}

func fetch(ctx context.Context, client *http.Client, url string, limit int64) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", "geocrew-server-update")
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("返回 %d", resp.StatusCode)
	}
	return io.ReadAll(io.LimitReader(resp.Body, limit))
}

// InDocker reports whether the server runs in a container, where the
// image, not the program file, is what gets updated.
func InDocker() bool {
	if v := os.Getenv("GEOCREW_IN_DOCKER"); v != "" {
		b, _ := strconv.ParseBool(v)
		return b
	}
	_, err := os.Stat("/.dockerenv")
	return err == nil
}
