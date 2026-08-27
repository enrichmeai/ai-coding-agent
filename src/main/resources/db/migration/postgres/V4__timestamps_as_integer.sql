-- No-op sibling of the SQLite V4 migration, kept so the two vendor histories
-- stay on the same version numbers.
--
-- SQLite V4 rebuilds the Instant-backed columns as INTEGER because its TEXT
-- columns could not round-trip the epoch millis Hibernate writes. Postgres
-- declared these columns TIMESTAMP WITH TIME ZONE in V1/V3, which the driver
-- already maps to Instant correctly, so there is nothing to change here.

SELECT 1;
