import Foundation
import UIKit
import WebKit
import CoreText
import ComposeApp
import ReadiumShared
import ReadiumStreamer
import ReadiumNavigator
import ReadiumAdapterGCDWebServer

/// Available highlight styles for ReadAloud text highlighting.
enum HighlightStyle {
    /// Background highlight only
    case highlight
    /// Background highlight with underline
    case highlightUnderline
    /// Underline only (no background highlight)
    case underline
}

/// Container view controller that properly handles layout for the EPUB navigator.
/// This ensures the child view controller's view always fills the container bounds,
/// which is necessary for proper integration with Compose Multiplatform's UIKitViewController.
///
/// The key fix here is width clamping: Compose's InteropWrappingView starts with 0x0 bounds,
/// and the WKWebView's cached intrinsic content size can push the container wider than the screen.
/// We prevent this by tracking the expected width and clamping the child frame.
class ReaderContainerViewController: UIViewController {
    private let childController: UIViewController

    /// Track the expected width to prevent the WKWebView from pushing the container wider.
    /// Initialized from screen width since Compose's InteropWrappingView has 0x0 bounds initially.
    private var expectedWidth: CGFloat

    /// Flag to track if we've captured the first valid width from layout.
    private var hasValidWidth: Bool = false

    init(childController: UIViewController) {
        self.childController = childController
        self.expectedWidth = UIScreen.main.bounds.width
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()

        // Add child view controller using proper containment API
        addChild(childController)
        view.addSubview(childController.view)
        childController.didMove(toParent: self)

        childController.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        childController.view.frame = view.bounds

        // Ensure content doesn't extend beyond bounds
        view.clipsToBounds = true
        childController.view.clipsToBounds = true
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()

        // Capture the first valid width to use as our maximum
        if !hasValidWidth && view.bounds.width > 0 && view.bounds.width <= self.expectedWidth {
            hasValidWidth = true
            if view.bounds.width < self.expectedWidth {
                self.expectedWidth = view.bounds.width
            }
        }

        // Calculate target frame, clamping width if it exceeds expected
        var targetFrame = view.bounds
        if view.bounds.width > self.expectedWidth + 1 {
            targetFrame.size.width = self.expectedWidth
        }

        if childController.view.frame != targetFrame {
            childController.view.frame = targetFrame
        }
    }
}

/// Swift implementation of the EPUB reader bridge.
/// This class wraps Readium iOS SDK and exposes it to Kotlin.
@MainActor
class ReadiumEpubReaderBridge: EpubReaderBridge {

    private var publication: Publication?
    private var navigatorViewController: EPUBNavigatorViewController?
    private var onPositionChangedCallback: ((PositionLocator) -> Void)?
    private var onSentenceTapCallback: ((String) -> Void)?

    // Cached table of contents (populated when publication is opened)
    private var tableOfContentsCache: [TocItem] = []

    // Media overlay support
    private var mediaOverlayPlayer: MediaOverlayPlayer?
    private var onPlaybackStateChangedCallback: ((PlaybackState) -> Void)?
    private var onAudioLocatorChangedCallback: ((AudioLocator) -> Void)?
    private var onMediaPlayerReadyCallback: (() -> Void)?
    private var onChapterAudioCompletedCallback: ((String) -> Void)?
    private var currentHighlightId: String?
    private var currentHighlightedLocator: Locator?
    private var currentChapterHref: RelativeURL?
    private var currentHighlightColor: UIColor = .yellow
    private var currentUnderlineColor: UIColor = .blue
    private var currentHighlightStyle: HighlightStyle = .highlight

    // Readium 3.x infrastructure
    private lazy var httpClient: HTTPClient = DefaultHTTPClient()
    private lazy var assetRetriever = AssetRetriever(httpClient: httpClient)
    private lazy var httpServer: HTTPServer = GCDHTTPServer(assetRetriever: assetRetriever)
    private lazy var publicationOpener = PublicationOpener(
        parser: DefaultPublicationParser(
            httpClient: httpClient,
            assetRetriever: assetRetriever,
            pdfFactory: DefaultPDFDocumentFactory()
        ),
        contentProtections: []
    )

