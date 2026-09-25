# Audiobookshelf Server Implementation Plan

Detailed implementation plan for adding Audiobookshelf (ABS) server support to Parrot.

## Overview

Audiobookshelf is a self-hosted audiobook, ebook, and podcast server (~17k+ GitHub stars).
This plan adds it as a new server type alongside Storyteller, using the existing
multi-server Strategy + Composite Factory architecture.

API docs: https://api.audiobookshelf.org
Public demo server: https://audiobooks.dev/ (username: `demo`, password: `demo`)

---

## Step 0: Prerequisites — Add ServerType Enum Value

Add `Audiobookshelf` to the `ServerType` enum. This is the root change that triggers
exhaustive-when compilation errors in all places that need updating.

**File: `base/src/commonMain/kotlin/com/retro99/base/server/ServerType.kt`**

```kotlin
enum class ServerType(
    val identifier: String,
    val displayName: String,
) {
    Storyteller(
        identifier = "storyteller",
        displayName = "Storyteller",
    ),
    Audiobookshelf(
        identifier = "audiobookshelf",
        displayName = "Audiobookshelf",
    ),
    Local(
        identifier = LOCAL_SERVER_ID,
        displayName = "Local",
    );
    // ... companion object unchanged
}
```

After this change, the following compilation errors will surface (by design):

1. `ServerCapabilities.kt` — exhaustive `when` missing `Audiobookshelf` branch
2. `StorytellerAuthenticatorFactory.kt` — exhaustive `when` missing `Audiobookshelf` arm

---

## Step 1: Add Audiobookshelf Capabilities

**File: `lib/server/api/src/commonMain/kotlin/com/retro99/server/api/ServerCapabilities.kt`**

Add a branch to the `when` statement:

```kotlin
fun ServerType.getCapabilities(): ServerCapabilities = when (this) {
    ServerType.Storyteller -> ServerCapabilities(
        supportsEbooks = true,
        supportsAudiobooks = true,
        supportsReadAloud = true,
        supportsReadingProgress = true,
        supportsCollections = true,
        supportsSeries = true,
        supportsSearch = true,
        supportsUserLibrary = true,
    )
    ServerType.Audiobookshelf -> ServerCapabilities(
        supportsEbooks = true,
        supportsAudiobooks = true,
        supportsReadAloud = false,
        supportsReadingProgress = true,
        supportsCollections = true,
        supportsSeries = true,
        supportsSearch = true,
        supportsUserLibrary = true,
    )
    ServerType.Local -> ServerCapabilities(
        supportsEbooks = true,
        supportsAudiobooks = false,
        supportsReadAloud = false,
        supportsReadingProgress = true,
        supportsCollections = false,
        supportsSeries = false,
        supportsSearch = true,
        supportsUserLibrary = false,
    )
}
```

Key differences from Storyteller: `supportsReadAloud = false` (ABS does not have media overlays
for synchronized narration).

---

## Step 2: Update StorytellerAuthenticatorFactory Exhaustive When

**File: `lib/server-storyteller/src/commonMain/kotlin/com/retro99/server/storyteller/StorytellerAuthenticatorFactory.kt`**

The `when (serverType)` block at line 28 is exhaustive. Since we added a new enum value,
we must handle it. However, the Audiobookshelf authenticator will be registered via the
`@Provided authenticators: List<ServerAuthenticator>` list (same as how the Storyteller
authenticator itself is discovered), so the map lookup will find it.

The compilable fix is to add the ABS arm:

```kotlin
override fun create(serverType: ServerType): ServerAuthenticator {
    authenticatorMap[serverType]?.let { return it }

    return when (serverType) {
        ServerType.Storyteller -> storytellerAuthenticator
        ServerType.Audiobookshelf -> authenticatorMap[serverType]
            ?: throw IllegalArgumentException("Audiobookshelf authenticator not registered")
        ServerType.Local -> throw IllegalArgumentException("Local server type does not require authentication")
    }
}
```

In practice the `authenticatorMap[serverType]` fallback in the first line will find the ABS
authenticator (registered via `@Factory` in the new module), so this `when` arm is dead code.
It exists only to satisfy the compiler. An alternative is to remove the fallback `when` block
entirely and rely solely on the map, but that changes the Storyteller module's behavior.

**Design note:** Consider extracting `StorytellerAuthenticatorFactory` into a neutral
`CompositeAuthenticatorFactory` in `lib/server/implementation/` that purely does
`authenticatorMap[serverType]` — same pattern as `CompositeRepositoryFactory`. This is
optional but is the cleaner long-term structure. This refactor is not required for ABS
to work; it only removes the need to touch the Storyteller module when adding future
server types.

---

## Step 3: Create `:lib:server-audiobookshelf` Module

### Step 3.1: Register Module in Build System

**File: `settings.gradle.kts`** — add after `:lib:server-storyteller`:

```kotlin
include(":lib:server-audiobookshelf")
```

### Step 3.2: Create `build.gradle.kts`

Mirror `lib/server-storyteller/build.gradle.kts`. Same dependencies since the contract
is identical.

**File: `lib/server-audiobookshelf/build.gradle.kts`** (new file)

```kotlin
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.koinCompilerPlugin)
    alias(libs.plugins.kotlinxSerialization)
}

version = "1.0"

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())

    androidLibrary {
        namespace = "com.retro99.server.audiobookshelf"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.koin.core)
            api(libs.koin.annotations)
            implementation(libs.serialization)
            implementation(libs.coroutines)
            implementation(libs.datetime)
            implementation(libs.ktor.client.core)
            implementation(projects.base)
            implementation(projects.lib.analytics.api)
            implementation(projects.lib.server.api)
            implementation(projects.lib.network.implementation)
            implementation(projects.lib.database.api)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
```

### Step 3.3: Register Koin Module

**File: `composeApp/src/commonMain/kotlin/com/retro99/parrot/di/AppModule.kt`**

Add import and include:

```kotlin
import com.retro99.server.audiobookshelf.di.AudiobookshelfModule

@Module(
    includes = [
        // ...
        StorytellerModule::class,
        AudiobookshelfModule::class,  // NEW
        LocalServerModule::class,
        // ...
    ],
)
class AppModule
```

---

## Step 4: API Models (`lib/server-audiobookshelf/src/commonMain/kotlin/com/retro99/server/audiobookshelf/model/`)

All models are `@Serializable` with `@SerialName` on its own line above each property,
following the project convention. Snake_case JSON keys → camelCase Kotlin properties.

