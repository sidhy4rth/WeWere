/**
 * POST /api/develop — turns a purchase into a developed roll.
 *
 *   Authorization: Bearer <Firebase ID token>
 *   { "groupId": "..." }
 *
 * The app's RevenueCat user id is the Firebase uid, so once the token is verified
 * this asks RevenueCat what that uid has bought, and develops the roll if either
 *   - the caller has an active "gold" entitlement, or
 *   - the caller has a "develop_roll" purchase not yet spent on another roll.
 * Spent purchases are recorded in `redemptions/{transactionId}` inside the same
 * transaction that develops the roll, so one purchase develops exactly one roll.
 *
 * Env: FIREBASE_SERVICE_ACCOUNT (the service account JSON, as one string) and
 *      REVENUECAT_SECRET_KEY (RevenueCat -> API keys -> secret key).
 */
const { initializeApp, cert, getApps } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");

const GOLD_ENTITLEMENT = "gold";
const SINGLE_ROLL_PRODUCT = "develop_roll";

function admin() {
  if (!getApps().length) {
    initializeApp({ credential: cert(JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT)) });
  }
  return { auth: getAuth(), db: getFirestore() };
}

function fail(res, status, message) {
  res.status(status).json({ developed: false, message });
}

async function subscriber(uid) {
  const response = await fetch(
    `https://api.revenuecat.com/v1/subscribers/${encodeURIComponent(uid)}`,
    { headers: { Authorization: `Bearer ${process.env.REVENUECAT_SECRET_KEY}` } },
  );
  if (!response.ok) throw new Error(`RevenueCat answered ${response.status}`);
  return (await response.json()).subscriber;
}

function hasGold(sub) {
  const gold = sub.entitlements?.[GOLD_ENTITLEMENT];
  if (!gold) return false;
  // A single-roll purchase attached to the entitlement by mistake would read as a
  // lifetime Gold subscription; it must only ever develop one roll.
  if ((gold.product_identifier ?? "").split(":")[0] === SINGLE_ROLL_PRODUCT) return false;
  return gold.expires_date === null || new Date(gold.expires_date) > new Date();
}

/** One-time purchases of the single-roll product, oldest first. */
function singleRollPurchases(sub) {
  const all = sub.non_subscriptions ?? {};
  // Keyed by product id; a Google product id may carry a ":suffix".
  return Object.entries(all)
    .filter(([product]) => product.split(":")[0] === SINGLE_ROLL_PRODUCT)
    .flatMap(([, purchases]) => purchases)
    .sort((a, b) => new Date(a.purchase_date) - new Date(b.purchase_date));
}

module.exports = async (req, res) => {
  if (req.method !== "POST") return fail(res, 405, "POST only");

  const token = (req.headers.authorization ?? "").replace(/^Bearer /, "");
  const groupId = req.body?.groupId;
  if (!token) return fail(res, 401, "Sign in first");
  if (typeof groupId !== "string" || !/^[A-Za-z0-9_-]{1,64}$/.test(groupId)) {
    return fail(res, 400, "Which roll?");
  }

  let uid;
  const { auth, db } = admin();
  try {
    uid = (await auth.verifyIdToken(token)).uid;
  } catch {
    return fail(res, 401, "Sign in again");
  }

  const groupRef = db.doc(`groups/${groupId}`);
  const [group, member] = await Promise.all([
    groupRef.get(),
    db.doc(`groups/${groupId}/members/${uid}`).get(),
  ]);
  if (!group.exists) return fail(res, 404, "That roll no longer exists");
  if (!member.exists) return fail(res, 403, "You're not in this roll");
  if (group.get("developed") === true) return res.status(200).json({ developed: true });

  let sub;
  try {
    sub = await subscriber(uid);
  } catch (error) {
    console.error(error);
    return fail(res, 502, "Couldn't reach the store. Try again in a moment.");
  }

  const developed = {
    developed: true,
    developedBy: uid,
    developedAt: FieldValue.serverTimestamp(),
  };

  if (hasGold(sub)) {
    await groupRef.update({ ...developed, developedVia: "gold" });
    return res.status(200).json({ developed: true, via: "gold" });
  }

  const candidates = singleRollPurchases(sub);
  if (!candidates.length) return fail(res, 402, "No purchase found for this roll");

  try {
    const via = await db.runTransaction(async (tx) => {
      const refs = candidates.map((p) => db.doc(`redemptions/${p.id}`));
      const spent = await tx.getAll(...refs);
      const index = spent.findIndex((snap) => !snap.exists);
      if (index === -1) return null;

      const current = await tx.get(groupRef);
      if (current.get("developed") === true) return "already";

      tx.create(refs[index], {
        uid, groupId, product: SINGLE_ROLL_PRODUCT,
        storeTransactionId: candidates[index].store_transaction_id ?? null,
        sandbox: candidates[index].is_sandbox ?? null,
        redeemedAt: FieldValue.serverTimestamp(),
      });
      tx.update(groupRef, { ...developed, developedVia: "single" });
      return "single";
    });

    if (via === null) return fail(res, 402, "Every purchase on this account is already used on another roll");
    return res.status(200).json({ developed: true, via });
  } catch (error) {
    console.error(error);
    return fail(res, 500, "Couldn't make the roll Exclusive. Try again.");
  }
};