    init() {
        registerBundledFonts()
    }

    private static let bundledFontNames: [String] = [
        "DroidSans",
        "AtkinsonHyperlegible-Regular",
        "Literata",
        "Merriweather",
        "SourceSerif4",
        "NotoSans",
        "NotoSerif",
    ]

    private func registerBundledFonts() {
        for fontName in Self.bundledFontNames {
            guard let url = Bundle.main.url(forResource: fontName, withExtension: "ttf", subdirectory: "reader-fonts")
                ?? Bundle.main.url(forResource: fontName, withExtension: "ttf")
            else {
                continue
            }
            CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
        }
    }

    func openPublication(
        filePath: String,
        onSuccess: @escaping () -> Void,
        onError: @escaping (String) -> Void
    ) {
        Task { @MainActor in
            do {
                // Convert file path to Foundation URL, then to Readium's FileURL
                let foundationUrl = URL(fileURLWithPath: filePath)
                guard let fileUrl = FileURL(url: foundationUrl) else {
                    onError("Invalid file path: \(filePath)")
                    return
                }

                // Retrieve the asset
                let assetResult = await assetRetriever.retrieve(url: fileUrl)
                guard case .success(let asset) = assetResult else {
                    if case .failure(let error) = assetResult {
                        onError("Failed to retrieve asset: \(error)")
                    }
                    return
                }

                // Open the publication using PublicationOpener
                let publicationResult = await publicationOpener.open(
                    asset: asset,
                    allowUserInteraction: false
                )

                switch publicationResult {
                case .success(let publication):
                    self.publication = publication
                    // Cache the table of contents
                    await self.cacheTableOfContents(publication: publication)
                    onSuccess()
                case .failure(let error):
                    onError("Failed to open publication: \(error)")
                }
            }
        }
    }

    func closePublication() {
        mediaOverlayPlayer?.release()
        mediaOverlayPlayer = nil

        // Properly clean up the navigator view controller
        // Remove it from its parent view controller and view hierarchy
        if let navigator = navigatorViewController {
            navigator.willMove(toParent: nil)
            navigator.view.removeFromSuperview()
            navigator.removeFromParent()
        }
        navigatorViewController = nil
        publication = nil
        tableOfContentsCache = []
    }

    func createReaderViewController(settings: EpubReaderSettings) -> UIViewController? {
        guard let publication = self.publication else {
            return nil
        }

        if let oldNavigator = navigatorViewController {
            oldNavigator.willMove(toParent: nil)
            oldNavigator.view.removeFromSuperview()
            oldNavigator.removeFromParent()
        }

        do {
            // Create initial preferences from settings
            let initialPreferences = settings.toEpubPreferences()
            registerCustomFonts(settings.customFonts)

            // Create initial locator from settings if available. A stored href this
            // publication doesn't have (e.g. an Audiobookshelf CFI) opens at its total
            // progression once the navigator is ready, instead of the start of the book.
            var initialLocation: Locator? = nil
            var openAtProgression: Double? = nil
            if let position = settings.initialPosition,
               !isInReadingOrder(position.href, of: publication) {
                openAtProgression = position.totalProgression?.doubleValue
            } else if let position = settings.initialPosition,
               let href = AnyURL(legacyHREF: position.href) {
                initialLocation = Locator(
                    href: href,
                    mediaType: MediaType(position.type) ?? .html,
                    title: position.title,
                    locations: Locator.Locations(
                        progression: position.progression?.doubleValue,
                        totalProgression: position.totalProgression?.doubleValue,
                        position: position.position.map {
                            Int($0.int32Value)
                        }
                    )
                )
            }

            let navigator = try EPUBNavigatorViewController(
                publication: publication,
                initialLocation: initialLocation,
                config: EPUBNavigatorViewController.Configuration(
                    preferences: initialPreferences
                ),
                httpServer: httpServer
            )
            self.navigatorViewController = navigator
            if let progression = openAtProgression {
                goToTotalProgression(progression)
            }

            // Set delegate to receive location change callbacks
            navigator.delegate = self

            // Reset the navigator's view to a standard size
            // This prevents any cached sizing from the previous session from affecting layout
            let screenBounds = UIScreen.main.bounds
            navigator.view.frame = CGRect(x: 0, y: 0, width: screenBounds.width, height: screenBounds.height)
            navigator.view.clipsToBounds = true

            // Wrap the navigator in a container that properly handles layout
            // This ensures the navigator's view always fills its parent bounds
            let containerVC = ReaderContainerViewController(childController: navigator)

            return containerVC
        } catch {
            return nil
        }
    }

