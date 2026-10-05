package engine

import (
	"regexp"
	"strings"
	"time"
)

var uuidPattern = regexp.MustCompile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
var currencyPattern = regexp.MustCompile("^[A-Z]{3}$")

const maxAmount int64 = 9007199254740991

func uuid(id string) error {
	if !uuidPattern.MatchString(id) {
		return invalid("Expected a UUID")
	}
	return nil
}
func date(value string) error {
	d, err := time.Parse("2006-01-02", value)
	if err != nil || len(value) != 10 || d.Year() < 1 {
		return invalid("Expected a calendar date YYYY-MM-DD")
	}
	return nil
}
func uniqueIDs(ids []string) ([]string, error) {
	if len(ids) > 100 {
		return nil, invalid("At most 100 references are allowed")
	}
	result := make([]string, 0, len(ids))
	seen := map[string]bool{}
	for _, id := range ids {
		if err := uuid(id); err != nil {
			return nil, err
		}
		id = strings.ToLower(id)
		if seen[id] {
			return nil, invalid("Duplicate reference: " + id)
		}
		seen[id] = true
		result = append(result, id)
	}
	return result, nil
}
func page(limit, offset int) (int, error) {
	if limit == 0 {
		limit = 50
	}
	if limit < 1 || limit > 200 || offset < 0 || offset > 1000000 {
		return 0, invalid("limit must be 1-200 and offset 0-1000000")
	}
	return limit, nil
}
func state(value string) (string, error) {
	switch value {
	case "", "active":
		return "deleted_at IS NULL", nil
	case "deleted":
		return "deleted_at IS NOT NULL", nil
	case "all":
		return "TRUE", nil
	}
	return "", invalid("state must be active, deleted, or all")
}
func nextOffset(offset, count, total int) *int {
	if offset+count >= total {
		return nil
	}
	v := offset + count
	return &v
}
func literal(value string) string {
	return "%" + strings.NewReplacer("\\", "\\\\", "%", "\\%", "_", "\\_").Replace(value) + "%"
}
