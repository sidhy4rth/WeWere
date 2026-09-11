/**
 * Roll — Cloud Functions.
 *
 * Two jobs, both of which a client genuinely cannot do:
 *
 *   1. Push fan-out. Sending a notification needs every member's FCM token, and one
 *      member must never be able to read another's tokens. So the rules deny that
 *      read to clients and this runs with admin credentials instead.
 *
 *   2. Membership custom claims. Storage rules cannot query Firestore, so the only
 *      way to enforce "members of this group only" on the image bytes themselves is
 *      to stamp the group list onto the user's auth token. Only a trusted server can
 *      set claims. Deploying this is what upgrades storage.rules from Tier 1 to
 *      Tier 2 — see README.
 *
 * Cloud Functions requires the Blaze plan. Everything else in Roll works without it;
 * without this deployment you lose push notifications and stay on Tier 1 storage
 * rules.
 */

const { onDocumentCreated, onDocumentDeleted, onDocumentWritten } =
  require("firebase-functions/v2/firestore");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");
const { getAuth } = require("firebase-admin/auth");
const logger = require("firebase-functions/logger");

initializeApp();
const db = getFirestore();

/** Claims are capped: the auth token has a hard 1000-byte budget. */
const MAX_CLAIM_GROUPS = 40;

/**
 * Collects every device token for a group except the person who caused the event —
 * nobody wants a push about their own upload.
 */
async function tokensForGroup(groupId, excludeUid, prefKey) {
  const members = await db.collection(`groups/${groupId}/members`).get();

  const tokenLists = await Promise.all(
    members.docs
      .filter((m) => m.id !== excludeUid)
      .map(async (member) => {
        const user = await db.doc(`users/${member.id}`).get();
        const prefs = user.get("notificationPrefs") || {};
        // Unset means opted in; only an explicit false suppresses.
        if (prefs[prefKey] === false) return [];

        const devices = await db.collection(`users/${member.id}/devices`).get();
        return devices.docs.map((d) => d.id);
      })
  );

  return tokenLists.flat();
}

/** Sends and prunes tokens the FCM service reports as dead. */
async function sendAndPrune(tokens, payload) {
  if (tokens.length === 0) return;

  const response = await getMessaging().sendEachForMulticast({
    tokens,
    data: payload,
    android: { priority: "high" },
  });

  const stale = [];
  response.responses.forEach((result, index) => {
    if (result.success) return;
    const code = result.error?.code;
    if (
      code === "messaging/registration-token-not-registered" ||
      code === "messaging/invalid-registration-token"
    ) {
      stale.push(tokens[index]);
    }
  });

  if (stale.length > 0) {
    logger.info(`Pruning ${stale.length} dead tokens`);
    const writes = await db
      .collectionGroup("devices")
      .where("token", "in", stale.slice(0, 30))
      .get();
    await Promise.all(writes.docs.map((d) => d.ref.delete()));
  }
}

/**
 * New photo → notify the rest of the group.
 *
 * Coalesced on a 5-minute window per uploader. Someone emptying their camera roll
 * after a party would otherwise fire thirty notifications at seven sleeping people.
 */
exports.onPhotoCreated = onDocumentCreated(
  "groups/{groupId}/photos/{photoId}",
  async (event) => {
    const photo = event.data?.data();
    if (!photo) return;

    const { groupId } = event.params;
    const uploader = photo.uploadedBy;

    const group = await db.doc(`groups/${groupId}`).get();
    const groupName = group.get("name") || "your group";

    const windowRef = db.doc(`groups/${groupId}/notifyWindows/${uploader}`);
    const now = Date.now();

    const shouldSend = await db.runTransaction(async (tx) => {
      const existing = await tx.get(windowRef);
      const lastSent = existing.get("lastSentAt")?.toMillis?.() ?? 0;
      const count = (existing.get("count") ?? 0) + 1;

      if (now - lastSent < 5 * 60 * 1000) {
        tx.set(windowRef, { count }, { merge: true });
        return null;
      }

      tx.set(windowRef, { lastSentAt: FieldValue.serverTimestamp(), count: 0 });
      return count;
    });

    if (shouldSend === null) return;

    const tokens = await tokensForGroup(groupId, uploader, "newPhotos");
    await sendAndPrune(tokens, {
      type: "PHOTOS_ADDED",
      groupId,
      groupName,
      actorName: photo.uploaderName || "Someone",
      photoCount: String(shouldSend),
    });
  }
);

