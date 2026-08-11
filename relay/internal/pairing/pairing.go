// Package pairing holds short-lived, single-use provisional-pairing exchanges
// in memory only.
//
// A registered Mac opens one bounded exchange per route; an unregistered
// device presents the out-of-band secret to deposit one message and collect
// one reply. The exchange is destroyed on completion, on expiry, or after
// repeated secret failures, and message content stays opaque to the relay.
package pairing

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"sync"
	"time"
)

const (
	Lifetime          = 5 * time.Minute
	MaxActive         = 128
	MaxMessageBytes   = 16 << 10
	MaxFailedAttempts = 10
	CreateCooldown    = 5 * time.Second
)

var (
	ErrBusy        = errors.New("pairing exchange already active")
	ErrFull        = errors.New("pairing capacity reached")
	ErrRateLimited = errors.New("pairing creation rate limited")
	ErrNotFound    = errors.New("pairing exchange not found")
	ErrExpired     = errors.New("pairing exchange expired")
	ErrSecret      = errors.New("pairing secret mismatch")
	ErrDuplicate   = errors.New("pairing message already present")
	ErrNoRequest   = errors.New("pairing request not present")
	ErrNotReady    = errors.New("pairing reply not ready")
	ErrMessageSize = errors.New("pairing message size invalid")
)

type Handle struct {
	ID        string
	Secret    string
	ExpiresAt time.Time
}

type exchange struct {
	routeID string
	secret  []byte
	expires time.Time
	request []byte
	reply   []byte
	failed  int
}

type Store struct {
	mu          sync.Mutex
	byID        map[string]*exchange
	byRoute     map[string]string
	lastCreate  map[string]time.Time
	provisional map[string]time.Time
	now         func() time.Time
	random      func(int) (string, error)
}

func New(now func() time.Time) *Store {
	if now == nil {
		now = time.Now
	}
	return &Store{byID: make(map[string]*exchange), byRoute: make(map[string]string), lastCreate: make(map[string]time.Time), now: now, random: randomString}
}

// Open creates the single active exchange for a route.
func (s *Store) Open(routeID string) (Handle, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.cleanupLocked()
	if len(s.byID) >= MaxActive {
		return Handle{}, ErrFull
	}
	if _, busy := s.byRoute[routeID]; busy {
		return Handle{}, ErrBusy
	}
	if last, ok := s.lastCreate[routeID]; ok && s.now().Before(last.Add(CreateCooldown)) {
		return Handle{}, ErrRateLimited
	}
	id, err := s.random(18)
	if err != nil {
		return Handle{}, err
	}
	secret, err := s.random(32)
	if err != nil {
		return Handle{}, err
	}
	handle := Handle{ID: id, Secret: secret, ExpiresAt: s.now().Add(Lifetime)}
	s.byID[id] = &exchange{routeID: routeID, secret: []byte(secret), expires: handle.ExpiresAt}
	s.byRoute[routeID] = id
	s.lastCreate[routeID] = s.now()
	return handle, nil
}

// Rotate replaces any existing exchange for the route with a fresh one.
// The Mac holds at most one active invitation, so a new invitation supersedes
// the previous rendezvous; the busy conflict and create cooldown do not apply
// to an intentional rotation.
func (s *Store) Rotate(routeID string) (Handle, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.cleanupLocked()
	if len(s.byID) >= MaxActive {
		return Handle{}, ErrFull
	}
	if existing, busy := s.byRoute[routeID]; busy {
		s.destroyLocked(existing)
	}
	id, err := s.random(18)
	if err != nil {
		return Handle{}, err
	}
	secret, err := s.random(32)
	if err != nil {
		return Handle{}, err
	}
	handle := Handle{ID: id, Secret: secret, ExpiresAt: s.now().Add(Lifetime)}
	s.byID[id] = &exchange{routeID: routeID, secret: []byte(secret), expires: handle.ExpiresAt}
	s.byRoute[routeID] = id
	s.lastCreate[routeID] = s.now()
	return handle, nil
}

