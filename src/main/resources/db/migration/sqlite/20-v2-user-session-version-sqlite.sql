-- R100-03: SQLite data backfill for the session version.
-- LocalSchemaMigration adds the column first because SQLite has no portable
-- conditional ADD COLUMN form in the hand-applied migration contract.

UPDATE farm_user SET token_version = 0 WHERE token_version IS NULL;
