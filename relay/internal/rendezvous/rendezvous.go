// Package rendezvous holds one-use data matches in memory only.
package rendezvous

import (
	"crypto/rand"
	"encoding/base64"
	"errors"
	"sync"
	"time"
)

const (
	Lifetime   = 20 * time.Second
	MaxPending = 1_000
)

var (
	ErrBusy     = errors.New("rendezvous already pending")
	ErrFull     = errors.New("rendezvous capacity reached")
	ErrNotFound = errors.New("rendezvous not found")
	ErrExpired  = errors.New("rendezvous expired")
	ErrNonce    = errors.New("rendezvous nonce mismatch")
)

type Notice struct {
	ID        string
	Nonce     string
	ExpiresAt time.Time
}

type Match[T any] struct {
	Peer T
	Done chan struct{}
}

type entry[T any] struct {
	routeID string
	nonce   string
	expires time.Time
	peer    T
	done    chan struct{}
}

type Store[T any] struct {
	mu      sync.Mutex
	entries map[string]entry[T]
	now     func() time.Time
	newID   func() (string, error)
}

func New[T any](now func() time.Time) *Store[T] {
	if now == nil {
		now = time.Now
	}
	return &Store[T]{entries: make(map[string]entry[T]), now: now, newID: randomID}
}

func (s *Store[T]) Start(routeID string, peer T) (Notice, <-chan struct{}, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.cleanupLocked()
	if len(s.entries) >= MaxPending {
		return Notice{}, nil, ErrFull
	}
	for _, existing := range s.entries {
		if existing.routeID == routeID {
			return Notice{}, nil, ErrBusy
		}
	}
	id, err := s.newID()
	if err != nil {
		return Notice{}, nil, err
	}
	nonce, err := randomNonce()
	if err != nil {
		return Notice{}, nil, err
	}
	expiresAt := s.now().Add(Lifetime)
	done := make(chan struct{})
	s.entries[id] = entry[T]{routeID: routeID, nonce: nonce, expires: expiresAt, peer: peer, done: done}
	return Notice{ID: id, Nonce: nonce, ExpiresAt: expiresAt}, done, nil
}

func (s *Store[T]) Take(routeID, id, nonce string) (Match[T], error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	entry, ok := s.entries[id]
	if !ok || entry.routeID != routeID {
		return Match[T]{}, ErrNotFound
	}
	if !entry.expires.After(s.now()) {
		delete(s.entries, id)
		close(entry.done)
		return Match[T]{}, ErrExpired
	}
	if entry.nonce != nonce {
		return Match[T]{}, ErrNonce
	}
	delete(s.entries, id)
	return Match[T]{Peer: entry.peer, Done: entry.done}, nil
}

// Cancel reports whether a pending entry was cancelled. A false result means
// the rendezvous was already taken (splice active) or never existed; callers
// must not tear down an active splice in that case.
func (s *Store[T]) Cancel(id string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if entry, ok := s.entries[id]; ok {
		delete(s.entries, id)
		close(entry.done)
		return true
	}
	return false
}

func (s *Store[T]) cleanupLocked() {
	now := s.now()
	for id, entry := range s.entries {
		if !entry.expires.After(now) {
			delete(s.entries, id)
			close(entry.done)
		}
	}
}

func randomID() (string, error) {
	return randomString(18)
}

func randomNonce() (string, error) {
	return randomString(32)
}

func randomString(size int) (string, error) {
	value := make([]byte, size)
	if _, err := rand.Read(value); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(value), nil
}
