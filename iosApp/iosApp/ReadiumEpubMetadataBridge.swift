import Foundation
import AVFoundation
import ComposeApp
import ReadiumShared
import ReadiumStreamer

/// Swift implementation of the EPUB metadata extraction bridge.
/// Uses Readium's PublicationOpener to extract title, author, description,
/// cover image, and media overlay detection from EPUB files.
class ReadiumEpubMetadataBridge: EpubMetadataBridge {

    private lazy var httpClient: HTTPClient = DefaultHTTPClient()
    private lazy var assetRetriever = AssetRetriever(httpClient: httpClient)
    private lazy var publicationOpener = PublicationOpener(
        parser: DefaultPublicationParser(
            httpClient: httpClient,
            assetRetriever: assetRetriever,
            pdfFactory: DefaultPDFDocumentFactory()
        ),
        contentProtections: []
    )

    func extractMetadata(
        filePath: String,
        callback: @escaping (EpubMetadataResult?) -> Void
    ) {
        Task.detached { [weak self] in
            guard let self = self else {
                callback(nil)
                return
            }

            do {
                let foundationUrl = URL(fileURLWithPath: filePath)
                guard let fileUrl = FileURL(url: foundationUrl) else {
                    callback(nil)
                    return
                }

                let assetResult = await self.assetRetriever.retrieve(url: fileUrl)
                guard case .success(let asset) = assetResult else {
                    callback(nil)
                    return
                }

                let publicationResult = await self.publicationOpener.open(
                    asset: asset,
                    allowUserInteraction: false
                )

                guard case .success(let publication) = publicationResult else {
                    callback(nil)
                    return
                }

                let metadata = publication.metadata

                let title = metadata.title ?? ""
                let author = metadata.authors.first?.name
                let description = metadata.description

                let publicationDate: String? = {
                    if let date = metadata.published {
                        return date.description
                    }
                    return nil
                }()

                let hasMediaOverlays = Self.detectMediaOverlays(publication: publication)

                var coverFilePath: String? = nil
                if let coverResult = try? await publication.cover().get() {
                    let tempDir = FileManager.default.temporaryDirectory
                    let coverUrl = tempDir.appendingPathComponent("cover_\(UUID().uuidString).png")
                    if let pngData = coverResult.pngData() {
                        try? pngData.write(to: coverUrl)
                        coverFilePath = coverUrl.path
                    }
                }

                callback(EpubMetadataResult(
                    title: title,
                    author: author,
                    description: description,
                    coverFilePath: coverFilePath,
                    hasMediaOverlays: hasMediaOverlays,
                    publicationDate: publicationDate,
                    identifier: metadata.identifier
                ))
            } catch {
                callback(nil)
            }
        }
    }

    private static func detectMediaOverlays(publication: Publication) -> Bool {
        if let duration = publication.metadata.duration, duration > 0 {
            return true
        }

        let audioTypes: Set<String> = ["audio/mpeg", "audio/mp4", "audio/aac", "audio/ogg"]
        let hasAudio = publication.readingOrder.contains { link in
            if let mediaType = link.mediaType {
                return audioTypes.contains(mediaType.string)
            }
            return false
        } || publication.resources.contains { link in
            if let mediaType = link.mediaType {
                return audioTypes.contains(mediaType.string)
            }
            return false
        }
        if hasAudio {
            return true
        }

        let hasSmil = publication.readingOrder.contains { link in
            link.mediaType?.matches(.smil) == true
        } || publication.resources.contains { link in
            link.mediaType?.matches(.smil) == true
        }
        return hasSmil
    }
}
