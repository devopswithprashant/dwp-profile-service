ALTER TABLE user_profiles RENAME TO profiles;

ALTER TABLE profiles
    RENAME CONSTRAINT user_profiles_preferences_object
    TO profiles_preferences_object;

ALTER TABLE profiles
    RENAME CONSTRAINT user_profiles_social_links_object
    TO profiles_social_links_object;
