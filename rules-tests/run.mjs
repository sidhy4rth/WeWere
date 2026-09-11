/**
 * Security-rules tests for Roll.
 *
 * These exercise the rules the way the app actually writes — in particular the
 * batched commits for "create a group" and "join a group", where each write is
 * evaluated against the pre-batch state. That detail is what makes or breaks both
 * flows, and it is invisible to a test that writes documents one at a time.
 */

import {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} from "@firebase/rules-unit-testing";
import { readFileSync } from "node:fs";
import {
  doc, setDoc, getDoc, deleteDoc, updateDoc, writeBatch,
  collection, getDocs, serverTimestamp, increment, arrayUnion, arrayRemove,
} from "firebase/firestore";

const PROJECT = "roll-rules-test";

const env = await initializeTestEnvironment({
  projectId: PROJECT,
  firestore: {
    rules: readFileSync("../firestore.rules", "utf8"),
    host: "127.0.0.1",
    port: 8080,
  },
});

const ALICE = "alice";
const BOB = "bob";
const MALLORY = "mallory";

const GROUP = "group1";
const CODE = "GA7X2M";

const alice = () => env.authenticatedContext(ALICE).firestore();
const bob = () => env.authenticatedContext(BOB).firestore();
const mallory = () => env.authenticatedContext(MALLORY).firestore();
const anon = () => env.unauthenticatedContext().firestore();

let passed = 0;
let failed = 0;

async function test(name, fn) {
  try {
    await fn();
    console.log(`  ok   ${name}`);
    passed++;
  } catch (error) {
    console.log(`  FAIL ${name}`);
    console.log(`       ${error.message.split("\n")[0]}`);
    failed++;
  }
}

/** Profiles + the invite doc, written with rules disabled. */
async function seedBase() {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    for (const uid of [ALICE, BOB, MALLORY]) {
      await setDoc(doc(db, `users/${uid}`), { name: uid, email: `${uid}@x.com` });
    }
  });
}

/** Alice owns GROUP with an active invite; Bob is not a member. */
async function seedGroup() {
  await seedBase();
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, `groups/${GROUP}`), {
      name: "Goa Trip", createdBy: ALICE, memberCount: 1, photoCount: 0,
      inviteCode: CODE, lastActivityAt: new Date(),
    });
    await setDoc(doc(db, `groups/${GROUP}/members/${ALICE}`), {
      name: "alice", role: "ADMIN", photoCount: 0, joinedAt: new Date(),
    });
    await setDoc(doc(db, `users/${ALICE}/memberships/${GROUP}`), {
      groupId: GROUP, role: "ADMIN",
    });
    await setDoc(doc(db, `invites/${CODE}`), {
      groupId: GROUP, groupName: "Goa Trip", memberCount: 1, photoCount: 0,
      createdBy: ALICE, revoked: false,
    });
  });
}

console.log("\nRoll — Firestore rules\n");

// ---------------------------------------------------------------- group create

console.log("group creation");

await test("creating a group writes group + admin member + pointer + activity in one batch", async () => {
  await seedBase();
  const db = alice();
  const batch = writeBatch(db);
  batch.set(doc(db, "groups/newgroup"), {
    name: "Trip", createdBy: ALICE, memberCount: 1, photoCount: 0,
    inviteCode: "ABC123", lastActivityAt: serverTimestamp(),
  });
  batch.set(doc(db, `groups/newgroup/members/${ALICE}`), {
    name: "alice", role: "ADMIN", photoCount: 0, joinedAt: serverTimestamp(),
  });
  batch.set(doc(db, `users/${ALICE}/memberships/newgroup`), {
    groupId: "newgroup", role: "ADMIN", joinedAt: serverTimestamp(),
  });
  batch.set(doc(db, "invites/ABC123"), {
    groupId: "newgroup", groupName: "Trip", createdBy: ALICE, revoked: false,
  });
  batch.set(doc(db, "groups/newgroup/activity/e1"), {
    type: "GROUP_CREATED", actorId: ALICE, createdAt: serverTimestamp(),
  });
  await assertSucceeds(batch.commit());
});

await test("cannot create a group claiming someone else made it", async () => {
  await seedBase();
  await assertFails(setDoc(doc(alice(), "groups/g2"), {
    name: "x", createdBy: BOB, memberCount: 1, photoCount: 0,
  }));
});

await test("cannot create a group that starts with inflated counts", async () => {
  await seedBase();
  await assertFails(setDoc(doc(alice(), "groups/g3"), {
    name: "x", createdBy: ALICE, memberCount: 99, photoCount: 0,
  }));
});

