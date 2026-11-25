package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// client talks to the server under test. It retries once on 429, waiting
// as long as Retry-After asks (up to a minute), so a default rate limit on
// create and join doesn't fail the run.
type client struct {
	base string
	http *http.Client
}

type response struct {
	status int
	header http.Header
	body   []byte
}

func (r response) json(v any) error {
	if err := json.Unmarshal(r.body, v); err != nil {
		return fmt.Errorf("response is not the expected JSON (%v): %s", err, clip(r.body))
	}
	return nil
}

// errorCode is the machine code of a section 1.1 error body, or "" when the body isn't one.
func (r response) errorCode() string {
	var e struct {
		Error   string `json:"error"`
		Message string `json:"message"`
	}
	if json.Unmarshal(r.body, &e) != nil {
		return ""
	}
	return e.Error
}

func (c *client) do(method, path, token string, body any, header map[string]string) (response, error) {
	for attempt := 0; ; attempt++ {
		var rd io.Reader
		switch b := body.(type) {
		case nil:
		case []byte:
			rd = bytes.NewReader(b)
		default:
			raw, err := json.Marshal(b)
			if err != nil {
				return response{}, err
			}
			rd = bytes.NewReader(raw)
		}
		req, err := http.NewRequest(method, c.base+path, rd)
		if err != nil {
			return response{}, err
		}
		if _, ok := body.([]byte); !ok && body != nil {
			req.Header.Set("Content-Type", "application/json")
		}
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}
		for k, v := range header {
			req.Header.Set(k, v)
		}
		res, err := c.http.Do(req)
		if err != nil {
			return response{}, err
		}
		raw, err := io.ReadAll(io.LimitReader(res.Body, 64<<20))
		res.Body.Close()
		if err != nil {
			return response{}, err
		}
		if res.StatusCode == http.StatusTooManyRequests && attempt == 0 {
			wait, _ := strconv.Atoi(res.Header.Get("Retry-After"))
			if wait < 1 {
				wait = 1
			}
			if wait > 60 {
				wait = 60
			}
			time.Sleep(time.Duration(wait) * time.Second)
			continue
		}
		return response{res.StatusCode, res.Header, raw}, nil
	}
}

func clip(b []byte) string {
	s := strings.TrimSpace(string(b))
	if len(s) > 300 {
		s = s[:300] + "…"
	}
	return s
}
