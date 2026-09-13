#!/usr/bin/env bash
set -e

: "${REPORT_DB_PASSWORD:?Falta la contraseña del usuario de Report}"

psql --set=ON_ERROR_STOP=1 \
    --username "$POSTGRES_USER" \
    --dbname "$POSTGRES_DB" \
    --set=report_password="$REPORT_DB_PASSWORD" <<'SQL'
CREATE ROLE pedidos360_report
    LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
    PASSWORD :'report_password';

CREATE DATABASE pedidos360_report OWNER pedidos360_report;
REVOKE ALL ON DATABASE pedidos360_report FROM PUBLIC;
GRANT CONNECT ON DATABASE pedidos360_report TO pedidos360_report;

\connect pedidos360_report
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SQL
