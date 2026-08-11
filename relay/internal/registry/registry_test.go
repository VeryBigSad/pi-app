package registry

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"path/filepath"
	"testing"
	"time"

	bbolt "go.etcd.io/bbolt"
)

func TestRegistryPersistsOnlyKeyAndRevocation(t *testing.T) {
	path := filepath.Join(t.TempDir(), "registry.db")
	keys := openRegistry(t, path)
	der := publicDER(t)
	if err := keys.Register("route-1", "key-1", der, RoleMac); err != nil {
		t.Fatal(err)
	}
	if err := keys.Register("route-1", "key-1", der, RoleMac); err != ErrExists {
		t.Fatalf("duplicate registration error = %v", err)
	}
	key, err := keys.Lookup("route-1", "key-1")
	if err != nil || key.Revoked || key.Role != RoleMac || string(key.SPKIDER) != string(der) {
		t.Fatalf("lookup = %#v, %v", key, err)
	}
	if err := keys.Revoke("route-1", "key-1"); err != nil {
		t.Fatal(err)
	}
	if _, err := keys.Lookup("route-1", "key-1"); err != ErrRevoked {
		t.Fatalf("revoked lookup error = %v", err)
	}
	if err := keys.Close(); err != nil {
		t.Fatal(err)
	}
	inspect, err := bbolt.Open(path, 0o600, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer inspect.Close()
	if err := inspect.View(func(tx *bbolt.Tx) error {
		cursor := tx.Cursor()
		name, _ := cursor.First()
		next, _ := cursor.Next()
		if tx.Bucket([]byte("routes")) == nil || string(name) != "routes" || next != nil {
			t.Fatal("registry has state outside routes bucket")
		}
		route := tx.Bucket([]byte("routes")).Bucket([]byte("route-1"))
		value := route.Get([]byte("key-1"))
		if len(value) != len(der)+11 || value[0] != keyFormatV2 || value[len(value)-10] != byte(RoleMac) || value[len(value)-9] != 1 || string(value[1:len(value)-10]) != string(der) {
			t.Fatal("registry record includes unexpected state")
		}
		return nil
	}); err != nil {
		t.Fatal(err)
	}
}

func TestRegistryRejectsWrongCurve(t *testing.T) {
	keys := openRegistry(t, filepath.Join(t.TempDir(), "registry.db"))
	privateKey, err := ecdsa.GenerateKey(elliptic.P384(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	der, err := x509.MarshalPKIXPublicKey(&privateKey.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	if err := keys.Register("route-1", "key-1", der, RoleMac); err == nil {
		t.Fatal("P-384 key accepted")
	}
}

func TestRegisterSuccessorOverlapAndRetirement(t *testing.T) {
	keys := openRegistry(t, filepath.Join(t.TempDir(), "registry.db"))
	now := time.Date(2026, 8, 10, 12, 0, 0, 0, time.UTC)
	keys.SetClock(func() time.Time { return now })
	oldDER := publicDER(t)
	newDER := publicDER(t)
	if err := keys.Register("route-1", "key-1", oldDER, RoleMac); err != nil {
		t.Fatal(err)
	}
	if err := keys.RegisterSuccessor("route-1", "key-1", "key-2", newDER, time.Hour); err != nil {
		t.Fatal(err)
	}
	for _, keyID := range []string{"key-1", "key-2"} {
		key, err := keys.Lookup("route-1", keyID)
		if err != nil || key.Role != RoleMac || key.Revoked {
			t.Fatalf("overlap lookup %s = %#v, %v", keyID, key, err)
		}
	}
	predecessor, err := keys.Lookup("route-1", "key-1")
	if err != nil || predecessor.RetiresAt != now.Add(time.Hour) {
		t.Fatalf("predecessor retirement = %#v, %v", predecessor, err)
	}
	now = now.Add(time.Hour)
	if _, err := keys.Lookup("route-1", "key-1"); err != ErrRevoked {
		t.Fatalf("retired lookup error = %v", err)
	}
	if _, err := keys.Lookup("route-1", "key-2"); err != nil {
		t.Fatalf("successor lookup error = %v", err)
	}
	if err := keys.RegisterSuccessor("route-1", "key-1", "key-3", publicDER(t), time.Hour); err != ErrRevoked {
		t.Fatalf("rotation from retired predecessor error = %v", err)
	}
}

func TestRegisterSuccessorFailuresAreAtomic(t *testing.T) {
	keys := openRegistry(t, filepath.Join(t.TempDir(), "registry.db"))
	macDER := publicDER(t)
	deviceDER := publicDER(t)
	if err := keys.Register("route-1", "key-1", macDER, RoleMac); err != nil {
		t.Fatal(err)
	}
	if err := keys.Register("route-1", "device-1", deviceDER, RoleDevice); err != nil {
		t.Fatal(err)
	}
	if err := keys.RegisterSuccessor("route-1", "device-1", "key-2", publicDER(t), time.Hour); err != ErrRole {
		t.Fatalf("device predecessor error = %v", err)
	}
	if err := keys.RegisterSuccessor("route-1", "key-1", "device-1", publicDER(t), time.Hour); err != ErrExists {
		t.Fatalf("existing successor error = %v", err)
	}
	if err := keys.RegisterSuccessor("route-1", "missing", "key-2", publicDER(t), time.Hour); err != ErrNotFound {
		t.Fatalf("missing predecessor error = %v", err)
	}
	if err := keys.RegisterSuccessor("route-1", "key-1", "key-2", publicDER(t), 0); err != ErrOverlap {
		t.Fatalf("zero overlap error = %v", err)
	}
	if err := keys.RegisterSuccessor("route-1", "key-1", "key-2", publicDER(t), MaxRotationOverlap+time.Second); err != ErrOverlap {
		t.Fatalf("oversized overlap error = %v", err)
	}
	predecessor, err := keys.Lookup("route-1", "key-1")
	if err != nil || !predecessor.RetiresAt.IsZero() {
		t.Fatalf("failed rotation mutated predecessor: %#v, %v", predecessor, err)
	}
	if _, err := keys.Lookup("route-1", "key-2"); err != ErrNotFound {
		t.Fatalf("failed rotation left successor: %v", err)
	}
}

func TestRevokePreservesScheduledRetirement(t *testing.T) {
	keys := openRegistry(t, filepath.Join(t.TempDir(), "registry.db"))
	now := time.Date(2026, 8, 10, 12, 0, 0, 0, time.UTC)
	keys.SetClock(func() time.Time { return now })
	if err := keys.Register("route-1", "key-1", publicDER(t), RoleMac); err != nil {
		t.Fatal(err)
	}
	if err := keys.RegisterSuccessor("route-1", "key-1", "key-2", publicDER(t), time.Hour); err != nil {
		t.Fatal(err)
	}
	if err := keys.Revoke("route-1", "key-2"); err != nil {
		t.Fatal(err)
	}
	if _, err := keys.Lookup("route-1", "key-2"); err != ErrRevoked {
		t.Fatalf("revoked successor error = %v", err)
	}
	key, err := keys.lookupRaw("route-1", "key-1")
	if err != nil || key.RetiresAt != now.Add(time.Hour) {
		t.Fatalf("retirement lost after sibling revoke: %#v, %v", key, err)
	}
}

func openRegistry(t *testing.T, path string) *Registry {
	t.Helper()
	keys, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = keys.Close() })
	return keys
}

func publicDER(t *testing.T) []byte {
	t.Helper()
	privateKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	der, err := x509.MarshalPKIXPublicKey(&privateKey.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	return der
}
