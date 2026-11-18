const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

function buildPlaylistPayload(uid, overrides = {}) {
  const now = new Date(Date.now() - 2000);
  return {
    userId: uid,
    name: "השירים שלי",
    description: "אוסף מכל המקורות",
    trackCount: 0,
    coverUrl: "",
    createdAt: now,
    updatedAt: now,
    ...overrides,
  };
}

function buildTrackPayload(uid, playlistId, overrides = {}) {
  return {
    userId: uid,
    playlistId,
    trackKey: "itunes_998877",
    title: "Song Title",
    artist: "Artist Name",
    album: "Album Name",
    source: "ITUNES",
    streamUrl: "https://audio-ssl.itunes.apple.com/preview.m4a",
    artworkUrl: "https://is1-ssl.mzstatic.com/image/thumb/600x600.jpg",
    durationSeconds: 30,
    fullTrack: false,
    addedAt: new Date(Date.now() - 1000),
    ...overrides,
  };
}

test("Unauthenticated user cannot read or write playlists or tracks", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("playlists").get());
  await assertFails(
    unauthDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("playlists")
      .doc("pl_1")
      .set(buildPlaylistPayload(ALICE_UID))
  );
});

test("Owner can create, list, rename, update count, and delete playlist", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const playlistsRef = aliceDb.collection("users").doc(ALICE_UID).collection("playlists");

  await assertSucceeds(playlistsRef.get());
  await assertSucceeds(playlistsRef.doc("pl_main").set(buildPlaylistPayload(ALICE_UID)));

  // Rename playlist
  await assertSucceeds(
    playlistsRef.doc("pl_main").update({
      name: "פלייליסט ערב",
      description: "מוזיקה רגועה",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Update track count & cover
  await assertSucceeds(
    playlistsRef.doc("pl_main").update({
      trackCount: 1,
      coverUrl: "https://example.com/cover.jpg",
      updatedAt: new Date(Date.now() - 200),
    })
  );

  // Reject immutable createdAt modification
  await assertFails(
    playlistsRef.doc("pl_main").update({
      createdAt: new Date(Date.now() - 100),
      updatedAt: new Date(Date.now() - 100),
    })
  );

  // Reject extra @DocumentId `id` field on create
  await assertFails(
    playlistsRef.doc("pl_bad").set(buildPlaylistPayload(ALICE_UID, { id: "pl_bad" }))
  );

  // Reject shadow field
  await assertFails(
    playlistsRef.doc("pl_shadow").set(buildPlaylistPayload(ALICE_UID, { isAdmin: true }))
  );

  await assertSucceeds(playlistsRef.doc("pl_main").delete());
});

test("Owner can add and delete tracks in playlist; invalid sources or missing parent rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const plRef = aliceDb.collection("users").doc(ALICE_UID).collection("playlists").doc("pl_1");

  // Track without parent playlist fails existsAfter
  await assertFails(
    plRef.collection("tracks").doc("tr_orphan").set(buildTrackPayload(ALICE_UID, "pl_1"))
  );

  await assertSucceeds(plRef.set(buildPlaylistPayload(ALICE_UID)));
  await assertSucceeds(
    plRef.collection("tracks").doc("tr_1").set(buildTrackPayload(ALICE_UID, "pl_1"))
  );

  // List tracks succeeds
  await assertSucceeds(plRef.collection("tracks").get());

  // Invalid source rejected
  await assertFails(
    plRef
      .collection("tracks")
      .doc("tr_bad_src")
      .set(buildTrackPayload(ALICE_UID, "pl_1", { source: "SPOTIFY_HACK" }))
  );

  // Future timestamp rejected
  await assertFails(
    plRef
      .collection("tracks")
      .doc("tr_future")
      .set(
        buildTrackPayload(ALICE_UID, "pl_1", {
          addedAt: new Date(Date.now() + 60000),
        })
      )
  );

  // Delete track succeeds
  await assertSucceeds(plRef.collection("tracks").doc("tr_1").delete());
});

test("Cross-user isolation: Bob cannot read, write, or delete Alice playlists or tracks", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await db
      .collection("users")
      .doc(ALICE_UID)
      .collection("playlists")
      .doc("pl_alice")
      .set(buildPlaylistPayload(ALICE_UID));
    await db
      .collection("users")
      .doc(ALICE_UID)
      .collection("playlists")
      .doc("pl_alice")
      .collection("tracks")
      .doc("tr_alice")
      .set(buildTrackPayload(ALICE_UID, "pl_alice"));
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const alicePl = bobDb.collection("users").doc(ALICE_UID).collection("playlists").doc("pl_alice");

  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("playlists").get());
  await assertFails(alicePl.get());
  await assertFails(alicePl.update({ name: "Hacked", updatedAt: new Date(Date.now() - 500) }));
  await assertFails(alicePl.delete());
  await assertFails(alicePl.collection("tracks").get());
  await assertFails(alicePl.collection("tracks").doc("tr_alice").delete());
});
