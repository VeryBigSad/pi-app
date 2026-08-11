// Package bootstrap consumes the one-time route-registration token.
//
// Consumption is fail-closed and crash-safe: the token file is claimed with an
// atomic rename followed by a directory fsync before the registration callback
// runs, so a crash can never leave both a registered route and a reusable
// token. The rename target is a spent tombstone that is scrubbed and never
// readable as a token again; any crash residue recovers to the spent state.
package bootstrap

import (
	"crypto/subtle"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
)

var (
	ErrUnavailable = errors.New("bootstrap token unavailable")
	ErrInvalid     = errors.New("bootstrap token invalid")
)

const spentSuffix = ".spent"

// Fault-injection hooks; overridden only by in-package tests.
var (
	renameFile    = os.Rename
	writeFile     = os.WriteFile
	syncDirectory = syncDir
	syncClaimFile = syncPath
)

type TokenFile struct {
	path string
	mu   sync.Mutex
}

func New(path string) *TokenFile {
	token := &TokenFile{path: path}
	token.recover()
	return token
}

// recover scrubs crash residue; a spent tombstone is never reusable, even if
// the process died between claiming the token and scrubbing it.
func (t *TokenFile) recover() {
	spent := t.path + spentSuffix
	if info, err := os.Stat(spent); err == nil && info.Mode().IsRegular() {
		_ = writeFile(spent, []byte("spent\n"), 0o600)
		_ = syncClaimFile(spent)
	}
}

func (t *TokenFile) Consume(token string, register func() error) error {
	t.mu.Lock()
	defer t.mu.Unlock()
	expected, err := t.read()
	if err != nil {
		return err
	}
	if len(expected) == 0 || subtle.ConstantTimeCompare(expected, []byte(token)) != 1 {
		return ErrInvalid
	}
	if err := t.claim(); err != nil {
		return err
	}
	return register()
}

// claim durably retires the token before the registration callback runs.
func (t *TokenFile) claim() error {
	dir := filepath.Dir(t.path)
	spent := t.path + spentSuffix
	if err := renameFile(t.path, spent); err != nil {
		return ErrUnavailable
	}
	if err := syncDirectory(dir); err != nil {
		return ErrUnavailable
	}
	if err := writeFile(spent, []byte("spent\n"), 0o600); err != nil {
		return ErrUnavailable
	}
	if err := syncClaimFile(spent); err != nil {
		return ErrUnavailable
	}
	return syncDirectory(dir)
}

func (t *TokenFile) read() ([]byte, error) {
	info, err := os.Stat(t.path)
	if err != nil || !info.Mode().IsRegular() || info.Size() > 1024 || info.Mode().Perm()&0o077 != 0 {
		return nil, ErrUnavailable
	}
	raw, err := os.ReadFile(t.path)
	if err != nil {
		return nil, ErrUnavailable
	}
	return []byte(strings.TrimSpace(string(raw))), nil
}

func syncDir(path string) error {
	dir, err := os.Open(path)
	if err != nil {
		return err
	}
	defer dir.Close()
	return dir.Sync()
}

func syncPath(path string) error {
	file, err := os.OpenFile(path, os.O_RDWR, 0)
	if err != nil {
		return err
	}
	defer file.Close()
	return file.Sync()
}
