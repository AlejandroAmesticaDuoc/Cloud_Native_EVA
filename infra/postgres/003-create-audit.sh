#!/usr/bin/env bash
set -e

: "${AUDIT_DB_PASSWORD:?Falta la contraseña del usuario de Audit}"

psql --set=ON_ERROR_STOP=1 \
    --username "$POSTGRES_USER" \
    --dbname "$POSTGRES_DB" \
    --set=audit_password="$AUDIT_DB_PASSWORD" <<'SQL'
CREATE ROLE pedidos360_audit
    LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
    PASSWORD :'audit_password';

CREATE DATABASE pedidos360_audit OWNER pedidos360_audit;
REVOKE ALL ON DATABASE pedidos360_audit FROM PUBLIC;
GRANT CONNECT ON DATABASE pedidos360_audit TO pedidos360_audit;

\connect pedidos360_audit
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SQL
