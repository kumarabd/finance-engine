package engine

import (
	"context"
	"strings"
)

const evidenceColumns = "id::text,title,source_ref,media_type,checksum,notes,version,deleted_at,created_at,updated_at"

func scanEvidence(row scanner) (Evidence, error) {
	var e Evidence
	err := row.Scan(&e.ID, &e.Title, &e.SourceRef, &e.MediaType, &e.Checksum, &e.Notes, &e.Version, &e.DeletedAt, &e.CreatedAt, &e.UpdatedAt)
	e.CreatedAt = e.CreatedAt.UTC()
	e.UpdatedAt = e.UpdatedAt.UTC()
	return e, err
}
func (u *unit) getEvidence(ctx context.Context, id string) (Evidence, error) {
	if err := uuid(id); err != nil {
		return Evidence{}, err
	}
	return scanEvidence(u.tx.QueryRow(ctx, "SELECT "+evidenceColumns+" FROM finance.evidence WHERE owner_id=$1 AND id=$2", u.owner, id))
}
func validateEvidence(e *EvidenceInput) error {
	e.Title = strings.TrimSpace(e.Title)
	e.SourceRef = strings.TrimSpace(e.SourceRef)
	if len(e.Title) < 1 || len(e.Title) > 200 || len(e.SourceRef) < 1 || len(e.SourceRef) > 2048 {
		return invalid("Evidence requires title (1-200 characters) and source_ref (1-2048 characters)")
	}
	if len(e.MediaType) > 120 || len(e.Checksum) > 255 || len(e.Notes) > 4000 {
		return invalid("Evidence metadata is too long")
	}
	return nil
}
func createEvidence(ctx context.Context, u *unit, input CreateEvidenceInput) (Evidence, error) {
	e := input.EvidenceInput
	if err := validateEvidence(&e); err != nil {
		return Evidence{}, err
	}
	result, err := scanEvidence(u.tx.QueryRow(ctx, `INSERT INTO finance.evidence(owner_id,title,source_ref,media_type,checksum,notes)
        VALUES($1,$2,$3,$4,$5,$6) RETURNING `+evidenceColumns, u.owner, e.Title, e.SourceRef, e.MediaType, e.Checksum, e.Notes))
	if err != nil {
		return result, err
	}
	return result, u.audit(ctx, "evidence", result.ID, nil, result)
}
func updateEvidence(ctx context.Context, u *unit, input UpdateEvidenceInput) (Evidence, error) {
	before, err := u.getEvidence(ctx, input.ID)
	if err != nil {
		return before, err
	}
	if err = version(input.ExpectedVersion, before.Version); err != nil {
		return Evidence{}, err
	}
	if before.DeletedAt != nil {
		return Evidence{}, conflict("Restore evidence before editing")
	}
	e := input.EvidenceInput
	if err = validateEvidence(&e); err != nil {
		return Evidence{}, err
	}
	after, err := scanEvidence(u.tx.QueryRow(ctx, `UPDATE finance.evidence SET title=$3,source_ref=$4,media_type=$5,checksum=$6,notes=$7,
        version=version+1,updated_at=clock_timestamp() WHERE owner_id=$1 AND id=$2 RETURNING `+evidenceColumns,
		u.owner, before.ID, e.Title, e.SourceRef, e.MediaType, e.Checksum, e.Notes))
	if err != nil {
		return after, err
	}
	return after, u.audit(ctx, "evidence", before.ID, before, after)
}
func listEvidence(ctx context.Context, u *unit, input PageInput) (Page[Evidence], error) {
	result := Page[Evidence]{Items: []Evidence{}}
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
	clause := "owner_id=$1 AND " + where + " AND (title ILIKE $2 OR source_ref ILIKE $2 OR notes ILIKE $2)"
	if err = u.tx.QueryRow(ctx, "SELECT count(*) FROM finance.evidence WHERE "+clause, u.owner, literal(input.Search)).Scan(&result.Total); err != nil {
		return result, err
	}
	rows, err := u.tx.Query(ctx, "SELECT "+evidenceColumns+" FROM finance.evidence WHERE "+clause+" ORDER BY created_at DESC,id DESC LIMIT $3 OFFSET $4", u.owner, literal(input.Search), limit, input.Offset)
	if err != nil {
		return result, err
	}
	defer rows.Close()
	for rows.Next() {
		e, err := scanEvidence(rows)
		if err != nil {
			return result, err
		}
		result.Items = append(result.Items, e)
	}
	result.NextOffset = nextOffset(input.Offset, len(result.Items), result.Total)
	return result, rows.Err()
}
func deleteEvidence(ctx context.Context, u *unit, input DeleteEvidenceInput) (Evidence, error) {
	before, err := u.getEvidence(ctx, input.ID)
	if err != nil {
		return before, err
	}
	if err = version(input.ExpectedVersion, before.Version); err != nil {
		return Evidence{}, err
	}
	if before.DeletedAt != nil {
		return Evidence{}, conflict("Evidence is already deleted")
	}
	rows, err := u.tx.Query(ctx, "SELECT spend_id::text FROM finance.spend_evidence WHERE owner_id=$1 AND evidence_id=$2 ORDER BY spend_id", u.owner, before.ID)
	if err != nil {
		return Evidence{}, err
	}
	ids := []string{}
	for rows.Next() {
		var id string
		if err = rows.Scan(&id); err != nil {
			rows.Close()
			return Evidence{}, err
		}
		ids = append(ids, id)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return Evidence{}, err
	}
	if len(ids) > 0 && !input.Detach {
		return Evidence{}, conflict("Evidence is linked to spends; set detach=true to remove those links")
	}
	for _, id := range ids {
		spend, err := u.getSpend(ctx, id)
		if err != nil {
			return Evidence{}, err
		}
		// Detach directly so deleting evidence remains possible even if a deleted
		// refund references a deleted original expense.
		_, err = u.tx.Exec(ctx, "DELETE FROM finance.spend_evidence WHERE owner_id=$1 AND spend_id=$2 AND evidence_id=$3", u.owner, id, before.ID)
		if err != nil {
			return Evidence{}, err
		}
		if _, err = u.tx.Exec(ctx, "UPDATE finance.spends SET version=version+1,updated_at=clock_timestamp() WHERE owner_id=$1 AND id=$2", u.owner, id); err != nil {
			return Evidence{}, err
		}
		after, err := u.getSpend(ctx, id)
		if err != nil {
			return Evidence{}, err
		}
		if err = u.audit(ctx, "spend", id, spend, after); err != nil {
			return Evidence{}, err
		}
	}
	after, err := scanEvidence(u.tx.QueryRow(ctx, `UPDATE finance.evidence SET deleted_at=clock_timestamp(),version=version+1,updated_at=clock_timestamp()
        WHERE owner_id=$1 AND id=$2 RETURNING `+evidenceColumns, u.owner, before.ID))
	if err != nil {
		return after, err
	}
	return after, u.audit(ctx, "evidence", before.ID, before, after)
}
func restoreEvidence(ctx context.Context, u *unit, input LifecycleInput) (Evidence, error) {
	before, err := u.getEvidence(ctx, input.ID)
	if err != nil {
		return before, err
	}
	if err = version(input.ExpectedVersion, before.Version); err != nil {
		return Evidence{}, err
	}
	if before.DeletedAt == nil {
		return Evidence{}, conflict("Evidence is already active")
	}
	after, err := scanEvidence(u.tx.QueryRow(ctx, `UPDATE finance.evidence SET deleted_at=NULL,version=version+1,updated_at=clock_timestamp()
        WHERE owner_id=$1 AND id=$2 RETURNING `+evidenceColumns, u.owner, before.ID))
	if err != nil {
		return after, err
	}
	return after, u.audit(ctx, "evidence", before.ID, before, after)
}
func linkEvidence(ctx context.Context, u *unit, input EvidenceLinkInput, attach bool) (Spend, error) {
	spend, err := u.getSpend(ctx, input.ID)
	if err != nil {
		return spend, err
	}
	if err = uuid(input.EvidenceID); err != nil {
		return Spend{}, err
	}
	id := strings.ToLower(input.EvidenceID)
	ids := []string{}
	found := false
	for _, current := range spend.EvidenceIDs {
		if current == id {
			found = true
			if !attach {
				continue
			}
		}
		ids = append(ids, current)
	}
	if attach && !found {
		ids = append(ids, id)
	}
	data := spend.SpendInput
	data.EvidenceIDs = ids
	return u.updateSpend(ctx, spend.ID, input.ExpectedVersion, data)
}
