CREATE TABLE waitlist_entries (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    resource_id     UUID NOT NULL REFERENCES resources (id) ON DELETE CASCADE,
    space_id        UUID NOT NULL REFERENCES spaces (id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    time_range      TSTZRANGE NOT NULL,
    status          VARCHAR(10) NOT NULL DEFAULT 'WAITING'
                    CHECK (status IN ('WAITING', 'OFFERED', 'FULFILLED', 'EXPIRED', 'CANCELLED')),
    held_booking_id UUID REFERENCES bookings (id) ON DELETE SET NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- A member may hold only one active position for the same resource and slot.
CREATE UNIQUE INDEX uq_waitlist_active_entry
    ON waitlist_entries (resource_id, user_id, time_range)
    WHERE status IN ('WAITING', 'OFFERED');

-- FIFO promotion lookup: oldest WAITING entry on a resource first.
CREATE INDEX idx_waitlist_resource_waiting
    ON waitlist_entries (resource_id, created_at) WHERE status = 'WAITING';

CREATE INDEX idx_waitlist_user ON waitlist_entries (user_id);
