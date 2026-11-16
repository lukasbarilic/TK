-- Základ schémy: rozšírenia PostgreSQL (HLD kap. 2.4). Doménové tabuľky pribudnú v ďalších migráciách.
CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
