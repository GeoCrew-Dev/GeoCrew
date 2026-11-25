package push

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/hkdf"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"strings"
)

// Keys are a device's Web Push encryption keys (RFC 8291), as base64url
// strings in the form browsers and UnifiedPush connectors hand out.
type Keys struct {
	P256dh string `json:"p256dh"`
	Auth   string `json:"auth"`
}

// Empty reports whether no keys were given (notices then go out in clear JSON).
func (k Keys) Empty() bool { return k.P256dh == "" && k.Auth == "" }

func decode64(s string) ([]byte, error) {
	s = strings.TrimRight(strings.TrimSpace(s), "=")
	if b, err := base64.RawURLEncoding.DecodeString(s); err == nil {
		return b, nil
	}
	return base64.RawStdEncoding.DecodeString(s)
}

// parse validates the keys and returns the device public key and auth secret.
func (k Keys) parse() (*ecdh.PublicKey, []byte, error) {
	pub, err := decode64(k.P256dh)
	if err != nil {
		return nil, nil, fmt.Errorf("p256dh: %w", err)
	}
	ua, err := ecdh.P256().NewPublicKey(pub)
	if err != nil {
		return nil, nil, fmt.Errorf("p256dh: %w", err)
	}
	auth, err := decode64(k.Auth)
	if err != nil || len(auth) != 16 {
		return nil, nil, errors.New("auth must be 16 bytes")
	}
	return ua, auth, nil
}

// Validate reports whether the keys can be used for encryption.
func (k Keys) Validate() error {
	_, _, err := k.parse()
	return err
}

const recordSize = 4096

// encrypt seals plaintext for the device as one aes128gcm record
// (RFC 8188 content coding with the RFC 8291 key schedule). random supplies
// the ephemeral key and salt; pass nil for crypto/rand.
func encrypt(plaintext []byte, k Keys, random io.Reader) ([]byte, error) {
	if random == nil {
		random = rand.Reader
	}
	ua, auth, err := k.parse()
	if err != nil {
		return nil, err
	}
	// One record only: the plaintext plus its delimiter and the AEAD tag must fit.
	if len(plaintext)+1+16 > recordSize {
		return nil, errors.New("notice too large")
	}
	as, err := generateKey(random)
	if err != nil {
		return nil, err
	}
	salt := make([]byte, 16)
	if _, err := io.ReadFull(random, salt); err != nil {
		return nil, err
	}
	shared, err := as.ECDH(ua)
	if err != nil {
		return nil, err
	}
	uaPub := ua.Bytes()
	asPub := as.PublicKey().Bytes()

	// IKM = HKDF(auth_secret, ecdh_secret, "WebPush: info" || 0x00 || ua_public || as_public, 32)
	prkKey, err := hkdf.Extract(sha256.New, shared, auth)
	if err != nil {
		return nil, err
	}
	keyInfo := "WebPush: info\x00" + string(uaPub) + string(asPub)
	ikm, err := hkdf.Expand(sha256.New, prkKey, keyInfo, 32)
	if err != nil {
		return nil, err
	}
	prk, err := hkdf.Extract(sha256.New, ikm, salt)
	if err != nil {
		return nil, err
	}
	cek, err := hkdf.Expand(sha256.New, prk, "Content-Encoding: aes128gcm\x00", 16)
	if err != nil {
		return nil, err
	}
	nonce, err := hkdf.Expand(sha256.New, prk, "Content-Encoding: nonce\x00", 12)
	if err != nil {
		return nil, err
	}
	block, err := aes.NewCipher(cek)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	// 0x02 marks the last (and only) record; no extra padding.
	padded := append(append([]byte{}, plaintext...), 0x02)

	header := make([]byte, 0, 16+4+1+len(asPub))
	header = append(header, salt...)
	header = binary.BigEndian.AppendUint32(header, recordSize)
	header = append(header, byte(len(asPub)))
	header = append(header, asPub...)
	return gcm.Seal(header, nonce, padded, nil), nil
}

// generateKey draws a P-256 private key from random. crypto/ecdh ignores a
// custom reader for key generation, so the scalar is read directly; this
// keeps the RFC test vector reproducible.
func generateKey(random io.Reader) (*ecdh.PrivateKey, error) {
	if random == rand.Reader {
		return ecdh.P256().GenerateKey(rand.Reader)
	}
	b := make([]byte, 32)
	for {
		if _, err := io.ReadFull(random, b); err != nil {
			return nil, err
		}
		if k, err := ecdh.P256().NewPrivateKey(b); err == nil {
			return k, nil
		}
	}
}
