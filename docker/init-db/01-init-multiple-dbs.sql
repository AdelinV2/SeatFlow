-- Initialization script for SeatFlow multi-database local development.
-- Creates the shared application role and the 7 per-service databases idempotently,
-- and makes the application role the owner of each database and its public schema
-- so Flyway can create tables at startup.
--
-- The application role name and password are read from DB_USERNAME/DB_PASSWORD
-- at initialization time, never embedded in the image or manifest.
--
-- NOTE: uses "SELECT 1" (standard, always valid) inside the NOT EXISTS
-- subquery, and relies on psql \gexec to execute each returned statement.

-- 1. Create the shared application role (idempotent). The official PostgreSQL
-- image runs init scripts through psql, where \getenv exposes environment values.
\getenv db_username DB_USERNAME
\getenv db_password DB_PASSWORD
\if :{?db_username}
\if :{?db_password}
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'db_username', :'db_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'db_username')\gexec
\else
\echo DB_PASSWORD must be supplied to initialize the SeatFlow application role
\quit
\endif
\else
\echo DB_USERNAME must be supplied to initialize the SeatFlow application role
\quit
\endif

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
SELECT format('ALTER SCHEMA public OWNER TO %I', :'db_username')\gexec

\connect seatflow_seatmap
SELECT format('ALTER SCHEMA public OWNER TO %I', :'db_username')\gexec

\connect seatflow_event
SELECT format('ALTER SCHEMA public OWNER TO %I', :'db_username')\gexec

\connect seatflow_reservation
SELECT format('ALTER SCHEMA public OWNER TO %I', :'db_username')\gexec

\connect seatflow_payment
SELECT format('ALTER SCHEMA public OWNER TO %I', :'db_username')\gexec

\connect seatflow_ticket
SELECT format('ALTER SCHEMA public OWNER TO %I', :'db_username')\gexec

\connect seatflow_notification
SELECT format('ALTER SCHEMA public OWNER TO %I', :'db_username')\gexec
