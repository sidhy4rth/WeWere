/**
 * Roll — Cloud Functions.
 *
 * One job, which a client genuinely cannot do: push fan-out. Sending a notification
 * needs every member's FCM token, and one member must never be able to read
 * another's tokens. So the rules deny that read to clients and this runs with admin
 * credentials instead.
 *
 * Cloud Functions requires the Blaze plan. Everything else in Roll works without it;
 * without this deployment you only lose push notifications.
 */

const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");
const logger = require("firebase-functions/logger");

initializeApp();
const db = getFirestore();

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
