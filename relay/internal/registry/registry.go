// Package registry stores route public keys and their revocation state only.
package registry

import (
	"encoding/binary"
	"errors"
	"os"
	"path/filepath"
	"sync/atomic"
	"time"

	"github.com/VeryBigSad/pi-app/relay/internal/auth"
	bbolt "go.etcd.io/bbolt"
)

var (
	ErrNotFound = errors.New("route key not found")
	ErrExists   = errors.New("route key already exists")
	ErrRevoked  = errors.New("route key revoked")
	ErrRole     = errors.New("route key role mismatch")
	ErrOverlap  = errors.New("rotation overlap out of bounds")
)

// MaxRotationOverlap bounds how long a retired predecessor key stays usable.
const MaxRotationOverlap = 24 * time.Hour

var routesBucket = []byte("routes")

// keyFormatV2 prefixes records that carry a retirement timestamp. Legacy
// records start with the DER SEQUENCE tag 0x30, so the formats never collide.
const keyFormatV2 = 0x02

type Role byte

const (
	RoleMac    Role = 1
	RoleDevice Role = 2
)

type Key struct {
	SPKIDER   []byte
	Role      Role
	Revoked   bool
	RetiresAt time.Time
}

type Registry struct {
	db  *bbolt.DB
	now atomic.Value
}

func Open(path string) (*Registry, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, err
	}
	db, err := bbolt.Open(path, 0o600, &bbolt.Options{NoSync: false})
	if err != nil {
		return nil, err
	}
	registry := &Registry{db: db}
	registry.now.Store(time.Now)
	if err := db.Update(func(tx *bbolt.Tx) error {
		_, err := tx.CreateBucketIfNotExists(routesBucket)
		return err
	}); err != nil {
		_ = db.Close()
		return nil, err
	}
	return registry, nil
}

func (r *Registry) Close() error {
	return r.db.Close()
}

// SetClock overrides the clock used for retirement checks.
func (r *Registry) SetClock(now func() time.Time) {
	r.now.Store(now)
}

func (r *Registry) clock() time.Time {
	return r.now.Load().(func() time.Time)()
}

func (r *Registry) Register(routeID, keyID string, spkiDER []byte, role Role) error {
	if !auth.ValidID(routeID) || !auth.ValidID(keyID) || role != RoleMac && role != RoleDevice {
		return auth.ErrMalformed
	}
	if _, err := auth.ParseP256SPKI(spkiDER); err != nil {
		return err
	}
	return r.db.Update(func(tx *bbolt.Tx) error {
		routes := tx.Bucket(routesBucket)
		route, err := routes.CreateBucketIfNotExists([]byte(routeID))
		if err != nil {
			return err
		}
		if route.Get([]byte(keyID)) != nil {
			return ErrExists
		}
		return route.Put([]byte(keyID), encodeKey(Key{SPKIDER: spkiDER, Role: role}))
	})
}

// RegisterSuccessor atomically registers a replacement Mac key and schedules
// the predecessor's retirement, so both keys are accepted exactly during the
// overlap window.
func (r *Registry) RegisterSuccessor(routeID, predecessorID, keyID string, spkiDER []byte, overlap time.Duration) error {
	if !auth.ValidID(routeID) || !auth.ValidID(predecessorID) || !auth.ValidID(keyID) {
		return auth.ErrMalformed
	}
	if overlap <= 0 || overlap > MaxRotationOverlap {
		return ErrOverlap
	}
	if _, err := auth.ParseP256SPKI(spkiDER); err != nil {
		return err
	}
	return r.db.Update(func(tx *bbolt.Tx) error {
		route := tx.Bucket(routesBucket).Bucket([]byte(routeID))
		if route == nil {
			return ErrNotFound
		}
		predecessor, err := parseKey(route.Get([]byte(predecessorID)))
		if err != nil {
			return err
		}
		if predecessor.Role != RoleMac {
			return ErrRole
		}
		if retired(predecessor, r.clock()) {
			return ErrRevoked
		}
		if route.Get([]byte(keyID)) != nil {
			return ErrExists
		}
		predecessor.RetiresAt = r.clock().Add(overlap).UTC()
		if err := route.Put([]byte(predecessorID), encodeKey(predecessor)); err != nil {
			return err
		}
		return route.Put([]byte(keyID), encodeKey(Key{SPKIDER: spkiDER, Role: RoleMac}))
	})
}

