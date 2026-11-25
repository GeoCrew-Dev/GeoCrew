// Package mcp serves a small Model Context Protocol endpoint: JSON-RPC 2.0
// over one HTTP POST per message ("streamable HTTP" without streaming).
// It is stateless, answers with application/json and only knows the
// methods a tool server needs: initialize, ping, tools/list and tools/call.
//
// The package does not know about workspaces; package api builds the
// tools for the member making the request (see [Tools]).
package mcp

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"slices"
)

// Versions are the MCP protocol revisions this server answers to, newest first.
var Versions = []string{"2025-06-18", "2025-03-26", "2024-11-05"}

// Tool is one callable tool. Call returns a JSON-encodable result; an
// error becomes a tool result with isError set, so the model can read it.
type Tool struct {
	Name        string
	Title       string
	Description string
	// InputSchema is a JSON Schema object for the arguments.
	InputSchema json.RawMessage
	Call        func(ctx context.Context, args json.RawMessage) (any, error)
	// Effect says what the tool changes; the zero value is read-only.
	// Clients use the hints to decide what to confirm with the user, so
	// a tool that changes anything must say so here.
	Effect Effect
}

// Effect classifies a tool's changes, in line with the client-side risk
// classes of actions.md: read, write (adds or edits, can be undone from
// history) and destructive (removes or reaches people outside the record).
type Effect int

const (
	Read Effect = iota
	Write
	Destructive
)

func (e Effect) annotations() map[string]any {
	return map[string]any{"readOnlyHint": e == Read, "destructiveHint": e == Destructive, "openWorldHint": false}
}

// Server answers MCP requests with a fixed set of tools.
type Server struct {
	Name, Version string
	Instructions  string
	Tools         []Tool
	// Log receives errors a tool hides from the model; nil drops them.
	Log func(tool string, err error)
}

// MaxBody bounds one request.
const MaxBody = 1 << 20

type request struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id,omitempty"`
	Method  string          `json:"method"`
	Params  json.RawMessage `json:"params,omitempty"`
}

type rpcError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
}

type response struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id"`
	Result  any             `json:"result,omitempty"`
	Error   *rpcError       `json:"error,omitempty"`
}

// JSON-RPC error codes.
const (
	codeParse          = -32700
	codeInvalidRequest = -32600
	codeNoMethod       = -32601
	codeInvalidParams  = -32602
)

// ServeHTTP handles one POSTed JSON-RPC message.
func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		// No server-initiated stream: GET is not offered (MCP transports, streamable HTTP).
		w.Header().Set("Allow", "POST")
		http.Error(w, "use POST", http.StatusMethodNotAllowed)
		return
	}
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, MaxBody))
	if err != nil {
		write(w, http.StatusRequestEntityTooLarge, response{JSONRPC: "2.0", ID: null, Error: &rpcError{codeInvalidRequest, "request too large"}})
		return
	}
	status, resp := s.Handle(r.Context(), body)
	if resp == nil {
		w.WriteHeader(status)
		return
	}
	write(w, status, resp)
}

var null = json.RawMessage("null")

// Handle answers one message. A nil response means a notification or a
// reply, which get 202 Accepted and no body.
func (s *Server) Handle(ctx context.Context, body []byte) (int, any) {
	var q request
	if err := json.Unmarshal(body, &q); err != nil {
		if len(body) > 0 && body[0] == '[' {
			return http.StatusBadRequest, response{JSONRPC: "2.0", ID: null, Error: &rpcError{codeInvalidRequest, "batches are not supported"}}
		}
		return http.StatusBadRequest, response{JSONRPC: "2.0", ID: null, Error: &rpcError{codeParse, "parse error"}}
	}
	if q.JSONRPC != "2.0" {
		return http.StatusBadRequest, response{JSONRPC: "2.0", ID: orNull(q.ID), Error: &rpcError{codeInvalidRequest, `jsonrpc must be "2.0"`}}
	}
	if q.Method == "" {
		// A response to something we never sent, or junk: nothing to answer.
		return http.StatusAccepted, nil
	}
	if len(q.ID) == 0 || string(q.ID) == "null" {
		// Notifications (notifications/initialized, cancelled, ...) need no answer.
		return http.StatusAccepted, nil
	}
	result, rerr := s.dispatch(ctx, q)
	if rerr != nil {
		return http.StatusOK, response{JSONRPC: "2.0", ID: q.ID, Error: rerr}
	}
	return http.StatusOK, response{JSONRPC: "2.0", ID: q.ID, Result: result}
}

func (s *Server) dispatch(ctx context.Context, q request) (any, *rpcError) {
	switch q.Method {
	case "initialize":
		var p struct {
			ProtocolVersion string `json:"protocolVersion"`
		}
		_ = json.Unmarshal(q.Params, &p)
		version := Versions[0]
		if slices.Contains(Versions, p.ProtocolVersion) {
			version = p.ProtocolVersion
		}
		return map[string]any{
			"protocolVersion": version,
			"capabilities":    map[string]any{"tools": map[string]any{"listChanged": false}},
			"serverInfo":      map[string]any{"name": s.Name, "version": s.Version},
			"instructions":    s.Instructions,
		}, nil
	case "ping":
		return map[string]any{}, nil
	case "tools/list":
		tools := make([]map[string]any, 0, len(s.Tools))
		for _, t := range s.Tools {
			tools = append(tools, map[string]any{
				"name": t.Name, "title": t.Title, "description": t.Description,
				"inputSchema": t.InputSchema, "annotations": t.Effect.annotations(),
			})
		}
		return map[string]any{"tools": tools}, nil
	case "tools/call":
		var p struct {
			Name      string          `json:"name"`
			Arguments json.RawMessage `json:"arguments"`
		}
		if err := json.Unmarshal(q.Params, &p); err != nil || p.Name == "" {
			return nil, &rpcError{codeInvalidParams, "params.name is required"}
		}
		i := slices.IndexFunc(s.Tools, func(t Tool) bool { return t.Name == p.Name })
		if i < 0 {
			return nil, &rpcError{codeInvalidParams, "unknown tool: " + p.Name}
		}
		args := p.Arguments
		if len(args) == 0 || string(args) == "null" {
			args = json.RawMessage("{}")
		}
		out, err := s.Tools[i].Call(ctx, args)
		if err != nil {
			var bad *ArgError
			msg := err.Error()
			if !errors.As(err, &bad) && !errors.Is(err, ErrNotFound) {
				// Internal details stay in the server log, not in the model's context.
				msg = "the server could not answer this; try again later"
				if s.Log != nil {
					s.Log(p.Name, err)
				}
			}
			return map[string]any{"content": []map[string]any{{"type": "text", "text": msg}}, "isError": true}, nil
		}
		text, err := json.Marshal(out)
		if err != nil {
			return nil, &rpcError{-32603, "internal error"}
		}
		return map[string]any{
			"content":           []map[string]any{{"type": "text", "text": string(text)}},
			"structuredContent": out,
			"isError":           false,
		}, nil
	}
	return nil, &rpcError{codeNoMethod, "method not found: " + q.Method}
}

// ArgError is a problem with a tool's arguments, shown to the model as is.
type ArgError struct{ Msg string }

func (e *ArgError) Error() string { return e.Msg }

// BadArgs builds an [ArgError].
func BadArgs(format string, a ...any) error { return &ArgError{fmt.Sprintf(format, a...)} }

// ErrNotFound is shown to the model as "not found".
var ErrNotFound = errors.New("not found")

func orNull(id json.RawMessage) json.RawMessage {
	if len(id) == 0 {
		return null
	}
	return id
}

func write(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
