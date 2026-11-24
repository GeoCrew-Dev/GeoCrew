package api

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"sync"
	"time"
)

// Member is one person in a workspace.
type Member struct {
	ID       string    `json:"id"`
	Nickname string    `json:"nickname"`
	Role     string    `json:"role"` // "leader" or "member"
	JoinedAt time.Time `json:"joined_at"`
	token    string
}

// Workspace is one field team's shared work area.
type Workspace struct {
	ID         string    `json:"id"`
	Name       string    `json:"name"`
	CreatedAt  time.Time `json:"created_at"`
	InviteCode string    `json:"-"`
	Members    []*Member `json:"-"`
}

var (
	ErrNotFound    = errors.New("not found")
	ErrBadInvite   = errors.New("invalid invite code")
	ErrEmptyFields = errors.New("required fields missing")
)

// Store keeps workspaces in memory. A persistent backend (SQLite) replaces it later.
type Store struct {
	mu         sync.RWMutex
	workspaces map[string]*Workspace
	byInvite   map[string]*Workspace
	byToken    map[string]*Member
}

func NewStore() *Store {
	return &Store{
		workspaces: map[string]*Workspace{},
		byInvite:   map[string]*Workspace{},
		byToken:    map[string]*Member{},
	}
}

func randHex(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return hex.EncodeToString(b)
}

func (s *Store) newMember(nickname, role string) *Member {
	m := &Member{ID: randHex(8), Nickname: nickname, Role: role, JoinedAt: time.Now().UTC(), token: randHex(24)}
	s.byToken[m.token] = m
	return m
}

// CreateWorkspace makes a workspace with its leader and returns both.
func (s *Store) CreateWorkspace(name, nickname string) (*Workspace, *Member, error) {
	if name == "" || nickname == "" {
		return nil, nil, ErrEmptyFields
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	w := &Workspace{ID: randHex(8), Name: name, CreatedAt: time.Now().UTC(), InviteCode: randHex(4)}
	leader := s.newMember(nickname, "leader")
	w.Members = []*Member{leader}
	s.workspaces[w.ID] = w
	s.byInvite[w.InviteCode] = w
	return w, leader, nil
}

// Join adds a member to the workspace that owns the invite code.
func (s *Store) Join(code, nickname string) (*Workspace, *Member, error) {
	if code == "" || nickname == "" {
		return nil, nil, ErrEmptyFields
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	w, ok := s.byInvite[code]
	if !ok {
		return nil, nil, ErrBadInvite
	}
	m := s.newMember(nickname, "member")
	w.Members = append(w.Members, m)
	return w, m, nil
}

// Members lists a workspace's members if the token belongs to it.
func (s *Store) Members(workspaceID, token string) ([]*Member, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	w, ok := s.workspaces[workspaceID]
	if !ok {
		return nil, ErrNotFound
	}
	for _, m := range w.Members {
		if m.token == token {
			return append([]*Member(nil), w.Members...), nil
		}
	}
	return nil, ErrNotFound
}
