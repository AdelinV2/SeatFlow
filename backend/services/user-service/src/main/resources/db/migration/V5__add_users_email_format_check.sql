-- ====================================================================
-- V5: Add email format check constraint on users table
-- Aligns schema with ADR-002 §3.2.3 and 03-database-models.md §2.1
-- ====================================================================

ALTER TABLE users
ADD CONSTRAINT chk_users_email_format
CHECK (email ~* '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$');