    func goToNextPage() {
        Task { @MainActor in
            _ = await navigatorViewController?.goForward(options: .animated)
        }
    }

    func goToPreviousPage() {
        Task { @MainActor in
            _ = await navigatorViewController?.goBackward(options: .animated)
        }
    }

    func goToChapter(href: String) {
        Task { @MainActor in
            guard let publication = self.publication else {
                return
            }

            // Find the link with the matching href
            let link = publication.readingOrder.first {
                $0.href == href
            }

            if let link = link {
                _ = await navigatorViewController?.go(to: link)
            }
        }
    }

    func setSettings(settings: EpubReaderSettings) {
        Task { @MainActor in
            registerCustomFonts(settings.customFonts)
            let oldHighlightColor = currentHighlightColor
            let oldUnderlineColor = currentUnderlineColor
            let oldStyle = currentHighlightStyle
            let oldFontSize = navigatorViewController?.settings.fontSize

            currentHighlightColor = highlightColorFromArgb(settings.highlightColorArgb)
            currentUnderlineColor = highlightColorFromArgb(settings.underlineColorArgb)
            currentHighlightStyle = highlightStyleFromString(settings.highlightStyle)

            // Save the current position before applying settings
            // Font size changes cause re-pagination which can lose the position
            let currentPosition = navigatorViewController?.currentLocation

            let preferences = settings.toEpubPreferences()
            navigatorViewController?.submitPreferences(preferences)

            // Restore the position after settings are applied (only if font size changed)
            // We need a delay because submitPreferences triggers async re-pagination in the WebView
            let newFontSize = navigatorViewController?.settings.fontSize
            if oldFontSize != newFontSize, let position = currentPosition {
                try? await Task.sleep(nanoseconds: 300_000_000) // 300ms delay
                _ = await navigatorViewController?.go(to: position)
            }

            // Refresh current decoration if highlight color, underline color, or style changed
            if (oldHighlightColor != currentHighlightColor || oldUnderlineColor != currentUnderlineColor || oldStyle != currentHighlightStyle),
               let locator = currentHighlightedLocator {
                refreshCurrentDecoration(locator: locator)
            }
        }
    }

    private func registerCustomFonts(_ customFonts: [EpubReaderCustomFont]) {
        for font in customFonts {
            let url = URL(fileURLWithPath: font.filePath)
            CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
        }
    }

    /// Refreshes the current decoration with updated highlight color/style.
    private func refreshCurrentDecoration(locator: Locator) {
        guard let navigator = navigatorViewController else { return }
        let decorations = createDecorations(for: locator)
        navigator.apply(decorations: decorations, in: "media-overlay")
    }

    /// Converts an ARGB Int32 value to UIColor
    private func highlightColorFromArgb(_ argb: Int32) -> UIColor {
        let alpha = CGFloat((argb >> 24) & 0xFF) / 255.0
        let red = CGFloat((argb >> 16) & 0xFF) / 255.0
        let green = CGFloat((argb >> 8) & 0xFF) / 255.0
        let blue = CGFloat(argb & 0xFF) / 255.0
        return UIColor(red: red, green: green, blue: blue, alpha: alpha)
    }

    private func highlightStyleFromString(_ styleName: String) -> HighlightStyle {
        switch styleName.uppercased() {
        case "HIGHLIGHT":
            return .highlight
        case "HIGHLIGHT_UNDERLINE":
            return .highlightUnderline
        case "UNDERLINE":
            return .underline
        default:
            return .highlight
        }
    }

