ALTER TABLE venues
    ADD COLUMN IF NOT EXISTS latitude DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS longitude DOUBLE PRECISION;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_venues_latitude') THEN
        ALTER TABLE venues
            ADD CONSTRAINT chk_venues_latitude
                CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_venues_longitude') THEN
        ALTER TABLE venues
            ADD CONSTRAINT chk_venues_longitude
                CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180);
    END IF;
END $$;
