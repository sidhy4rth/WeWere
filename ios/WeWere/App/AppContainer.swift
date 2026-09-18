import Foundation
import FirebaseCore
import FirebaseAuth
import FirebaseFirestore
import Kingfisher

/// The single place Firebase is named as the backend. Everything above this line
/// depends on the protocols in `Domain/Repositories.swift`, so swapping in a different
/// backend means writing new implementations and editing this file — not the UI.
@MainActor
final class AppContainer {

    static let shared = AppContainer()

    let auth: Auth
    let firestore: Firestore
    let imageStore: ImageStore
    let imageProcessor = ImageProcessor()
    let queueStore: UploadQueueStore
    let uploadScheduler: UploadScheduler

    let authRepository: AuthRepository
    let userRepository: UserRepository
    let groupRepository: GroupRepository
    let photoRepository: PhotoRepository
    let uploadQueueRepository: UploadQueueRepository

    /// Where captures and picker copies live until the queue delivers them.
    let stagingDir: URL
    let capturesDir: URL

    private init() {
        if FirebaseApp.app() == nil { FirebaseApp.configure() }
        auth = Auth.auth()

        firestore = Firestore.firestore()
        // Offline persistence is what makes the app usable on a patchy hotel wifi:
        // cached photo documents render immediately and writes queue locally.
        let settings = FirestoreSettings()
        settings.cacheSettings = PersistentCacheSettings(sizeBytes: NSNumber(value: FirestoreCacheSizeUnlimited))
        firestore.settings = settings

        let config = AppConfig.load()
        imageStore = SupabaseImageStore(baseUrl: config.supabaseUrl, anonKey: config.supabaseAnonKey,
                                        bucket: config.supabaseBucket, auth: auth)

        let fm = FileManager.default
        let support = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let caches = fm.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        stagingDir = support.appendingPathComponent(UploadWorker.stagingDirName)
        capturesDir = caches.appendingPathComponent("captures")
        try? fm.createDirectory(at: stagingDir, withIntermediateDirectories: true)
        try? fm.createDirectory(at: capturesDir, withIntermediateDirectories: true)

        queueStore = UploadQueueStore(directory: support)
        let worker = UploadWorker(store: queueStore, imageProcessor: imageProcessor, firestore: firestore,
                                  imageStore: imageStore, auth: auth, stagingDir: stagingDir)
        uploadScheduler = UploadScheduler(worker: worker, store: queueStore)

        authRepository = FirebaseAuthRepository(auth: auth, firestore: firestore)
        userRepository = FirestoreUserRepository(firestore: firestore, auth: auth, imageStore: imageStore, imageProcessor: imageProcessor)
        groupRepository = FirestoreGroupRepository(firestore: firestore, auth: auth, imageStore: imageStore, imageProcessor: imageProcessor)
        photoRepository = FirestorePhotoRepository(firestore: firestore, auth: auth, imageStore: imageStore, store: queueStore,
                                                   scheduler: uploadScheduler, stagingDir: stagingDir,
                                                   sharedDir: caches.appendingPathComponent("shared"))
        uploadQueueRepository = LocalUploadQueueRepository(store: queueStore, auth: auth, scheduler: uploadScheduler)

        Self.configureImageLoader(cacheDir: caches)
    }

    /// A shared camera roll is almost entirely images, so the loader is tuned wider
    /// than Kingfisher's defaults: a generous disk cache means scrolling back through
    /// a trip costs nothing, and a quarter of memory keeps a fast swipe through the
    /// carousel from re-decoding neighbours that were on screen a second ago.
    private static func configureImageLoader(cacheDir: URL) {
        let cache = ImageCache.default
        cache.diskStorage.config.sizeLimit = 256 * 1024 * 1024
        cache.diskStorage.config.expiration = .days(90)
        cache.memoryStorage.config.totalCostLimit = Int(ProcessInfo.processInfo.physicalMemory / 4)
        cache.memoryStorage.config.expiration = .seconds(600)
        KingfisherManager.shared.downloader.downloadTimeout = 30
    }
}

/// `SUPABASE_*` from `Config.plist` (git-ignored; copy `Config.example.plist`).
struct AppConfig {
    let supabaseUrl: String
    let supabaseAnonKey: String
    let supabaseBucket: String

    static func load() -> AppConfig {
        var dict: [String: Any] = [:]
        if let url = Bundle.main.url(forResource: "Config", withExtension: "plist"),
           let data = try? Data(contentsOf: url),
           let parsed = try? PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any] {
            dict = parsed
        }
        let url = dict["SUPABASE_URL"] as? String ?? ""
        let key = dict["SUPABASE_ANON_KEY"] as? String ?? ""
        precondition(!url.isEmpty && !key.isEmpty,
                     "SUPABASE_URL and SUPABASE_ANON_KEY must be set in ios/WeWere/Resources/Config.plist (see Config.example.plist)")
        return AppConfig(
            supabaseUrl: url,
            supabaseAnonKey: key,
            supabaseBucket: (dict["SUPABASE_BUCKET"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "roll"
        )
    }
}
