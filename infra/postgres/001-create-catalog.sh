#!/usr/bin/env bash
set -e

: "${CATALOG_DB_PASSWORD:?Falta la contraseña del usuario de Catalog}"

# Las variables de psql citadas como literales evitan concatenar la contraseña en SQL.
psql --set=ON_ERROR_STOP=1 \
    --username "$POSTGRES_USER" \
    --dbname "$POSTGRES_DB" \
    --set=catalog_password="$CATALOG_DB_PASSWORD" <<'SQL'
CREATE ROLE pedidos360_catalog
    LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
    PASSWORD :'catalog_password';

CREATE DATABASE pedidos360_catalog OWNER pedidos360_catalog;
REVOKE ALL ON DATABASE pedidos360_catalog FROM PUBLIC;
GRANT CONNECT ON DATABASE pedidos360_catalog TO pedidos360_catalog;

\connect pedidos360_catalog
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SQL
