-- Initialization script for SeatFlow multi-database local development
-- Creates the 7 per-service databases idempotently.
-- NOTE: uses "SELECT 1" (standard, always valid) inside the NOT EXISTS
-- subquery, and relies on psql \gexec to execute each returned statement.

SELECT 'CREATE DATABASE seatflow_user'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'seatflow_user')\gexec

SELECT 'CREATE DATABASE seatflow_seatmap'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'seatflow_seatmap')\gexec

SELECT 'CREATE DATABASE seatflow_event'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'seatflow_event')\gexec

SELECT 'CREATE DATABASE seatflow_reservation'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'seatflow_reservation')\gexec

SELECT 'CREATE DATABASE seatflow_payment'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'seatflow_payment')\gexec

SELECT 'CREATE DATABASE seatflow_ticket'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'seatflow_ticket')\gexec

SELECT 'CREATE DATABASE seatflow_notification'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'seatflow_notification')\gexec