### Step 4.1: Authentication Models

**File: `model/AudiobookshelfLoginRequest.kt`** (new)

ABS uses `POST /login` with JSON body (not form-encoded like Storyteller).

```kotlin
package com.retro99.server.audiobookshelf.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AudiobookshelfLoginRequest(
    @SerialName("username")
    val username: String,

    @SerialName("password")
    val password: String,
)
```

**File: `model/AudiobookshelfLoginResponse.kt`** (new)

Response from `POST /login`. The user token is at `response.user.token`.

```kotlin
package com.retro99.server.audiobookshelf.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AudiobookshelfLoginResponse(
    @SerialName("user")
    val user: AudiobookshelfUserApiModel,

    @SerialName("userDefaultLibraryId")
    val userDefaultLibraryId: String? = null,

    @SerialName("serverSettings")
    val serverSettings: AudiobookshelfServerSettingsApiModel? = null,

    @SerialName("Source")
    val source: String? = null,
)

@Serializable
data class AudiobookshelfUserApiModel(
    @SerialName("id")
    val id: String,

    @SerialName("username")
    val username: String,

    @SerialName("token")
    val token: String,

    @SerialName("type")
    val type: String? = null,

    @SerialName("isActive")
    val isActive: Boolean = true,

    @SerialName("createdAt")
    val createdAt: Long? = null,
)

@Serializable
data class AudiobookshelfServerSettingsApiModel(
    @SerialName("version")
    val version: String? = null,
)
```

### Step 4.2: Library Models

ABS organizes books into libraries. A flat book list requires listing libraries first,
then fetching items per library.

**File: `model/AudiobookshelfLibraryApiModel.kt`** (new)

```kotlin
package com.retro99.server.audiobookshelf.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AudiobookshelfLibraryListApiModel(
    @SerialName("libraries")
    val libraries: List<AudiobookshelfLibraryApiModel> = emptyList(),
)

@Serializable
data class AudiobookshelfLibraryApiModel(
    @SerialName("id")
    val id: String,

    @SerialName("name")
    val name: String,

    @SerialName("mediaType")
    val mediaType: String? = null,
)
```

### Step 4.3: Library Items (Books)

The response from `GET /api/libraries/:id/items` is paginated with a `results` array.

**File: `model/AudiobookshelfLibraryItemsApiModel.kt`** (new)

```kotlin
package com.retro99.server.audiobookshelf.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AudiobookshelfLibraryItemsResponse(
    @SerialName("results")
    val results: List<AudiobookshelfLibraryItemApiModel> = emptyList(),

    @SerialName("total")
    val total: Int = 0,

    @SerialName("limit")
    val limit: Int = 0,

    @SerialName("page")
    val page: Int = 0,
)
```

**File: `model/AudiobookshelfLibraryItemApiModel.kt`** (new)

This is the most complex model. A library item has a `mediaType` ("book" or "podcast")
and a nested `media` object containing metadata, audio tracks, and ebook file info.

```kotlin
package com.retro99.server.audiobookshelf.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AudiobookshelfLibraryItemApiModel(
    @SerialName("id")
    val id: String,

    @SerialName("libraryId")
    val libraryId: String? = null,

    @SerialName("mediaType")
    val mediaType: String? = null,

    @SerialName("media")
    val media: AudiobookshelfMediaApiModel? = null,

    @SerialName("path")
    val path: String? = null,

    @SerialName("addedAt")
    val addedAt: Long? = null,

    @SerialName("updatedAt")
    val updatedAt: Long? = null,

    @SerialName("size")
    val size: Long? = null,

    @SerialName("isMissing")
    val isMissing: Boolean? = null,
)

@Serializable
data class AudiobookshelfMediaApiModel(
    @SerialName("metadata")
    val metadata: AudiobookshelfBookMetadataApiModel? = null,

    @SerialName("coverPath")
    val coverPath: String? = null,

    @SerialName("duration")
    val duration: Double? = null,

    @SerialName("size")
    val size: Long? = null,

    @SerialName("numTracks")
    val numTracks: Int? = null,

    @SerialName("numAudioFiles")
    val numAudioFiles: Int? = null,

    @SerialName("numChapters")
    val numChapters: Int? = null,

    @SerialName("tags")
    val tags: List<String> = emptyList(),

    @SerialName("ebookFile")
    val ebookFile: AudiobookshelfEbookFileApiModel? = null,
)

@Serializable
data class AudiobookshelfBookMetadataApiModel(
    @SerialName("title")
    val title: String,

    @SerialName("subtitle")
    val subtitle: String? = null,

    @SerialName("authorName")
    val authorName: String? = null,

    @SerialName("narratorName")
    val narratorName: String? = null,

    @SerialName("seriesName")
    val seriesName: List<AudiobookshelfSeriesItemApiModel> = emptyList(),

    @SerialName("genres")
    val genres: List<String> = emptyList(),

    @SerialName("publishedYear")
    val publishedYear: String? = null,

    @SerialName("publishedDate")
    val publishedDate: String? = null,

    @SerialName("publisher")
    val publisher: String? = null,

    @SerialName("description")
    val description: String? = null,

    @SerialName("isbn")
    val isbn: String? = null,

    @SerialName("asin")
    val asin: String? = null,

    @SerialName("language")
    val language: String? = null,

    @SerialName("explicit")
    val explicit: Boolean? = null,

    @SerialName("series")
    val series: List<AudiobookshelfSeriesItemApiModel> = emptyList(),
)

@Serializable
data class AudiobookshelfSeriesItemApiModel(
    @SerialName("id")
    val id: String? = null,

    @SerialName("name")
    val name: String,

    @SerialName("sequence")
    val sequence: String? = null,
)

@Serializable
data class AudiobookshelfEbookFileApiModel(
    @SerialName("ebookFormat")
    val ebookFormat: String? = null,

    @SerialName("path")
    val path: String? = null,

    @SerialName("size")
    val size: Long? = null,
)
```

### Step 4.4: Series Models

**File: `model/AudiobookshelfSeriesApiModel.kt`** (new)

From `GET /api/libraries/:id/series`:

```kotlin
package com.retro99.server.audiobookshelf.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AudiobookshelfSeriesListResponse(
    @SerialName("results")
    val results: List<AudiobookshelfSeriesApiModel> = emptyList(),

    @SerialName("total")
    val total: Int = 0,

    @SerialName("limit")
    val limit: Int = 0,

    @SerialName("page")
    val page: Int = 0,
)

@Serializable
data class AudiobookshelfSeriesApiModel(
    @SerialName("id")
    val id: String,

    @SerialName("name")
    val name: String,

    @SerialName("addedAt")
    val addedAt: Long? = null,

    @SerialName("updatedAt")
    val updatedAt: Long? = null,
)
```