// SubmitRequest stores the device's one message; only the first submission
// with the correct secret is accepted.
func (s *Store) SubmitRequest(routeID, id, secret string, message []byte) error {
	if len(message) == 0 || len(message) > MaxMessageBytes {
		return ErrMessageSize
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	exchange, err := s.findLocked(routeID, id)
	if err != nil {
		return err
	}
	if err := s.checkSecretLocked(id, exchange, secret); err != nil {
		return err
	}
	if exchange.request != nil {
		return ErrDuplicate
	}
	exchange.request = append([]byte(nil), message...)
	return nil
}

// Request returns the device message for the route owner.
func (s *Store) Request(routeID, id string) ([]byte, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	exchange, err := s.findLocked(routeID, id)
	if err != nil {
		return nil, err
	}
	if exchange.request == nil {
		return nil, ErrNotReady
	}
	return append([]byte(nil), exchange.request...), nil
}

// MarkProvisional records that the next device-data attachment for the route
// belongs to an accepted provisional pairing.
func (s *Store) MarkProvisional(routeID string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.provisional == nil {
		s.provisional = map[string]time.Time{}
	}
	s.provisional[routeID] = s.now().Add(Lifetime)
}

// ConsumeProvisional reports and clears a provisional pairing mark for the route.
func (s *Store) ConsumeProvisional(routeID string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	until, ok := s.provisional[routeID]
	if !ok {
		return false
	}
	delete(s.provisional, routeID)
	return s.now().Before(until)
}

// SubmitReply stores the route owner's one reply; it requires a request.
func (s *Store) SubmitReply(routeID, id string, message []byte) error {
	if len(message) == 0 || len(message) > MaxMessageBytes {
		return ErrMessageSize
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	exchange, err := s.findLocked(routeID, id)
	if err != nil {
		return err
	}
	if exchange.request == nil {
		return ErrNoRequest
	}
	if exchange.reply != nil {
		return ErrDuplicate
	}
	exchange.reply = append([]byte(nil), message...)
	return nil
}

// TakeReply delivers the reply once and destroys the exchange.
func (s *Store) TakeReply(routeID, id, secret string) ([]byte, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	exchange, err := s.findLocked(routeID, id)
	if err != nil {
		return nil, err
	}
	if err := s.checkSecretLocked(id, exchange, secret); err != nil {
		return nil, err
	}
	if exchange.reply == nil {
		return nil, ErrNotReady
	}
	reply := append([]byte(nil), exchange.reply...)
	s.destroyLocked(id)
	return reply, nil
}

func (s *Store) findLocked(routeID, id string) (*exchange, error) {
	exchange, ok := s.byID[id]
	if !ok || exchange.routeID != routeID {
		return nil, ErrNotFound
	}
	if !exchange.expires.After(s.now()) {
		s.destroyLocked(id)
		return nil, ErrExpired
	}
	return exchange, nil
}

func (s *Store) checkSecretLocked(id string, exchange *exchange, secret string) error {
	if subtle.ConstantTimeCompare(exchange.secret, []byte(secret)) == 1 {
		return nil
	}
	exchange.failed++
	if exchange.failed >= MaxFailedAttempts {
		s.destroyLocked(id)
	}
	return ErrSecret
}

func (s *Store) destroyLocked(id string) {
	if exchange, ok := s.byID[id]; ok {
		delete(s.byID, id)
		if s.byRoute[exchange.routeID] == id {
			delete(s.byRoute, exchange.routeID)
		}
	}
}

func (s *Store) cleanupLocked() {
	now := s.now()
	for id, exchange := range s.byID {
		if !exchange.expires.After(now) {
			s.destroyLocked(id)
		}
	}
}

func randomString(size int) (string, error) {
	value := make([]byte, size)
	if _, err := rand.Read(value); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(value), nil
}