    func goToPosition(
        href: String,
        type: String,
        progression: KotlinDouble?,
        position: KotlinInt?,
        totalProgression: KotlinDouble?
    ) {
        if let publication = self.publication, !isInReadingOrder(href, of: publication) {
            if let totalProgression = totalProgression?.doubleValue {
                goToTotalProgression(totalProgression)
            }
            return
        }
        Task { @MainActor in
            guard let url = AnyURL(legacyHREF: href) else {
                return
            }

            let locator = Locator(
                href: url,
                mediaType: MediaType(type) ?? .html,
                locations: Locator.Locations(
                    progression: progression?.doubleValue,
                    position: position.map {
                        Int($0.int32Value)
                    }
                )
            )
            _ = await navigatorViewController?.go(to: locator)
        }
    }

    /// Whether `href` names one of the publication's reading-order resources, ignoring a
    /// fragment and a leading slash.
    private func isInReadingOrder(_ href: String, of publication: Publication) -> Bool {
        guard !publication.readingOrder.isEmpty else { return true }
        let path = Self.resourcePath(href)
        guard !path.isEmpty else { return false }
        return publication.readingOrder.contains { link in
            Self.resourcePath(link.href.description) == path
        }
    }

    private static func resourcePath(_ href: String) -> String {
        let withoutFragment = href.split(separator: "#", omittingEmptySubsequences: false)
            .first.map(String.init) ?? href
        return String(withoutFragment.drop(while: { $0 == "/" }))
    }

    /// Moves to `progression` of the whole book once the navigator has loaded.
    private func goToTotalProgression(_ progression: Double) {
        guard let publication = self.publication, (0.0...1.0).contains(progression) else {
            return
        }
        Task { @MainActor [weak self] in
            guard let target = await publication.locate(progression: progression) else {
                return
            }
            for _ in 0..<50 {
                if let navigator = self?.navigatorViewController,
                   navigator.currentLocation != nil {
                    _ = await navigator.go(to: target)
                    return
                }
                try? await Task.sleep(nanoseconds: 100_000_000)
            }
        }
    }

    func setOnPositionChangedCallback(callback: ((PositionLocator) -> Void)?) {
        self.onPositionChangedCallback = callback
    }

    func setOnSentenceTapCallback(callback: ((String) -> Void)?) {
        self.onSentenceTapCallback = callback
    }

    func getTableOfContents() -> [TocItem] {
        return tableOfContentsCache
    }

    func search(query: String, callback: @escaping ([SearchResultLocator]) -> Void) {
        guard let publication = self.publication else {
            callback([])
            return
        }

        Task { @MainActor in
            let searchResult = await publication.search(query: query)
            switch searchResult {
            case .success(let iterator):
                var results: [SearchResultLocator] = []
                do {
                    while let page = try await iterator.next().get() {
                        for locator in page.locators {
                            let snippet = [
                                locator.text.before,
                                locator.text.highlight,
                                locator.text.after
                            ]
                            .compactMap { $0 }
                            .joined(separator: " ")
                            .replacingOccurrences(
                                of: "\\s+",
                                with: " ",
                                options: .regularExpression
                            )

                            results.append(
                                SearchResultLocator(
                                    href: locator.href.string,
                                    type: locator.mediaType.string,
                                    title: locator.title,
                                    progression: locator.locations.progression.map {
                                        KotlinDouble(value: $0)
                                    },
                                    position: locator.locations.position.map {
                                        KotlinInt(value: Int32($0))
                                    },
                                    totalProgression: locator.locations.totalProgression.map {
                                        KotlinDouble(value: $0)
                                    },
                                    snippet: snippet
                                )
                            )
                        }
                    }
                } catch {
                    // Return all results collected so far if an individual resource fails.
                }
                callback(results)
            case .failure:
                callback([])
            }
        }
    }

