package main

import (
	"encoding/json"
	"github.com/kumarabd/finance-engine/internal/engine"
	"os"
)

func main() {
	encoder := json.NewEncoder(os.Stdout)
	encoder.SetIndent("", "  ")
	if err := encoder.Encode(engine.New(nil).OpenAPI()); err != nil {
		panic(err)
	}
}