func (r *Registry) Lookup(routeID, keyID string) (Key, error) {
	var key Key
	if !auth.ValidID(routeID) || !auth.ValidID(keyID) {
		return Key{}, ErrNotFound
	}
	err := r.db.View(func(tx *bbolt.Tx) error {
		route := tx.Bucket(routesBucket).Bucket([]byte(routeID))
		if route == nil {
			return ErrNotFound
		}
		var err error
		key, err = parseKey(route.Get([]byte(keyID)))
		return err
	})
	if err != nil {
		return Key{}, err
	}
	if retired(key, r.clock()) {
		return Key{}, ErrRevoked
	}
	return key, nil
}

// lookupRaw returns the stored key without applying revocation or retirement.
func (r *Registry) lookupRaw(routeID, keyID string) (Key, error) {
	var key Key
	err := r.db.View(func(tx *bbolt.Tx) error {
		route := tx.Bucket(routesBucket).Bucket([]byte(routeID))
		if route == nil {
			return ErrNotFound
		}
		var err error
		key, err = parseKey(route.Get([]byte(keyID)))
		return err
	})
	return key, err
}

func (r *Registry) Revoke(routeID, keyID string) error {
	if !auth.ValidID(routeID) || !auth.ValidID(keyID) {
		return ErrNotFound
	}
	return r.db.Update(func(tx *bbolt.Tx) error {
		route := tx.Bucket(routesBucket).Bucket([]byte(routeID))
		if route == nil {
			return ErrNotFound
		}
		key, err := parseKey(route.Get([]byte(keyID)))
		if err != nil {
			return err
		}
		key.Revoked = true
		return route.Put([]byte(keyID), encodeKey(key))
	})
}

func retired(key Key, now time.Time) bool {
	return key.Revoked || !key.RetiresAt.IsZero() && !now.Before(key.RetiresAt)
}

func parseKey(value []byte) (Key, error) {
	if len(value) >= 12 && value[0] == keyFormatV2 {
		end := len(value) - 10
		role := Role(value[end])
		if role != RoleMac && role != RoleDevice {
			return Key{}, ErrNotFound
		}
		key := Key{SPKIDER: append([]byte(nil), value[1:end]...), Role: role, Revoked: value[end+1] == 1}
		if retire := int64(binary.BigEndian.Uint64(value[end+2:])); retire > 0 {
			key.RetiresAt = time.Unix(0, retire).UTC()
		}
		return key, nil
	}
	if len(value) < 3 {
		return Key{}, ErrNotFound
	}
	role := Role(value[len(value)-2])
	if role != RoleMac && role != RoleDevice {
		return Key{}, ErrNotFound
	}
	return Key{SPKIDER: append([]byte(nil), value[:len(value)-2]...), Role: role, Revoked: value[len(value)-1] == 1}, nil
}

func encodeKey(key Key) []byte {
	value := make([]byte, 0, len(key.SPKIDER)+11)
	value = append(value, keyFormatV2)
	value = append(value, key.SPKIDER...)
	value = append(value, byte(key.Role))
	if key.Revoked {
		value = append(value, 1)
	} else {
		value = append(value, 0)
	}
	var retire [8]byte
	if !key.RetiresAt.IsZero() {
		binary.BigEndian.PutUint64(retire[:], uint64(key.RetiresAt.UnixNano()))
	}
	return append(value, retire[:]...)
}