### Step 4.5: Media Progress Models

**File: `model/AudiobookshelfMediaProgressApiModel.kt`** (new)

From `GET /api/me/progress/:id` and `POST /api/me/progress/:id`:

```kotlin
package com.retro99.server.audiobookshelf.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AudiobookshelfMediaProgressApiModel(
    @SerialName("id")
    val id: String? = null,

    @SerialName("libraryItemId")
    val libraryItemId: String? = null,

    @SerialName("episodeId")
    val episodeId: String? = null,

    @SerialName("duration")
    val duration: Double? = null,

    @SerialName("progress")
    val progress: Double? = null,

    @SerialName("currentTime")
    val currentTime: Double? = null,

    @SerialName("isFinished")
    val isFinished: Boolean? = null,

    @SerialName("hideFromContinueListening")
    val hideFromContinueListening: Boolean? = null,

    @SerialName("lastUpdate")
    val lastUpdate: Long? = null,

    @SerialName("startedAt")
    val startedAt: Long? = null,

    @SerialName("finishedAt")
    val finishedAt: Long? = null,

    @SerialName("ebookLocation")
    val ebookLocation: String? = null,

    @SerialName("ebookProgress")
    val ebookProgress: Double? = null,
)
```

---

## Step 5: Domain Mappers (`model/` — same files as the models)

### Step 5.1: Library Item → ServerBook

**File: `model/AudiobookshelfLibraryItemApiModel.kt`** (appended to the same file)

Cover URL pattern: `{baseUrl}/api/items/{itemId}/cover`

```kotlin
fun AudiobookshelfLibraryItemApiModel.toDomain(
    serverId: String,
    baseUrl: String?,
): ServerBook {
    val metadata = media?.metadata
    val hasEbook = media?.ebookFile != null
    val hasAudiobook = media?.numAudioFiles?.let { it > 0 } ?: false

    return ServerBook(
        uuid = id,
        serverId = serverId,
        title = metadata?.title ?: "",
        description = metadata?.description,
        coverUrl = baseUrl?.let { "${it.trimEnd('/')}/api/items/$id/cover" },
        authors = listOfNotNull(metadata?.authorName),
        narrators = listOfNotNull(metadata?.narratorName),
        series = metadata?.series.orEmpty().map { seriesItem ->
            ServerBookSeries(
                id = seriesItem.id,
                name = seriesItem.name,
                sequence = seriesItem.sequence?.toFloatOrNull(),
            )
        },
        tags = media?.tags.orEmpty(),
        hasEbook = hasEbook,
        hasAudiobook = hasAudiobook,
        hasReadaloud = false,
        ebookFilepath = media?.ebookFile?.path,
        audiobookFilepath = media?.let { if (hasAudiobook) it.toString() else null },
        ebookFileSize = media?.ebookFile?.size,
        audiobookFileSize = media?.size,
        createdAt = addedAt?.toString(),
        lastOpenedAt = null,
        publicationDate = metadata?.publishedYear,
        isLocal = false,
    )
}
```

### Step 5.2: Media Progress ↔ ServerPosition

ABS uses a different progress model than Storyteller:
- Audio progress: `currentTime` (seconds) + `duration` (seconds) + `progress` (0.0–1.0)
- Ebook progress: `ebookLocation` (CFI string) + `ebookProgress` (0.0–1.0)

The `ServerPosition` model can represent both since it has:
- `audioTimestampMs` (slot for `currentTime * 1000`)
- `totalDurationMs` (slot for `duration * 1000`)
- `progression` (slot for `progress`)
- `totalProgression` (slot for `progress`)
- `locatorHref` (slot for `ebookLocation` — the CFI/href)

**File: `model/AudiobookshelfMediaProgressApiModel.kt`** (appended)

```kotlin
fun AudiobookshelfMediaProgressApiModel.toServerPosition(
    bookUuid: String,
    serverId: String,
): ServerPosition {
    return ServerPosition(
        bookUuid = bookUuid,
        serverId = serverId,
        timestamp = lastUpdate,
        createdAt = startedAt?.toString(),
        updatedAt = lastUpdate?.toString(),
        locatorHref = ebookLocation,
        locatorType = null,
        locatorTitle = null,
        locatorTarget = null,
        audioTimestampMs = currentTime?.let { (it * 1000).toLong() },
        chapterIndex = null,
        progression = progress,
        totalChapters = null,
        totalDurationMs = duration?.let { (it * 1000).toLong() },
        totalProgression = ebookProgress ?: progress,
        position = null,
    )
}

fun ServerPosition.toAudiobookshelfMediaProgress(
    libraryItemId: String,
): AudiobookshelfMediaProgressApiModel {
    return AudiobookshelfMediaProgressApiModel(
        libraryItemId = libraryItemId,
        duration = totalDurationMs?.let { it / 1000.0 },
        progress = progression,
        currentTime = audioTimestampMs?.let { it / 1000.0 },
        isFinished = null,
        hideFromContinueListening = null,
        lastUpdate = timestamp,
        startedAt = null,
        finishedAt = null,
        ebookLocation = locatorHref,
        ebookProgress = totalProgression,
    )
}
```

---

## Step 6: Authenticator + Factory

### Step 6.1: AudiobookshelfAuthenticator

**File: `AudiobookshelfAuthenticator.kt`** (new)

ABS auth is simpler than Storyteller: `POST /login` JSON body → JWT in `response.user.token`.
No refresh token, no OAuth. Validation uses `GET /ping`.