// ------------------------------------------------------------------- isolation

console.log("\nnon-members are locked out");

await test("a non-member cannot read the group", async () => {
  await seedGroup();
  await assertFails(getDoc(doc(bob(), `groups/${GROUP}`)));
});

await test("a non-member cannot read its photos", async () => {
  await seedGroup();
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), `groups/${GROUP}/photos/p1`), {
      uploadedBy: ALICE, imageUrl: "u", createdAt: new Date(),
    });
  });
  await assertFails(getDoc(doc(bob(), `groups/${GROUP}/photos/p1`)));
});

await test("a non-member cannot list its photos", async () => {
  await seedGroup();
  await assertFails(getDocs(collection(bob(), `groups/${GROUP}/photos`)));
});

await test("a non-member cannot read the member list", async () => {
  await seedGroup();
  await assertFails(getDocs(collection(bob(), `groups/${GROUP}/members`)));
});

await test("signed-out users are refused entirely", async () => {
  await seedGroup();
  await assertFails(getDoc(doc(anon(), `groups/${GROUP}`)));
});

await test("a member CAN read the group", async () => {
  await seedGroup();
  await assertSucceeds(getDoc(doc(alice(), `groups/${GROUP}`)));
});

// ----------------------------------------------------------------------- join

console.log("\njoining by invite");

await test("anyone signed in can resolve an invite code", async () => {
  await seedGroup();
  await assertSucceeds(getDoc(doc(bob(), `invites/${CODE}`)));
});

await test("the invites collection cannot be enumerated", async () => {
  await seedGroup();
  await assertFails(getDocs(collection(bob(), "invites")));
});

await test("joining writes member + pointer + count + activity in one batch", async () => {
  await seedGroup();
  const db = bob();
  const batch = writeBatch(db);
  batch.set(doc(db, `groups/${GROUP}/members/${BOB}`), {
    name: "bob", role: "MEMBER", photoCount: 0, joinedAt: serverTimestamp(),
  });
  batch.set(doc(db, `users/${BOB}/memberships/${GROUP}`), {
    groupId: GROUP, role: "MEMBER", joinedAt: serverTimestamp(),
  });
  batch.update(doc(db, `groups/${GROUP}`), {
    memberCount: increment(1),
    lastActivityAt: serverTimestamp(),
    recentMemberPhotos: arrayUnion("http://x/a.jpg"),
  });
  batch.set(doc(db, `groups/${GROUP}/activity/e2`), {
    type: "MEMBER_JOINED", actorId: BOB, createdAt: serverTimestamp(),
  });
  await assertSucceeds(batch.commit());
});

await test("cannot join as ADMIN", async () => {
  await seedGroup();
  await assertFails(setDoc(doc(bob(), `groups/${GROUP}/members/${BOB}`), {
    name: "bob", role: "ADMIN", photoCount: 0, joinedAt: serverTimestamp(),
  }));
});

await test("cannot add somebody else to a group", async () => {
  await seedGroup();
  await assertFails(setDoc(doc(bob(), `groups/${GROUP}/members/${MALLORY}`), {
    name: "m", role: "MEMBER", photoCount: 0, joinedAt: serverTimestamp(),
  }));
});

await test("cannot inflate memberCount by more than one while joining", async () => {
  await seedGroup();
  const db = bob();
  const batch = writeBatch(db);
  batch.set(doc(db, `groups/${GROUP}/members/${BOB}`), {
    name: "bob", role: "MEMBER", photoCount: 0, joinedAt: serverTimestamp(),
  });
  batch.update(doc(db, `groups/${GROUP}`), { memberCount: increment(50) });
  await assertFails(batch.commit());
});

await test("a joining user cannot rename the group on the way in", async () => {
  await seedGroup();
  await assertFails(updateDoc(doc(bob(), `groups/${GROUP}`), {
    memberCount: increment(1), name: "Hijacked",
  }));
});

// --------------------------------------------------------------------- photos

console.log("\nphotos");

async function seedTwoMembers() {
  await seedGroup();
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, `groups/${GROUP}/members/${BOB}`), {
      name: "bob", role: "MEMBER", photoCount: 0, joinedAt: new Date(),
    });
    await setDoc(doc(db, `groups/${GROUP}/photos/p1`), {
      uploadedBy: BOB, uploaderName: "bob", imageUrl: "u", createdAt: new Date(),
      caption: null, reactionCounts: {}, favoritedBy: [],
    });
  });
}

