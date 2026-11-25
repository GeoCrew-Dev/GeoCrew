// Package realtime fans out live messages (member positions, change
// notifications, presence) to the connected members of each workspace.
//
// The hub is transport-agnostic: the HTTP layer adapts WebSocket
// connections to Client values, so the hub can be tested without sockets.
package realtime

import (
	"encoding/json"
	"sync"
	"time"
)

// Message types of the v0 realtime channel.
const (
	TypeHello           = "hello"
	TypePosition        = "position"
	TypeFeatureChanged  = "feature_changed"
	TypeMembersChanged  = "members_changed"
	TypeSettingsChanged = "settings_changed"
	TypeCommentsChanged = "comments_changed"
	TypePresence        = "presence"
	TypePing            = "ping"
	TypePong            = "pong"
	TypeError           = "error"
)

// Position is a member's live location. It is relayed and kept briefly in
// memory but never written to the feature history.
type Position struct {
	Type     string    `json:"type"`
	MemberID string    `json:"member_id"`
	Nickname string    `json:"nickname,omitempty"`
	Lon      float64   `json:"lon"`
	Lat      float64   `json:"lat"`
	Alt      *float64  `json:"alt,omitempty"`
	Accuracy float64   `json:"accuracy,omitempty"`
	Heading  *float64  `json:"heading,omitempty"`
	Speed    *float64  `json:"speed,omitempty"`
	At       time.Time `json:"at"`
}

// Valid reports whether the coordinates are plausible WGS84 values.
func (p Position) Valid() bool {
	return p.Lon >= -180 && p.Lon <= 180 && p.Lat >= -90 && p.Lat <= 90 && p.Accuracy >= 0
}

// Client is one live connection. Send is drained by the transport; when it
// is full the hub drops the client instead of blocking everyone else.
type Client struct {
	MemberID string
	Nickname string
	// Watcher clients (a browser on a view link) receive everything but
	// do not count as the member being online.
	Watcher bool
	Send    chan []byte
	// Close is called (once) when the hub drops the client.
	Close func()

	closeOnce sync.Once
}

func (c *Client) drop() {
	c.closeOnce.Do(func() {
		if c.Close != nil {
			c.Close()
		}
	})
}

type room struct {
	clients   map[*Client]struct{}
	positions map[string]Position
	lastPos   map[string]time.Time
}

// Hub routes messages between clients of the same workspace.
type Hub struct {
	// PositionTTL is how long a last-known position is replayed to newcomers.
	PositionTTL time.Duration
	// MinPositionInterval throttles position updates per member.
	MinPositionInterval time.Duration

	mu    sync.Mutex
	rooms map[string]*room
	now   func() time.Time
}

// NewHub returns a hub with field-friendly defaults.
func NewHub() *Hub {
	return &Hub{
		PositionTTL:         15 * time.Minute,
		MinPositionInterval: time.Second,
		rooms:               map[string]*room{},
		now:                 time.Now,
	}
}

func (h *Hub) room(ws string) *room {
	r := h.rooms[ws]
	if r == nil {
		r = &room{clients: map[*Client]struct{}{}, positions: map[string]Position{}, lastPos: map[string]time.Time{}}
		h.rooms[ws] = r
	}
	return r
}

func marshal(v any) []byte {
	b, _ := json.Marshal(v)
	return b
}

// hello is the first message every client receives.
type hello struct {
	Type      string     `json:"type"`
	MemberID  string     `json:"member_id"`
	Online    []string   `json:"online"`
	Positions []Position `json:"positions"`
}

type presence struct {
	Type     string `json:"type"`
	MemberID string `json:"member_id"`
	Online   bool   `json:"online"`
}

// Join registers c in workspace ws and greets it with the current state.
func (h *Hub) Join(ws string, c *Client) {
	h.mu.Lock()
	defer h.mu.Unlock()
	r := h.room(ws)
	cutoff := h.now().Add(-h.PositionTTL)
	hi := hello{Type: TypeHello, MemberID: c.MemberID, Online: []string{}, Positions: []Position{}}
	seen := map[string]bool{}
	for other := range r.clients {
		if !other.Watcher && !seen[other.MemberID] {
			seen[other.MemberID] = true
			hi.Online = append(hi.Online, other.MemberID)
		}
	}
	for id, p := range r.positions {
		if p.At.Before(cutoff) {
			delete(r.positions, id)
			continue
		}
		hi.Positions = append(hi.Positions, p)
	}
	r.clients[c] = struct{}{}
	h.sendLocked(c, marshal(hi))
	if !c.Watcher && !seen[c.MemberID] {
		h.broadcastLocked(r, marshal(presence{TypePresence, c.MemberID, true}), c)
	}
}

