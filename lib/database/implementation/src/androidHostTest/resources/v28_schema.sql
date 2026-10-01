CREATE TABLE IF NOT EXISTS authors (
    uuid TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    file_as TEXT,
    created_at TEXT,
    updated_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_authors_name ON authors(name);

CREATE TABLE IF NOT EXISTS books (
    uuid TEXT NOT NULL PRIMARY KEY,
    server_id TEXT NOT NULL DEFAULT 'legacy',
    server_type TEXT,
    id INTEGER NOT NULL,
    title TEXT NOT NULL,
    subtitle TEXT,
    language TEXT,
    publication_date TEXT,
    description TEXT,
    rating REAL,
    suffix TEXT,
    status_uuid TEXT,
    cover_url TEXT,
    created_at TEXT,
    updated_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_books_title ON books(title);

CREATE INDEX IF NOT EXISTS idx_books_rating ON books(rating);

CREATE INDEX IF NOT EXISTS idx_books_status ON books(status_uuid);

CREATE INDEX IF NOT EXISTS idx_books_server_id ON books(server_id);

CREATE TABLE IF NOT EXISTS book_authors (
    book_uuid TEXT NOT NULL,
    person_uuid TEXT NOT NULL,
    PRIMARY KEY (book_uuid, person_uuid)
);

CREATE INDEX IF NOT EXISTS idx_book_authors_person ON book_authors(person_uuid);

CREATE TABLE IF NOT EXISTS book_collections (
    book_uuid TEXT NOT NULL,
    collection_uuid TEXT NOT NULL,
    PRIMARY KEY (book_uuid, collection_uuid)
);

CREATE INDEX IF NOT EXISTS idx_book_collections_collection ON book_collections(collection_uuid);

CREATE TABLE IF NOT EXISTS book_creators (
    book_uuid TEXT NOT NULL,
    person_uuid TEXT NOT NULL,
    PRIMARY KEY (book_uuid, person_uuid)
);

CREATE INDEX IF NOT EXISTS idx_book_creators_person ON book_creators(person_uuid);

CREATE TABLE IF NOT EXISTS book_narrators (
    book_uuid TEXT NOT NULL,
    person_uuid TEXT NOT NULL,
    PRIMARY KEY (book_uuid, person_uuid)
);

CREATE INDEX IF NOT EXISTS idx_book_narrators_person ON book_narrators(person_uuid);

CREATE TABLE IF NOT EXISTS book_series (
    book_uuid TEXT NOT NULL,
    series_uuid TEXT NOT NULL,
    position REAL,
    PRIMARY KEY (book_uuid, series_uuid)
);

CREATE INDEX IF NOT EXISTS idx_book_series_series ON book_series(series_uuid);

CREATE TABLE IF NOT EXISTS book_tags (
    book_uuid TEXT NOT NULL,
    tag_uuid TEXT NOT NULL,
    PRIMARY KEY (book_uuid, tag_uuid)
);

CREATE INDEX IF NOT EXISTS idx_book_tags_tag ON book_tags(tag_uuid);

CREATE TABLE IF NOT EXISTS bookmarks (
    id TEXT NOT NULL PRIMARY KEY,
    book_uuid TEXT NOT NULL,
    locator_href TEXT NOT NULL,
    locator_type TEXT,
    locator_title TEXT,
    progression REAL,
    total_progression REAL,
    chapter_index INTEGER,
    position INTEGER,
    created_at TEXT NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    remote_revision INTEGER,
    deleted_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_bookmarks_book ON bookmarks(book_uuid);

CREATE INDEX IF NOT EXISTS idx_bookmarks_created ON bookmarks(created_at);

CREATE TABLE IF NOT EXISTS cloud_book_file_state (
    library_book_id TEXT NOT NULL,
    cloud_book_file_id TEXT NOT NULL,
    media_type TEXT NOT NULL,
    relative_path TEXT NOT NULL DEFAULT '',
    file_name TEXT NOT NULL,
    status TEXT NOT NULL,
    size_bytes INTEGER NOT NULL,
    content_hash TEXT NOT NULL,
    content_hash_algorithm TEXT NOT NULL,
    remote_revision INTEGER NOT NULL,
    updated_at TEXT NOT NULL,
    PRIMARY KEY (library_book_id, media_type, relative_path)
);

CREATE INDEX IF NOT EXISTS idx_cloud_book_file_state_book
ON cloud_book_file_state(library_book_id);

CREATE INDEX IF NOT EXISTS idx_cloud_book_file_state_hash
ON cloud_book_file_state(content_hash_algorithm, content_hash);

CREATE TABLE IF NOT EXISTS cloud_file_transfers (
    transfer_id TEXT NOT NULL PRIMARY KEY,
    server_id TEXT NOT NULL,
    direction TEXT NOT NULL,
    library_book_id TEXT NOT NULL,
    cloud_book_file_id TEXT,
    media_type TEXT NOT NULL,
    staging_path TEXT,
    size_bytes INTEGER NOT NULL,
    bytes_transferred INTEGER NOT NULL DEFAULT 0,
    content_hash TEXT,
    content_hash_algorithm TEXT,
    upload_id TEXT,
    storage_path TEXT,
    tus_upload_url TEXT,
    tus_expires_at TEXT,
    rights_attestation TEXT,
    state TEXT NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TEXT,
    last_error TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_cloud_file_transfers_eligible
ON cloud_file_transfers(server_id, state, next_attempt_at);

CREATE INDEX IF NOT EXISTS idx_cloud_file_transfers_book
ON cloud_file_transfers(server_id, library_book_id, created_at);

CREATE TABLE IF NOT EXISTS collections (
    uuid TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    created_at TEXT,
    updated_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_collections_name ON collections(name);

CREATE TABLE IF NOT EXISTS device_files (
    library_book_id TEXT NOT NULL,
    media_type TEXT NOT NULL,
    file_path TEXT NOT NULL,
    file_size INTEGER NOT NULL,
    content_hash TEXT,
    content_hash_algorithm TEXT,
    origin TEXT NOT NULL DEFAULT 'import',
    added_at TEXT NOT NULL,
    PRIMARY KEY (library_book_id, media_type)
);

CREATE INDEX IF NOT EXISTS idx_device_files_hash
ON device_files(content_hash_algorithm, content_hash);

CREATE TABLE IF NOT EXISTS favorites (
    book_uuid TEXT NOT NULL PRIMARY KEY,
    added_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_favorites_added_at ON favorites(added_at);

CREATE TABLE IF NOT EXISTS library_books (
    library_book_id TEXT NOT NULL PRIMARY KEY,
    title TEXT NOT NULL,
    author TEXT,
    description TEXT,
    cover_path TEXT,
    publication_date TEXT,
    source_content_hash TEXT,
    source_content_hash_algorithm TEXT,
    added_at TEXT NOT NULL,
    last_opened_at TEXT,
    remote_revision INTEGER,
    deleted_at TEXT,
    metadata_json TEXT
);

CREATE INDEX IF NOT EXISTS idx_library_books_source_hash
ON library_books(source_content_hash_algorithm, source_content_hash);

CREATE TABLE IF NOT EXISTS media_files (
    uuid TEXT NOT NULL PRIMARY KEY,
    book_uuid TEXT NOT NULL,
    type TEXT NOT NULL,
    filepath TEXT,
    missing INTEGER,
    size INTEGER,
    created_at TEXT,
    updated_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_media_files_book ON media_files(book_uuid);

CREATE UNIQUE INDEX IF NOT EXISTS idx_media_files_book_type ON media_files(book_uuid, type);

CREATE TABLE IF NOT EXISTS persons (
    uuid TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    file_as TEXT,
    created_at TEXT,
    updated_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_persons_name ON persons(name);

CREATE TABLE IF NOT EXISTS position (
    book_uuid TEXT NOT NULL PRIMARY KEY,
    library_book_id TEXT,
    local_generation INTEGER NOT NULL DEFAULT 0,
    remote_revision INTEGER,
    timestamp INTEGER,
    created_at TEXT,
    updated_at TEXT,
    locator_href TEXT,
    locator_type TEXT,
    locator_title TEXT,
    locator_target INTEGER,
    css_selector TEXT,
    audio_timestamp_ms INTEGER,
    chapter_index INTEGER,
    progression REAL,
    total_chapters INTEGER,
    total_duration_ms INTEGER,
    total_progression REAL,
    position INTEGER
);

CREATE INDEX IF NOT EXISTS idx_position_updated ON position(updated_at);

CREATE INDEX IF NOT EXISTS idx_position_progression ON position(progression);

CREATE INDEX IF NOT EXISTS idx_position_library_book ON position(library_book_id);

CREATE TABLE IF NOT EXISTS remote_position (
    book_uuid TEXT NOT NULL PRIMARY KEY,
    library_book_id TEXT,
    remote_revision INTEGER,
    timestamp INTEGER,
    created_at TEXT,
    updated_at TEXT,
    locator_href TEXT,
    locator_type TEXT,
    locator_title TEXT,
    locator_target INTEGER,
    css_selector TEXT,
    audio_timestamp_ms INTEGER,
    chapter_index INTEGER,
    progression REAL,
    total_chapters INTEGER,
    total_duration_ms INTEGER,
    total_progression REAL,
    position INTEGER
);

CREATE TABLE IF NOT EXISTS readalouds (
    uuid TEXT NOT NULL PRIMARY KEY,
    book_uuid TEXT NOT NULL UNIQUE,
    filepath TEXT,
    missing INTEGER,
    status TEXT,
    current_stage TEXT,
    stage_progress REAL,
    queue_position INTEGER,
    restart_pending INTEGER,
    created_at TEXT,
    updated_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_readalouds_book ON readalouds(book_uuid);

CREATE TABLE IF NOT EXISTS reader_settings (
    setting_key TEXT NOT NULL PRIMARY KEY,
    setting_value TEXT NOT NULL,
    remote_revision INTEGER,
    deleted_at TEXT
);

CREATE TABLE IF NOT EXISTS reading_session (
    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    book_uuid TEXT NOT NULL,
    book_title TEXT NOT NULL,
    book_type TEXT NOT NULL,
    start_time INTEGER NOT NULL,
    end_time INTEGER NOT NULL,
    duration_ms INTEGER NOT NULL,
    pages_read INTEGER,
    start_progression REAL,
    end_progression REAL,
    reading_speed_wpm INTEGER
);

CREATE INDEX IF NOT EXISTS idx_reading_session_book ON reading_session(book_uuid);

CREATE INDEX IF NOT EXISTS idx_reading_session_start_time ON reading_session(start_time);

CREATE TABLE IF NOT EXISTS series (
    uuid TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    featured INTEGER,
    created_at TEXT,
    updated_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_series_name ON series(name);

CREATE TABLE IF NOT EXISTS statuses (
    uuid TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    created_at TEXT,
    updated_at TEXT
);

CREATE TABLE IF NOT EXISTS sync_checkpoints (
    destination_id TEXT NOT NULL,
    remote_account_id TEXT NOT NULL,
    cursor TEXT,
    updated_at TEXT NOT NULL,
    status TEXT,
    pending_mutation_count INTEGER NOT NULL DEFAULT 0,
    last_successful_at TEXT,
    last_error TEXT,
    PRIMARY KEY (destination_id, remote_account_id)
);

CREATE TABLE IF NOT EXISTS sync_outbox (
    local_mutation_sequence INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    mutation_id TEXT NOT NULL UNIQUE,
    cloud_user_id TEXT,
    entity_type TEXT NOT NULL,
    entity_id TEXT NOT NULL,
    operation TEXT NOT NULL,
    payload TEXT NOT NULL,
    base_revision INTEGER,
    local_generation INTEGER NOT NULL DEFAULT 0,
    state TEXT NOT NULL DEFAULT 'pending',
    created_at TEXT NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TEXT,
    last_error TEXT
);

CREATE INDEX IF NOT EXISTS idx_sync_outbox_entity
ON sync_outbox(entity_type, entity_id);

CREATE TABLE IF NOT EXISTS tags (
    uuid TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    created_at TEXT,
    updated_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_tags_name ON tags(name);
