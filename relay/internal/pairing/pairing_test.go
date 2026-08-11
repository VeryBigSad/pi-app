package pairing

import (
	"sync"
	"testing"
	"time"
)

func TestOneShotExchangeClosesAfterDelivery(t *testing.T) {
	now := time.Date(2026, 8, 10, 12, 0, 0, 0, time.UTC)
	store := New(func() time.Time { return now })
	handle, err := store.Open("route-1")
	if err != nil {
		t.Fatal(err)
	}
	if err := store.SubmitRequest("route-1", handle.ID, handle.Secret, []byte("csr")); err != nil {
		t.Fatal(err)
	}
	if err := store.SubmitRequest("route-1", handle.ID, handle.Secret, []byte("again")); err != ErrDuplicate {
		t.Fatalf("duplicate request error = %v", err)
	}
	request, err := store.Request("route-1", handle.ID)
	if err != nil || string(request) != "csr" {
		t.Fatalf("request = %q, %v", request, err)
	}
	if err := store.SubmitReply("route-1", handle.ID, []byte("cert")); err != nil {
		t.Fatal(err)
	}
	if err := store.SubmitReply("route-1", handle.ID, []byte("again")); err != ErrDuplicate {
		t.Fatalf("duplicate reply error = %v", err)
	}
	reply, err := store.TakeReply("route-1", handle.ID, handle.Secret)
	if err != nil || string(reply) != "cert" {
		t.Fatalf("reply = %q, %v", reply, err)
	}
	if _, err := store.TakeReply("route-1", handle.ID, handle.Secret); err != ErrNotFound {
		t.Fatalf("exchange survived delivery: %v", err)
	}
	if _, err := store.Request("route-1", handle.ID); err != ErrNotFound {
		t.Fatalf("request readable after close: %v", err)
	}
}

func TestExchangeRequiresRequestBeforeReplyAndSecret(t *testing.T) {
	store := New(nil)
	handle, err := store.Open("route-1")
	if err != nil {
		t.Fatal(err)
	}
	if err := store.SubmitReply("route-1", handle.ID, []byte("cert")); err != ErrNoRequest {
		t.Fatalf("reply without request error = %v", err)
	}
	if err := store.SubmitRequest("route-1", handle.ID, "wrong", []byte("csr")); err != ErrSecret {
		t.Fatalf("wrong secret error = %v", err)
	}
	if _, err := store.TakeReply("route-1", handle.ID, "wrong"); err != ErrSecret {
		t.Fatalf("wrong reply secret error = %v", err)
	}
	if _, err := store.Request("route-1", handle.ID); err != ErrNotReady {
		t.Fatalf("missing request error = %v", err)
	}
	if err := store.SubmitRequest("route-1", handle.ID, handle.Secret, []byte("csr")); err != nil {
		t.Fatal(err)
	}
	if _, err := store.TakeReply("route-1", handle.ID, handle.Secret); err != ErrNotReady {
		t.Fatalf("missing reply error = %v", err)
	}
	if _, err := store.Request("other-route", handle.ID); err != ErrNotFound {
		t.Fatalf("cross-route read error = %v", err)
	}
}

func TestExchangeExpiryAndSingleActivePerRoute(t *testing.T) {
	now := time.Date(2026, 8, 10, 12, 0, 0, 0, time.UTC)
	store := New(func() time.Time { return now })
	handle, err := store.Open("route-1")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.Open("route-1"); err != ErrBusy {
		t.Fatalf("second open error = %v", err)
	}
	now = now.Add(Lifetime)
	if err := store.SubmitRequest("route-1", handle.ID, handle.Secret, []byte("csr")); err != ErrExpired {
		t.Fatalf("expired submit error = %v", err)
	}
	now = now.Add(CreateCooldown)
	if _, err := store.Open("route-1"); err != nil {
		t.Fatalf("re-open after expiry failed: %v", err)
	}
}

func TestCreationCooldownAndFailedAttemptsDestroy(t *testing.T) {
	now := time.Date(2026, 8, 10, 12, 0, 0, 0, time.UTC)
	store := New(func() time.Time { return now })
	handle, err := store.Open("route-1")
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < MaxFailedAttempts; i++ {
		if err := store.SubmitRequest("route-1", handle.ID, "wrong", []byte("csr")); err != ErrSecret {
			t.Fatalf("attempt %d error = %v", i, err)
		}
	}
	if err := store.SubmitRequest("route-1", handle.ID, handle.Secret, []byte("csr")); err != ErrNotFound {
		t.Fatalf("brute-forced exchange error = %v", err)
	}
	if _, err := store.Open("route-1"); err != ErrRateLimited {
		t.Fatalf("cooldown error = %v", err)
	}
	now = now.Add(CreateCooldown)
	if _, err := store.Open("route-1"); err != nil {
		t.Fatalf("open after cooldown failed: %v", err)
	}
}

func TestCapacityAndConcurrentOpenAreBounded(t *testing.T) {
	store := New(nil)
	store.random = sequentialIDs()
	for i := 0; i < MaxActive; i++ {
		if _, err := store.Open(routeName(i)); err != nil {
			t.Fatalf("open %d: %v", i, err)
		}
	}
	if _, err := store.Open("route-overflow"); err != ErrFull {
		t.Fatalf("overflow error = %v", err)
	}
	store = New(nil)
	var wg sync.WaitGroup
	var mu sync.Mutex
	succeeded := 0
	for i := 0; i < 64; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, err := store.Open("same-route"); err == nil {
				mu.Lock()
				succeeded++
				mu.Unlock()
			}
		}()
	}
	wg.Wait()
	if succeeded != 1 {
		t.Fatalf("concurrent opens succeeded = %d, want 1", succeeded)
	}
}

func TestMessageSizeIsBounded(t *testing.T) {
	store := New(nil)
	handle, err := store.Open("route-1")
	if err != nil {
		t.Fatal(err)
	}
	if err := store.SubmitRequest("route-1", handle.ID, handle.Secret, make([]byte, MaxMessageBytes+1)); err != ErrMessageSize {
		t.Fatalf("oversized request error = %v", err)
	}
	if err := store.SubmitRequest("route-1", handle.ID, handle.Secret, nil); err != ErrMessageSize {
		t.Fatalf("empty request error = %v", err)
	}
}

func sequentialIDs() func(int) (string, error) {
	var mu sync.Mutex
	counter := 0
	return func(int) (string, error) {
		mu.Lock()
		defer mu.Unlock()
		counter++
		return "id-" + string(rune('a'+counter%26)) + "-" + string(rune('0'+counter/26)), nil
	}
}

func routeName(index int) string {
	return "route-" + string(rune('a'+index%26)) + string(rune('a'+index/26))
}

func TestProvisionalMarkConsumeOnce(t *testing.T) {
	now := time.Now()
	s := New(func() time.Time { return now })
	if s.ConsumeProvisional("route-1") {
		t.Fatal("unmarked route consumed")
	}
	s.MarkProvisional("route-1")
	if !s.ConsumeProvisional("route-1") {
		t.Fatal("marked route not consumed")
	}
	if s.ConsumeProvisional("route-1") {
		t.Fatal("mark consumed twice")
	}
	s.MarkProvisional("route-2")
	expired := New(func() time.Time { return now.Add(Lifetime + time.Second) })
	_ = expired
	s2 := New(func() time.Time { return now })
	s2.MarkProvisional("route-3")
	s2.now = func() time.Time { return now.Add(Lifetime + time.Second) }
	if s2.ConsumeProvisional("route-3") {
		t.Fatal("expired mark consumed")
	}
}