```kotlin
package com.retro99.server.audiobookshelf

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.server.api.ServerAuthenticator
import com.retro99.server.api.ServerCredentials
import com.retro99.server.api.ServerType
import com.retro99.server.api.ServerValidationResult
import com.retro99.server.audiobookshelf.model.AudiobookshelfLoginRequest
import com.retro99.server.audiobookshelf.model.AudiobookshelfLoginResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class AudiobookshelfAuthenticator(
    private val httpClient: HttpClient,
    @Provided private val analytics: Analytics,
) : ServerAuthenticator {

    override val serverType: ServerType = ServerType.Audiobookshelf

    override suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
    ): AppResult<ServerCredentials> {
        return try {
            val response = httpClient.post("${baseUrl.trimEnd('/')}/login") {
                contentType(ContentType.Application.Json)
                setBody(AudiobookshelfLoginRequest(username, password))
            }

            if (response.status.isSuccess()) {
                val loginResponse = response.body<AudiobookshelfLoginResponse>()
                Ok(
                    ServerCredentials(
                        serverId = "",
                        username = username,
                        accessToken = loginResponse.user.token,
                        refreshToken = null,
                        expiresAt = null,
                    )
                )
            } else {
                Err(AppError.AuthError("Login failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Err(mapException(e))
        }
    }

    override suspend fun refreshToken(
        baseUrl: String,
        refreshToken: String,
    ): AppResult<ServerCredentials> {
        return Err(AppError.AuthError("Token refresh not supported for Audiobookshelf"))
    }

    override suspend fun validateServer(baseUrl: String): AppResult<ServerValidationResult> {
        return try {
            val response = httpClient.get("${baseUrl.trimEnd('/')}/ping")

            if (response.status.isSuccess()) {
                Ok(
                    ServerValidationResult(
                        isValid = true,
                        serverVersion = null,
                        serverName = "Audiobookshelf",
                        errorMessage = null,
                    )
                )
            } else {
                Ok(
                    ServerValidationResult(
                        isValid = false,
                        serverVersion = null,
                        serverName = null,
                        errorMessage = "Server returned ${response.status}",
                    )
                )
            }
        } catch (e: Exception) {
            Ok(
                ServerValidationResult(
                    isValid = false,
                    serverVersion = null,
                    serverName = null,
                    errorMessage = e.message,
                )
            )
        }
    }

    private fun mapException(e: Exception): AppError {
        return when {
            e.message?.contains("401") == true -> AppError.AuthError("Invalid credentials")
            e.message?.contains("timeout", ignoreCase = true) == true -> AppError.NetworkError(e)
            else -> AppError.UnknownError(e)
        }
    }
}
```

The `@Factory` annotation + Koin `@ComponentScan` means this authenticator will be
auto-collected into `StorytellerAuthenticatorFactory.authenticators: List<ServerAuthenticator>`,
and `authenticatorMap[ServerType.Audiobookshelf]` will return it.

### Step 6.2: No Separate Authenticator Factory Needed

`StorytellerAuthenticatorFactory` is the active `ServerAuthenticatorFactory` and it already
does `authenticatorMap[serverType]` lookup. Since `AudiobookshelfAuthenticator` is a `@Factory`
with `serverType = ServerType.Audiobookshelf`, Koin inserts it into the `List<ServerAuthenticator>`
and the map lookup finds it. No new factory class needed.

---

## Step 7: Network Client Factory

**File: `AudiobookshelfNetworkClientFactory.kt`** (new)

This is a near-copy of `StorytellerNetworkClientFactory`. The `ServerNetworkClientBuilder`
from `lib/network/implementation` is server-type-agnostic — it just wires up the Ktor
client with the correct base URL + bearer token provider.

```kotlin
package com.retro99.server.audiobookshelf

import com.retro99.network.implementation.ServerNetworkClientBuilder
import com.retro99.server.api.ServerConfig
import com.retro99.server.api.ServerNetworkClient
import com.retro99.server.api.ServerNetworkClientFactory
import com.retro99.server.api.ServerRegistry
import com.retro99.server.api.ServerTokenProvider
import com.retro99.server.api.ServerType
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class AudiobookshelfNetworkClientFactory(
    @Provided private val serverNetworkClientBuilder: ServerNetworkClientBuilder,
    @Provided private val tokenProvider: ServerTokenProvider,
    @Provided private val serverRegistry: ServerRegistry,
) : ServerNetworkClientFactory {

    override val serverType: ServerType = ServerType.Audiobookshelf

    override fun create(serverConfig: ServerConfig): ServerNetworkClient {
        require(serverConfig.type == ServerType.Audiobookshelf) {
            "AudiobookshelfNetworkClientFactory can only create clients for Audiobookshelf servers"
        }
        return serverNetworkClientBuilder.build(
            serverId = serverConfig.id,
            baseUrl = serverConfig.baseUrl,
            tokenProvider = { tokenProvider.getToken(serverConfig.id) },
        )
    }

    override suspend fun createForActiveServer(): ServerNetworkClient {
        val activeServer = serverRegistry.getActiveServer()
            ?: error("No active server configured")
        return create(activeServer)
    }
}
```

The `@Factory` annotation + `binds = [ServerNetworkClientFactory::class]` (implicit via
the interface) means Koin collects it into the composite network client factory's
`List<ServerNetworkClientFactory>`.

---

## Step 8: Books Repository + Factory

### Step 8.1: AudiobookshelfBooksRepository

**File: `AudiobookshelfBooksRepository.kt`** (new)

This is the most complex piece because ABS uses library fan-out: you must list
libraries first, then fetch items per library (with pagination).

The Storyteller `getBooks()` is a single `GET /api/v2/books`. ABS requires:
1. `GET /api/libraries` → list of libraries
2. For each library: `GET /api/libraries/:id/items?limit=0&page=0` (limit=0 = all items)

