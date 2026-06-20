CREATE TABLE spaces (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name         VARCHAR(150) NOT NULL,
    location     VARCHAR(255) NOT NULL,
    description  TEXT,
    opening_time TIME NOT NULL,
    closing_time TIME NOT NULL,
    is_active    BOOLEAN NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT chk_spaces_hours CHECK (opening_time < closing_time)
);

CREATE INDEX idx_spaces_is_active ON spaces (is_active);
