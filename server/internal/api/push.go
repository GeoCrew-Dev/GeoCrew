package api

import (
	"context"
	"encoding/json"
	"net/http"
	"slices"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew/server/internal/push"
	"github.com/GeoCrew-Dev/GeoCrew/server/internal/store"
)

type putPushReq struct {
	Endpoint string          `json:"endpoint"`
	Events   []string        `json:"events"`
	Keys     *store.PushKeys `json:"keys"`
}

func (s *Server) pushDisabled(w http.ResponseWriter) bool {
	if s.Push == nil {
		writeErr(w, http.StatusNotFound, "push_disabled", "this server does not send push notifications")
		return true
	}
	return false
}

func (s *Server) getPush(w http.ResponseWriter, r *http.Request) {
	if s.pushDisabled(w) {
		return
	}
	t, err := s.Store.PushTarget(r.Context(), r.PathValue("ws"), member(r).ID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, t)
}

func (s *Server) putPush(w http.ResponseWriter, r *http.Request) {
	if s.pushDisabled(w) {
		return
	}
	var req putPushReq
	if !decode(w, r, &req) {
		return
	}
	if err := s.Push.CheckEndpoint(req.Endpoint); err != nil {
		writeErr(w, http.StatusBadRequest, "invalid_endpoint", err.Error())
		return
	}
	if req.Keys != nil {
		if err := (push.Keys{P256dh: req.Keys.P256dh, Auth: req.Keys.Auth}).Validate(); err != nil {
			writeErr(w, http.StatusBadRequest, "invalid_keys", err.Error())
			return
		}
	}
	events := req.Events
	if events == nil {
		events = push.Events
	}
	clean := []string{}
	for _, e := range events {
		if !slices.Contains(push.Events, e) {
			writeErr(w, http.StatusBadRequest, "invalid_events", "unknown event "+e)
			return
		}
		if !slices.Contains(clean, e) {
			clean = append(clean, e)
		}
	}
	t := store.PushTarget{MemberID: member(r).ID, Endpoint: req.Endpoint, Events: clean, Keys: req.Keys, UpdatedAt: time.Now().UTC()}
	if err := s.Store.SetPushTarget(r.Context(), r.PathValue("ws"), t); err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, t)
}

func (s *Server) deletePush(w http.ResponseWriter, r *http.Request) {
	if s.pushDisabled(w) {
		return
	}
	if err := s.Store.DeletePushTarget(r.Context(), r.PathValue("ws"), member(r).ID); err != nil {
		s.fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// notifyComment tells the other members about a new message or record comment.
func (s *Server) notifyComment(ws string, c store.Comment) {
	if s.Push == nil {
		return
	}
	n := push.Notice{Type: push.EventMessage, Workspace: ws, From: c.AuthorID, Seq: c.Seq}
	audience := push.Audience{Except: c.AuthorID}
	switch {
	case c.Urgent:
		n.Type = push.EventAlert
	case c.RollCall:
		n.Type = push.EventRollCall
	case c.AnswerTo != "":
		// Only the leader who called the roll hears about each answer.
		call, err := s.Store.Comment(context.Background(), ws, c.AnswerTo)
		if err != nil {
			return
		}
		n.Type = push.EventRollCall
		audience.Only = call.AuthorID
	case c.FeatureID != "":
		n.Type = push.EventComment
		n.Feature = c.FeatureID
	}
	s.Push.Notify(n, audience)
}

// taskProps are the task fields of a record (protocol §6.1).
type taskProps struct {
	Assignee   string `json:"assignee"`
	TaskStatus string `json:"task_status"`
}

func assigneeOf(props json.RawMessage) taskProps {
	var t taskProps
	if len(props) > 0 {
		_ = json.Unmarshal(props, &t)
	}
	return t
}

// notifyAssignments tells members about records newly assigned to them by someone else.
func (s *Server) notifyAssignments(ws, by string, fs []store.Feature, results []store.PushResult) {
	if s.Push == nil {
		return
	}
	for i, res := range results {
		if res.Status != store.PushApplied || i >= len(fs) || fs[i].Deleted {
			continue
		}
		now := assigneeOf(fs[i].Properties)
		if now.Assignee == "" || now.Assignee == by || now.TaskStatus == "done" {
			continue
		}
		if res.Previous != nil && !res.Previous.Deleted && assigneeOf(res.Previous.Properties).Assignee == now.Assignee {
			continue
		}
		s.Push.Notify(push.Notice{Type: push.EventTask, Workspace: ws, Feature: res.ID, From: by, Seq: res.Seq},
			push.Audience{Only: now.Assignee, Except: by})
	}
}