await test("a member can upload a photo attributed to themselves", async () => {
  await seedTwoMembers();
  await assertSucceeds(setDoc(doc(bob(), `groups/${GROUP}/photos/p2`), {
    uploadedBy: BOB, imageUrl: "u", createdAt: serverTimestamp(),
  }));
});

await test("cannot upload a photo attributed to someone else", async () => {
  await seedTwoMembers();
  await assertFails(setDoc(doc(bob(), `groups/${GROUP}/photos/p3`), {
    uploadedBy: ALICE, imageUrl: "u", createdAt: serverTimestamp(),
  }));
});

await test("a member can delete their own photo", async () => {
  await seedTwoMembers();
  await assertSucceeds(deleteDoc(doc(bob(), `groups/${GROUP}/photos/p1`)));
});

await test("an admin can delete someone else's photo", async () => {
  await seedTwoMembers();
  await assertSucceeds(deleteDoc(doc(alice(), `groups/${GROUP}/photos/p1`)));
});

await test("a plain member cannot delete someone else's photo", async () => {
  await seedTwoMembers();
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), `groups/${GROUP}/members/${MALLORY}`), {
      name: "m", role: "MEMBER", photoCount: 0, joinedAt: new Date(),
    });
  });
  await assertFails(deleteDoc(doc(mallory(), `groups/${GROUP}/photos/p1`)));
});

await test("the uploader can edit their own caption", async () => {
  await seedTwoMembers();
  await assertSucceeds(updateDoc(doc(bob(), `groups/${GROUP}/photos/p1`), {
    caption: "bro thought he could drive",
  }));
});

await test("another member cannot edit someone else's caption", async () => {
  await seedTwoMembers();
  await assertFails(updateDoc(doc(alice(), `groups/${GROUP}/photos/p1`), {
    caption: "rewritten",
  }));
});

await test("a member can move a reaction counter", async () => {
  await seedTwoMembers();
  await assertSucceeds(updateDoc(doc(alice(), `groups/${GROUP}/photos/p1`), {
    "reactionCounts.heart": increment(1),
  }));
});

await test("a member cannot swap the image out from under a photo", async () => {
  await seedTwoMembers();
  await assertFails(updateDoc(doc(alice(), `groups/${GROUP}/photos/p1`), {
    imageUrl: "http://evil/x.jpg",
  }));
});

await test("reactions are one per person and only your own", async () => {
  await seedTwoMembers();
  await assertSucceeds(setDoc(doc(bob(), `groups/${GROUP}/photos/p1/reactions/${BOB}`), {
    key: "heart", createdAt: serverTimestamp(),
  }));
  await assertFails(setDoc(doc(bob(), `groups/${GROUP}/photos/p1/reactions/${ALICE}`), {
    key: "heart", createdAt: serverTimestamp(),
  }));
});

// ------------------------------------------------------------------ favourites

console.log("\nstarring");

await test("a member can star a photo for themselves", async () => {
  await seedTwoMembers();
  await assertSucceeds(updateDoc(doc(alice(), `groups/${GROUP}/photos/p1`), {
    favoritedBy: arrayUnion(ALICE),
  }));
});

await test("a member can remove their own star", async () => {
  await seedTwoMembers();
  await env.withSecurityRulesDisabled(async (ctx) => {
    await updateDoc(doc(ctx.firestore(), `groups/${GROUP}/photos/p1`), {
      favoritedBy: [ALICE, BOB],
    });
  });
  await assertSucceeds(updateDoc(doc(alice(), `groups/${GROUP}/photos/p1`), {
    favoritedBy: arrayRemove(ALICE),
  }));
});

/** The reason favouriting lives on a shared array rather than a subcollection. */
await test("a member cannot star on someone else's behalf", async () => {
  await seedTwoMembers();
  await assertFails(updateDoc(doc(alice(), `groups/${GROUP}/photos/p1`), {
    favoritedBy: arrayUnion(BOB),
  }));
});

await test("a member cannot wipe everyone else's stars", async () => {
  await seedTwoMembers();
  await env.withSecurityRulesDisabled(async (ctx) => {
    await updateDoc(doc(ctx.firestore(), `groups/${GROUP}/photos/p1`), {
      favoritedBy: [BOB, MALLORY],
    });
  });
  await assertFails(updateDoc(doc(alice(), `groups/${GROUP}/photos/p1`), {
    favoritedBy: [ALICE],
  }));
});

