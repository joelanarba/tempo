CREATE TABLE bookings (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    resource_id  UUID NOT NULL REFERENCES resources (id) ON DELETE CASCADE,
    user_id      UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    space_id     UUID NOT NULL REFERENCES spaces (id) ON DELETE CASCADE,
    time_range   TSTZRANGE NOT NULL,
    status       VARCHAR(10) NOT NULL DEFAULT 'CONFIRMED'
                 CHECK (status IN ('CONFIRMED', 'CANCELLED')),
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    cancelled_at TIMESTAMP WITH TIME ZONE,

    -- Defense-in-depth: the database itself forbids two CONFIRMED bookings on the
    -- same resource whose time ranges overlap (&&). btree_gist is what allows the
    -- equality test on resource_id to sit in the same GiST index as the range
    -- overlap test on time_range.
    CONSTRAINT bookings_no_overlap
        EXCLUDE USING gist (resource_id WITH =, time_range WITH &&)
        WHERE (status = 'CONFIRMED')
);

CREATE INDEX idx_bookings_resource_id ON bookings (resource_id);
CREATE INDEX idx_bookings_user_id ON bookings (user_id);
CREATE INDEX idx_bookings_space_id ON bookings (space_id);