// Leave unregisters c. It is safe to call more than once.
func (h *Hub) Leave(ws string, c *Client) {
	h.mu.Lock()
	defer h.mu.Unlock()
	r := h.rooms[ws]
	if r == nil {
		return
	}
	if _, ok := r.clients[c]; !ok {
		return
	}
	delete(r.clients, c)
	stillOnline := c.Watcher // a watcher leaving says nothing about the member
	for other := range r.clients {
		if !other.Watcher && other.MemberID == c.MemberID {
			stillOnline = true
			break
		}
	}
	if !stillOnline {
		h.broadcastLocked(r, marshal(presence{TypePresence, c.MemberID, false}), nil)
	}
	if len(r.clients) == 0 && len(r.positions) == 0 {
		delete(h.rooms, ws)
	}
}

// UpdatePosition records and relays a position from c. It returns false if
// the update was invalid or throttled.
func (h *Hub) UpdatePosition(ws string, c *Client, p Position) bool {
	if !p.Valid() {
		return false
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	r := h.room(ws)
	now := h.now()
	if last, ok := r.lastPos[c.MemberID]; ok && now.Sub(last) < h.MinPositionInterval {
		return false
	}
	r.lastPos[c.MemberID] = now
	p.Type = TypePosition
	p.MemberID = c.MemberID
	p.Nickname = c.Nickname
	if p.At.IsZero() || p.At.After(now.Add(time.Minute)) {
		p.At = now.UTC()
	}
	r.positions[c.MemberID] = p
	h.broadcastLocked(r, marshal(p), c)
	return true
}

// FeatureChanged tells everyone in ws that changes up to seq are available.
func (h *Hub) FeatureChanged(ws string, seq int64) {
	h.Broadcast(ws, marshal(struct {
		Type string `json:"type"`
		Seq  int64  `json:"seq"`
	}{TypeFeatureChanged, seq}))
}

// MembersChanged tells everyone in ws to refresh the member list.
func (h *Hub) MembersChanged(ws string) {
	h.Broadcast(ws, marshal(struct {
		Type string `json:"type"`
	}{TypeMembersChanged}))
}

// SettingsChanged tells everyone in ws that the shared settings reached version.
func (h *Hub) SettingsChanged(ws string, version int64) {
	h.Broadcast(ws, marshal(struct {
		Type    string `json:"type"`
		Version int64  `json:"version"`
	}{TypeSettingsChanged, version}))
}

// CommentsChanged tells everyone in ws that comments up to seq are available.
func (h *Hub) CommentsChanged(ws string, seq int64) {
	h.Broadcast(ws, marshal(struct {
		Type string `json:"type"`
		Seq  int64  `json:"seq"`
	}{TypeCommentsChanged, seq}))
}

// Broadcast sends msg to every client in ws.
func (h *Hub) Broadcast(ws string, msg []byte) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if r := h.rooms[ws]; r != nil {
		h.broadcastLocked(r, msg, nil)
	}
}

// Send delivers msg to a single client.
func (h *Hub) Send(c *Client, msg []byte) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.sendLocked(c, msg)
}

// Kick disconnects every connection of memberID (e.g. after revocation)
// and forgets its position.
func (h *Hub) Kick(ws, memberID string) {
	h.mu.Lock()
	r := h.rooms[ws]
	var victims []*Client
	if r != nil {
		for c := range r.clients {
			if c.MemberID == memberID {
				victims = append(victims, c)
			}
		}
		delete(r.positions, memberID)
	}
	h.mu.Unlock()
	for _, c := range victims {
		h.Leave(ws, c)
		c.drop()
	}
}

// Online returns the member ids currently connected to ws.
func (h *Hub) Online(ws string) []string {
	h.mu.Lock()
	defer h.mu.Unlock()
	out := []string{}
	if r := h.rooms[ws]; r != nil {
		seen := map[string]bool{}
		for c := range r.clients {
			if !c.Watcher && !seen[c.MemberID] {
				seen[c.MemberID] = true
				out = append(out, c.MemberID)
			}
		}
	}
	return out
}

func (h *Hub) broadcastLocked(r *room, msg []byte, except *Client) {
	for c := range r.clients {
		if c != except {
			h.sendLocked(c, msg)
		}
	}
}

func (h *Hub) sendLocked(c *Client, msg []byte) {
	select {
	case c.Send <- msg:
	default:
		// Slow consumer: drop it rather than stall the room. The client
		// reconnects and catches up through the hello message and a pull.
		go c.drop()
	}
}

// Stats reports how many workspaces have someone connected and how many
// connections are open: members' devices and web viewers separately.
func (h *Hub) Stats() (workspaces, members, watchers int) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for _, r := range h.rooms {
		if len(r.clients) > 0 {
			workspaces++
		}
		for c := range r.clients {
			if c.Watcher {
				watchers++
			} else {
				members++
			}
		}
	}
	return
}