    private func cacheTableOfContents(publication: Publication) async {
        var result: [TocItem] = []
        let tocResult = await publication.tableOfContents()
        if case .success(let links) = tocResult {
            flattenToc(links: links, level: 0, into: &result)
        }
        tableOfContentsCache = result
    }

    private func flattenToc(links: [Link], level: Int, into result: inout [TocItem]) {
        for link in links {
            let hrefString = link.href.description
            let item = TocItem(
                href: hrefString,
                title: link.title ?? hrefString,
                level: Int32(level)
            )
            result.append(item)
            flattenToc(links: link.children, level: level + 1, into: &result)
        }
    }

    // MARK: - Media Overlay Methods

    func hasMediaOverlays() -> Bool {
        guard let publication = self.publication else {
            return false
        }

        // Check three indicators for media overlays:
        // 1. Duration metadata (must be > 0, not just non-null)
        let duration = publication.metadata.duration
        let hasDuration = duration != nil && duration! > 0.0

        // 2. Audio resources
        let hasAudioResource = publication.resources.contains(where: { link in
            link.mediaType?.matches(.mp3) == true ||
            link.mediaType?.matches(.mp4) == true ||
            link.mediaType?.matches(.aac) == true ||
            link.mediaType?.matches(.ogg) == true
        })

        // 3. SMIL resources
        let hasSmilResource = publication.resources.contains(where: { link in
            let hrefString: String = link.href.description
            return link.mediaType?.matches(.smil) == true || hrefString.hasSuffix(".smil")
        })

        return hasDuration || hasAudioResource || hasSmilResource
    }

    func initializeMediaOverlays(onReady: @escaping () -> Void) {
        guard let publication = self.publication else {
            onReady()
            return
        }

        if let existingPlayer = mediaOverlayPlayer {
            handlePlaybackStateChanged(MediaPlaybackState(
                isPlaying: existingPlayer.isPlaying,
                currentPositionMs: existingPlayer.currentPositionMs,
                durationMs: existingPlayer.durationMs
            ))
            onReady()
            self.onMediaPlayerReadyCallback?()
            return
        }

        let player = MediaOverlayPlayer(publication: publication)
        self.mediaOverlayPlayer = player

        // Set up callbacks
        player.onPlaybackStateChanged = { [weak self] state in
            self?.handlePlaybackStateChanged(state)
        }

        player.onLocatorChanged = { [weak self] locator, sentenceDurationMs in
            self?.handleLocatorChanged(locator, sentenceDurationMs: sentenceDurationMs)
        }

        player.onChapterAudioCompleted = { [weak self] chapterHref in
            self?.onChapterAudioCompletedCallback?(chapterHref)
        }

        Task {
            // Get initial chapter href for optimized lazy loading
            let initialChapterHref: String?
            if let currentLocator = self.navigatorViewController?.currentLocation,
               let chapterHref = currentLocator.href.relativeURL {
                self.currentChapterHref = chapterHref
                initialChapterHref = chapterHref.description
            } else {
                initialChapterHref = nil
            }

            // Initialize with lazy loading, passing initial chapter for optimization
            await player.initialize(initialChapterHref: initialChapterHref)

            // Prepare duration for the initial chapter
            if let chapterHref = self.currentChapterHref {
                await player.prepareChapterDuration(chapterHref: chapterHref)
            }

            onReady()
            self.onMediaPlayerReadyCallback?()
        }
    }

    func playAudio(initialPositionMs: KotlinLong?) {
        guard let player = mediaOverlayPlayer else {
            return
        }

        // Get current locator from navigator
        let currentLocator = navigatorViewController?.currentLocation
        // Extract the fragment ID from the locator (e.g., "chapter44.xhtml-sentence50")
        let fragmentId = currentLocator?.locations.fragments.first
        // Extract the progression (0.0 to 1.0) through the chapter
        let progression = currentLocator?.locations.progression

        // Get current chapter href from navigator and convert to RelativeURL
        if let currentHref = currentLocator?.href,
           let relativeHref = RelativeURL(string: currentHref.string) {
            player.play(chapterHref: relativeHref, initialFragmentId: fragmentId, initialProgression: progression, initialPositionMs: initialPositionMs?.int64Value)
        } else {
            player.play(chapterHref: nil, initialFragmentId: fragmentId, initialProgression: progression, initialPositionMs: initialPositionMs?.int64Value)
        }
    }

