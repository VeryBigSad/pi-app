package rendezvous

import (
	"sync"
	"testing"
	"time"
)

func TestOneUseMatchAndNonceBinding(t *testing.T) {
	now := time.Date(2026, 8, 9, 12, 0, 0, 0, time.UTC)
	store := New[string](func() time.Time { return now })
	notice, done, err := store.Start("route-1", "device")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.Take("route-1", notice.ID, "wrong"); err != ErrNonce {
		t.Fatalf("wrong nonce error = %v", err)
	}
	match, err := store.Take("route-1", notice.ID, notice.Nonce)
	if err != nil || match.Peer != "device" || match.Done != done {
		t.Fatalf("match = %#v, %v", match, err)
	}
	if _, err := store.Take("route-1", notice.ID, notice.Nonce); err != ErrNotFound {
		t.Fatalf("reused notice error = %v", err)
	}
	close(match.Done)
}

func TestExpiryAndConcurrentStartAreBounded(t *testing.T) {
	now := time.Date(2026, 8, 9, 12, 0, 0, 0, time.UTC)
	store := New[int](func() time.Time { return now })
	notice, done, err := store.Start("route-1", 1)
	if err != nil {
		t.Fatal(err)
	}
	now = now.Add(Lifetime)
	if _, err := store.Take("route-1", notice.ID, notice.Nonce); err != ErrExpired {
		t.Fatalf("expired match error = %v", err)
	}
	select {
	case <-done:
	default:
		t.Fatal("expiry did not release waiting endpoint")
	}
	var wg sync.WaitGroup
	var successes int
	var mu sync.Mutex
	for i := 0; i < 100; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			_, _, err := store.Start("same-route", i)
			if err == nil {
				mu.Lock()
				successes++
				mu.Unlock()
			}
		}(i)
	}
	wg.Wait()
	if successes != 1 {
		t.Fatalf("starts accepted = %d, want 1", successes)
	}
}
