CREATE TABLE profiles (
    user_id UUID PRIMARY KEY,
    display_name TEXT,
    bio TEXT,
    avatar_url TEXT,
    preferences JSONB NOT NULL DEFAULT '{}'::jsonb,
    social_links JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT profiles_preferences_object
        CHECK (jsonb_typeof(preferences) = 'object'),
    CONSTRAINT profiles_social_links_object
        CHECK (jsonb_typeof(social_links) = 'object')
);
