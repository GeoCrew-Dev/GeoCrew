package main

import (
	"bufio"
	"crypto/rand"
	"crypto/sha1"
	"crypto/tls"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"time"
)

// wsConn is just enough of RFC 6455 for the checks: a client handshake,
// masked text frames out, and text frames in (ping frames are answered,
// fragments are not expected from a JSON server).
type wsConn struct {
	conn net.Conn
	r    *bufio.Reader
}

func dialWS(base, path, token string) (*wsConn, error) {
	u, err := url.Parse(base + path)
	if err != nil {
		return nil, err
	}
	host := u.Host
	var conn net.Conn
	d := &net.Dialer{Timeout: 10 * time.Second}
	switch u.Scheme {
	case "https":
		if u.Port() == "" {
			host += ":443"
		}
		conn, err = tls.DialWithDialer(d, "tcp", host, &tls.Config{ServerName: u.Hostname()})
	case "http":
		if u.Port() == "" {
			host += ":80"
		}
		conn, err = d.Dial("tcp", host)
	default:
		return nil, fmt.Errorf("unsupported scheme %q", u.Scheme)
	}
	if err != nil {
		return nil, err
	}
	keyRaw := make([]byte, 16)
	rand.Read(keyRaw)
	key := base64.StdEncoding.EncodeToString(keyRaw)
	req := "GET " + u.RequestURI() + " HTTP/1.1\r\n" +
		"Host: " + u.Host + "\r\n" +
		"Upgrade: websocket\r\nConnection: Upgrade\r\n" +
		"Sec-WebSocket-Version: 13\r\n" +
		"Sec-WebSocket-Key: " + key + "\r\n" +
		"Authorization: Bearer " + token + "\r\n\r\n"
	conn.SetDeadline(time.Now().Add(10 * time.Second))
	if _, err := io.WriteString(conn, req); err != nil {
		conn.Close()
		return nil, err
	}
	r := bufio.NewReader(conn)
	res, err := http.ReadResponse(r, &http.Request{Method: http.MethodGet})
	if err != nil {
		conn.Close()
		return nil, err
	}
	if res.StatusCode != http.StatusSwitchingProtocols {
		conn.Close()
		return nil, fmt.Errorf("upgrade answered %d, want 101", res.StatusCode)
	}
	sum := sha1.Sum([]byte(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))
	if res.Header.Get("Sec-WebSocket-Accept") != base64.StdEncoding.EncodeToString(sum[:]) {
		conn.Close()
		return nil, errors.New("wrong Sec-WebSocket-Accept")
	}
	conn.SetDeadline(time.Time{})
	return &wsConn{conn: conn, r: r}, nil
}

func (c *wsConn) Close() { c.conn.Close() }

func (c *wsConn) writeFrame(op byte, payload []byte) error {
	head := []byte{0x80 | op}
	n := len(payload)
	switch {
	case n < 126:
		head = append(head, 0x80|byte(n))
	case n < 1<<16:
		head = append(head, 0x80|126, byte(n>>8), byte(n))
	default:
		head = append(head, 0x80|127)
		head = binary.BigEndian.AppendUint64(head, uint64(n))
	}
	mask := make([]byte, 4)
	rand.Read(mask)
	head = append(head, mask...)
	body := make([]byte, n)
	for i := range payload {
		body[i] = payload[i] ^ mask[i%4]
	}
	c.conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
	_, err := c.conn.Write(append(head, body...))
	return err
}

func (c *wsConn) send(v any) error {
	raw, err := json.Marshal(v)
	if err != nil {
		return err
	}
	return c.writeFrame(0x1, raw)
}

// next returns the next text message, answering pings on the way.
func (c *wsConn) next(deadline time.Time) (map[string]any, error) {
	c.conn.SetReadDeadline(deadline)
	for {
		var h [2]byte
		if _, err := io.ReadFull(c.r, h[:]); err != nil {
			return nil, err
		}
		op := h[0] & 0x0f
		n := uint64(h[1] & 0x7f)
		switch n {
		case 126:
			var b [2]byte
			if _, err := io.ReadFull(c.r, b[:]); err != nil {
				return nil, err
			}
			n = uint64(binary.BigEndian.Uint16(b[:]))
		case 127:
			var b [8]byte
			if _, err := io.ReadFull(c.r, b[:]); err != nil {
				return nil, err
			}
			n = binary.BigEndian.Uint64(b[:])
		}
		if h[1]&0x80 != 0 {
			return nil, errors.New("server sent a masked frame")
		}
		if n > 1<<20 {
			return nil, errors.New("frame too large")
		}
		payload := make([]byte, n)
		if _, err := io.ReadFull(c.r, payload); err != nil {
			return nil, err
		}
		switch op {
		case 0x1:
			var m map[string]any
			if err := json.Unmarshal(payload, &m); err != nil {
				return nil, fmt.Errorf("text frame is not JSON: %s", clip(payload))
			}
			return m, nil
		case 0x8:
			return nil, errors.New("server closed the connection")
		case 0x9:
			if err := c.writeFrame(0xA, payload); err != nil {
				return nil, err
			}
		}
	}
}

// waitFor reads messages until one of the given type arrives.
func (c *wsConn) waitFor(typ string, within time.Duration, match func(map[string]any) bool) (map[string]any, error) {
	deadline := time.Now().Add(within)
	for {
		m, err := c.next(deadline)
		if err != nil {
			if ne, ok := err.(net.Error); ok && ne.Timeout() {
				return nil, fmt.Errorf("no %q message within %s", typ, within)
			}
			return nil, err
		}
		if m["type"] == typ && (match == nil || match(m)) {
			return m, nil
		}
	}
}
