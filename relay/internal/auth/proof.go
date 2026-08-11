// Package auth verifies bounded P-256 route proofs.
package auth

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"regexp"
	"sync"
	"time"

	"github.com/gowebpki/jcs"
)

const (
	NonceBytes       = 32
	MaxProofBytes    = 16 << 10
	MaxProofLifetime = 30 * time.Second
	ReplayRetention  = 2 * time.Minute
	MaxReplayEntries = 10_000
)

var (
	ErrMalformed   = errors.New("malformed proof")
	ErrAudience    = errors.New("wrong proof audience")
	ErrExpired     = errors.New("expired proof")
	ErrReplay      = errors.New("replayed proof")
	ErrReplayFull  = errors.New("replay cache full")
	ErrInvalidKey  = errors.New("invalid route public key")
	ErrInvalidSig  = errors.New("invalid proof signature")
	ErrChallenge   = errors.New("proof does not match challenge")
	idPattern      = regexp.MustCompile(`^[A-Za-z0-9._-]{1,128}$`)
	base64URLNoPad = base64.RawURLEncoding
)

type Signed struct {
	Audience     string `json:"audience"`
	RouteID      string `json:"routeId"`
	KeyID        string `json:"keyId"`
	Nonce        string `json:"nonce"`
	ExpiresAt    string `json:"expiresAt"`
	RendezvousID string `json:"rendezvousId,omitempty"`
}

type Proof struct {
	Signed    Signed
	Signature []byte
	Canonical []byte
}

type wireProof struct {
	Type      string          `json:"type,omitempty"`
	Signed    json.RawMessage `json:"signed"`
	Signature string          `json:"signature"`
}

type ReplayCache struct {
	mu      sync.Mutex
	entries map[string]time.Time
	now     func() time.Time
}

func NewReplayCache(now func() time.Time) *ReplayCache {
	if now == nil {
		now = time.Now
	}
	return &ReplayCache{entries: make(map[string]time.Time), now: now}
}

func ParseP256SPKI(der []byte) (*ecdsa.PublicKey, error) {
	key, err := x509.ParsePKIXPublicKey(der)
	if err != nil {
		return nil, ErrInvalidKey
	}
	publicKey, ok := key.(*ecdsa.PublicKey)
	if !ok || publicKey.Curve.Params().Name != "P-256" {
		return nil, ErrInvalidKey
	}
	return publicKey, nil
}

func ParseProof(raw []byte) (Proof, error) {
	if len(raw) == 0 || len(raw) > MaxProofBytes {
		return Proof{}, ErrMalformed
	}
	if _, err := jcs.Transform(raw); err != nil {
		return Proof{}, fmt.Errorf("%w: %v", ErrMalformed, err)
	}
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.DisallowUnknownFields()
	var wire wireProof
	if err := decoder.Decode(&wire); err != nil || decoder.Decode(&struct{}{}) != io.EOF || wire.Type != "" && wire.Type != "route.proof" {
		return Proof{}, ErrMalformed
	}
	if len(wire.Signed) == 0 || wire.Signature == "" {
		return Proof{}, ErrMalformed
	}
	if _, err := jcs.Transform(wire.Signed); err != nil {
		return Proof{}, ErrMalformed
	}
	decoder = json.NewDecoder(bytes.NewReader(wire.Signed))
	decoder.DisallowUnknownFields()
	var signed Signed
	if err := decoder.Decode(&signed); err != nil || decoder.Decode(&struct{}{}) != io.EOF || !validSigned(signed) {
		return Proof{}, ErrMalformed
	}
	signature, err := base64URLNoPad.DecodeString(wire.Signature)
	if err != nil || len(signature) == 0 || len(signature) > 144 {
		return Proof{}, ErrMalformed
	}
	canonical, err := jcs.Transform(wire.Signed)
	if err != nil {
		return Proof{}, ErrMalformed
	}
	return Proof{Signed: signed, Signature: signature, Canonical: canonical}, nil
}

func (r *ReplayCache) Verify(proof Proof, publicKey *ecdsa.PublicKey, audience string) error {
	if proof.Signed.Audience != audience {
		return ErrAudience
	}
	expiresAt, err := time.Parse(time.RFC3339Nano, proof.Signed.ExpiresAt)
	now := r.now()
	if err != nil || !expiresAt.After(now) || expiresAt.Sub(now) > MaxProofLifetime {
		return ErrExpired
	}
	digest := sha256.Sum256(proof.Canonical)
	if !ecdsa.VerifyASN1(publicKey, digest[:], proof.Signature) {
		return ErrInvalidSig
	}
	key := proof.Signed.Audience + "\x00" + proof.Signed.RouteID + "\x00" + proof.Signed.KeyID + "\x00" + proof.Signed.Nonce
	r.mu.Lock()
	defer r.mu.Unlock()
	for replayKey, until := range r.entries {
		if !until.After(now) {
			delete(r.entries, replayKey)
		}
	}
	if _, exists := r.entries[key]; exists {
		return ErrReplay
	}
	if len(r.entries) >= MaxReplayEntries {
		return ErrReplayFull
	}
	r.entries[key] = now.Add(ReplayRetention)
	return nil
}

func MatchChallenge(proof Proof, challenge Signed) error {
	if proof.Signed != challenge {
		return ErrChallenge
	}
	return nil
}

func validSigned(s Signed) bool {
	if !idPattern.MatchString(s.RouteID) || !idPattern.MatchString(s.KeyID) || !validAudience(s.Audience) {
		return false
	}
	if s.RendezvousID != "" && !idPattern.MatchString(s.RendezvousID) {
		return false
	}
	nonce, err := base64URLNoPad.DecodeString(s.Nonce)
	return err == nil && len(nonce) == NonceBytes
}

func validAudience(audience string) bool {
	switch audience {
	case "control", "route-admin", "device-data", "mac-data":
		return true
	default:
		return false
	}
}

func NewNonce(random []byte) (string, error) {
	if len(random) != NonceBytes {
		return "", ErrMalformed
	}
	return base64URLNoPad.EncodeToString(random), nil
}

func ValidID(id string) bool {
	return idPattern.MatchString(id)
}
