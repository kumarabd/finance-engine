package config

import (
	"errors"
	"net"
	"os"
	"strings"
)

type Config struct {
	DatabaseURL     string
	ListenAddr      string
	DevVerifiedUser string
	TrustedOrigins  []string
}

func Load() (Config, error) {
	c := Config{
		DatabaseURL:     os.Getenv("DATABASE_URL"),
		ListenAddr:      os.Getenv("LISTEN_ADDR"),
		DevVerifiedUser: os.Getenv("DEV_VERIFIED_USER"),
		TrustedOrigins:  strings.Split(os.Getenv("TRUSTED_ORIGINS"), ","),
	}
	if c.DatabaseURL == "" {
		return Config{}, errors.New("DATABASE_URL is required")
	}
	if c.ListenAddr == "" {
		c.ListenAddr = "127.0.0.1:8091"
	}
	if c.DevVerifiedUser != "" {
		host, _, err := net.SplitHostPort(c.ListenAddr)
		ip := net.ParseIP(host)
		if err != nil || (host != "localhost" && (ip == nil || !ip.IsLoopback())) {
			return Config{}, errors.New("DEV_VERIFIED_USER requires a loopback LISTEN_ADDR")
		}
		if len(c.DevVerifiedUser) > 255 {
			return Config{}, errors.New("DEV_VERIFIED_USER is too long")
		}
	}
	for i := range c.TrustedOrigins {
		c.TrustedOrigins[i] = strings.TrimSpace(c.TrustedOrigins[i])
	}
	return c, nil
}
