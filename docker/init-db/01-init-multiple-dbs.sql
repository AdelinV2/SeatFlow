-- Initialization script for SeatFlow multi-database local development.
-- Creates the shared application role and the 7 per-service databases idempotently,
-- and makes the application role the owner of each database and its public schema
-- so Flyway can create tables at startup.
--
-- The application role name/password MUST match the services' .env defaults
-- (DB_USERNAME / DB_PASSWORD -> seatflow / seatflow_dev).
--
-- NOTE: uses "SELECT 1" (standard, always valid) inside the NOT EXISTS
-- subquery, and relies on psql \gexec to execute each returned statement.

-- 1. Create the shared application role (idempotent).
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'seatflow') THEN
        CREATE ROLE seatflow LOGIN PASSWORD 'seatflow_dev';
    END IF;
END
$$;

-- 2. Create the per-service databases (idempotent).
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

-- 3. Make the application role own each database and its public schema so that
--    Flyway (connecting as seatflow) can create and migrate tables.
\connect seatflow_user
ALTER SCHEMA public OWNER TO seatflow;

\connect seatflow_seatmap
ALTER SCHEMA public OWNER TO seatflow;

\connect seatflow_event
ALTER SCHEMA public OWNER TO seatflow;

\connect seatflow_reservation
ALTER SCHEMA public OWNER TO seatflow;

\connect seatflow_payment
ALTER SCHEMA public OWNER TO seatflow;

\connect seatflow_ticket
ALTER SCHEMA public OWNER TO seatflow;

\connect seatflow_notification
ALTER SCHEMA public OWNER TO seatflow;
