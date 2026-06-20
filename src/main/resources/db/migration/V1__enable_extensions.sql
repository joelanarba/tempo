-- btree_gist lets a GiST index mix the equality operator on a scalar column
-- (resource_id) with the range overlap operator (&&) in one exclusion constraint.
CREATE EXTENSION IF NOT EXISTS btree_gist;
