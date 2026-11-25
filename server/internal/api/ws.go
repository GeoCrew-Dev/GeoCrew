package api

import (
	"context"
	"encoding/json"
	"net/http"
	"time"

	"github.com/coder/websocket"

	"github.com/GeoCrew-Dev/GeoCrew/server/internal/realtime"
)

const (
	wsWriteTimeout = 10 * time.Second
	wsPingInterval = 30 * time.Second
	wsReadLimit    = 64 << 10
)

// realtime upgrades to the WebSocket channel described in protocol §5.
func (s *Server) realtime(w http.ResponseWriter, r *http.Request) {
	conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{
		// Auth is by bearer token, not cookies, so cross-origin browsers are harmless.
		OriginPatterns: []string{"*"},
	})
	if err != nil {
		return // Accept already wrote the HTTP error.
	}
	conn.SetReadLimit(wsReadLimit)
	m := member(r)
	ws := r.PathValue("ws")

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	c := &realtime.Client{MemberID: m.ID, Nickname: m.Nickname, Watcher: m.Viewer, Send: make(chan []byte, 64)}
	c.Close = func() {
		cancel()
		conn.Close(websocket.StatusPolicyViolation, "disconnected by server")
	}
	s.Hub.Join(ws, c)
	defer s.Hub.Leave(ws, c)

	go s.wsWriter(ctx, conn, c)

	for {
		_, data, err := conn.Read(ctx)
		if err != nil {
			conn.CloseNow()
			return
		}
		var env struct {
			Type string `json:"type"`
		}
		if json.Unmarshal(data, &env) != nil {
			s.Hub.Send(c, errMsg("bad_json"))
			continue
		}
		switch env.Type {
		case realtime.TypePing:
			s.Hub.Send(c, []byte(`{"type":"pong"}`))
		case realtime.TypePosition:
			if c.Watcher {
				// A browser on a view link is not where the member is.
				s.Hub.Send(c, errMsg("read_only"))
				continue
			}
			// Read-only members may share their position too: seeing each
			// other on the map is the point of joining a team.
			var p realtime.Position
			if json.Unmarshal(data, &p) != nil {
				s.Hub.Send(c, errMsg("bad_position"))
				continue
			}
			s.Hub.UpdatePosition(ws, c, p)
		default:
			// Unknown types are ignored so older servers tolerate newer clients.
		}
	}
}

func errMsg(code string) []byte {
	b, _ := json.Marshal(map[string]string{"type": realtime.TypeError, "error": code})
	return b
}

func (s *Server) wsWriter(ctx context.Context, conn *websocket.Conn, c *realtime.Client) {
	ping := time.NewTicker(wsPingInterval)
	defer ping.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case msg := <-c.Send:
			wctx, cancel := context.WithTimeout(ctx, wsWriteTimeout)
			err := conn.Write(wctx, websocket.MessageText, msg)
			cancel()
			if err != nil {
				conn.CloseNow()
				return
			}
		case <-ping.C:
			pctx, cancel := context.WithTimeout(ctx, wsWriteTimeout)
			err := conn.Ping(pctx)
			cancel()
			if err != nil {
				conn.CloseNow()
				return
			}
		}
	}
}