```kotlin
package com.retro99.server.audiobookshelf

import com.github.michaelbull.result.map
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import co.touchlab.kermit.Logger
import com.retro99.base.repository.BaseRepository
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.base.result.mapCatching
import com.retro99.server.api.ServerBook
import com.retro99.server.api.ServerBooksRepository
import com.retro99.server.api.ServerNetworkClient
import com.retro99.server.audiobookshelf.model.AudiobookshelfLibraryItemApiModel
import com.retro99.server.audiobookshelf.model.AudiobookshelfLibraryItemsResponse
import com.retro99.server.audiobookshelf.model.AudiobookshelfLibraryListApiModel
import com.retro99.server.audiobookshelf.model.toDomain
import com.retro99.server.storyteller.source.ServerBooksLocalSource
import kotlinx.coroutines.flow.Flow
import retro99.network.api.get

class AudiobookshelfBooksRepository(
    private val networkClient: ServerNetworkClient,
    private val localSource: ServerBooksLocalSource,
) : ServerBooksRepository, BaseRepository {

    private val logger = Logger.withTag("AudiobookshelfBooksRepository")

    override val serverId: String = networkClient.serverId
    private val baseUrl: String? = networkClient.baseUrl

    override fun getBooks(): Flow<AppResult<List<ServerBook>>> {
        return cachedRemoteFlow(
            cacheSource = {
                localSource.getBooks(serverId).mapCatching { books ->
                    books?.map { it.withCoverUrl(baseUrl) }
                }
            },
            remoteSource = {
                fetchAllLibrariesItems().map { items ->
                    items.map { it.toDomain(serverId, baseUrl) }
                        .sortedBy { it.title.lowercase() }
                }.onSuccess { books ->
                    logger.d { "Fetched ${books.size} books from ${serverId}" }
                }.onFailure { error ->
                    logger.e { "Remote fetch failed: $error" }
                }
            },
            saveToCache = { books ->
                localSource.saveBooks(serverId, books)
            },
        )
    }

    override fun getBook(uuid: String): Flow<AppResult<ServerBook>> {
        return cachedRemoteFlow(
            cacheSource = {
                localSource.getBook(serverId, uuid).mapCatching { book ->
                    book?.withCoverUrl(baseUrl)
                }
            },
            remoteSource = {
                networkClient.get<AudiobookshelfLibraryItemApiModel>(
                    path = "/api/items/$uuid"
                ).map { item ->
                    item.toDomain(serverId, baseUrl)
                }
            },
            saveToCache = { book ->
                localSource.saveBook(serverId, book)
            },
        )
    }

    override suspend fun saveBook(book: ServerBook): CompletableResult {
        return Err(AppError.UnknownError(
            NotImplementedError("Uploading books to Audiobookshelf is not yet supported")
        ))
    }

    override suspend fun searchBooks(query: String): AppResult<List<ServerBook>> {
        return fetchAllLibrariesItems(query = query).map { items ->
            items.map { it.toDomain(serverId, baseUrl) }
        }
    }

    private suspend fun fetchAllLibrariesItems(
        query: String? = null,
    ): AppResult<List<AudiobookshelfLibraryItemApiModel>> {
        return networkClient.get<AudiobookshelfLibraryListApiModel>(
            path = "/api/libraries"
        ).map { libraryList ->
            libraryList.libraries.flatMap { library ->
                networkClient.get<AudiobookshelfLibraryItemsResponse>(
                    path = "/api/libraries/${library.id}/items",
                    queryBuilder = {
                        "limit" to "0"
                        if (query != null) "search" to query
                    }
                ).fold(
                    success = { response -> response.results },
                    failure = { error ->
                        logger.w { "Failed to fetch items for library ${library.id}: $error" }
                        emptyList()
                    },
                )
            }
        }
    }

    private fun ServerBook.withCoverUrl(baseUrl: String?): ServerBook {
        return if (coverUrl == null && baseUrl != null) {
            copy(coverUrl = "${baseUrl.trimEnd('/')}/api/items/$uuid/cover")
        } else {
            this
        }
    }
}
```

**Key differences from Storyteller:**
1. Library fan-out in `fetchAllLibrariesItems()` — fetches all libraries, then items per library.
2. `searchBooks` is implemented per-library (or could use `GET /api/libraries/:id/search`
   for more targeted results — verify which is more efficient during testing).
3. Book detail is `GET /api/items/:id` (not `/api/v2/books/:uuid`).
4. Cover URL is `${baseUrl}/api/items/${id}/cover`.

### Step 8.2: AudiobookshelfBooksRepositoryFactory

**File: `AudiobookshelfBooksRepositoryFactory.kt`** (new)

```kotlin
package com.retro99.server.audiobookshelf

import com.retro99.server.api.ServerBooksRepository
import com.retro99.server.api.ServerBooksRepositoryFactory
import com.retro99.server.api.ServerConfig
import com.retro99.server.api.ServerNetworkClientFactory
import com.retro99.server.api.ServerType
import com.retro99.server.storyteller.source.ServerBooksLocalSource
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [ServerBooksRepositoryFactory::class])
class AudiobookshelfBooksRepositoryFactory(
    @Provided private val networkClientFactory: ServerNetworkClientFactory,
    @Provided private val localSource: ServerBooksLocalSource,
) : ServerBooksRepositoryFactory {

    override val serverType: ServerType = ServerType.Audiobookshelf

    override fun create(serverConfig: ServerConfig): ServerBooksRepository {
        require(serverConfig.type == ServerType.Audiobookshelf) {
            "AudiobookshelfBooksRepositoryFactory can only create repositories for Audiobookshelf servers"
        }
        val networkClient = networkClientFactory.create(serverConfig)
        return AudiobookshelfBooksRepository(networkClient, localSource)
    }
}
```

**Note on the local source dependency:** The current `ServerBooksLocalSource` interface lives
in `lib/server-storyteller/source/` and is bound as a `@Single` in that module. The ABS module's
factory depends on it via `@Provided`. This works but creates a physical dependency on the
Storyteller module. As a cleanup, consider moving `ServerBooksLocalSource` and its implementation
to `lib/server/api` or `lib/server/implementation` to decouple. This is optional; for now the
dependency works.

---

## Step 9: Series Repository + Factory

**File: `AudiobookshelfSeriesRepository.kt`** (new)

ABS series are per-library: `GET /api/libraries/:id/series`.

```kotlin
package com.retro99.server.audiobookshelf

import com.github.michaelbull.result.map
import com.retro99.base.result.AppResult
import com.retro99.server.api.ServerNetworkClient
import com.retro99.server.api.ServerSeries
import com.retro99.server.api.ServerSeriesRepository
import com.retro99.server.audiobookshelf.model.AudiobookshelfLibraryListApiModel
import com.retro99.server.audiobookshelf.model.AudiobookshelfSeriesListResponse
import com.retro99.server.audiobookshelf.model.toServerSeries
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import retro99.network.api.get

class AudiobookshelfSeriesRepository(
    private val networkClient: ServerNetworkClient,
) : ServerSeriesRepository {

    override val serverId: String = networkClient.serverId

    override fun getSeries(): Flow<AppResult<List<ServerSeries>>> = flow {
        val result = networkClient.get<AudiobookshelfLibraryListApiModel>(
            path = "/api/libraries"
        ).map { libraryList ->
            libraryList.libraries.flatMap { library ->
                networkClient.get<AudiobookshelfSeriesListResponse>(
                    path = "/api/libraries/${library.id}/series",
                    queryBuilder = {
                        "limit" to "0"
                    }
                ).fold(
                    success = { response ->
                        response.results.map { it.toServerSeries(serverId) }
                    },
                    failure = { emptyList() },
                )
            }.sortedBy { it.name.lowercase() }
        }
        emit(result)
    }
}
```

