CREATE TABLE resources (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    space_id   UUID NOT NULL REFERENCES spaces (id) ON DELETE CASCADE,
    name       VARCHAR(150) NOT NULL,
    type       VARCHAR(10)  NOT NULL CHECK (type IN ('DESK', 'ROOM')),
    capacity   INTEGER,
    is_active  BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_resources_space_id ON resources (space_id);
