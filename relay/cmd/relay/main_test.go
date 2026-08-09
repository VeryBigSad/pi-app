package main

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestHealthResponse(t *testing.T) {
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodGet, "/healthz", nil)
	newHandler().ServeHTTP(recorder, request)

	if recorder.Code != http.StatusOK || recorder.Body.String() != "ok\n" {
		t.Fatalf("unexpected health response: %d %q", recorder.Code, recorder.Body.String())
	}
}
