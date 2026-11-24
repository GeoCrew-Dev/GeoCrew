// Package push delivers short "something happened" notices to members'
// devices through endpoints they registered (UnifiedPush distributors such
// as ntfy, or any service that accepts a POSTed message).
//
// Messages carry only ids and a type, never record content or message text:
// the endpoint is often run by a third party, and the app fetches the
// details over the authenticated API when it wakes up.
package push

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"slices"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store"
)

// Event types a member can subscribe to.
const (
	EventMessage = "message" // workspace message stream
	EventComment = "comment" // discussion under a record
	EventTask    = "task"    // a record was assigned to the member
	EventAlert   = "alert"   // an urgent workspace message; cannot be unsubscribed
	// EventRollCall is a leader's roll call (to everyone) or an answer to it
	// (to the leader who called it); cannot be unsubscribed.
	EventRollCall = "rollcall"
)

// Events lists every known event type.
var Events = []string{EventMessage, EventComment, EventTask, EventAlert, EventRollCall}

// Notice is the JSON body POSTed to an endpoint.
type Notice struct {
	Type      string `json:"type"`
	Workspace string `json:"workspace"`
	// Feature is the record concerned (comments and tasks).
	Feature string `json:"feature,omitempty"`
	// From is the member who caused the notice.
	From string `json:"from,omitempty"`
	// Seq is the comment seq or feature seq to sync up to.
	Seq int64 `json:"seq,omitempty"`
}

// Targets is the part of the store the sender needs.
type Targets interface {
	PushTargets(ctx context.Context, workspaceID string) ([]store.PushTarget, error)
	DeletePushTarget(ctx context.Context, workspaceID, memberID string) error
}

// Audience selects recipients: everyone but Except, or only Only when set.
type Audience struct {
	Except string
	Only   string
}

func (a Audience) includes(memberID string) bool {
	if a.Only != "" {
		return memberID == a.Only && memberID != a.Except
	}
	return memberID != a.Except
}

type job struct {
	notice   Notice
	audience Audience
}

// Sender queues notices and delivers them in the background. The zero
// value is not usable; call New.
type Sender struct {
	targets Targets
	client  *http.Client
	log     *slog.Logger
	// AllowPrivate permits endpoints on private and loopback addresses
	// (for a distributor on the same LAN). Off by default to prevent the
	// server being used to probe its own network.
	allowPrivate bool

	queue chan job
	wg    sync.WaitGroup
	once  sync.Once
}

// Options configures New.
type Options struct {
	AllowPrivate bool
	// Timeout per delivery; default 10 s.
	Timeout time.Duration
	// Workers delivering in parallel; default 4.
	Workers int
	// Queue length before new notices are dropped; default 256.
	Queue int
	Log   *slog.Logger
	// Transport overrides the HTTP transport (tests).
	Transport http.RoundTripper
}

// New starts a sender. Close it to stop the workers.
func New(t Targets, o Options) *Sender {
	if o.Timeout == 0 {
		o.Timeout = 10 * time.Second
	}
	if o.Workers == 0 {
		o.Workers = 4
	}
	if o.Queue == 0 {
		o.Queue = 256
	}
	if o.Log == nil {
		o.Log = slog.Default()
	}
	s := &Sender{targets: t, log: o.Log, allowPrivate: o.AllowPrivate, queue: make(chan job, o.Queue)}
	tr := o.Transport
	if tr == nil {
		dialer := &net.Dialer{Timeout: 5 * time.Second, Control: s.checkDial}
		tr = &http.Transport{
			DialContext:         dialer.DialContext,
			TLSHandshakeTimeout: 5 * time.Second,
			MaxIdleConns:        16,
			IdleConnTimeout:     time.Minute,
		}
	}
	s.client = &http.Client{
		Timeout:   o.Timeout,
		Transport: tr,
		// Endpoints answer directly; a redirect could point anywhere.
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
	for range o.Workers {
		s.wg.Add(1)
		go s.work()
	}
	return s
}

// Notify queues a notice without blocking; it is dropped when the queue is full.
func (s *Sender) Notify(n Notice, a Audience) {
	if s == nil {
		return
	}
	select {
	case s.queue <- job{n, a}:
	default:
		s.log.Warn("push queue full, notice dropped", "workspace", n.Workspace, "type", n.Type)
	}
}

// Close stops accepting notices and waits for queued ones to be delivered.
func (s *Sender) Close() {
	if s == nil {
		return
	}
	s.once.Do(func() { close(s.queue) })
	s.wg.Wait()
}

func (s *Sender) work() {
	defer s.wg.Done()
	for j := range s.queue {
		s.deliver(j)
	}
}

func (s *Sender) deliver(j job) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	targets, err := s.targets.PushTargets(ctx, j.notice.Workspace)
	if err != nil {
		s.log.Warn("push targets", "workspace", j.notice.Workspace, "err", err)
		return
	}
	body, _ := json.Marshal(j.notice)
	for _, t := range targets {
		wanted := j.notice.Type == EventAlert || j.notice.Type == EventRollCall || slices.Contains(t.Events, j.notice.Type)
		if !j.audience.includes(t.MemberID) || !wanted {
			continue
		}
		var keys Keys
		if t.Keys != nil {
			keys = Keys{P256dh: t.Keys.P256dh, Auth: t.Keys.Auth}
		}
		gone, err := s.post(ctx, t.Endpoint, body, keys)
		switch {
		case gone:
			// The distributor says the registration no longer exists.
			s.log.Info("push endpoint gone, removed", "workspace", j.notice.Workspace, "member", t.MemberID)
			_ = s.targets.DeletePushTarget(ctx, j.notice.Workspace, t.MemberID)
		case err != nil:
			s.log.Warn("push delivery failed", "workspace", j.notice.Workspace, "member", t.MemberID, "err", err)
		}
	}
}