**File: `model/AudiobookshelfSeriesApiModel.kt`** (appended mapper)

```kotlin
fun AudiobookshelfSeriesApiModel.toServerSeries(serverId: String): ServerSeries {
    return ServerSeries(
        uuid = id,
        serverId = serverId,
        name = name,
        featured = null,
        position = null,
        createdAt = addedAt?.toString(),
        updatedAt = updatedAt?.toString(),
    )
}
```

**File: `AudiobookshelfSeriesRepositoryFactory.kt`** (new)

```kotlin
package com.retro99.server.audiobookshelf

import com.retro99.server.api.ServerConfig
import com.retro99.server.api.ServerNetworkClientFactory
import com.retro99.server.api.ServerSeriesRepository
import com.retro99.server.api.ServerSeriesRepositoryFactory
import com.retro99.server.api.ServerType
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [ServerSeriesRepositoryFactory::class])
class AudiobookshelfSeriesRepositoryFactory(
    @Provided private val networkClientFactory: ServerNetworkClientFactory,
) : ServerSeriesRepositoryFactory {

    override val serverType: ServerType = ServerType.Audiobookshelf

    override fun create(serverConfig: ServerConfig): ServerSeriesRepository {
        require(serverConfig.type == ServerType.Audiobookshelf) {
            "AudiobookshelfSeriesRepositoryFactory can only create repositories for Audiobookshelf servers"
        }
        val networkClient = networkClientFactory.create(serverConfig)
        return AudiobookshelfSeriesRepository(networkClient)
    }
}
```

---

## Step 10: Reader Repository + Factory

**File: `AudiobookshelfReaderRepository.kt`** (new)

ABS reading progress: `GET /api/me/progress/:id` and `POST /api/me/progress/:id`.
The `:id` is the library item ID (which is also the book UUID in our domain).

```kotlin
package com.retro99.server.audiobookshelf

import com.github.michaelbull.result.map
import com.retro99.base.repository.BaseRepository
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.server.api.ServerNetworkClient
import com.retro99.server.api.ServerPosition
import com.retro99.server.api.ServerPositionLocalSource
import com.retro99.server.api.ServerReaderRepository
import com.retro99.server.audiobookshelf.model.AudiobookshelfMediaProgressApiModel
import com.retro99.server.audiobookshelf.model.toAudiobookshelfMediaProgress
import com.retro99.server.audiobookshelf.model.toServerPosition
import retro99.network.api.get
import retro99.network.api.post

class AudiobookshelfReaderRepository(
    private val networkClient: ServerNetworkClient,
    private val localSource: ServerPositionLocalSource,
) : ServerReaderRepository, BaseRepository {

    override val serverId: String = networkClient.serverId

    override suspend fun getPosition(bookUuid: String): AppResult<ServerPosition?> {
        return remoteWithCacheFallback(
            remoteSource = { getRemotePosition(bookUuid) },
            cacheSource = { getLocalPosition(bookUuid) },
            saveToCache = { position -> localSource.savePosition(position) },
        )
    }

    override suspend fun savePosition(bookUuid: String, position: ServerPosition): CompletableResult {
        localSource.savePosition(position)

        return networkClient.post(
            path = "/api/me/progress/$bookUuid",
            body = position.toAudiobookshelfMediaProgress(libraryItemId = bookUuid)
        )
    }

    override suspend fun getLocalPosition(bookUuid: String): AppResult<ServerPosition?> {
        return localSource.getPosition(bookUuid).map { position ->
            position?.copy(serverId = serverId)
        }
    }

    override suspend fun saveLocalPosition(position: ServerPosition): CompletableResult {
        return localSource.savePosition(position)
    }

    override suspend fun getRemotePosition(bookUuid: String): AppResult<ServerPosition?> {
        return networkClient.get<AudiobookshelfMediaProgressApiModel?>(
            path = "/api/me/progress/$bookUuid"
        ).map { apiModel ->
            apiModel?.toServerPosition(bookUuid, serverId)
        }
    }
}
```

**File: `AudiobookshelfReaderRepositoryFactory.kt`** (new)

```kotlin
package com.retro99.server.audiobookshelf

import com.retro99.server.api.ServerConfig
import com.retro99.server.api.ServerNetworkClientFactory
import com.retro99.server.api.ServerPositionLocalSource
import com.retro99.server.api.ServerReaderRepository
import com.retro99.server.api.ServerReaderRepositoryFactory
import com.retro99.server.api.ServerType
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [ServerReaderRepositoryFactory::class])
class AudiobookshelfReaderRepositoryFactory(
    @Provided private val networkClientFactory: ServerNetworkClientFactory,
    @Provided private val localSource: ServerPositionLocalSource,
) : ServerReaderRepositoryFactory {

    override val serverType: ServerType = ServerType.Audiobookshelf

    override fun create(serverConfig: ServerConfig): ServerReaderRepository {
        require(serverConfig.type == ServerType.Audiobookshelf) {
            "AudiobookshelfReaderRepositoryFactory can only create repositories for Audiobookshelf servers"
        }
        val networkClient = networkClientFactory.create(serverConfig)
        return AudiobookshelfReaderRepository(networkClient, localSource)
    }
}
```

---

## Step 11: Koin Module

**File: `lib/server-audiobookshelf/src/commonMain/kotlin/com/retro99/server/audiobookshelf/di/AudiobookshelfModule.kt`** (new)

```kotlin
package com.retro99.server.audiobookshelf.di

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module

@Module
@ComponentScan("com.retro99.server.audiobookshelf")
class AudiobookshelfModule
```

The `@ComponentScan` scans the entire `com.retro99.server.audiobookshelf` package and
registers all `@Factory`, `@Single` annotated classes. The Koin annotations plugin generates
the binding code at compile time.

The composite factories in `lib/server/implementation/` collect all factories via
`@Provided List<...>`. Adding the ABS module's factories to Koin is enough — no
manual registration in the composite factories.

---

## Step 12: UI Changes

### Step 12.1: Login Screen — Remove Hardcoded ServerType

**File: `feature/login/ui/src/commonMain/kotlin/com/retro99/login/ui/login/LoginViewModel.kt`**

Currently at lines 89 and 131:
```kotlin
// TODO: Allow user to select server type in the future
val serverType = ServerType.Storyteller
```

Two options:

**Option A (Minimal — auto-detect):** Validate the URL first, then auto-detect the
server type based on the validation response. This avoids adding a type picker to the
login screen but requires `validateServer` to return enough info to distinguish.

