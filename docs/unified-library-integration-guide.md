# Unified library integration guide

This guide describes the adapter contracts used by the unified library. It is
for adding a source to the existing library projection, then opting into actions
and native progress where the integration supports them. The library core does
not require a new `ServerType` branch.

## Contract map

| Concern | Required for | Contract |
| --- | --- | --- |
| Source adapter | Listing a `ServerBooksRepository` in the unified library | [`ServerBookSourceAdapter`](../lib/server/api/src/commonMain/kotlin/com/retro99/server/api/library/LibrarySourceAdapter.kt) |
| Operation adapter | Each action the integration implements | [`LibraryOperationAdapter`](../lib/server/api/src/commonMain/kotlin/com/retro99/server/api/library/LibraryOperationAdapter.kt) |
| Progress adapter | Native progress not handled by the existing reader-repository bridge | [`LibraryProgressAdapter`](../lib/server/api/src/commonMain/kotlin/com/retro99/server/api/library/LibraryProgressAdapter.kt) |
| Group and evidence persistence | Shared library state; owned by the library data layer | [`LibraryGroupsDatabase`](../lib/database/api/src/commonMain/kotlin/com/retro99/database/api/library/LibraryGroupsDatabase.kt), [`LibraryEvidenceDatabase`](../lib/database/api/src/commonMain/kotlin/com/retro99/database/api/library/LibraryEvidenceDatabase.kt), and [`LibrarySourceSnapshotsDatabase`](../lib/database/api/src/commonMain/kotlin/com/retro99/database/api/library/LibrarySourceSnapshotsDatabase.kt) |

The source adapter is needed to project books from an existing server repository.
Operation and progress adapters are independent: implement only the capabilities
the integration can perform safely. A book can still be listed when it has no
download, upload, removal, or native progress adapter.

## 1. Give the source a scoped identity

Choose a stable, unique `LibraryAdapterId`. Set the same value on the associated
[`ServerBooksRepository`](../lib/server/api/src/commonMain/kotlin/com/retro99/server/api/ServerBooksRepository.kt)
and source adapter. Keep native book and resource IDs scoped; they are not globally
unique. `SourceBookKey` combines profile, adapter, account identity, and native book
ID. A connection ID is an installation-local execution route, not portable identity.
See [`SourceBookRef.kt`](../lib/server/api/src/commonMain/kotlin/com/retro99/server/api/library/SourceBookRef.kt).

Return `SourceAccountIdentity.Portable` only when the backend and authenticated
account values are stable, server-owned identifiers. Do not derive them from a URL,
username, or local connection UUID. If that contract is unavailable, return `null`
from `libraryAccountIdentity()`; the library keeps the source under an
installation-scoped `Unresolved` identity. Local grouping remains available, while
sync operations that require portable members are deferred until identity can be
resolved.

## 2. Normalize books, resources, and listing status

For an existing `ServerBooksRepository`, implement
[`ServerBookSourceAdapter`](../lib/server/api/src/commonMain/kotlin/com/retro99/server/api/library/LibrarySourceAdapter.kt).
[`ServerBookLibrarySourceAdapter`](../lib/server/api/src/commonMain/kotlin/com/retro99/server/api/library/ServerBookSourceAdapter.kt)
provides the common metadata and resource mapping when `MediaResource` has the
correct native resource ID, format, revision, and availability. Override its
resource/evidence mapping where the source needs different rules. Use a direct
`LibrarySourceAdapter` only when normalizing another `LibrarySourceRecord` boundary;
the current server-list projection selects a `ServerBookSourceAdapter`.

For every source emission:

- Build a `SourceBookRef` with the profile, adapter, account scope, native book ID,
  and current local connection (when connected).
- Give each resource a stable source-native `SourceResourceRef`; do not identify it
  by media type alone. Use `DeviceStorageRef` for a device path and
  `RemoteResourceRef` for a server resource. Keep availability separate from
  transfer state.
- Preserve legacy aliases such as `LegacyLibraryBookId`; never substitute a
  displayed `LibraryGroupId` for a native/content ID.
- Return no identity evidence when none is trustworthy. Supported evidence types
  are in [`SourceIdentityEvidence.kt`](../lib/server/api/src/commonMain/kotlin/com/retro99/server/api/library/SourceIdentityEvidence.kt).
  Verified fingerprints must carry the true algorithm and scope. Completed-transfer
  evidence requires both concrete endpoints. ISBN, title, author, or filename alone
  are not identity evidence; edition evidence does not establish file or progress
  compatibility.