    func resumeAudio() {
        mediaOverlayPlayer?.resume()
    }

    func pauseAudio() {
        mediaOverlayPlayer?.pause()
    }

    func seekToAudioPosition(timestampMs: Int64) {
        mediaOverlayPlayer?.seekTo(positionMs: timestampMs)
    }

    func setPlaybackSpeed(speed: Float) {
        mediaOverlayPlayer?.setPlaybackSpeed(speed: speed)
    }

    func skipForward() {
        mediaOverlayPlayer?.skipForward()
    }

    func skipBackward() {
        mediaOverlayPlayer?.skipBackward()
    }

    func playFromFragment(fragmentId: String, chapterHref: String?) {
        guard let player = mediaOverlayPlayer else {
            return
        }

        let relativeHref: RelativeURL?
        if let href = chapterHref {
            relativeHref = RelativeURL(string: href)
        } else if let currentHref = navigatorViewController?.currentLocation?.href.relativeURL {
            relativeHref = currentHref
        } else {
            relativeHref = nil
        }

        player.play(
            chapterHref: relativeHref,
            initialFragmentId: fragmentId,
            initialProgression: nil,
            initialPositionMs: nil
        )
    }

    func updatePositionForFragment(fragmentId: String) {
        guard let player = mediaOverlayPlayer else {
            return
        }

        if let positionMs = player.findPositionForFragment(fragmentId: fragmentId) {
            // Seek AVPlayer so playback starts from this position
            player.seekTo(positionMs: positionMs)
            // Emit the position through the playback state callback
            let state = PlaybackState(
                isPlaying: false,
                currentPositionMs: positionMs,
                durationMs: player.durationMs.map {
                    KotlinLong(value: $0)
                }
            )
            onPlaybackStateChangedCallback?(state)
        }
    }

    func setOnPlaybackStateChangedCallback(callback: ((PlaybackState) -> Void)?) {
        self.onPlaybackStateChangedCallback = callback
    }

    func setOnAudioLocatorChangedCallback(callback: ((AudioLocator) -> Void)?) {
        self.onAudioLocatorChangedCallback = callback
    }

    func setOnMediaPlayerReadyCallback(callback: (() -> Void)?) {
        self.onMediaPlayerReadyCallback = callback
    }

    func setOnChapterAudioCompletedCallback(callback: ((String) -> Void)?) {
        self.onChapterAudioCompletedCallback = callback
    }

    func applyAudioHighlight(locator: AudioLocator) {
        guard let href = AnyURL(legacyHREF: locator.href) else {
            return
        }

        let fragmentId = locator.fragment
        let fragments = fragmentId.map {
            [$0]
        } ?? []
        let readiumLocator = Locator(
            href: href,
            mediaType: MediaType(locator.type) ?? .html,
            title: locator.title,
            locations: Locator.Locations(
                fragments: fragments,
                progression: locator.progression?.doubleValue,
                totalProgression: locator.totalProgression?.doubleValue,
                position: locator.position.map {
                    Int($0.int32Value)
                },
                )
        )
        applyHighlightDecoration(locator: readiumLocator)
    }

    func evaluateJavaScript(script: String, callback: @escaping (String?) -> Void) {
        guard let navigator = navigatorViewController else {
            callback(nil)
            return
        }

        // EPUBNavigatorViewController provides evaluateJavaScript which returns Result<Any, Error>
        Task { @MainActor in
            let result = await navigator.evaluateJavaScript(script)
            switch result {
            case .success(let value):
                // Convert the result to a string
                if let stringValue = value as? String {
                    callback(stringValue)
                } else {
                    // For other types, convert to string representation
                    callback(String(describing: value))
                }
            case .failure(let error):
                print("JavaScript evaluation failed: \(error)")
                callback(nil)
            }
        }
    }

