package api

import (
	"context"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew/server/internal/push"
)

// WatchOutings checks every so often for outings past their back_by with no
// report back (protocol §8.3) and notifies the whole workspace once for each,
// so a member whose phone has died is still missed. Runs until ctx ends.
func (s *Server) WatchOutings(ctx context.Context, every time.Duration) {
	t := time.NewTicker(every)
	defer t.Stop()
	for {
		s.notifyOverdue(ctx, time.Now())
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

// notifyOverdue sends the overdue notices due at now and returns how many.
func (s *Server) notifyOverdue(ctx context.Context, now time.Time) int {
	due, err := s.Store.DueOutings(ctx, now, 100)
	if err != nil {
		if ctx.Err() == nil {
			s.logger().Warn("overdue outings", "err", err)
		}
		return 0
	}
	for _, d := range due {
		if s.Push != nil {
			// Everyone, the one who is late included: a reminder to report back.
			s.Push.Notify(push.Notice{Type: push.EventOverdue, Workspace: d.WorkspaceID, From: d.Comment.AuthorID, Seq: d.Comment.Seq}, push.Audience{})
		}
		if err := s.Store.MarkOverdue(ctx, d.WorkspaceID, d.Comment.ID); err != nil {
			s.logger().Warn("mark overdue", "workspace", d.WorkspaceID, "comment", d.Comment.ID, "err", err)
		}
	}
	return len(due)
}