await test("starring cannot smuggle in another field", async () => {
  await seedTwoMembers();
  await assertFails(updateDoc(doc(alice(), `groups/${GROUP}/photos/p1`), {
    favoritedBy: arrayUnion(ALICE),
    imageUrl: "http://evil/x.jpg",
  }));
});

await test("a non-member cannot star anything", async () => {
  await seedGroup();
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), `groups/${GROUP}/photos/p1`), {
      uploadedBy: ALICE, imageUrl: "u", createdAt: new Date(), favoritedBy: [],
    });
  });
  await assertFails(updateDoc(doc(bob(), `groups/${GROUP}/photos/p1`), {
    favoritedBy: arrayUnion(BOB),
  }));
});

// ------------------------------------------------------------------- admin ops

console.log("\nadmin controls");

await test("an admin can rename the group", async () => {
  await seedTwoMembers();
  await assertSucceeds(updateDoc(doc(alice(), `groups/${GROUP}`), { name: "Goa 2026" }));
});

await test("a plain member cannot rename the group", async () => {
  await seedTwoMembers();
  await assertFails(updateDoc(doc(bob(), `groups/${GROUP}`), { name: "Nope" }));
});

await test("a member can bump photoCount but not rename in the same write", async () => {
  await seedTwoMembers();
  await assertSucceeds(updateDoc(doc(bob(), `groups/${GROUP}`), {
    photoCount: increment(1), lastActivityAt: serverTimestamp(),
  }));
  await assertFails(updateDoc(doc(bob(), `groups/${GROUP}`), {
    photoCount: increment(1), name: "Sneaky",
  }));
});

await test("an admin can remove a member", async () => {
  await seedTwoMembers();
  await assertSucceeds(deleteDoc(doc(alice(), `groups/${GROUP}/members/${BOB}`)));
});

await test("a member cannot remove another member", async () => {
  await seedTwoMembers();
  await assertFails(deleteDoc(doc(bob(), `groups/${GROUP}/members/${ALICE}`)));
});

await test("a member can remove themselves (leave)", async () => {
  await seedTwoMembers();
  await assertSucceeds(deleteDoc(doc(bob(), `groups/${GROUP}/members/${BOB}`)));
});

await test("a member cannot promote themselves to admin", async () => {
  await seedTwoMembers();
  await assertFails(updateDoc(doc(bob(), `groups/${GROUP}/members/${BOB}`), { role: "ADMIN" }));
});

await test("only an admin can delete the group", async () => {
  await seedTwoMembers();
  await assertFails(deleteDoc(doc(bob(), `groups/${GROUP}`)));
  await assertSucceeds(deleteDoc(doc(alice(), `groups/${GROUP}`)));
});

// -------------------------------------------------------------------- profiles

console.log("\nprofiles and private data");

await test("a user cannot edit another user's profile", async () => {
  await seedBase();
  await assertFails(updateDoc(doc(bob(), `users/${ALICE}`), { name: "hacked" }));
});

await test("a user can edit their own profile", async () => {
  await seedBase();
  await assertSucceeds(updateDoc(doc(bob(), `users/${BOB}`), { name: "Bob B" }));
});

await test("device tokens are private to their owner", async () => {
  await seedBase();
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), `users/${ALICE}/devices/tok1`), { token: "tok1" });
  });
  await assertFails(getDoc(doc(bob(), `users/${ALICE}/devices/tok1`)));
  await assertSucceeds(getDoc(doc(alice(), `users/${ALICE}/devices/tok1`)));
});

await test("which groups someone is in is private to them", async () => {
  await seedGroup();
  await assertFails(getDocs(collection(bob(), `users/${ALICE}/memberships`)));
  await assertSucceeds(getDocs(collection(alice(), `users/${ALICE}/memberships`)));
});

await test("reports are write-only from a client", async () => {
  await seedGroup();
  await assertSucceeds(setDoc(doc(bob(), "reports/r1"), {
    groupId: GROUP, photoId: "p1", reportedBy: BOB, reason: "x",
  }));
  await assertFails(getDoc(doc(bob(), "reports/r1")));
});

await test("push coalescing windows are unreachable from a client", async () => {
  await seedTwoMembers();
  await assertFails(getDoc(doc(alice(), `groups/${GROUP}/notifyWindows/${ALICE}`)));
  await assertFails(setDoc(doc(alice(), `groups/${GROUP}/notifyWindows/${ALICE}`), { count: 1 }));
});

await env.cleanup();

console.log(`\n${passed} passed, ${failed} failed\n`);
process.exit(failed === 0 ? 0 : 1);