func (s *Sender) post(ctx context.Context, endpoint string, body []byte, keys Keys) (gone bool, err error) {
	contentType := "application/json"
	if !keys.Empty() {
		if body, err = encrypt(body, keys, nil); err != nil {
			return false, fmt.Errorf("encrypt: %w", err)
		}
		contentType = "application/octet-stream"
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(body))
	if err != nil {
		return false, err
	}
	req.Header.Set("Content-Type", contentType)
	if !keys.Empty() {
		req.Header.Set("Content-Encoding", "aes128gcm")
	}
	// Web Push style hints, understood by common distributors.
	req.Header.Set("TTL", "86400")
	req.Header.Set("Urgency", "normal")
	resp, err := s.client.Do(req)
	if err != nil {
		return false, err
	}
	resp.Body.Close()
	switch {
	case resp.StatusCode == http.StatusNotFound || resp.StatusCode == http.StatusGone:
		return true, nil
	case resp.StatusCode >= 300:
		return false, fmt.Errorf("endpoint answered %d", resp.StatusCode)
	}
	return false, nil
}

// checkDial refuses connections to non-public addresses unless allowed. It
// runs after DNS resolution, so a public name pointing inside is refused too.
func (s *Sender) checkDial(network, address string, _ syscall.RawConn) error {
	if s.allowPrivate {
		return nil
	}
	host, _, err := net.SplitHostPort(address)
	if err != nil {
		return err
	}
	ip := net.ParseIP(host)
	if ip == nil || !public(ip) {
		return fmt.Errorf("push endpoint address %s is not public", host)
	}
	return nil
}

func public(ip net.IP) bool {
	return !(ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() ||
		ip.IsMulticast() || ip.IsUnspecified() || ip.IsInterfaceLocalMulticast() ||
		// Carrier-grade NAT, 100.64.0.0/10.
		(ip.To4() != nil && ip.To4()[0] == 100 && ip.To4()[1]&0xC0 == 64))
}

// ErrEndpoint describes why an endpoint URL was refused.
var ErrEndpoint = errors.New("invalid push endpoint")

// CheckEndpoint validates a URL a member wants notices sent to. Plain http
// is accepted only together with AllowPrivate (LAN distributors).
func (s *Sender) CheckEndpoint(raw string) error {
	if len(raw) > store.MaxPushEndpoint {
		return fmt.Errorf("%w: longer than %d characters", ErrEndpoint, store.MaxPushEndpoint)
	}
	u, err := url.Parse(raw)
	if err != nil || u.Host == "" || u.User != nil {
		return fmt.Errorf("%w: not an absolute URL", ErrEndpoint)
	}
	switch strings.ToLower(u.Scheme) {
	case "https":
	case "http":
		if !s.allowPrivate {
			return fmt.Errorf("%w: must use https", ErrEndpoint)
		}
	default:
		return fmt.Errorf("%w: must use https", ErrEndpoint)
	}
	if ip := net.ParseIP(u.Hostname()); ip != nil && !s.allowPrivate && !public(ip) {
		return fmt.Errorf("%w: address is not public", ErrEndpoint)
	}
	if strings.EqualFold(u.Hostname(), "localhost") && !s.allowPrivate {
		return fmt.Errorf("%w: address is not public", ErrEndpoint)
	}
	return nil
}