    // MARK: - Private Media Overlay Helpers

    private func handlePlaybackStateChanged(_ state: MediaPlaybackState) {
        let playbackState = PlaybackState(
            isPlaying: state.isPlaying,
            currentPositionMs: state.currentPositionMs,
            durationMs: state.durationMs.map {
                KotlinLong(value: $0)
            }
        )
        onPlaybackStateChangedCallback?(playbackState)
    }

    private func handleLocatorChanged(_ locator: Locator, sentenceDurationMs: Int64) {
        applyHighlightDecoration(locator: locator)
        notifyAudioLocatorChanged(locator, sentenceDurationMs: sentenceDurationMs)
    }

    private func notifyAudioLocatorChanged(_ locator: Locator, sentenceDurationMs: Int64) {
        guard let callback = onAudioLocatorChangedCallback else {
            return
        }

        let audioLocator = AudioLocator(
            href: locator.href.string,
            type: locator.mediaType.string,
            title: locator.title,
            progression: locator.locations.progression.map {
                KotlinDouble(value: $0)
            },
            position: locator.locations.position.map {
                KotlinInt(value: Int32($0))
            },
            totalProgression: locator.locations.totalProgression.map {
                KotlinDouble(value: $0)
            },
            fragment: locator.locations.fragments.first,
            sentenceDurationMs: sentenceDurationMs
        )
        callback(audioLocator)
    }

    private func applyHighlightDecoration(locator: Locator) {
        guard let navigator = navigatorViewController else {
            return
        }

        guard let fragmentId = locator.locations.fragments.first else {
            return
        }

        // Skip if same fragment
        guard fragmentId != currentHighlightId else {
            return
        }
        currentHighlightId = fragmentId
        currentHighlightedLocator = locator

        // Create decorations based on the current highlight style
        let decorations = createDecorations(for: locator)

        // Apply decoration using Readium's Decoration API
        // EPUBNavigatorViewController conforms to DecorableNavigator
        navigator.apply(decorations: decorations, in: "media-overlay")

        // Navigate to the locator to ensure the highlighted text is visible on screen
        // This is especially important when seeking audio - the text should follow
        Task {
            _ = await navigator.go(to: locator, options: .init(animated: false))
        }
    }

    /// Creates decorations based on the current highlight style setting.
    /// Uses currentHighlightColor for highlight decorations and currentUnderlineColor for underline decorations.
    private func createDecorations(for locator: Locator) -> [Decoration] {
        switch currentHighlightStyle {
        case .highlight:
            return [
                Decoration(
                    id: "media-overlay-highlight",
                    locator: locator,
                    style: .highlight(tint: currentHighlightColor, isActive: false)
                )
            ]
        case .underline:
            return [
                Decoration(
                    id: "media-overlay-underline",
                    locator: locator,
                    style: .underline(tint: currentUnderlineColor, isActive: false)
                )
            ]
        case .highlightUnderline:
            return [
                Decoration(
                    id: "media-overlay-highlight",
                    locator: locator,
                    style: .highlight(tint: currentHighlightColor, isActive: false)
                ),
                Decoration(
                    id: "media-overlay-underline",
                    locator: locator,
                    style: .underline(tint: currentUnderlineColor, isActive: false)
                )
            ]
        }
    }
}

// MARK: - EPUBNavigatorDelegate

extension ReadiumEpubReaderBridge: EPUBNavigatorDelegate {
    func navigator(_ navigator: any Navigator, presentError error: NavigatorError) {
        print("Navigator error: \(error)")
    }

    func navigator(_ navigator: any Navigator, didFailToLoadResourceAt href: RelativeURL, withError error: ReadError) {
        print("Failed to load resource at \(href): \(error)")
    }

