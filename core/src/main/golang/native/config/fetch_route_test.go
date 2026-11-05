package config

import (
	"context"
	"errors"
	"net/http"
	"reflect"
	"testing"
)

func TestRunRoutedAttemptDirectThenProxy(t *testing.T) {
	var routes []bool
	header, err := runRoutedAttempt(context.Background(), route{viaProxy: false, fallback: true},
		func(_ context.Context, viaProxy bool) (map[string][]string, error) {
			routes = append(routes, viaProxy)
			if !viaProxy {
				return nil, errors.New("connection reset while reading body")
			}
			return map[string][]string{"x-route": []string{"proxy"}}, nil
		})
	if err != nil {
		t.Fatalf("runRoutedAttempt() error = %v", err)
	}
	if !reflect.DeepEqual(routes, []bool{false, true}) {
		t.Fatalf("routes = %v, want direct then proxy", routes)
	}
	if got := header["x-route"]; !reflect.DeepEqual(got, []string{"proxy"}) {
		t.Fatalf("header = %v", header)
	}
}

func TestRunRoutedAttemptDoesNotRetrySubscriptionError(t *testing.T) {
	var routes []bool
	_, err := runRoutedAttempt(context.Background(), route{viaProxy: false, fallback: true},
		func(_ context.Context, viaProxy bool) (map[string][]string, error) {
			routes = append(routes, viaProxy)
			return nil, httpStatusError{code: http.StatusUnauthorized, status: "401 Unauthorized"}
		})
	if err == nil {
		t.Fatal("runRoutedAttempt() error = nil")
	}
	if !reflect.DeepEqual(routes, []bool{false}) {
		t.Fatalf("routes = %v, want direct only", routes)
	}
}

func TestRunRoutedAttemptDoesNotRetryLocalWriteError(t *testing.T) {
	var routes []bool
	_, err := runRoutedAttempt(context.Background(), route{viaProxy: false, fallback: true},
		func(_ context.Context, viaProxy bool) (map[string][]string, error) {
			routes = append(routes, viaProxy)
			return nil, localFetchError{err: errors.New("disk full")}
		})
	if err == nil {
		t.Fatal("runRoutedAttempt() error = nil")
	}
	if !reflect.DeepEqual(routes, []bool{false}) {
		t.Fatalf("routes = %v, want direct only", routes)
	}
}
