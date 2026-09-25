package com.retro99.database.implementation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.db.QueryResult
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull

class LibraryGroupQueriesTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: AppDatabase

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        database = AppDatabase(driver)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun nativeIdsAreUniqueOnlyWithinTheirProfileAndAccountScope() {
        // Given
        insertGroup("profile-a", "group-a")
        insertGroup("profile-a", "group-b")
        insertGroup("profile-b", "group-c")

        // When
        insertPortableMember("profile-a", "group-a", "account-a")
        insertPortableMember("profile-a", "group-b", "account-b")
        insertPortableMember("profile-b", "group-c", "account-a")

        // Then
        assertEquals(1, database.libraryGroupQueries.getLibraryGroupMemberships(
            "profile-a", "group-a",
        ).executeAsList().size)
        assertEquals(1, database.libraryGroupQueries.getLibraryGroupMemberships(
            "profile-a", "group-b",
        ).executeAsList().size)
        assertEquals(1, database.libraryGroupQueries.getLibraryGroupMemberships(
            "profile-b", "group-c",
        ).executeAsList().size)
        assertFails { insertPortableMember("profile-a", "group-b", "account-a") }
    }

    @Test
    fun unresolvedMembershipRetainsItsConnectionAndCannotClaimPortableIdentity() {
        // Given
        insertGroup("profile-a", "group-a")

        // When
        database.libraryGroupQueries.insertLibraryGroupMembership(
            profile_id = "profile-a",
            adapter_id = "future-source",
            identity_kind = "unresolved",
            backend_id = "",
            account_id = "",
            unresolved_connection_id = "connection-a",
            native_book_id = "native-book",
            group_id = "group-a",
            execution_connection_id = "connection-a",
            legacy_library_book_id = null,
        )

        // Then
        assertNotNull(database.libraryGroupQueries.getLibraryGroupMembership(
            profile_id = "profile-a",
            adapter_id = "future-source",
            identity_kind = "unresolved",
            backend_id = "",
            account_id = "",
            unresolved_connection_id = "connection-a",
            native_book_id = "native-book",
        ).executeAsOneOrNull())
        assertFails {
            database.libraryGroupQueries.insertLibraryGroupMembership(
                profile_id = "profile-a",
                adapter_id = "future-source",
                identity_kind = "portable",
                backend_id = "",
                account_id = "",
                unresolved_connection_id = "connection-a",
                native_book_id = "other-book",
                group_id = "group-a",
                execution_connection_id = "connection-a",
                legacy_library_book_id = null,
            )
        }
    }

    @Test
    fun migrationFromVersion25PreservesExistingRowsAndAddsEvidenceTables() {
        // Given
        val oldDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            oldDriver.execute(
                identifier = null,
                sql = """
                    CREATE TABLE library_books (
                        library_book_id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL
                    );
                """.trimIndent(),
                parameters = 0,
            )
            oldDriver.execute(
                identifier = null,
                sql = "INSERT INTO library_books VALUES ('legacy-hash-id', 'Existing book');",
                parameters = 0,
            )
            oldDriver.execute(
                identifier = null,
                sql = "CREATE TABLE imported_books (uuid TEXT NOT NULL PRIMARY KEY);",
                parameters = 0,
            )

            // When
            AppDatabase.Schema.migrate(oldDriver, 25, AppDatabase.Schema.version)
            val migrated = AppDatabase(oldDriver)

            // Then
            assertEquals(
                emptyList(),
                migrated.libraryGroupQueries.getLibraryGroupsForProfile("profile-a")
                    .executeAsList(),
            )
            assertEquals(
                emptyList(),
                migrated.libraryGroupDecisionQueries.getLibraryManualSeparations("profile-a")
                    .executeAsList(),
            )
            assertEquals(
                emptyList(),
                migrated.libraryEvidenceQueries.getActiveLibraryIdentityEvidence("profile-a")
                    .executeAsList(),
            )
            assertEquals(
                emptyList(),
                migrated.librarySourceSnapshotQueries.getLibrarySourceSnapshots("profile-a")
                    .executeAsList(),
            )
            migrated.libraryGroupQueries.insertLibraryGroup(
                profile_id = "profile-a",
                group_id = "new-group",
                created_at = "2026-09-24T00:00:00Z",
            )
            val row = oldDriver.executeQuery(
                identifier = null,
                sql = "SELECT library_book_id FROM library_books;",
                mapper = { cursor ->
                    cursor.next()
                    QueryResult.Value(cursor.getString(0))
                },
                parameters = 0,
            ).value
            assertEquals("legacy-hash-id", row)
        } finally {
            oldDriver.close()
        }
    }

    @Test
    fun migrationFromVersion30PreservesExistingMembershipAndAddsDecisionProvenance() {
        // Given
        val oldDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            oldDriver.execute(
                identifier = null,
                sql = """
                    CREATE TABLE library_groups (
                        profile_id TEXT NOT NULL,
                        group_id TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        PRIMARY KEY (profile_id, group_id)
                    );
                """.trimIndent(),
                parameters = 0,
            )
            oldDriver.execute(
                identifier = null,
                sql = """
                    CREATE TABLE library_group_aliases (
                        profile_id TEXT NOT NULL,
                        alias_group_id TEXT NOT NULL,
                        target_group_id TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        PRIMARY KEY (profile_id, alias_group_id),
                        CHECK (alias_group_id <> target_group_id)
                    );
                """.trimIndent(),
                parameters = 0,
            )
            oldDriver.execute(
                identifier = null,
                sql = """
                    CREATE TABLE library_source_resources (
                        resource_id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        profile_id TEXT NOT NULL,
                        source_key TEXT NOT NULL,
                        native_resource_id TEXT NOT NULL,
                        revision_present INTEGER NOT NULL,
                        revision_value TEXT NOT NULL,
                        UNIQUE (profile_id, source_key, native_resource_id,
                            revision_present, revision_value),
                        UNIQUE (profile_id, resource_id)
                    );
                """.trimIndent(),
                parameters = 0,
            )
            oldDriver.execute(
                identifier = null,
                sql = """
                    CREATE TABLE library_identity_evidence (
                        profile_id TEXT NOT NULL,
                        evidence_id TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        source_resource_id INTEGER NOT NULL,
                        destination_resource_id INTEGER,
                        transfer_id TEXT,
                        algorithm TEXT,
                        content_hash TEXT,
                        fingerprint_scope TEXT,
                        fingerprint_verification TEXT,
                        certified_namespace TEXT,
                        certified_identity TEXT,
                        certified_kind TEXT,
                        provenance_kind TEXT NOT NULL,
                        provenance_id TEXT NOT NULL,
                        observed_at TEXT NOT NULL,
                        is_active INTEGER NOT NULL DEFAULT 1,
                        PRIMARY KEY (profile_id, evidence_id)
                    );
                """.trimIndent(),
                parameters = 0,
            )
            oldDriver.execute(
                identifier = null,
                sql = """
                    INSERT INTO library_source_resources(
                        resource_id, profile_id, source_key, native_resource_id,
                        revision_present, revision_value
                    ) VALUES (1, 'profile-a', 'source-key', 'epub', 0, '');
                """.trimIndent(),
                parameters = 0,
            )
            oldDriver.execute(
                identifier = null,
                sql = """
                    INSERT INTO library_source_resources(
                        resource_id, profile_id, source_key, native_resource_id,
                        revision_present, revision_value
                    ) VALUES (2, 'profile-b', 'source-key-b', 'audiobook', 0, '');
                """.trimIndent(),
                parameters = 0,
            )
            oldDriver.execute(
                identifier = null,
                sql = """
                    INSERT INTO library_identity_evidence(
                        profile_id, evidence_id, kind, source_resource_id,
                        algorithm, content_hash, fingerprint_scope,
                        fingerprint_verification, provenance_kind, provenance_id,
                        observed_at, is_active
                    ) VALUES (
                        'profile-a', 'legacy-fingerprint', 'fingerprint', 1,
                        'sha-256-v1', 'retained-hash', 'WholeFile', 'Verified',
                        'Adapter', 'legacy-source', '2026-09-24T00:00:00Z', 1
                    );
                """.trimIndent(),
                parameters = 0,
            )
            oldDriver.execute(
                identifier = null,
                sql = """
                    INSERT INTO library_identity_evidence(
                        profile_id, evidence_id, kind, source_resource_id,
                        algorithm, content_hash, fingerprint_scope,
                        fingerprint_verification, provenance_kind, provenance_id,
                        observed_at, is_active
                    ) VALUES (
                        'profile-b', 'profile-b-fingerprint', 'fingerprint', 2,
                        'sha-256-v1', 'profile-b-hash', 'WholeFile', 'Verified',
                        'Adapter', 'profile-b-source', '2026-09-24T00:01:00Z', 1
                    );
                """.trimIndent(),
                parameters = 0,
            )
            oldDriver.execute(
                identifier = null,
                sql = """
                    CREATE TABLE library_group_memberships (
                        profile_id TEXT NOT NULL,
                        adapter_id TEXT NOT NULL,
                        identity_kind TEXT NOT NULL,
                        backend_id TEXT NOT NULL DEFAULT '',
                        account_id TEXT NOT NULL DEFAULT '',
                        unresolved_connection_id TEXT NOT NULL DEFAULT '',
                        native_book_id TEXT NOT NULL,
                        group_id TEXT NOT NULL,
                        execution_connection_id TEXT,
                        legacy_library_book_id TEXT,
                        PRIMARY KEY (
                            profile_id, adapter_id, identity_kind, backend_id, account_id,
                            unresolved_connection_id, native_book_id
                        )
                    );
                """.trimIndent(),
                parameters = 0,
            )
            oldDriver.execute(
                identifier = null,
                sql = """
                    CREATE TABLE library_manual_separations (
                        profile_id TEXT NOT NULL,
                        first_source_key TEXT NOT NULL,
                        second_source_key TEXT NOT NULL,
                        decision_id TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        PRIMARY KEY (profile_id, first_source_key, second_source_key),
                        UNIQUE (profile_id, decision_id)
                    );
                """.trimIndent(),
                parameters = 0,
            )
            oldDriver.execute(
                identifier = null,
                sql = """
                    CREATE TABLE library_source_snapshots (
                        profile_id TEXT NOT NULL,
                        source_key TEXT NOT NULL,
                        execution_connection_id TEXT,
                        legacy_library_book_id TEXT,
                        title TEXT NOT NULL,
                        description TEXT,
                        cover_reference TEXT,
                        observed_at_epoch_ms INTEGER NOT NULL,
                        presence TEXT NOT NULL,
                        is_authoritative INTEGER NOT NULL,
                        revision TEXT,
                        PRIMARY KEY (profile_id, source_key)
                    );
                """.trimIndent(),
                parameters = 0,
            )
            oldDriver.execute(
                identifier = null,
                sql = """
                    CREATE TABLE library_source_snapshot_resources (
                        profile_id TEXT NOT NULL,
                        source_key TEXT NOT NULL,
                        resource_id INTEGER NOT NULL,
                        media_type TEXT NOT NULL,
                        format TEXT,
                        size_bytes INTEGER,
                        local_storage_reference TEXT,
                        remote_resource_reference TEXT,
                        PRIMARY KEY (profile_id, source_key, resource_id)
                    );
                """.trimIndent(),
                parameters = 0,
            )
            oldDriver.execute(
                identifier = null,
                sql = """
                    INSERT INTO library_source_snapshots(
                        profile_id, source_key, execution_connection_id,
                        legacy_library_book_id, title, observed_at_epoch_ms,
                        presence, is_authoritative, revision
                    ) VALUES (
                        'profile-a', 'source-key', 'connection', 'legacy-id',
                        'Existing snapshot', 0, 'Present', 1, 'revision'
                    );
                """.trimIndent(),
                parameters = 0,
            )
            oldDriver.execute(
                identifier = null,
                sql = """
                    INSERT INTO library_source_snapshots(
                        profile_id, source_key, execution_connection_id,
                        legacy_library_book_id, title, observed_at_epoch_ms,
                        presence, is_authoritative, revision
                    ) VALUES (
                        'profile-b', 'source-key-b', 'connection-b', 'legacy-id-b',
                        'Profile B snapshot', 1, 'Present', 1, 'revision-b'
                    );
                """.trimIndent(),
                parameters = 0,
            )
            oldDriver.execute(
                identifier = null,
                sql = "INSERT INTO library_groups VALUES ('profile-a', 'group-a', 'created');",
                parameters = 0,
            )
            oldDriver.execute(
                identifier = null,
                sql = "INSERT INTO library_groups VALUES ('profile-b', 'group-b', 'created');",
                parameters = 0,
            )
            oldDriver.execute(
                identifier = null,
                sql = """
                    INSERT INTO library_group_aliases VALUES (
                        'profile-a', 'alias-a', 'group-a', '2026-09-24T00:00:00Z'
                    );
                """.trimIndent(),
                parameters = 0,
            )
            oldDriver.execute(
                identifier = null,
                sql = """
                    INSERT INTO library_group_aliases VALUES (
                        'profile-b', 'alias-b', 'group-b', '2026-09-24T00:01:00Z'
                    );
                """.trimIndent(),
                parameters = 0,
            )
            oldDriver.execute(
                identifier = null,
                sql = """
                    INSERT INTO library_group_memberships(
                        profile_id, adapter_id, identity_kind, backend_id, account_id,
                        unresolved_connection_id, native_book_id, group_id,
                        execution_connection_id, legacy_library_book_id
                    ) VALUES (
                        'profile-a', 'adapter', 'portable', 'backend', 'account', '',
                        'native-book', 'group-a', 'connection', 'legacy-id'
                    );
                """.trimIndent(),
                parameters = 0,
            )
            oldDriver.execute(
                identifier = null,
                sql = """
                    INSERT INTO library_group_memberships(
                        profile_id, adapter_id, identity_kind, backend_id, account_id,
                        unresolved_connection_id, native_book_id, group_id,
                        execution_connection_id, legacy_library_book_id
                    ) VALUES (
                        'profile-b', 'adapter-b', 'portable', 'backend-b', 'account-b', '',
                        'native-book-b', 'group-b', 'connection-b', 'legacy-id-b'
                    );
                """.trimIndent(),
                parameters = 0,
            )

            // When
            AppDatabase.Schema.migrate(oldDriver, 30, AppDatabase.Schema.version)
            val migrated = AppDatabase(oldDriver)
            val membership = migrated.libraryGroupQueries.getLibraryGroupMembership(
                profile_id = "profile-a",
                adapter_id = "adapter",
                identity_kind = "portable",
                backend_id = "backend",
                account_id = "account",
                unresolved_connection_id = "",
                native_book_id = "native-book",
            ).executeAsOne()
            val profileBMembership = migrated.libraryGroupQueries.getLibraryGroupMembership(
                profile_id = "profile-b",
                adapter_id = "adapter-b",
                identity_kind = "portable",
                backend_id = "backend-b",
                account_id = "account-b",
                unresolved_connection_id = "",
                native_book_id = "native-book-b",
            ).executeAsOne()

            // Then
            assertEquals("legacy-id", membership.legacy_library_book_id)
            assertEquals("Backfill", membership.membership_origin)
            assertEquals(0L, membership.revision)
            assertEquals(null, membership.decision_id)
            assertEquals("legacy-id-b", profileBMembership.legacy_library_book_id)
            assertEquals("Backfill", profileBMembership.membership_origin)
            assertEquals(0L, profileBMembership.revision)
            assertEquals(null, profileBMembership.decision_id)
            assertEquals(
                listOf("group-a"),
                migrated.libraryGroupQueries.getLibraryGroupsForProfile("profile-a")
                    .executeAsList()
                    .map { group -> group.group_id },
            )
            assertEquals(
                listOf("group-b"),
                migrated.libraryGroupQueries.getLibraryGroupsForProfile("profile-b")
                    .executeAsList()
                    .map { group -> group.group_id },
            )
            assertEquals(
                listOf("native-book"),
                migrated.libraryGroupQueries.getAllLibraryGroupMemberships("profile-a")
                    .executeAsList()
                    .map { member -> member.native_book_id },
            )
            assertEquals(
                listOf("native-book-b"),
                migrated.libraryGroupQueries.getAllLibraryGroupMemberships("profile-b")
                    .executeAsList()
                    .map { member -> member.native_book_id },
            )
            assertEquals(
                "group-a",
                migrated.libraryGroupDecisionQueries.getLibraryGroupAlias(
                    "profile-a",
                    "alias-a",
                ).executeAsOne().target_group_id,
            )
            assertEquals(
                "group-b",
                migrated.libraryGroupDecisionQueries.getLibraryGroupAlias(
                    "profile-b",
                    "alias-b",
                ).executeAsOne().target_group_id,
            )
            assertEquals(
                null,
                migrated.libraryGroupDecisionQueries.getLibraryGroupAlias(
                    "profile-a",
                    "alias-b",
                ).executeAsOneOrNull(),
            )
            assertEquals(
                null,
                migrated.libraryGroupDecisionQueries.getLibraryGroupAlias(
                    "profile-b",
                    "alias-a",
                ).executeAsOneOrNull(),
            )
            val migratedEvidence = migrated.libraryEvidenceQueries.getLibraryIdentityEvidence(
                "profile-a",
                "legacy-fingerprint",
            ).executeAsOne()
            assertEquals("retained-hash", migratedEvidence.content_hash)
            assertEquals(1L, migratedEvidence.source_resource_id)
            assertEquals(null, migratedEvidence.source_book_key)
            val profileBEvidence = migrated.libraryEvidenceQueries.getLibraryIdentityEvidence(
                "profile-b",
                "profile-b-fingerprint",
            ).executeAsOne()
            assertEquals("profile-b-hash", profileBEvidence.content_hash)
            assertEquals(2L, profileBEvidence.source_resource_id)
            assertEquals(null, profileBEvidence.source_book_key)
            assertEquals(
                listOf("legacy-fingerprint"),
                migrated.libraryEvidenceQueries.getActiveLibraryIdentityEvidence("profile-a")
                    .executeAsList()
                    .map { evidence -> evidence.evidence_id },
            )
            assertEquals(
                listOf("profile-b-fingerprint"),
                migrated.libraryEvidenceQueries.getActiveLibraryIdentityEvidence("profile-b")
                    .executeAsList()
                    .map { evidence -> evidence.evidence_id },
            )
            val snapshot = migrated.librarySourceSnapshotQueries.getLibrarySourceSnapshot(
                profile_id = "profile-a",
                source_key = "source-key",
            ).executeAsOne()
            assertEquals("Existing snapshot", snapshot.title)
            assertEquals(null, snapshot.publication_date)
            val profileBSnapshot = migrated.librarySourceSnapshotQueries
                .getLibrarySourceSnapshot(
                    profile_id = "profile-b",
                    source_key = "source-key-b",
                ).executeAsOne()
            assertEquals("Profile B snapshot", profileBSnapshot.title)
            assertEquals(null, profileBSnapshot.publication_date)
            assertEquals(
                null,
                migrated.librarySourceSnapshotQueries.getLibrarySourceSnapshot(
                    profile_id = "profile-a",
                    source_key = "source-key-b",
                ).executeAsOneOrNull(),
            )
            assertEquals(
                null,
                migrated.librarySourceSnapshotQueries.getLibrarySourceSnapshot(
                    profile_id = "profile-b",
                    source_key = "source-key",
                ).executeAsOneOrNull(),
            )
        } finally {
            oldDriver.close()
        }
    }

    private fun insertGroup(profileId: String, groupId: String) {
        database.libraryGroupQueries.insertLibraryGroup(
            profile_id = profileId,
            group_id = groupId,
            created_at = "2026-09-24T00:00:00Z",
        )
    }

    private fun insertPortableMember(profileId: String, groupId: String, accountId: String) {
        database.libraryGroupQueries.insertLibraryGroupMembership(
            profile_id = profileId,
            adapter_id = "future-source",
            identity_kind = "portable",
            backend_id = "backend-a",
            account_id = accountId,
            unresolved_connection_id = "",
            native_book_id = "native-book",
            group_id = groupId,
            execution_connection_id = null,
            legacy_library_book_id = null,
        )
    }
}