**Option B (Explicit — server type picker):** Add a dropdown like the one in
`ServerManagementScreen.kt` to the login screen. This is the more transparent UX.

Recommendation: **Option B** for the main login screen. The `ServerManagementScreen`
already has the dropdown pattern; reuse it.

For `handleSignInClicked`:
```kotlin
// Replace hardcoded serverType with a parameter from the UI
val serverType = state.selectedServerType  // New field in LoginViewState
```

For `handleOAuthSignInClicked`: OAuth is Storyteller-only. The OAuth button should only
be visible when `selectedServerType == ServerType.Storyteller`. Add a `isOAuthVisible`
field to `LoginViewState` and hide the OAuth button for ABS.

**File: `feature/login/data/src/commonMain/kotlin/com/retro99/login/data/LoginDataRepository.kt`**

The `loginWithOAuth` guard at line 61:
```kotlin
if (serverType != ServerType.Storyteller) {
    return Err(AppError.AuthError("OAuth login is only supported for Storyteller servers"))
}
```
This is correct and needs no change — ABS users won't hit this path if the UI hides
the OAuth button for ABS.

### Step 12.2: Server Management Screen — Add ABS to Dropdown

**File: `feature/settings/ui/src/commonMain/kotlin/com/retro99/settings/ui/servers/ServerManagementScreen.kt`**

Line 312:
```kotlin
listOf(ServerType.Storyteller, ServerType.Local).forEach { type ->
```

Change to:
```kotlin
listOf(ServerType.Storyteller, ServerType.Audiobookshelf, ServerType.Local).forEach { type ->
```

Or better, use all entries except Local:
```kotlin
ServerType.entries.filter { it != ServerType.Local }.forEach { type ->
```

### Step 12.3: Translations

Add server type display name strings to the translations module if the existing
`ServerType.displayName` is not sufficient (it returns "Audiobookshelf" directly).
No new translation strings are needed for the enum itself since `displayName` returns
the hard-coded string.

Add new strings only if the login screen needs new labels:

**File: `translations/src/commonMain/composeResources/values/strings.xml`**

```xml
<string name="login_server_type" tools:ignore="MissingTranslation">Server Type</string>
<string name="login_oauth_unavailable" tools:ignore="MissingTranslation">OAuth is not available for this server type</string>
```

---

## Step 13: Testing

### Step 13.1: Parameterized Mapper Tests

Following the project convention of `BaseKoinTest` + parameterized tests for mappers.

**File: `lib/server-audiobookshelf/src/commonTest/kotlin/com/retro99/server/audiobookshelf/model/AudiobookshelfLibraryItemApiModelTest.kt`** (new)

Test cases:
1. Book with both ebook + audiobook
2. Book with ebook only
3. Book with audiobook only
4. Book with series (with sequence number)
5. Book with multiple authors
6. Book with null metadata

**File: `lib/server-audiobookshelf/src/commonTest/kotlin/com/retro99/server/audiobookshelf/model/AudiobookshelfMediaProgressApiModelTest.kt`** (new)

Test cases:
1. Audio progress → ServerPosition (currentTime, duration, progress populated)
2. Ebook progress → ServerPosition (ebookLocation, ebookProgress populated)
3. Finished book (isFinished = true)
4. ServerPosition → ABS model round-trip fidelity

### Step 13.2: Authenticator Test

**File: `lib/server-audiobookshelf/src/commonTest/kotlin/com/retro99/server/audiobookshelf/AudiobookshelfAuthenticatorTest.kt`** (new)

- Mock `HttpClient` to return a fake `AudiobookshelfLoginResponse`
- Verify `ServerCredentials` is constructed with the correct token
- Test 401 → `AppError.AuthError`
- Test network timeout → `AppError.NetworkError`
- Test `validateServer` with successful `/ping` → valid result

### Step 13.3: Repository Tests

**File: `lib/server-audiobookshelf/src/commonTest/kotlin/com/retro99/server/audiobookshelf/AudiobookshelfBooksRepositoryTest.kt`** (new)

- Mock `ServerNetworkClient` to return 2 libraries, each with 2 books → expect 4 books total
- Test library fan-out (one library fails → remaining books still returned)
- Test cache hit vs remote fetch
- Test `searchBooks` returns filtered results

### Step 13.4: Manual Testing Against Demo Server

Use the public ABS demo at https://audiobooks.dev/ (`demo`/`demo`):

1. Add server: URL `https://audiobooks.dev`, type `Audiobookshelf`, username `demo`, password `demo`
2. Verify book list loads (library fan-out works)
3. Open a book → verify cover image loads
4. Open an audiobook → verify playback
5. Verify reading progress syncs (save position, close app, reopen → position restored)
6. Verify series list loads
7. Verify search works

---

## Summary of All File Changes

### New files (`:lib:server-audiobookshelf` module)

| File | Purpose | Est. Lines |
|------|---------|-------------|
| `build.gradle.kts` | Module build config | ~40 |
| `di/AudiobookshelfModule.kt` | Koin ComponentScan | ~8 |
| `AudiobookshelfAuthenticator.kt` | `POST /login` + `GET /ping` | ~90 |
| `AudiobookshelfNetworkClientFactory.kt` | Creates server-scoped Ktor client | ~35 |
| `AudiobookshelfBooksRepository.kt` | Library fan-out + caching | ~120 |
| `AudiobookshelfBooksRepositoryFactory.kt` | Koin factory | ~25 |
| `AudiobookshelfSeriesRepository.kt` | Per-library series fetch | ~40 |
| `AudiookshelfSeriesRepositoryFactory.kt` | Koin factory | ~25 |
| `AudiobookshelfReaderRepository.kt` | Progress sync via `/api/me/progress` | ~60 |
| `AudiobookshelfReaderRepositoryFactory.kt` | Koin factory | ~25 |
| `model/AudiobookshelfLoginRequest.kt` | Login request body | ~12 |
| `model/AudiobookshelfLoginResponse.kt` | Login response + user + settings | ~45 |
| `model/AudiobookshelfLibraryApiModel.kt` | Library list models | ~25 |
| `model/AudiobookshelfLibraryItemApiModel.kt` | Library item + media + metadata + mappers | ~140 |
| `model/AudiobookshelfSeriesApiModel.kt` | Series models + mapper | ~40 |
| `model/AudiobookshelfMediaProgressApiModel.kt` | Progress model + mappers | ~65 |
| **Total new** | | **~795** |

### Existing files modified

