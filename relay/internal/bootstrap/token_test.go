package bootstrap

import (
	"errors"
	"os"
	"path/filepath"
	"sync"
	"testing"
)

func TestConsumeRunsRegistrationOnceAndErasesToken(t *testing.T) {
	path := writeToken(t)
	calls := 0
	token := New(path)
	if err := token.Consume("token-value", func() error { calls++; return nil }); err != nil {
		t.Fatal(err)
	}
	if calls != 1 {
		t.Fatalf("registration calls = %d", calls)
	}
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatalf("token remains: %v", err)
	}
	if err := token.Consume("token-value", func() error { calls++; return nil }); err != ErrUnavailable {
		t.Fatalf("reused token error = %v", err)
	}
}

func TestConsumeClaimsTokenBeforeRegistration(t *testing.T) {
	path := writeToken(t)
	token := New(path)
	if err := token.Consume("token-value", func() error {
		if _, err := os.Stat(path); !os.IsNotExist(err) {
			t.Fatalf("token still available during registration: %v", err)
		}
		spent, err := os.ReadFile(path + spentSuffix)
		if err != nil || string(spent) != "spent\n" {
			t.Fatalf("spent tombstone = %q, %v", spent, err)
		}
		return nil
	}); err != nil {
		t.Fatal(err)
	}
}

func TestConsumeRejectsWrongTokenWithoutClaiming(t *testing.T) {
	path := writeToken(t)
	token := New(path)
	if err := token.Consume("wrong", func() error { t.Fatal("called with wrong token"); return nil }); err != ErrInvalid {
		t.Fatalf("wrong token error = %v", err)
	}
	if _, err := os.Stat(path); err != nil {
		t.Fatalf("token erased after wrong token: %v", err)
	}
	if _, err := os.Stat(path + spentSuffix); !os.IsNotExist(err) {
		t.Fatalf("tombstone left after wrong token: %v", err)
	}
}

func TestConsumeFailedRegistrationLeavesTokenSpent(t *testing.T) {
	path := writeToken(t)
	token := New(path)
	if err := token.Consume("token-value", func() error { return errors.New("storage failure") }); err == nil {
		t.Fatal("failed registration accepted")
	}
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatalf("token restored after failed registration: %v", err)
	}
	if err := token.Consume("token-value", func() error { return nil }); err != ErrUnavailable {
		t.Fatalf("token reusable after failed registration: %v", err)
	}
}

func TestConsumeCrashBetweenClaimAndRegistrationNeverReusable(t *testing.T) {
	path := writeToken(t)
	injected := errors.New("injected crash")
	restore := swapSyncDirectory(func(string) error { return injected })
	token := New(path)
	if err := token.Consume("token-value", func() error { t.Fatal("registration ran after crash"); return nil }); err != ErrUnavailable {
		t.Fatalf("crash error = %v", err)
	}
	restore()
	restarted := New(path)
	if err := restarted.Consume("token-value", func() error { t.Fatal("crash residue reused"); return nil }); err != ErrUnavailable {
		t.Fatalf("crash residue error = %v", err)
	}
	spent, err := os.ReadFile(path + spentSuffix)
	if err != nil || string(spent) != "spent\n" {
		t.Fatalf("residue not scrubbed on recovery: %q, %v", spent, err)
	}
}

func TestConsumeCrashBeforeClaimLeavesTokenUsable(t *testing.T) {
	path := writeToken(t)
	injected := errors.New("injected crash")
	restore := swapRename(func(string, string) error { return injected })
	token := New(path)
	if err := token.Consume("token-value", func() error { t.Fatal("registration ran after failed claim"); return nil }); err != ErrUnavailable {
		t.Fatalf("claim failure error = %v", err)
	}
	restore()
	restarted := New(path)
	if err := restarted.Consume("token-value", func() error { return nil }); err != nil {
		t.Fatalf("token unusable after pre-claim crash: %v", err)
	}
}

func TestConsumeCrashDuringScrubNeverReusable(t *testing.T) {
	path := writeToken(t)
	injected := errors.New("injected crash")
	restore := swapWriteFile(func(name string, data []byte, perm os.FileMode) error {
		if name == path+spentSuffix {
			return injected
		}
		return os.WriteFile(name, data, perm)
	})
	token := New(path)
	if err := token.Consume("token-value", func() error { t.Fatal("registration ran after scrub crash"); return nil }); err != ErrUnavailable {
		t.Fatalf("scrub crash error = %v", err)
	}
	restore()
	restarted := New(path)
	if err := restarted.Consume("token-value", func() error { t.Fatal("unscrubbed residue reused"); return nil }); err != ErrUnavailable {
		t.Fatalf("unscrubbed residue error = %v", err)
	}
}

func TestConsumeConcurrentClaimsSucceedOnce(t *testing.T) {
	path := writeToken(t)
	token := New(path)
	const attempts = 32
	var wg sync.WaitGroup
	var mu sync.Mutex
	succeeded, registrations := 0, 0
	for i := 0; i < attempts; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if err := token.Consume("token-value", func() error { mu.Lock(); registrations++; mu.Unlock(); return nil }); err == nil {
				mu.Lock()
				succeeded++
				mu.Unlock()
			}
		}()
	}
	wg.Wait()
	if succeeded != 1 || registrations != 1 {
		t.Fatalf("succeeded = %d, registrations = %d, want 1", succeeded, registrations)
	}
}

func TestConsumeRestartAfterSuccessKeepsTokenSpent(t *testing.T) {
	path := writeToken(t)
	if err := New(path).Consume("token-value", func() error { return nil }); err != nil {
		t.Fatal(err)
	}
	restarted := New(path)
	if err := restarted.Consume("token-value", func() error { t.Fatal("spent token reused after restart"); return nil }); err != ErrUnavailable {
		t.Fatalf("restart reuse error = %v", err)
	}
	spent, err := os.ReadFile(path + spentSuffix)
	if err != nil || string(spent) != "spent\n" {
		t.Fatalf("tombstone holds raw token: %q, %v", spent, err)
	}
}

func TestConsumeRejectsBroadPermissions(t *testing.T) {
	path := filepath.Join(t.TempDir(), "bootstrap.token")
	if err := os.WriteFile(path, []byte("token-value"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := New(path).Consume("token-value", func() error { return nil }); err != ErrUnavailable {
		t.Fatalf("broad permissions error = %v", err)
	}
}

func writeToken(t *testing.T) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "bootstrap.token")
	if err := os.WriteFile(path, []byte("token-value\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

func swapRename(hook func(string, string) error) func() {
	original := renameFile
	renameFile = hook
	return func() { renameFile = original }
}

func swapWriteFile(hook func(string, []byte, os.FileMode) error) func() {
	original := writeFile
	writeFile = hook
	return func() { writeFile = original }
}

func swapSyncDirectory(hook func(string) error) func() {
	original := syncDirectory
	syncDirectory = hook
	return func() { syncDirectory = original }
}
