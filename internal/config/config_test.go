package config

import "testing"

func TestDevelopmentIdentityRequiresLoopback(t *testing.T) {
	t.Setenv("DATABASE_URL", "postgres://localhost/finance")
	t.Setenv("DEV_VERIFIED_USER", "user")
	for _, address := range []string{":8091", "0.0.0.0:8091", "192.168.1.2:8091"} {
		t.Setenv("LISTEN_ADDR", address)
		if _, err := Load(); err == nil {
			t.Fatalf("accepted %s", address)
		}
	}
	t.Setenv("LISTEN_ADDR", "127.0.0.1:8091")
	if _, err := Load(); err != nil {
		t.Fatal(err)
	}
}