/** Reaction → notify only the photo's owner. */
exports.onReactionCreated = onDocumentCreated(
  "groups/{groupId}/photos/{photoId}/reactions/{userId}",
  async (event) => {
    const reaction = event.data?.data();
    if (!reaction) return;

    const { groupId, photoId, userId } = event.params;

    const photo = await db.doc(`groups/${groupId}/photos/${photoId}`).get();
    const owner = photo.get("uploadedBy");
    if (!owner || owner === userId) return;

    const ownerDoc = await db.doc(`users/${owner}`).get();
    if ((ownerDoc.get("notificationPrefs") || {}).reactions === false) return;

    const actor = await db.doc(`users/${userId}`).get();
    const group = await db.doc(`groups/${groupId}`).get();
    const devices = await db.collection(`users/${owner}/devices`).get();

    await sendAndPrune(
      devices.docs.map((d) => d.id),
      {
        type: "REACTION",
        groupId,
        groupName: group.get("name") || "",
        actorName: actor.get("name") || "Someone",
        reaction: reaction.key || "",
      }
    );
  }
);

/**
 * Membership changes → rewrite that user's `groups` custom claim.
 *
 * The claim is what storage.rules Tier 2 reads. Note the client must refresh its ID
 * token before a new claim takes effect; Roll does this naturally because Firebase
 * refreshes tokens roughly hourly, and a freshly joined user is already authorised
 * through Firestore in the meantime.
 */
exports.syncMembershipClaims = onDocumentWritten(
  "groups/{groupId}/members/{userId}",
  async (event) => {
    const { userId } = event.params;

    // The user's own membership pointers are the authoritative list — they are
    // written in the same batch as the member document itself.
    const pointers = await db.collection(`users/${userId}/memberships`).get();
    const groups = pointers.docs
      .map((d) => d.get("groupId") || d.id)
      .slice(0, MAX_CLAIM_GROUPS);

    try {
      await getAuth().setCustomUserClaims(userId, { groups });
      logger.info(`Claims updated for ${userId}: ${groups.length} groups`);
    } catch (error) {
      logger.error(`Failed to set claims for ${userId}`, error);
    }
  }
);

/** New member → tell the group someone arrived. */
exports.onMemberJoined = onDocumentCreated(
  "groups/{groupId}/members/{userId}",
  async (event) => {
    const member = event.data?.data();
    if (!member) return;

    const { groupId, userId } = event.params;
    const group = await db.doc(`groups/${groupId}`).get();

    // The creator's own membership is written with the group; that is not a "join".
    if (group.get("createdBy") === userId) return;

    const tokens = await tokensForGroup(groupId, userId, "memberJoined");
    await sendAndPrune(tokens, {
      type: "MEMBER_JOINED",
      groupId,
      groupName: group.get("name") || "",
      actorName: member.name || "Someone",
    });
  }
);

/**
 * Deleting a photo document should not leave its bytes behind. The client already
 * attempts this, but a client that dies mid-delete would orphan the blobs.
 */
exports.onPhotoDeleted = onDocumentDeleted(
  "groups/{groupId}/photos/{photoId}",
  async (event) => {
    const photo = event.data?.data();
    if (!photo) return;

    const { getStorage } = require("firebase-admin/storage");
    const bucket = getStorage().bucket();

    await Promise.all(
      [photo.storagePath, photo.thumbnailStoragePath]
        .filter(Boolean)
        .map((path) => bucket.file(path).delete().catch(() => {}))
    );
  }
);