| File | Change | Lines Changed |
|------|--------|---------------|
| `base/src/.../ServerType.kt` | Add `Audiobookshelf` enum value | +4 |
| `lib/server/api/.../ServerCapabilities.kt` | Add `when` branch | +11 |
| `lib/server-storyteller/.../StorytellerAuthenticatorFactory.kt` | Add `when` arm | +2 |
| `settings.gradle.kts` | `include(":lib:server-audiobookshelf")` | +1 |
| `composeApp/.../AppModule.kt` | Import + include `AudiobookshelfModule` | +2 |
| `feature/settings/ui/.../ServerManagementScreen.kt` | Add ABS to dropdown | +1 |
| `feature/login/ui/.../LoginViewModel.kt` | Pass `selectedServerType` from state | ~10 |
| `feature/login/ui/.../LoginViewState.kt` | Add `selectedServerType` field | +2 |
| `translations/.../strings.xml` | Login labels for server type | +2 |
| **Total modified** | | **~35** |

### Test files (new)

| File | Test cases | Est. Lines |
|------|------------|-------------|
| `model/AudiobookshelfLibraryItemApiModelTest.kt` | 6 parameterized scenarios | ~120 |
| `model/AudiobookshelfMediaProgressApiModelTest.kt` | 4 round-trip scenarios | ~80 |
| `AudiobookshelfAuthenticatorTest.kt` | 4 test methods | ~100 |
| `AudiobookshelfBooksRepositoryTest.kt` | 4 test methods | ~120 |
| **Total tests** | | **~420** |

### Grand total

| Category | Files | Lines |
|----------|-------|-------|
| New source | 16 | ~795 |
| Existing modified | 9 | ~35 |
| Tests | 4 | ~420 |
| **Grand total** | **29** | **~1250** |

---

## API Endpoint Mapping Summary

| Parrot Contract | Storyteller (existing) | Audiobookshelf (new) |
|-----------------|----------------------|---------------------|
| `login` | `POST /api/v2/token` (form-encoded) | `POST /login` (JSON body) |
| `validateServer` | `GET /api/health`, then protected `GET /api/v2/books` as a fallback | `GET /ping` |
| `refreshToken` | Not supported | Not supported |
| `loginWithAppToken` | `POST /api/v2/token/app` (OAuth) | Not needed (absent) |
| `getBooks` | `GET /api/v2/books` (flat) | `GET /api/libraries` → `GET /api/libraries/:id/items` (fan-out) |
| `getBook(uuid)` | `GET /api/v2/books/:uuid` | `GET /api/items/:id` |
| `searchBooks` | `GET /api/v2/books?search=...` | Per-library `GET /api/libraries/:id/items?search=...` |
| `getSeries` | `GET /api/v2/series` (flat) | Per-library `GET /api/libraries/:id/series` |
| `getPosition` | `GET /api/v2/books/:uuid/positions` | `GET /api/me/progress/:id` |
| `savePosition` | `POST /api/v2/books/:uuid/positions` | `POST /api/me/progress/:id` |
| Cover URL | `{baseUrl}/api/v2/books/{uuid}/cover` | `{baseUrl}/api/items/{id}/cover` |
| Auth header | `Authorization: Bearer {token}` | `Authorization: Bearer {token}` (same) |
| Token type | Access token (opaque) | JWT (no refresh) |

---

## Implementation Order

| Step | Description | Dependencies | Est. Time |
|------|-------------|--------------|-----------|
| 0 | Add `ServerType.Audiobookshelf` enum value | None | 5 min |
| 1 | Add `ServerCapabilities` branch | Step 0 | 5 min |
| 2 | Fix `StorytellerAuthenticatorFactory` `when` | Step 0 | 5 min |
| 3 | Create module (gradle, settings, Koin module) | Step 0 | 15 min |
| 4 | API models + mappers | Step 3 | 2 hours |
| 5 | `AudiobookshelfAuthenticator` | Steps 3, 4 | 30 min |
| 6 | `AudiobookshelfNetworkClientFactory` | Step 3 | 15 min |
| 7 | `AudiobookshelfBooksRepository` + factory | Steps 4, 6 | 1 hour |
| 8 | `AudiobookshelfSeriesRepository` + factory | Steps 4, 6 | 30 min |
| 9 | `AudiobookshelfReaderRepository` + factory | Steps 4, 6 | 30 min |
| 10 | Register `AudiobookshelfModule` in `AppModule` | Step 3 | 5 min |
| 11 | UI: Add ABS to server type dropdown | Step 0 | 10 min |
| 12 | UI: Login screen server type picker | Step 11 | 1 hour |
| 13 | Tests (mappers, auth, repository) | Steps 4-9 | 2-3 hours |
| 14 | Manual testing against demo server | Steps 1-12 | 1 hour |
| **Total** | | | **~1-1.5 days** |

---

## Risks & Open Questions

1. **`ServerBooksLocalSource` package location:** Currently in `lib/server-storyteller/source/`.
   The ABS module depends on it, creating a cross-module dependency on the Storyteller module.
   Consider moving the interface + implementation to `lib/server/api/` or
   `lib/server/implementation/` as cleanup. Not blocking; works as-is.

2. **ABS `series` field shape:** The API docs show `seriesName` as a String and a separate
   `series` array in the book metadata. Verify which is populated on the demo server and
   adjust the mapper accordingly. The mapper above uses `metadata.series` (the array form).

3. **ABS `ebookLocation` format:** The docs mention `ebookLocation` for ebook progress
   but don't specify the format (CFI? percentage? chapter?). Verify against a real ABS
   instance with an ebook library. The mapper currently stores it directly in
   `locatorHref`, which may need adjustment depending on format compatibility with
   Readium's locator model.

4. **Pagination:** The `fetchAllLibrariesItems()` method uses `limit=0` (return all items).
   For very large libraries, this could be slow. Consider chunked pagination
   (`limit=100&page=0..N`) if performance becomes an issue. Verify against the demo server
   with a large library.

5. **ABS API docs accuracy:** The official API docs page states they are "out-of-date and
   no longer maintained." Verify all response shapes against the live demo server during
   implementation. Use `curl` against `https://audiobooks.dev` to confirm field names.

6. **Storyteller `seriesName` field:** In `AudiobookshelfBookMetadataApiModel`, the ABS
   API response has both `seriesName` (string) and `series` (array). The mapper uses
   `series` (array) for richer data. If the demo server only populates `seriesName`,
   fall back to parsing it.