`ServerBooksRepository.getLibraryListing()` defaults to `Partial`. Keep this default
unless a successful emission is a complete listing for the relevant account and
scope. Only then may the repository return `Complete`; omitted books from a partial
listing are not deletions. An explicit book tombstone may be reported in
`removedNativeBookIds`. A removal snapshot must be authoritative. Fetch failures
make cached source state unknown/stale; they are not removal events. The listing
contract is defined in
[`ServerBooksRepository.kt`](../lib/server/api/src/commonMain/kotlin/com/retro99/server/api/ServerBooksRepository.kt),
and reconciliation is handled by
[`LibraryGroupingDataRepository.kt`](../feature/library/data/src/commonMain/kotlin/com/retro99/library/data/LibraryGroupingDataRepository.kt).

## 3. Add only supported operations

Implement `LibraryOperationAdapter` when the integration supports a library action.
`adapterId`, `availability(target)`, and `execute(request)` are the required
methods. Availability must inspect the exact selected source/resource/replica and
current connection/account state; return an explanatory reason when an action is
unavailable. Execution must validate the target again because source state can
change after the UI resolves it.

Optional methods have conservative defaults: upload proposals and transfer
observation are empty, reader-cache support is false, and cancel/retry throw unless
implemented. Downloads are resource-level by default; override
`downloadOperationResources()` for a real multi-resource bundle. The UI obtains
actions from registered adapters and their availability; do not add server-type
checks or infer action support from static capability flags. Keep remote deletion
and device-replica removal semantics explicit, including whether a shared device
file may be deleted.

Whole-book actions use `LibraryBookOperation` and the book-level methods on
`LibraryOperationAdapter`; they do not invent a media-resource target. Keep a
whole-book removal separate from `DeleteRemoteReplica`, which deletes one exact
remote resource. For `RemoveRemoteBook`, require explicit user confirmation,
revalidate the selected source and its current backend state at execution, and
preserve completed device copies and native reading/listening progress unless the
product contract explicitly says otherwise. The Parrot Cloud adapter currently
queues a book tombstone only after confirming that no Cloud files remain, and
coalesces duplicate pending or dispatched removals. See
[`LibraryBookOperation.kt`](../lib/server/api/src/commonMain/kotlin/com/retro99/server/api/library/LibraryBookOperation.kt),
[`ParrotCloudLibraryOperationAdapter`](../lib/server-parrot-cloud/src/commonMain/kotlin/com/retro99/server/parrotcloud/ParrotCloudLibraryOperationAdapter.kt),
and [`ParrotCloudBookRemovalService`](../lib/server-parrot-cloud/src/commonMain/kotlin/com/retro99/server/parrotcloud/ParrotCloudBookRemovalService.kt).

See the current implementations in [Local](../lib/server-local/src/commonMain/kotlin/com/retro99/server/local/LocalLibraryOperationAdapter.kt),
[Storyteller](../lib/server-storyteller/src/commonMain/kotlin/com/retro99/server/storyteller/StorytellerLibraryOperationAdapter.kt),
[Audiobookshelf](../lib/server-audiobookshelf/src/commonMain/kotlin/com/retro99/server/audiobookshelf/AudiobookshelfLibraryOperationAdapter.kt),
and [Parrot Cloud](../lib/server-parrot-cloud/src/commonMain/kotlin/com/retro99/server/parrotcloud/ParrotCloudLibraryOperationAdapter.kt).

## 4. Preserve native progress ownership

If the source already implements `ServerReaderRepository` and sets its
`libraryAdapterId`, the default progress registry can bridge its existing
`ServerPosition` operations. For a different native progress format, register a
`LibraryProgressAdapter` and use a `ProgressOwnerRef` containing the source and the
native progress ID. Preserve the native locator/timestamp and conflict behavior;
do not convert adapter-owned progress to a generic percentage for writes. Override
the position projection methods when an adapter-owned value cannot be losslessly
represented as `ServerPosition`.

See [`DefaultLibraryProgressAdapterRegistry`](../lib/server/implementation/src/commonMain/kotlin/com/retro99/server/implementation/library/DefaultLibraryProgressAdapterRegistry.kt),
its [reader bridge](../lib/server/implementation/src/commonMain/kotlin/com/retro99/server/implementation/library/ServerReaderLibraryProgressAdapter.kt),
and [reader routing](../feature/reader/domain/src/commonTest/kotlin/com/retro99/reader/domain/usecase/ServerReaderProgressRoutingTest.kt).

## 5. Register through dependency injection

Bind each implemented adapter to its interface with Koin, for example:

```kotlin
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibrarySourceAdapter
import com.retro99.server.api.library.ServerBookLibrarySourceAdapter
import org.koin.core.annotation.Single

@Single(binds = [LibrarySourceAdapter::class])
class ExampleLibrarySourceAdapter : ServerBookLibrarySourceAdapter() {
    override val adapterId = LibraryAdapterId("example")
}
```

Bind optional operation or progress adapters to their corresponding interfaces.
Add the integration's `@ComponentScan` module to the application module list; see
[`LocalServerModule`](../lib/server-local/src/commonMain/kotlin/com/retro99/server/local/di/LocalServerModule.kt)
and [`AppModule`](../composeApp/src/commonMain/kotlin/com/retro99/parrot/di/AppModule.kt).
The generic registries receive injected adapter lists and reject duplicate IDs:
[`DefaultLibrarySourceAdapterRegistry`](../lib/server/implementation/src/commonMain/kotlin/com/retro99/server/implementation/library/DefaultLibrarySourceAdapterRegistry.kt)
and [`DefaultLibraryOperationAdapterRegistry`](../lib/server/implementation/src/commonMain/kotlin/com/retro99/server/implementation/library/DefaultLibraryOperationAdapterRegistry.kt).

## 6. Leave shared grouping persistence in the library layer

The adapter reports source snapshots and evidence; it does not create displayed
group IDs, write memberships, merge groups, or persist manual decisions. The shared
[`LibraryGroupingDataRepository`](../feature/library/data/src/commonMain/kotlin/com/retro99/library/data/LibraryGroupingDataRepository.kt)
normalizes repository emissions, persists source snapshots, records verified
evidence, and reconciles membership through the database APIs above. Shared schema
and transaction changes belong in `lib/database`, with migration and preservation
tests. Adapter-owned remote state belongs in the adapter's existing repository or
transport. Keep device paths local and out of synchronized state.

## Tests

Extend the relevant existing test class and run the matching `allTests` task. The
generic extension suites used by the current implementation are:

| Coverage | Tests and exact Gradle suite tasks |
| --- | --- |
| Unknown adapter registration, identity grouping, operations, and progress | [`UnknownLibraryIntegrationTest`](../lib/server/implementation/src/commonTest/kotlin/com/retro99/server/implementation/library/UnknownLibraryIntegrationTest.kt), `:lib:server:implementation:allTests` |
| Source API, registry, and duplicate-ID behavior | [`DefaultLibrarySourceAdapterRegistryTest`](../lib/server/implementation/src/commonTest/kotlin/com/retro99/server/implementation/library/DefaultLibrarySourceAdapterRegistryTest.kt), `:lib:server:api:allTests :lib:server:implementation:allTests` |
| Identity and projection rules | [`LibraryIdentityResolverTest`](../feature/library/domain/src/commonTest/kotlin/com/retro99/library/domain/grouping/LibraryIdentityResolverTest.kt), `:feature:library:domain:allTests` |
| Persisted source snapshots and feed reconciliation | [`LibraryGroupingDataRepositoryTest`](../feature/library/data/src/commonTest/kotlin/com/retro99/library/data/LibraryGroupingDataRepositoryTest.kt), `:feature:library:data:allTests` |
| Operation resolution and Details targets | [`LibraryOperationUseCasesTest`](../feature/library/domain/src/commonTest/kotlin/com/retro99/library/domain/operation/LibraryOperationUseCasesTest.kt), [`LibraryGroupDetailOperationTargetTest`](../feature/books/ui/src/commonTest/kotlin/com/retro99/books/ui/detail/LibraryGroupDetailOperationTargetTest.kt), `:feature:library:domain:allTests :feature:books:ui:allTests` |
| Reader progress routing | [`ServerReaderLibraryProgressAdapterTest`](../lib/server/implementation/src/commonTest/kotlin/com/retro99/server/implementation/library/ServerReaderLibraryProgressAdapterTest.kt), [`ServerReaderProgressRoutingTest`](../feature/reader/domain/src/commonTest/kotlin/com/retro99/reader/domain/usecase/ServerReaderProgressRoutingTest.kt), `:lib:server:implementation:allTests :feature:reader:domain:allTests` |

Concrete source/operation adapter suites used for the existing integrations are
`:lib:server-local:allTests`, `:lib:server-storyteller:allTests`,
`:lib:server-audiobookshelf:allTests`, and `:lib:server-parrot-cloud:allTests`.
Run the task for the module being changed. If the change adds or alters shared
database schema, run `:lib:database:implementation:verifyCommonMainAppDatabaseMigration`
and `:lib:database:implementation:testAndroidHostTest`. These tasks cover code-level
contracts; they do not replace linked-account or two-device acceptance.