    func navigator(_ navigator: any Navigator, locationDidChange locator: Locator) {
        // Update chapter duration if chapter changed
        if let chapterHref = locator.href.relativeURL,
           chapterHref.removingFragment() != currentChapterHref?.removingFragment() {
            currentChapterHref = chapterHref
            Task {
                await mediaOverlayPlayer?.prepareChapterDuration(chapterHref: chapterHref)
            }
        }

        guard let callback = onPositionChangedCallback else {
            return
        }

        let positionLocator = PositionLocator(
            href: locator.href.string,
            type: locator.mediaType.string,
            title: locator.title,
            progression: locator.locations.progression.map {
                KotlinDouble(value: $0)
            },
            position: locator.locations.position.map {
                KotlinInt(value: Int32($0))
            },
            totalProgression: locator.locations.totalProgression.map {
                KotlinDouble(value: $0)
            }
        )
        callback(positionLocator)

        Task { [weak self] in
            guard let self = self,
                  let epubNavigator = navigator as? EPUBNavigatorViewController else {
                return
            }

            let enrichedLocator = await epubNavigator.firstVisibleElementLocator()

            if let cssSelector = enrichedLocator?.locations.otherLocations["cssSelector"] as? String {
                let enrichedPositionLocator = PositionLocator(
                    href: locator.href.string,
                    type: locator.mediaType.string,
                    title: locator.title,
                    progression: locator.locations.progression.map {
                        KotlinDouble(value: $0)
                    },
                    position: locator.locations.position.map {
                        KotlinInt(value: Int32($0))
                    },
                    totalProgression: locator.locations.totalProgression.map {
                        KotlinDouble(value: $0)
                    },
                    cssSelector: cssSelector
                )
                callback(enrichedPositionLocator)
            }
        }
    }
}

// MARK: - EpubReaderSettings Extension

extension EpubReaderSettings {
    /// Converts reader settings to Readium EPUBPreferences.
    /// This is used both for initial preferences and dynamic updates.
    func toEpubPreferences() -> EPUBPreferences {
        let fontWeightOverride = fontWeightOverride()
        return EPUBPreferences(
            fontFamily: fontFamilyToReadium(),
            fontSize: fontSize,
            fontWeight: fontWeightOverride,
            lineHeight: Double(lineHeight),
            pageMargins: calculatePageMargins(),
            paragraphSpacing: paragraphSpacing,
            publisherStyles: publisherStyles,
            scroll: scrollMode?.boolValue,
            textAlign: textAlignToReadium(),
            textNormalization: textNormalization,
            theme: themeToReadium()
        )
    }

    /// Converts font family string to Readium's FontFamily.
    /// Returns nil for "default" to use publisher's font.
    private func fontFamilyToReadium() -> FontFamily? {
        if fontFamily == "default" {
            return nil
        }
        return FontFamily(rawValue: fontFamily)
    }

    private func fontWeightOverride() -> Double? {
        if fontWeight == 1.0 {
            return nil
        }
        return fontWeight
    }

    /// Converts theme string to Readium's Theme enum.
    /// Note: SYSTEM theme is not directly supported by Readium, so we return nil to use default.
    private func themeToReadium() -> Theme? {
        switch theme {
        case "LIGHT":
            return .light
        case "DARK":
            return .dark
        case "SEPIA":
            return .sepia
        case "SYSTEM":
            return nil // Let Readium use its default
        default:
            return nil
        }
    }

    /// Converts textAlign string to Readium's TextAlignment enum.
    private func textAlignToReadium() -> TextAlignment {
        switch textAlign {
        case "START":
            return .start
        case "END":
            return .end
        case "CENTER":
            return .center
        case "JUSTIFY":
            return .justify
        default:
            return .start
        }
    }

    /// Calculates page margins as a factor for Readium.
    /// Readium's pageMargins is a multiplier applied to horizontal margins only.
    /// We convert our dp-based horizontal margin to a factor based on a 16dp baseline.
    /// Vertical margins are applied separately via SwiftUI padding on the reader view.
    private func calculatePageMargins() -> Double {
        // Baseline is 16dp, so 16dp = 1.0 factor
        return min(max(Double(marginHorizontal) / 16.0, 0.0), 4.0)
    }
}
