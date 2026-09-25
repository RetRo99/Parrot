package com.retro99.database.implementation

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals

class DatabaseMigrationPreservationTest {
    @Test
    fun migrationFromVersion25PreservesUserAndPendingSyncRows() {
        // Given
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            createPreUnifiedVersion25Fixture(driver)

            // When
            AppDatabase.Schema.migrate(driver, 25, AppDatabase.Schema.version)

            // Then
            assertEquals(
                listOf(
                    listOf(
                        "audio-book",
                        "audiobookshelf-server-b",
                        "audiobookshelf",
                        "202",
                        "The Audio",
                    ),
                    listOf(
                        "story-book",
                        "storyteller-server-a",
                        "storyteller",
                        "101",
                        "The Story",
                    ),
                ),
                queryRows(
                    driver,
                    "SELECT uuid, server_id, server_type, CAST(id AS TEXT), title " +
                        "FROM books ORDER BY uuid",
                    columnCount = 5,
                ),
            )
            assertEquals(
                listOf(
                    listOf(
                        "cloud-library-book",
                        "cloud-content-hash",
                        "sha-256-v1",
                        "Cloud copy",
                        "epub",
                        "cloud-book-22",
                    ),
                    listOf(
                        "local-library-book",
                        "local-content-hash",
                        "sha-256-v1",
                        "Local import",
                        "epub",
                        null,
                    ),
                ),
                queryRows(
                    driver,
                    "SELECT library_book_id, content_hash, content_hash_algorithm, title, " +
                        "format, " +
                        "cloud_book_id FROM library_books ORDER BY library_book_id",
                    columnCount = 6,
                ),
            )
            assertEquals(
                listOf(
                    listOf(
                        "import-cloud-epub",
                        "Cloud copy",
                        "B. Author",
                        "/books/cloud.epub",
                        "16384",
                        "cloud-content-hash",
                        "sha-256-v1",
                        "ebook",
                        "import",
                        null,
                        "2026-09-21T11:00:00Z",
                    ),
                    listOf(
                        "import-local-epub",
                        "Local import",
                        "A. Author",
                        "/books/local.epub",
                        "8192",
                        "local-content-hash",
                        "sha-256-v1",
                        "ebook",
                        "import",
                        null,
                        "2026-09-20T10:00:00Z",
                    ),
                ),
                queryRows(
                    driver,
                    "SELECT uuid, title, author, file_path, CAST(file_size AS TEXT), " +
                        "content_hash, " +
                        "content_hash_algorithm, book_type, origin, cloud_book_file_id, " +
                        "last_opened_at FROM imported_books ORDER BY uuid",
                    columnCount = 11,
                ),
            )
            assertEquals(
                listOf(
                    listOf(
                        "import-cloud-epub",
                        "cloud-library-book",
                        "available",
                    ),
                    listOf(
                        "import-local-epub",
                        "local-library-book",
                        "available",
                    ),
                ),
                queryRows(
                    driver,
                    "SELECT imported_book_uuid, library_book_id, file_availability " +
                        "FROM local_book_files ORDER BY imported_book_uuid",
                    columnCount = 3,
                ),
            )
            assertEquals(
                listOf(
                    listOf(
                        "import-local-epub",
                        "local-library-book",
                        "4",
                        "3",
                        "chapter-2.xhtml",
                        "0.42",
                        "0.57",
                        "91",
                    ),
                ),
                queryRows(
                    driver,
                    "SELECT book_uuid, library_book_id, CAST(local_generation AS TEXT), " +
                        "CAST(remote_revision AS TEXT), locator_href, " +
                        "CAST(progression AS TEXT), CAST(total_progression AS TEXT), " +
                        "CAST(position AS TEXT) FROM position",
                    columnCount = 8,
                ),
            )
            assertEquals(
                listOf(
                    listOf(
                        "story-book",
                        "cloud-library-book",
                        "8",
                        "chapter-4.xhtml",
                        "0.75",
                    ),
                ),
                queryRows(
                    driver,
                    "SELECT book_uuid, library_book_id, CAST(remote_revision AS TEXT), " +
                        "locator_href, CAST(progression AS TEXT) FROM remote_position",
                    columnCount = 5,
                ),
            )
            assertEquals(
                listOf(
                    listOf(
                        "bookmark-active",
                        "import-local-epub",
                        "chapter-3.xhtml",
                        "5",
                        "12",
                        null,
                    ),
                    listOf(
                        "bookmark-deleted",
                        "import-cloud-epub",
                        "chapter-1.xhtml",
                        "1",
                        "9",
                        "2026-09-22T12:00:00Z",
                    ),
                ),
                queryRows(
                    driver,
                    "SELECT id, book_uuid, locator_href, CAST(sort_order AS TEXT), " +
                        "CAST(remote_revision AS TEXT), deleted_at FROM bookmarks ORDER BY id",
                    columnCount = 6,
                ),
            )
            assertEquals(
                listOf(
                    listOf(
                        "transfer-download-resume",
                        "download",
                        "cloud-library-book",
                        "cloud-book-22",
                        "cloud-file-7",
                        "ebook",
                        "import-cloud-epub",
                        "/cache/staging/cloud.epub.part",
                        "16384",
                        "8192",
                        "transferring",
                        "2",
                        null,
                        "network-interrupted",
                    ),
                    listOf(
                        "transfer-upload-pending",
                        "upload",
                        "local-library-book",
                        null,
                        null,
                        "ebook",
                        "import-local-epub",
                        null,
                        "8192",
                        "0",
                        "pending",
                        "1",
                        "2026-09-23T10:00:00Z",
                        null,
                    ),
                ),
                queryRows(
                    driver,
                    "SELECT transfer_id, direction, library_book_id, cloud_book_id, " +
                        "cloud_book_file_id, media_type, local_source_uuid, staging_path, " +
                        "CAST(size_bytes AS TEXT), CAST(bytes_transferred AS TEXT), state, " +
                        "CAST(attempt_count AS TEXT), next_attempt_at, last_error " +
                        "FROM cloud_file_transfers ORDER BY transfer_id",
                    columnCount = 14,
                ),
            )
            assertEquals(
                listOf(
                    listOf(
                        "cloud-library-book",
                        "cloud-book-22",
                        "cloud-file-7",
                        "ebook",
                        "cloud.epub",
                        "stored",
                        "16384",
                        "cloud-content-hash",
                        "sha-256-v1",
                        "8",
                    ),
                ),
                queryRows(
                    driver,
                    "SELECT library_book_id, cloud_book_id, cloud_book_file_id, media_type, " +
                        "file_name, status, CAST(size_bytes AS TEXT), content_hash, " +
                        "content_hash_algorithm, CAST(remote_revision AS TEXT) " +
                        "FROM cloud_book_file_state",
                    columnCount = 10,
                ),
            )
            assertEquals(
                listOf(
                    listOf(
                        "mutation-dispatched",
                        "cloud-user-b",
                        "position",
                        "story-book",
                        "upsert",
                        null,
                        "6",
                        "dispatched",
                        "timeout",
                    ),
                    listOf(
                        "mutation-pending",
                        "cloud-user-a",
                        "library_book",
                        "cloud-library-book",
                        "upsert",
                        "8",
                        "5",
                        "pending",
                        null,
                    ),
                ),
                queryRows(
                    driver,
                    "SELECT mutation_id, cloud_user_id, entity_type, entity_id, operation, " +
                        "CAST(base_revision AS TEXT), CAST(local_generation AS TEXT), state, " +
                        "last_error FROM sync_outbox ORDER BY mutation_id",
                    columnCount = 9,
                ),
            )
            assertEquals(
                listOf(
                    listOf("cloud-server-a", "cloud-user-a", "cursor-a", "ready", "1"),
                    listOf("cloud-server-b", "cloud-user-b", "cursor-b", "syncing", "2"),
                ),
                queryRows(
                    driver,
                    "SELECT destination_id, remote_account_id, cursor, status, " +
                        "CAST(pending_mutation_count AS TEXT) FROM sync_checkpoints " +
                        "ORDER BY destination_id",
                    columnCount = 5,
                ),
            )
            assertEquals(
                "library_group_media_preferences",
                queryValue(
                    driver,
                    "SELECT name FROM sqlite_master WHERE type = 'table' " +
                        "AND name = 'library_group_media_preferences'",
                ),
            )
            assertEquals(
                "library_group_aliases",
                queryValue(
                    driver,
                    "SELECT name FROM sqlite_master WHERE type = 'table' " +
                        "AND name = 'library_group_aliases'",
                ),
            )
            assertEquals(
                "library_source_identity_aliases",
                queryValue(
                    driver,
                    "SELECT name FROM sqlite_master WHERE type = 'table' " +
                        "AND name = 'library_source_identity_aliases'",
                ),
            )
            assertEquals(42L, AppDatabase.Schema.version)
        } finally {
            driver.close()
        }
    }

    @Test
    fun migrationFromVersion38PreservesAliasesWithinEachProfile() {
        // Given
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            createVersion38AliasFixture(driver)

            // When
            AppDatabase.Schema.migrate(driver, 38, AppDatabase.Schema.version)

            // Then
            assertEquals(
                listOf(
                    listOf("profile-a", "legacy-group", "group-a", "2026-09-20T00:00:00Z"),
                    listOf("profile-b", "legacy-group", "group-b", "2026-09-21T00:00:00Z"),
                ),
                queryRows(
                    driver,
                    "SELECT profile_id, alias_group_id, target_group_id, created_at " +
                        "FROM library_group_aliases ORDER BY profile_id",
                    columnCount = 4,
                ),
            )
            assertEquals(
                listOf(
                    listOf("profile-a", "legacy-source", "source-a", "2026-09-20T00:01:00Z"),
                    listOf("profile-b", "legacy-source", "source-b", "2026-09-21T00:01:00Z"),
                ),
                queryRows(
                    driver,
                    "SELECT profile_id, old_source_key, target_source_key, created_at " +
                        "FROM library_source_identity_aliases ORDER BY profile_id",
                    columnCount = 4,
                ),
            )
            assertEquals(42L, AppDatabase.Schema.version)
        } finally {
            driver.close()
        }
    }

    @Test
    fun migrationFromVersion39PreservesRemovalIntentsAndRenamesAppliedPhase() {
        // Given
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            createPreVersion39RemovalFixture(driver)

            // When
            AppDatabase.Schema.migrate(driver, 39, AppDatabase.Schema.version)

            // Then
            assertEquals(
                listOf(
                    listOf(
                        "remove-applied",
                        "profile-a",
                        "group-a",
                        "source-a",
                        "connection-a",
                        null,
                        "resource-a",
                        "1",
                        "revision-a",
                        "asset-a",
                        "replica-a",
                        "/media/applied.epub",
                        "2026-09-24T00:00:00Z",
                        "EffectApplied",
                    ),
                    listOf(
                        "remove-requested",
                        "profile-b",
                        "group-b",
                        "source-b",
                        "connection-b",
                        "legacy-book-b",
                        "resource-b",
                        "0",
                        "",
                        "asset-b",
                        "replica-b",
                        "/media/requested.epub",
                        "2026-09-24T00:01:00Z",
                        "Requested",
                    ),
                ),
                queryRemovalRows(driver),
            )
            assertEquals(
                "idx_library_replica_removal_active_target",
                queryValue(
                    driver,
                    "SELECT name FROM sqlite_master WHERE type = 'index' " +
                        "AND name = 'idx_library_replica_removal_active_target'",
                ),
            )
            assertEquals(
                "idx_library_replica_removal_pending",
                queryValue(
                    driver,
                    "SELECT name FROM sqlite_master WHERE type = 'index' " +
                        "AND name = 'idx_library_replica_removal_pending'",
                ),
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun migrationFromVersion40AddsSnapshotPrecisionWithoutLosingRows() {
        // Given
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            executeSql(
                driver,
                """
                    CREATE TABLE library_source_snapshots (
                        profile_id TEXT NOT NULL,
                        source_key TEXT NOT NULL,
                        observed_at_epoch_ms INTEGER NOT NULL,
                        PRIMARY KEY (profile_id, source_key)
                    )
                """.trimIndent(),
            )
            executeSql(
                driver,
                """
                    INSERT INTO library_source_snapshots(
                        profile_id, source_key, observed_at_epoch_ms
                    ) VALUES ('profile-a', 'source-a', 1790208000000)
                """.trimIndent(),
            )

            // When
            AppDatabase.Schema.migrate(driver, 40, AppDatabase.Schema.version)

            // Then
            assertEquals("1790208000000", queryValue(
                driver,
                "SELECT CAST(observed_at_epoch_ms AS TEXT) FROM library_source_snapshots",
            ))
            assertEquals("0", queryValue(
                driver,
                "SELECT CAST(observed_at_submillisecond_ns AS TEXT) " +
                    "FROM library_source_snapshots",
            ))
            assertEquals(42L, AppDatabase.Schema.version)
        } finally {
            driver.close()
        }
    }

    @Test
    fun migrationFromVersion41AddsCollectionsWithoutChangingSnapshots() {
        // Given
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            executeSql(
                driver,
                """
                    CREATE TABLE library_source_snapshots (
                        profile_id TEXT NOT NULL,
                        source_key TEXT NOT NULL,
                        PRIMARY KEY (profile_id, source_key)
                    )
                """.trimIndent(),
            )
            executeSql(
                driver,
                """
                    INSERT INTO library_source_snapshots(profile_id, source_key)
                    VALUES ('profile-a', 'source-a')
                """.trimIndent(),
            )

            // When
            AppDatabase.Schema.migrate(driver, 41, AppDatabase.Schema.version)

            // Then
            assertEquals("source-a", queryValue(
                driver,
                "SELECT source_key FROM library_source_snapshots WHERE profile_id = 'profile-a'",
            ))
            assertEquals(
                "library_source_snapshot_collections",
                queryValue(
                    driver,
                    "SELECT name FROM sqlite_master WHERE type = 'table' " +
                        "AND name = 'library_source_snapshot_collections'",
                ),
            )
            assertEquals(42L, AppDatabase.Schema.version)
        } finally {
            driver.close()
        }
    }

    private fun createPreUnifiedVersion25Fixture(driver: JdbcSqliteDriver) {
        listOf(
            """
                CREATE TABLE books (
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
                )
            """.trimIndent(),
            """
                CREATE TABLE imported_books (
                    uuid TEXT NOT NULL PRIMARY KEY,
                    title TEXT NOT NULL,
                    author TEXT,
                    description TEXT,
                    cover_path TEXT,
                    file_path TEXT NOT NULL,
                    file_size INTEGER NOT NULL,
                    content_hash TEXT,
                    content_hash_algorithm TEXT,
                    imported_at TEXT NOT NULL,
                    last_opened_at TEXT,
                    book_type TEXT NOT NULL DEFAULT 'ebook',
                    publication_date TEXT
                )
            """.trimIndent(),
            """
                CREATE TABLE library_books (
                    library_book_id TEXT NOT NULL PRIMARY KEY,
                    content_hash TEXT,
                    content_hash_algorithm TEXT,
                    title TEXT NOT NULL,
                    author TEXT,
                    format TEXT NOT NULL,
                    remote_revision INTEGER,
                    deleted_at TEXT,
                    cloud_book_id TEXT,
                    metadata_json TEXT
                )
            """.trimIndent(),
            """
                CREATE TABLE local_book_files (
                    library_book_id TEXT NOT NULL,
                    imported_book_uuid TEXT NOT NULL PRIMARY KEY,
                    file_availability TEXT NOT NULL DEFAULT 'available'
                )
            """.trimIndent(),
            """
                CREATE TABLE position (
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
                )
            """.trimIndent(),
            """
                CREATE TABLE remote_position (
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
                )
            """.trimIndent(),
            """
                CREATE TABLE bookmarks (
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
                )
            """.trimIndent(),
            """
                CREATE TABLE cloud_book_file_state (
                    library_book_id TEXT NOT NULL,
                    cloud_book_id TEXT NOT NULL,
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
                )
            """.trimIndent(),
            """
                CREATE TABLE cloud_file_transfers (
                    transfer_id TEXT NOT NULL PRIMARY KEY,
                    server_id TEXT NOT NULL,
                    direction TEXT NOT NULL,
                    library_book_id TEXT NOT NULL,
                    cloud_book_id TEXT,
                    cloud_book_file_id TEXT,
                    media_type TEXT NOT NULL,
                    local_source_uuid TEXT,
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
                )
            """.trimIndent(),
            """
                CREATE TABLE sync_outbox (
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
                )
            """.trimIndent(),
            """
                CREATE TABLE sync_checkpoints (
                    destination_id TEXT NOT NULL,
                    remote_account_id TEXT NOT NULL,
                    cursor TEXT,
                    updated_at TEXT NOT NULL,
                    status TEXT,
                    pending_mutation_count INTEGER NOT NULL DEFAULT 0,
                    last_successful_at TEXT,
                    last_error TEXT,
                    PRIMARY KEY (destination_id, remote_account_id)
                )
            """.trimIndent(),
            "CREATE INDEX idx_books_title ON books(title)",
            "CREATE INDEX idx_books_rating ON books(rating)",
            "CREATE INDEX idx_books_status ON books(status_uuid)",
            "CREATE INDEX idx_books_server_id ON books(server_id)",
            "CREATE INDEX idx_imported_books_title ON imported_books(title)",
            "CREATE INDEX idx_imported_books_imported_at ON imported_books(imported_at)",
            "CREATE INDEX idx_imported_books_content_hash ON imported_books(content_hash)",
            "CREATE INDEX idx_library_books_content_hash ON library_books(content_hash)",
            "CREATE INDEX idx_library_books_hash_identity " +
                "ON library_books(content_hash_algorithm, content_hash)",
            "CREATE UNIQUE INDEX idx_library_books_cloud_book ON library_books(cloud_book_id) " +
                "WHERE cloud_book_id IS NOT NULL",
            "CREATE INDEX idx_local_book_files_library_book ON local_book_files(library_book_id)",
            "CREATE INDEX idx_position_updated ON position(updated_at)",
            "CREATE INDEX idx_position_progression ON position(progression)",
            "CREATE INDEX idx_position_library_book ON position(library_book_id)",
            "CREATE INDEX idx_bookmarks_book ON bookmarks(book_uuid)",
            "CREATE INDEX idx_bookmarks_created ON bookmarks(created_at)",
            "CREATE INDEX idx_cloud_book_file_state_book " +
                "ON cloud_book_file_state(library_book_id)",
            "CREATE INDEX idx_cloud_file_transfers_eligible " +
                "ON cloud_file_transfers(server_id, state, next_attempt_at)",
            "CREATE INDEX idx_cloud_file_transfers_book " +
                "ON cloud_file_transfers(server_id, library_book_id, created_at)",
            "CREATE INDEX idx_sync_outbox_entity ON sync_outbox(entity_type, entity_id)",
        ).forEach { statement -> executeSql(driver, statement) }

        listOf(
            """
                INSERT INTO books(
                    uuid, server_id, server_type, id, title, subtitle, language,
                    publication_date, description, rating, suffix, status_uuid, cover_url,
                    created_at, updated_at
                ) VALUES (
                    'story-book', 'storyteller-server-a', 'storyteller', 101, 'The Story',
                    'A chaptered book', 'en', '2024-01-02', 'A reader fixture', 4.5, NULL,
                    NULL, 'cover-story', '2024-01-02T00:00:00Z', '2024-01-03T00:00:00Z'
                )
            """.trimIndent(),
            """
                INSERT INTO books(
                    uuid, server_id, server_type, id, title, subtitle, language,
                    publication_date, description, rating, suffix, status_uuid, cover_url,
                    created_at, updated_at
                ) VALUES (
                    'audio-book', 'audiobookshelf-server-b', 'audiobookshelf', 202,
                    'The Audio', NULL, 'en', NULL, NULL, NULL, NULL, NULL, NULL,
                    '2024-02-02T00:00:00Z', '2024-02-03T00:00:00Z'
                )
            """.trimIndent(),
            """
                INSERT INTO library_books(
                    library_book_id, content_hash, content_hash_algorithm, title, author,
                    format, remote_revision, deleted_at, cloud_book_id, metadata_json
                ) VALUES (
                    'local-library-book', 'local-content-hash', 'sha-256-v1', 'Local import',
                    'A. Author', 'epub', NULL, NULL, NULL, '{"source":"local"}'
                )
            """.trimIndent(),
            """
                INSERT INTO library_books(
                    library_book_id, content_hash, content_hash_algorithm, title, author,
                    format, remote_revision, deleted_at, cloud_book_id, metadata_json
                ) VALUES (
                    'cloud-library-book', 'cloud-content-hash', 'sha-256-v1', 'Cloud copy',
                    'B. Author', 'epub', 8, NULL, 'cloud-book-22', '{"source":"cloud"}'
                )
            """.trimIndent(),
            """
                INSERT INTO imported_books(
                    uuid, title, author, description, cover_path, file_path, file_size,
                    content_hash, content_hash_algorithm, imported_at, last_opened_at,
                    book_type, publication_date
                ) VALUES (
                    'import-local-epub', 'Local import', 'A. Author', 'Local reader file',
                    '/covers/local.jpg', '/books/local.epub', 8192, 'local-content-hash',
                    'sha-256-v1', '2026-09-20T09:00:00Z', '2026-09-20T10:00:00Z',
                    'ebook', '2020-01-01'
                )
            """.trimIndent(),
            """
                INSERT INTO imported_books(
                    uuid, title, author, description, cover_path, file_path, file_size,
                    content_hash, content_hash_algorithm, imported_at, last_opened_at,
                    book_type, publication_date
                ) VALUES (
                    'import-cloud-epub', 'Cloud copy', 'B. Author', 'Downloaded reader file',
                    '/covers/cloud.jpg', '/books/cloud.epub', 16384, 'cloud-content-hash',
                    'sha-256-v1', '2026-09-21T10:00:00Z', '2026-09-21T11:00:00Z',
                    'ebook', '2021-02-02'
                )
            """.trimIndent(),
            """
                INSERT INTO local_book_files(
                    library_book_id, imported_book_uuid, file_availability
                ) VALUES ('local-library-book', 'import-local-epub', 'available')
            """.trimIndent(),
            """
                INSERT INTO local_book_files(
                    library_book_id, imported_book_uuid, file_availability
                ) VALUES ('cloud-library-book', 'import-cloud-epub', 'available')
            """.trimIndent(),
            """
                INSERT INTO position(
                    book_uuid, library_book_id, local_generation, remote_revision, timestamp,
                    created_at, updated_at, locator_href, locator_type, locator_title,
                    locator_target, css_selector, audio_timestamp_ms, chapter_index,
                    progression, total_chapters, total_duration_ms, total_progression, position
                ) VALUES (
                    'import-local-epub', 'local-library-book', 4, 3, 1790000000000,
                    '2026-09-20T10:00:00Z', '2026-09-20T10:05:00Z', 'chapter-2.xhtml',
                    'application/xhtml+xml', 'Chapter Two', 1, 'body > p:nth-child(4)',
                    NULL, 2, 0.42, 10, NULL, 0.57, 91
                )
            """.trimIndent(),
            """
                INSERT INTO remote_position(
                    book_uuid, library_book_id, remote_revision, timestamp, created_at,
                    updated_at, locator_href, locator_type, locator_title, locator_target,
                    css_selector, audio_timestamp_ms, chapter_index, progression,
                    total_chapters, total_duration_ms, total_progression, position
                ) VALUES (
                    'story-book', 'cloud-library-book', 8, 1790100000000,
                    '2026-09-21T11:00:00Z', '2026-09-21T11:05:00Z', 'chapter-4.xhtml',
                    'application/xhtml+xml', 'Chapter Four', 1, NULL, NULL, 4, 0.75,
                    10, NULL, 0.75, 144
                )
            """.trimIndent(),
            """
                INSERT INTO bookmarks(
                    id, book_uuid, locator_href, locator_type, locator_title, progression,
                    total_progression, chapter_index, position, created_at, sort_order,
                    remote_revision, deleted_at
                ) VALUES (
                    'bookmark-active', 'import-local-epub', 'chapter-3.xhtml',
                    'application/xhtml+xml', 'Chapter Three', 0.52, 0.57, 3, 118,
                    '2026-09-20T10:02:00Z', 5, 12, NULL
                )
            """.trimIndent(),
            """
                INSERT INTO bookmarks(
                    id, book_uuid, locator_href, locator_type, locator_title, progression,
                    total_progression, chapter_index, position, created_at, sort_order,
                    remote_revision, deleted_at
                ) VALUES (
                    'bookmark-deleted', 'import-cloud-epub', 'chapter-1.xhtml',
                    'application/xhtml+xml', 'Chapter One', 0.1, 0.8, 1, 20,
                    '2026-09-21T11:02:00Z', 1, 9, '2026-09-22T12:00:00Z'
                )
            """.trimIndent(),
            """
                INSERT INTO cloud_book_file_state(
                    library_book_id, cloud_book_id, cloud_book_file_id, media_type,
                    relative_path, file_name, status, size_bytes, content_hash,
                    content_hash_algorithm, remote_revision, updated_at
                ) VALUES (
                    'cloud-library-book', 'cloud-book-22', 'cloud-file-7', 'ebook', '',
                    'cloud.epub', 'stored', 16384, 'cloud-content-hash', 'sha-256-v1', 8,
                    '2026-09-21T11:00:00Z'
                )
            """.trimIndent(),
            """
                INSERT INTO cloud_file_transfers(
                    transfer_id, server_id, direction, library_book_id, cloud_book_id,
                    cloud_book_file_id, media_type, local_source_uuid, staging_path,
                    size_bytes, bytes_transferred, content_hash, content_hash_algorithm,
                    upload_id, storage_path, tus_upload_url, tus_expires_at, rights_attestation,
                    state, attempt_count, next_attempt_at, last_error, created_at, updated_at
                ) VALUES (
                    'transfer-download-resume', 'cloud-server-a', 'download',
                    'cloud-library-book', 'cloud-book-22', 'cloud-file-7', 'ebook',
                    'import-cloud-epub', '/cache/staging/cloud.epub.part', 16384, 8192,
                    'cloud-content-hash', 'sha-256-v1', NULL, NULL, NULL, NULL, NULL,
                    'transferring', 2, NULL, 'network-interrupted',
                    '2026-09-21T11:00:00Z', '2026-09-21T11:05:00Z'
                )
            """.trimIndent(),
            """
                INSERT INTO cloud_file_transfers(
                    transfer_id, server_id, direction, library_book_id, cloud_book_id,
                    cloud_book_file_id, media_type, local_source_uuid, staging_path,
                    size_bytes, bytes_transferred, content_hash, content_hash_algorithm,
                    upload_id, storage_path, tus_upload_url, tus_expires_at, rights_attestation,
                    state, attempt_count, next_attempt_at, last_error, created_at, updated_at
                ) VALUES (
                    'transfer-upload-pending', 'cloud-server-b', 'upload',
                    'local-library-book', NULL, NULL, 'ebook', 'import-local-epub', NULL,
                    8192, 0, 'local-content-hash', 'sha-256-v1', 'upload-31',
                    'backups/local.epub', 'https://upload.example/tus/31',
                    '2026-09-23T10:05:00Z', 'rights-confirmed', 'pending', 1,
                    '2026-09-23T10:00:00Z', NULL, '2026-09-23T09:00:00Z',
                    '2026-09-23T09:05:00Z'
                )
            """.trimIndent(),
            """
                INSERT INTO sync_outbox(
                    mutation_id, cloud_user_id, entity_type, entity_id, operation, payload,
                    base_revision, local_generation, state, created_at, attempt_count,
                    next_attempt_at, last_error
                ) VALUES (
                    'mutation-pending', 'cloud-user-a', 'library_book', 'cloud-library-book',
                    'upsert', '{"title":"Cloud copy"}', 8, 5, 'pending',
                    '2026-09-21T11:01:00Z', 0, NULL, NULL
                )
            """.trimIndent(),
            """
                INSERT INTO sync_outbox(
                    mutation_id, cloud_user_id, entity_type, entity_id, operation, payload,
                    base_revision, local_generation, state, created_at, attempt_count,
                    next_attempt_at, last_error
                ) VALUES (
                    'mutation-dispatched', 'cloud-user-b', 'position', 'story-book', 'upsert',
                    '{"progression":0.75}', NULL, 6, 'dispatched',
                    '2026-09-21T11:02:00Z', 1, '2026-09-22T11:02:00Z', 'timeout'
                )
            """.trimIndent(),
            """
                INSERT INTO sync_checkpoints(
                    destination_id, remote_account_id, cursor, updated_at, status,
                    pending_mutation_count, last_successful_at, last_error
                ) VALUES (
                    'cloud-server-a', 'cloud-user-a', 'cursor-a',
                    '2026-09-21T11:00:00Z', 'ready', 1, '2026-09-21T10:59:00Z', NULL
                )
            """.trimIndent(),
            """
                INSERT INTO sync_checkpoints(
                    destination_id, remote_account_id, cursor, updated_at, status,
                    pending_mutation_count, last_successful_at, last_error
                ) VALUES (
                    'cloud-server-b', 'cloud-user-b', 'cursor-b',
                    '2026-09-22T11:00:00Z', 'syncing', 2, '2026-09-22T10:59:00Z', NULL
                )
            """.trimIndent(),
        ).forEach { statement -> executeSql(driver, statement) }
    }

    private fun createVersion38AliasFixture(driver: JdbcSqliteDriver) {
        createPreVersion39RemovalFixture(driver)
        listOf(
            """
                CREATE TABLE library_groups (
                    profile_id TEXT NOT NULL,
                    group_id TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    PRIMARY KEY (profile_id, group_id)
                )
            """.trimIndent(),
            """
                CREATE TABLE library_group_aliases (
                    profile_id TEXT NOT NULL,
                    alias_group_id TEXT NOT NULL,
                    target_group_id TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    PRIMARY KEY (profile_id, alias_group_id),
                    CHECK (alias_group_id <> target_group_id)
                )
            """.trimIndent(),
            """
                CREATE TABLE library_source_identity_aliases (
                    profile_id TEXT NOT NULL,
                    old_source_key TEXT NOT NULL,
                    target_source_key TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    PRIMARY KEY (profile_id, old_source_key),
                    CHECK (old_source_key <> target_source_key)
                )
            """.trimIndent(),
            "CREATE INDEX idx_library_source_identity_alias_target " +
                "ON library_source_identity_aliases(profile_id, target_source_key)",
            """
                INSERT INTO library_groups(profile_id, group_id, created_at) VALUES
                    ('profile-a', 'group-a', '2026-09-20T00:00:00Z'),
                    ('profile-a', 'legacy-group', '2026-09-20T00:00:00Z'),
                    ('profile-b', 'group-b', '2026-09-21T00:00:00Z'),
                    ('profile-b', 'legacy-group', '2026-09-21T00:00:00Z')
            """.trimIndent(),
            """
                INSERT INTO library_group_aliases(
                    profile_id, alias_group_id, target_group_id, created_at
                ) VALUES
                    ('profile-a', 'legacy-group', 'group-a', '2026-09-20T00:00:00Z'),
                    ('profile-b', 'legacy-group', 'group-b', '2026-09-21T00:00:00Z')
            """.trimIndent(),
            """
                INSERT INTO library_source_identity_aliases(
                    profile_id, old_source_key, target_source_key, created_at
                ) VALUES
                    ('profile-a', 'legacy-source', 'source-a', '2026-09-20T00:01:00Z'),
                    ('profile-b', 'legacy-source', 'source-b', '2026-09-21T00:01:00Z')
            """.trimIndent(),
        ).forEach { statement -> executeSql(driver, statement) }
    }

    private fun createPreVersion39RemovalFixture(driver: JdbcSqliteDriver) {
        executeSql(
            driver,
            """
                CREATE TABLE library_source_snapshots (
                    profile_id TEXT NOT NULL,
                    source_key TEXT NOT NULL,
                    execution_connection_id TEXT,
                    legacy_library_book_id TEXT,
                    title TEXT NOT NULL,
                    description TEXT,
                    cover_reference TEXT,
                    observed_at_epoch_ms INTEGER NOT NULL,
                    presence TEXT NOT NULL CHECK (presence IN ('Present', 'Removed', 'Unknown')),
                    is_authoritative INTEGER NOT NULL CHECK (is_authoritative IN (0, 1)),
                    revision TEXT,
                    PRIMARY KEY (profile_id, source_key),
                    CHECK (presence <> 'Removed' OR is_authoritative = 1)
                )
            """.trimIndent(),
        )
        executeSql(
            driver,
            """
                CREATE TABLE library_replica_removal_intents (
                    operation_id TEXT NOT NULL PRIMARY KEY,
                    profile_id TEXT NOT NULL,
                    group_id TEXT NOT NULL,
                    source_key TEXT NOT NULL,
                    execution_connection_id TEXT NOT NULL,
                    legacy_library_book_id TEXT,
                    resource_native_id TEXT NOT NULL,
                    resource_revision_present INTEGER NOT NULL
                        CHECK (resource_revision_present IN (0, 1)),
                    resource_revision_value TEXT NOT NULL,
                    asset_id TEXT NOT NULL,
                    replica_id TEXT NOT NULL,
                    storage_reference TEXT NOT NULL,
                    requested_at TEXT NOT NULL,
                    phase TEXT NOT NULL
                        CHECK (phase IN ('Requested', 'BytesRemoved', 'Completed')),
                    CHECK (resource_revision_present = 1 OR resource_revision_value = '')
                )
            """.trimIndent(),
        )
        executeSql(
            driver,
            """
                CREATE UNIQUE INDEX idx_library_replica_removal_active_target
                ON library_replica_removal_intents(
                    profile_id, source_key, resource_native_id, resource_revision_present,
                    resource_revision_value, storage_reference
                ) WHERE phase <> 'Completed'
            """.trimIndent(),
        )
        executeSql(
            driver,
            """
                CREATE INDEX idx_library_replica_removal_pending
                ON library_replica_removal_intents(profile_id, phase, requested_at)
            """.trimIndent(),
        )
        executeSql(
            driver,
            """
                INSERT INTO library_replica_removal_intents VALUES (
                    'remove-applied', 'profile-a', 'group-a', 'source-a', 'connection-a',
                    NULL, 'resource-a', 1, 'revision-a', 'asset-a', 'replica-a',
                    '/media/applied.epub', '2026-09-24T00:00:00Z', 'BytesRemoved'
                )
            """.trimIndent(),
        )
        executeSql(
            driver,
            """
                INSERT INTO library_replica_removal_intents VALUES (
                    'remove-requested', 'profile-b', 'group-b', 'source-b', 'connection-b',
                    'legacy-book-b', 'resource-b', 0, '', 'asset-b', 'replica-b',
                    '/media/requested.epub', '2026-09-24T00:01:00Z', 'Requested'
                )
            """.trimIndent(),
        )
    }

    private fun executeSql(driver: JdbcSqliteDriver, sql: String) {
        driver.execute(identifier = null, sql = sql, parameters = 0)
    }

    private fun queryRemovalRows(driver: JdbcSqliteDriver): List<List<String?>> =
        queryRows(
            driver,
            """
                SELECT operation_id, profile_id, group_id, source_key,
                    execution_connection_id, legacy_library_book_id, resource_native_id,
                    CAST(resource_revision_present AS TEXT), resource_revision_value,
                    asset_id, replica_id, storage_reference, requested_at, phase
                FROM library_replica_removal_intents ORDER BY operation_id
            """.trimIndent(),
            columnCount = 14,
        )

    private fun queryRows(
        driver: JdbcSqliteDriver,
        sql: String,
        columnCount: Int,
    ): List<List<String?>> = driver.executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            val rows = mutableListOf<List<String?>>()
            while (cursor.next().value) {
                rows += List(columnCount) { columnIndex -> cursor.getString(columnIndex) }
            }
            QueryResult.Value(rows)
        },
        parameters = 0,
    ).value

    private fun queryValue(
        driver: JdbcSqliteDriver,
        sql: String,
    ): String? = driver.executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getString(0))
        },
        parameters = 0,
    ).value
}
