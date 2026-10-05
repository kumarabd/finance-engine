package engine

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
)

const dimensionColumns = "id::text,kind,name,aliases,version,deleted_at,created_at,updated_at"

func scanDimension(row scanner) (Dimension, error) {
	var d Dimension
	var aliases []byte
	err := row.Scan(&d.ID, &d.Kind, &d.Name, &aliases, &d.Version, &d.DeletedAt, &d.CreatedAt, &d.UpdatedAt)
	if err != nil {
		return d, err
	}
	err = json.Unmarshal(aliases, &d.Aliases)
	d.CreatedAt = d.CreatedAt.UTC()
	d.UpdatedAt = d.UpdatedAt.UTC()
	return d, err
}
func (u *unit) getDimension(ctx context.Context, id, kind string) (Dimension, error) {
	if err := uuid(id); err != nil {
		return Dimension{}, err
	}
	return scanDimension(u.tx.QueryRow(ctx, "SELECT "+dimensionColumns+" FROM finance.dimensions WHERE owner_id=$1 AND id=$2 AND kind=$3", u.owner, id, kind))
}
func (u *unit) activeDimension(ctx context.Context, id, kind string) error {
	d, err := u.getDimension(ctx, id, kind)
	if err != nil {
		return err
	}
	if d.DeletedAt != nil {
		return conflict("Referenced " + kind + " is deleted")
	}
	return nil
}
func (u *unit) validateDimension(ctx context.Context, input *DimensionInput, kind string, exclude []string) error {
	input.Name = strings.TrimSpace(input.Name)
	if len(input.Name) < 1 || len(input.Name) > 120 {
		return invalid("name must contain 1-120 characters")
	}
	if len(input.Aliases) > 50 {
		return invalid("At most 50 merchant aliases are allowed")
	}
	if kind != "merchant" && len(input.Aliases) > 0 {
		return invalid("Only merchants have aliases")
	}
	names := []string{strings.ToLower(input.Name)}
	aliases := make([]string, 0, len(input.Aliases))
	seen := map[string]bool{names[0]: true}
	for _, alias := range input.Aliases {
		alias = strings.TrimSpace(alias)
		lower := strings.ToLower(alias)
		if len(alias) < 1 || len(alias) > 120 {
			return invalid("Aliases must contain 1-120 characters")
		}
		if seen[lower] {
			return invalid("Duplicate name or alias")
		}
		seen[lower] = true
		aliases = append(aliases, alias)
		names = append(names, lower)
	}
	input.Aliases = aliases
	if exclude == nil {
		exclude = []string{}
	}
	var exists bool
	err := u.tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM finance.dimensions d WHERE owner_id=$1 AND kind=$2
        AND deleted_at IS NULL AND NOT(id=ANY($3::uuid[]))
        AND (lower(name)=ANY($4::text[]) OR EXISTS(SELECT 1 FROM jsonb_array_elements_text(d.aliases) a WHERE lower(a)=ANY($4::text[]))))`,
		u.owner, kind, exclude, names).Scan(&exists)
	if err != nil {
		return err
	}
	if exists {
		return duplicate("That name or alias is already in use")
	}
	return nil
}
func createDimension(ctx context.Context, u *unit, input CreateDimensionInput, kind string) (Dimension, error) {
	d := input.DimensionInput
	if err := u.validateDimension(ctx, &d, kind, nil); err != nil {
		return Dimension{}, err
	}
	aliases, _ := json.Marshal(d.Aliases)
	result, err := scanDimension(u.tx.QueryRow(ctx, "INSERT INTO finance.dimensions(owner_id,kind,name,aliases) VALUES($1,$2,$3,$4) RETURNING "+dimensionColumns, u.owner, kind, d.Name, aliases))
	if err != nil {
		return result, err
	}
	return result, u.audit(ctx, kind, result.ID, nil, result)
}
func updateDimension(ctx context.Context, u *unit, input UpdateDimensionInput, kind string) (Dimension, error) {
	before, err := u.getDimension(ctx, input.ID, kind)
	if err != nil {
		return Dimension{}, err
	}
	if err = version(input.ExpectedVersion, before.Version); err != nil {
		return Dimension{}, err
	}
	if before.DeletedAt != nil {
		return Dimension{}, conflict("Restore the record before editing")
	}
	d := input.DimensionInput
	if err = u.validateDimension(ctx, &d, kind, []string{before.ID}); err != nil {
		return Dimension{}, err
	}
	aliases, _ := json.Marshal(d.Aliases)
	after, err := scanDimension(u.tx.QueryRow(ctx, `UPDATE finance.dimensions SET name=$3,aliases=$4,version=version+1,updated_at=clock_timestamp()
        WHERE owner_id=$1 AND id=$2 RETURNING `+dimensionColumns, u.owner, before.ID, d.Name, aliases))
	if err != nil {
		return after, err
	}
	return after, u.audit(ctx, kind, before.ID, before, after)
}
func listDimensions(ctx context.Context, u *unit, input PageInput, kind string) (Page[Dimension], error) {
	result := Page[Dimension]{Items: []Dimension{}}
	limit, err := page(input.Limit, input.Offset)
	if err != nil {
		return result, err
	}
	where, err := state(input.State)
	if err != nil {
		return result, err
	}
	if len(input.Search) > 200 {
		return result, invalid("search is too long")
	}
	clause := "owner_id=$1 AND kind=$2 AND " + where + " AND (name ILIKE $3 OR aliases::text ILIKE $3)"
	if err = u.tx.QueryRow(ctx, "SELECT count(*) FROM finance.dimensions WHERE "+clause, u.owner, kind, literal(input.Search)).Scan(&result.Total); err != nil {
		return result, err
	}
	rows, err := u.tx.Query(ctx, "SELECT "+dimensionColumns+" FROM finance.dimensions WHERE "+clause+" ORDER BY lower(name),id LIMIT $4 OFFSET $5", u.owner, kind, literal(input.Search), limit, input.Offset)
	if err != nil {
		return result, err
	}
	defer rows.Close()
	for rows.Next() {
		d, err := scanDimension(rows)
		if err != nil {
			return result, err
		}
		result.Items = append(result.Items, d)
	}
	result.NextOffset = nextOffset(input.Offset, len(result.Items), result.Total)
	return result, rows.Err()
}
func (u *unit) dimensionSpendIDs(ctx context.Context, id, kind string) ([]string, error) {
	clause := "merchant_id=$2"
	if kind == "category" {
		clause = "EXISTS(SELECT 1 FROM finance.spend_allocations a WHERE a.owner_id=s.owner_id AND a.spend_id=s.id AND a.category_id=$2)"
	}
	if kind == "tag" {
		clause = "EXISTS(SELECT 1 FROM finance.spend_tags t WHERE t.owner_id=s.owner_id AND t.spend_id=s.id AND t.tag_id=$2)"
	}
	rows, err := u.tx.Query(ctx, "SELECT id::text FROM finance.spends s WHERE owner_id=$1 AND ("+clause+") ORDER BY id", u.owner, id)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	ids := []string{}
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		ids = append(ids, id)
	}
	return ids, rows.Err()
}
func (u *unit) reassignDimension(ctx context.Context, source, target, kind string, ids []string) error {
	for _, id := range ids {
		before, err := u.getSpend(ctx, id)
		if err != nil {
			return err
		}
		data := before.SpendInput
		switch kind {
		case "merchant":
			data.MerchantID = target
		case "tag":
			tags := []string{}
			seen := map[string]bool{}
			for _, tag := range data.TagIDs {
				if tag == source {
					tag = target
				}
				if !seen[tag] {
					seen[tag] = true
					tags = append(tags, tag)
				}
			}
			data.TagIDs = tags
		case "category":
			data.Allocations = append([]Allocation{}, data.Allocations...)
			combined := []Allocation{}
			index := map[string]int{}
			for _, a := range data.Allocations {
				if a.CategoryID == source {
					a.CategoryID = target
				}
				if i, ok := index[a.CategoryID]; ok {
					combined[i].AmountMinor += a.AmountMinor
				} else {
					index[a.CategoryID] = len(combined)
					combined = append(combined, a)
				}
			}
			data.Allocations = combined
		}
		if _, err = u.replaceSpend(ctx, before, data); err != nil {
			return err
		}
	}
	return nil
}
func mergeDimension(ctx context.Context, u *unit, input MergeDimensionInput, kind string) (MergeResult, error) {
	var result MergeResult
	source, err := u.getDimension(ctx, input.ID, kind)
	if err != nil {
		return result, err
	}
	target, err := u.getDimension(ctx, input.TargetID, kind)
	if err != nil {
		return result, err
	}
	if source.ID == target.ID {
		return result, invalid("Cannot merge a record into itself")
	}
	if err = version(input.ExpectedVersion, source.Version); err != nil {
		return result, err
	}
	if err = version(input.TargetVersion, target.Version); err != nil {
		return result, err
	}
	if source.DeletedAt != nil || target.DeletedAt != nil {
		return result, conflict("Both merge records must be active")
	}
	ids, err := u.dimensionSpendIDs(ctx, source.ID, kind)
	if err != nil {
		return result, err
	}
	data := target.DimensionInput
	if kind == "merchant" {
		data.Aliases = append(append([]string{}, data.Aliases...), source.Name)
		data.Aliases = append(data.Aliases, source.Aliases...)
	}
	if err = u.validateDimension(ctx, &data, kind, []string{source.ID, target.ID}); err != nil {
		return result, err
	}
	aliases, _ := json.Marshal(data.Aliases)
	result.Target, err = scanDimension(u.tx.QueryRow(ctx, `UPDATE finance.dimensions SET aliases=$3,version=version+1,updated_at=clock_timestamp()
        WHERE owner_id=$1 AND id=$2 RETURNING `+dimensionColumns, u.owner, target.ID, aliases))
	if err != nil {
		return result, err
	}
	if err = u.audit(ctx, kind, target.ID, target, result.Target); err != nil {
		return result, err
	}
	if err = u.reassignDimension(ctx, source.ID, target.ID, kind, ids); err != nil {
		return result, err
	}
	result.Source, err = scanDimension(u.tx.QueryRow(ctx, `UPDATE finance.dimensions SET deleted_at=clock_timestamp(),version=version+1,updated_at=clock_timestamp()
        WHERE owner_id=$1 AND id=$2 RETURNING `+dimensionColumns, u.owner, source.ID))
	if err != nil {
		return result, err
	}
	result.ChangedSpends = len(ids)
	return result, u.audit(ctx, kind, source.ID, source, result.Source)
}
func deleteDimension(ctx context.Context, u *unit, input DeleteDimensionInput, kind string) (Dimension, error) {
	if input.ReplacementID != "" {
		merged, err := mergeDimension(ctx, u, MergeDimensionInput{Meta: input.Meta, Versioned: input.Versioned, TargetID: input.ReplacementID, TargetVersion: input.ReplacementVersion}, kind)
		return merged.Source, err
	}
	before, err := u.getDimension(ctx, input.ID, kind)
	if err != nil {
		return before, err
	}
	if err = version(input.ExpectedVersion, before.Version); err != nil {
		return Dimension{}, err
	}
	if before.DeletedAt != nil {
		return Dimension{}, conflict("Record is already deleted")
	}
	ids, err := u.dimensionSpendIDs(ctx, before.ID, kind)
	if err != nil {
		return Dimension{}, err
	}
	if len(ids) > 0 {
		return Dimension{}, conflict(fmt.Sprintf("%d spends reference this %s; provide a replacement or remove their references first", len(ids), kind))
	}
	after, err := scanDimension(u.tx.QueryRow(ctx, `UPDATE finance.dimensions SET deleted_at=clock_timestamp(),version=version+1,updated_at=clock_timestamp()
        WHERE owner_id=$1 AND id=$2 RETURNING `+dimensionColumns, u.owner, before.ID))
	if err != nil {
		return after, err
	}
	return after, u.audit(ctx, kind, before.ID, before, after)
}
func restoreDimension(ctx context.Context, u *unit, input LifecycleInput, kind string) (Dimension, error) {
	before, err := u.getDimension(ctx, input.ID, kind)
	if err != nil {
		return before, err
	}
	if err = version(input.ExpectedVersion, before.Version); err != nil {
		return Dimension{}, err
	}
	if before.DeletedAt == nil {
		return Dimension{}, conflict("Record is already active")
	}
	data := before.DimensionInput
	if err = u.validateDimension(ctx, &data, kind, []string{before.ID}); err != nil {
		return Dimension{}, err
	}
	after, err := scanDimension(u.tx.QueryRow(ctx, `UPDATE finance.dimensions SET deleted_at=NULL,version=version+1,updated_at=clock_timestamp()
        WHERE owner_id=$1 AND id=$2 RETURNING `+dimensionColumns, u.owner, before.ID))
	if err != nil {
		return after, err
	}
	return after, u.audit(ctx, kind, before.ID, before, after)
}
