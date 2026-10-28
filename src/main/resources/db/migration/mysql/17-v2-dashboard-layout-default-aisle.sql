-- SYNC-002: correct only the exact legacy 4x12 singleton default.
-- The version is intentionally unchanged so clients do not observe a spurious layout update.
UPDATE farm_dashboard_layout
SET aisles_json = '[{"id":"default-column-aisle","axis":"COLUMN","afterIndex":7,"label":"过道","size":100}]'
WHERE id = 1
  AND row_count = 4
  AND column_count = 12
  AND version = 1
  AND updated_by IS NULL
  AND JSON_VALID(aisles_json)
  AND CAST(aisles_json AS JSON) = CAST(
        '[{"id":"default-column-aisle","axis":"COLUMN","afterIndex":6,"label":"过道","size":100}]'
        AS JSON
      );
