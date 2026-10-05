package engine

import "context"

func (s *Service) registerOperations() {
	register(s, "spends_create", "Record an expense, refund, or excluded transfer with optional splits, tags, merchant and evidence.", true,
		func(ctx context.Context, u *unit, in CreateSpendInput) (Spend, error) {
			return u.createSpend(ctx, in.Spend)
		})
	register(s, "spends_get", "Read a spend by ID, including deleted records and its current version.", false,
		func(ctx context.Context, u *unit, in GetInput) (Spend, error) { return u.getSpend(ctx, in.ID) })
	register(s, "spends_update", "Replace all editable spend fields using the version last read. Omitted optional fields are cleared.", true,
		func(ctx context.Context, u *unit, in UpdateSpendInput) (Spend, error) {
			return u.updateSpend(ctx, in.ID, in.ExpectedVersion, in.Spend)
		})
	register(s, "spends_delete", "Soft-delete a spend, retaining evidence and history. Active linked refunds must be removed or unlinked first.", true,
		func(ctx context.Context, u *unit, in LifecycleInput) (Spend, error) {
			return u.lifecycleSpend(ctx, in.ID, in.ExpectedVersion, false)
		})
	register(s, "spends_restore", "Restore a deleted spend. Its referenced records must be active.", true,
		func(ctx context.Context, u *unit, in LifecycleInput) (Spend, error) {
			return u.lifecycleSpend(ctx, in.ID, in.ExpectedVersion, true)
		})
	register(s, "spends_bulk_create", "Create 1-100 spends atomically. Any invalid record rolls back the entire batch.", true,
		func(ctx context.Context, u *unit, in BulkCreateInput) (SpendsResult, error) {
			result := SpendsResult{Items: []Spend{}}
			if len(in.Spends) < 1 || len(in.Spends) > 100 {
				return result, invalid("A batch must contain 1-100 spends")
			}
			for _, input := range in.Spends {
				spend, err := u.createSpend(ctx, input)
				if err != nil {
					return result, err
				}
				result.Items = append(result.Items, spend)
			}
			return result, nil
		})
	register(s, "spends_bulk_update", "Apply a shared patch to 1-100 versioned spends atomically. Supports bulk categorization and adding, removing or replacing tags.", true, bulkUpdate)
	register(s, "spends_bulk_delete", "Soft-delete 1-100 versioned spends atomically, removing refunds before their original expenses.", true,
		func(ctx context.Context, u *unit, in BulkLifecycleInput) (SpendsResult, error) {
			return bulkLifecycle(ctx, u, in, false)
		})
	register(s, "spends_bulk_restore", "Restore 1-100 versioned spends atomically, restoring original expenses before linked refunds.", true,
		func(ctx context.Context, u *unit, in BulkLifecycleInput) (SpendsResult, error) {
			return bulkLifecycle(ctx, u, in, true)
		})
	register(s, "spends_search", "Filter and paginate spends. Category filters match any allocation; returned records retain all their splits. All tag filters must match.", false, searchSpends)
	register(s, "spends_export", "Export one filtered page as CSV, with total and next_offset. Each page includes a header. Repeat with next_offset for all records.", false, exportSpends)
	register(s, "spending_analyze", "Exact spending totals, trends and comparisons. Excludes transfers, subtracts refunds on their own dates, and separates currencies. Category filters sum matching splits only. Tag groups overlap.", false, analyze)
	for _, kind := range []string{"category", "tag", "merchant"} {
		prefix := map[string]string{"category": "categories", "tag": "tags", "merchant": "merchants"}[kind]
		register(s, prefix+"_create", "Create a "+kind+". Names are unique per user, ignoring case.", true,
			func(ctx context.Context, u *unit, in CreateDimensionInput) (Dimension, error) {
				return createDimension(ctx, u, in, kind)
			})
		register(s, prefix+"_get", "Read a "+kind+" by ID, including deleted records.", false,
			func(ctx context.Context, u *unit, in GetInput) (Dimension, error) {
				return u.getDimension(ctx, in.ID, kind)
			})
		register(s, prefix+"_list", "Search and paginate "+prefix+", with optional deleted records.", false,
			func(ctx context.Context, u *unit, in PageInput) (Page[Dimension], error) {
				return listDimensions(ctx, u, in, kind)
			})
		register(s, prefix+"_update", "Rename or edit a "+kind+" using its current version. Existing spend links are preserved.", true,
			func(ctx context.Context, u *unit, in UpdateDimensionInput) (Dimension, error) {
				return updateDimension(ctx, u, in, kind)
			})
		register(s, prefix+"_delete", "Delete a "+kind+". Referenced records require replacement_id and replacement_version; reassignment is atomic and audited.", true,
			func(ctx context.Context, u *unit, in DeleteDimensionInput) (Dimension, error) {
				return deleteDimension(ctx, u, in, kind)
			})
		register(s, prefix+"_restore", "Restore an unused "+kind+" if its name and aliases are available. Merged references are not moved back.", true,
			func(ctx context.Context, u *unit, in LifecycleInput) (Dimension, error) {
				return restoreDimension(ctx, u, in, kind)
			})
		register(s, prefix+"_merge", "Merge a "+kind+" into an active target, reassign every spend, combine matching splits or tags, and retain history. Merchants retain the source name as an alias.", true,
			func(ctx context.Context, u *unit, in MergeDimensionInput) (MergeResult, error) {
				return mergeDimension(ctx, u, in, kind)
			})
	}
	register(s, "evidence_create", "Store an optional supporting document reference. Does not fetch or parse documents.", true, createEvidence)
	register(s, "evidence_get", "Read evidence metadata by ID, including deleted records.", false,
		func(ctx context.Context, u *unit, in GetInput) (Evidence, error) { return u.getEvidence(ctx, in.ID) })
	register(s, "evidence_list", "Search and paginate evidence metadata.", false, listEvidence)
	register(s, "evidence_update", "Replace evidence metadata using the version last read.", true, updateEvidence)
	register(s, "evidence_delete", "Delete evidence metadata. Linked records require detach=true; every detached spend receives a new version and audit entry.", true, deleteEvidence)
	register(s, "evidence_restore", "Restore deleted evidence metadata. Previously detached spend links are not recreated.", true, restoreEvidence)
	register(s, "evidence_attach", "Attach evidence to a spend. id and expected_version refer to the spend.", true,
		func(ctx context.Context, u *unit, in EvidenceLinkInput) (Spend, error) {
			return linkEvidence(ctx, u, in, true)
		})
	register(s, "evidence_detach", "Detach evidence from a spend. id and expected_version refer to the spend.", true,
		func(ctx context.Context, u *unit, in EvidenceLinkInput) (Spend, error) {
			return linkEvidence(ctx, u, in, false)
		})
	register(s, "history_list", "Read the complete paginated before/after change history for a record, including actor and operation.", false, history)
}
