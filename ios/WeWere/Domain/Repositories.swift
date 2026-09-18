import Foundation
import Combine

/// Live streams are Combine publishers that never fail — errors are folded into a
/// fallback value at the repository boundary, so a ViewModel decides what to show.
typealias Stream<T> = AnyPublisher<T, Never>

protocol AuthRepository: AnyObject {
    /// Emits the signed-in user, or nil when signed out. Never completes.
    var currentUser: Stream<User?> { get }

    /// Synchronous read of the current uid, for repository-layer queries.
    func currentUid() -> String?

    func signInWithGoogle(idToken: String, accessToken: String) async -> Outcome<User>
    func signInWithEmail(email: String, password: String) async -> Outcome<User>
    func signUpWithEmail(name: String, email: String, password: String) async -> Outcome<User>
    func signInAnonymously() async -> Outcome<User>

    /// Upgrades the current anonymous account in place, keeping uid and memberships.
    func linkAnonymousToEmail(name: String, email: String, password: String) async -> Outcome<User>
    func linkAnonymousToGoogle(idToken: String, accessToken: String) async -> Outcome<User>

    /// Creates the profile document if it is missing. Called on launch so an account
    /// whose first write was interrupted repairs itself instead of showing as "Someone".
    func ensureProfile() async -> Outcome<Void>

    func sendPasswordReset(email: String) async -> Outcome<Void>
    func signOut() async -> Outcome<Void>
    func deleteAccount() async -> Outcome<Void>
}

protocol UserRepository: AnyObject {
    func observeUser(uid: String) -> Stream<User?>
    func getUser(uid: String) async -> Outcome<User>
    func updateDisplayName(_ name: String) async -> Outcome<Void>
    func updateProfilePhoto(url: URL) async -> Outcome<String>

    func observeNotificationPrefs() -> Stream<NotificationPrefs>
    func updateNotificationPrefs(_ prefs: NotificationPrefs) async -> Outcome<Void>
}

protocol GroupRepository: AnyObject {
    /// Live list of groups the signed-in user belongs to, most recent activity first.
    func observeMyGroups() -> Stream<[Group]>

    func observeGroup(groupId: String) -> Stream<Group?>
    func observeMembers(groupId: String) -> Stream<[Member]>

    /// Emits nil the moment this user stops being a member (e.g. removed by an admin).
    func observeMembership(groupId: String) -> Stream<Member?>

    func observeActivity(limit: Int) -> Stream<[ActivityEvent]>

    func createGroup(name: String, description: String?, coverUrl: URL?) async -> Outcome<Group>
    func previewByInviteCode(_ code: String) async -> Outcome<GroupPreview>
    func joinByInviteCode(_ code: String) async -> Outcome<Group>
    func leaveGroup(groupId: String) async -> Outcome<Void>

    func updateGroup(groupId: String, name: String?, description: String?) async -> Outcome<Void>
    func updateCoverPhoto(groupId: String, url: URL) async -> Outcome<String>
    func removeMember(groupId: String, uid: String) async -> Outcome<Void>
    func regenerateInviteCode(groupId: String, expiresAt: Millis?) async -> Outcome<String>
    func revokeInvite(groupId: String) async -> Outcome<Void>
    func deleteGroup(groupId: String) async -> Outcome<Void>
}

/// One page of photos plus whether older ones remain.
struct PhotoPage: Equatable {
    var photos: [Photo] = []
    var hasMore: Bool = false
    var isLoadingMore: Bool = false
}

protocol PhotoRepository: AnyObject {
    /// Live, paginated feed. The newest page arrives over a realtime listener;
    /// `loadOlder` extends the same window backwards. `filter` is applied server-side
    /// so paging still works when only one person's photos are showing.
    func observePhotos(groupId: String, filter: PhotoFilter) -> Stream<PhotoPage>
    func loadOlder(groupId: String) async
    func resetPagination(groupId: String)

    func observePhoto(groupId: String, photoId: String) -> Stream<Photo?>

    /// Queues an upload; returns immediately with the pending-upload id.
    func enqueueUpload(groupId: String, localUrl: URL, caption: String?, capturedAt: Millis) async -> Outcome<String>

    func deletePhoto(groupId: String, photoId: String) async -> Outcome<Void>
    func setCaption(groupId: String, photoId: String, caption: String?) async -> Outcome<Void>

    /// Passing nil clears this user's reaction. Toggling is handled by the caller.
    func setReaction(groupId: String, photoId: String, reaction: Reaction?) async -> Outcome<Void>

    /// Saves the full-resolution image into the device's photo library.
    func downloadToGallery(_ photo: Photo) async -> Outcome<Void>

    /// Copies the image to cache and returns a file URL for the share sheet.
    func prepareForSharing(_ photo: Photo) async -> Outcome<URL>

    /// Stars or unstars a photo for the signed-in user only.
    func setFavorite(groupId: String, photoId: String, favorite: Bool) async -> Outcome<Void>

    /// Saves many photos at once, reporting progress as it goes. Returns how many
    /// landed — a partial success is still worth telling the user about.
    func downloadAllToGallery(_ photos: [Photo], onProgress: @escaping (Int, Int) -> Void) async -> Outcome<Int>

    /// Deletes several photos, skipping any the user has no right to remove.
    func deletePhotos(groupId: String, photoIds: [String]) async -> Outcome<Int>

    func reportPhoto(groupId: String, photoId: String, reason: String) async -> Outcome<Void>
}

protocol UploadQueueRepository: AnyObject {
    /// Everything not yet delivered, across all groups. Drives the "3 waiting" banner.
    func observePending() -> Stream<[PendingUpload]>
    func observePendingForGroup(groupId: String) -> Stream<[PendingUpload]>

    func retry(uploadId: String) async -> Outcome<Void>
    func retryAllFailed() async -> Outcome<Void>
    func cancel(uploadId: String) async -> Outcome<Void>
    func clearCompleted() async -> Outcome<Void>
}
