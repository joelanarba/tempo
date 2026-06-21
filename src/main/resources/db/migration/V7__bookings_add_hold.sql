-- A hold is a real booking row in HELD status. The exclusion constraint is widened to
-- cover HELD so a held slot is protected at the database level exactly like a confirmed
-- one; an EXPIRED hold releases that protection.

ALTER TABLE bookings DROP CONSTRAINT bookings_status_check;
ALTER TABLE bookings
    ADD CONSTRAINT bookings_status_check
    CHECK (status IN ('CONFIRMED', 'CANCELLED', 'HELD', 'EXPIRED'));

ALTER TABLE bookings ADD COLUMN hold_expires_at TIMESTAMP WITH TIME ZONE;

-- A HELD row must always carry an expiry; nothing else may.
ALTER TABLE bookings
    ADD CONSTRAINT bookings_hold_expiry_check
    CHECK (status <> 'HELD' OR hold_expires_at IS NOT NULL);

ALTER TABLE bookings DROP CONSTRAINT bookings_no_overlap;
ALTER TABLE bookings
    ADD CONSTRAINT bookings_no_overlap
    EXCLUDE USING gist (resource_id WITH =, time_range WITH &&)
    WHERE (status IN ('CONFIRMED', 'HELD'));

-- Backs the scheduled sweep that finds holds past their expiry.
CREATE INDEX idx_bookings_active_holds ON bookings (hold_expires_at) WHERE status = 'HELD';
