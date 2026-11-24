const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_owner_uid";
const BOB_UID = "bob_other_uid";
const DEVICE_ID = "IG-DEVICE1234";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
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

test("Authenticated owner can write and read their own device summary, telemetry, and breadcrumbs", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const deviceRef = aliceDb.collection("users").doc(ALICE_UID).collection("devices").doc(DEVICE_ID);

  await assertSucceeds(
    deviceRef.set({
      deviceId: DEVICE_ID,
      ownerUid: ALICE_UID,
      deviceName: "Pixel 8 Pro",
      securityState: "STOLEN_MODE",
      lastKnownLat: -1.9441,
      lastKnownLng: 30.0619,
      batteryLevel: 82,
      updatedAt: 1700000000000,
    })
  );

  await assertSucceeds(
    deviceRef.collection("recovery_telemetry").doc("latest").set({
      deviceId: DEVICE_ID,
      ownerUid: ALICE_UID,
      securityState: "STOLEN_MODE",
      lastKnownLat: -1.9441,
      lastKnownLng: 30.0619,
      locationAccuracy: 8.5,
      isFreshFix: true,
      batteryLevel: 82,
      timestamp: 1700000000000,
    })
  );

  await assertSucceeds(
    deviceRef.collection("location_breadcrumbs").doc("bc_1").set({
      id: "bc_1",
      deviceId: DEVICE_ID,
      ownerUid: ALICE_UID,
      latitude: -1.9441,
      longitude: 30.0619,
      accuracy: 8.5,
      batteryLevel: 82,
      timestamp: 1700000000000,
      isFreshFix: true,
    })
  );

  await assertSucceeds(deviceRef.get());
  await assertSucceeds(deviceRef.collection("recovery_telemetry").doc("latest").get());
  await assertSucceeds(deviceRef.collection("location_breadcrumbs").get());
});

test("Another authenticated user (Bob) cannot read or write Alice's device coordinates", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const adminDevice = ctx.firestore().collection("users").doc(ALICE_UID).collection("devices").doc(DEVICE_ID);
    await adminDevice.set({
      deviceId: DEVICE_ID,
      ownerUid: ALICE_UID,
      lastKnownLat: -1.9441,
      lastKnownLng: 30.0619,
      batteryLevel: 75,
    });
    await adminDevice.collection("recovery_telemetry").doc("latest").set({
      deviceId: DEVICE_ID,
      ownerUid: ALICE_UID,
      lastKnownLat: -1.9441,
      lastKnownLng: 30.0619,
      timestamp: 1700000000000,
    });
    await adminDevice.collection("location_breadcrumbs").doc("bc_1").set({
      deviceId: DEVICE_ID,
      ownerUid: ALICE_UID,
      latitude: -1.9441,
      longitude: 30.0619,
      timestamp: 1700000000000,
    });
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const aliceDeviceAsBob = bobDb.collection("users").doc(ALICE_UID).collection("devices").doc(DEVICE_ID);

  await assertFails(aliceDeviceAsBob.get());
  await assertFails(aliceDeviceAsBob.collection("recovery_telemetry").doc("latest").get());
  await assertFails(aliceDeviceAsBob.collection("location_breadcrumbs").doc("bc_1").get());
  await assertFails(
    aliceDeviceAsBob.collection("recovery_telemetry").doc("latest").set({
      deviceId: DEVICE_ID,
      ownerUid: BOB_UID,
      timestamp: 1700000000000,
    })
  );
  await assertFails(
    aliceDeviceAsBob.collection("location_breadcrumbs").doc("bc_2").set({
      deviceId: DEVICE_ID,
      ownerUid: ALICE_UID,
      latitude: -1.95,
      longitude: 30.05,
      timestamp: 1700000005000,
    })
  );
});

test("Unauthenticated users cannot read or write any device telemetry or breadcrumbs", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const devRef = unauthDb.collection("users").doc(ALICE_UID).collection("devices").doc(DEVICE_ID);

  await assertFails(devRef.get());
  await assertFails(devRef.collection("recovery_telemetry").doc("latest").get());
  await assertFails(devRef.collection("location_breadcrumbs").get());
});

test("Invalid coordinates (0,0 or out-of-range) and forged ownerUid are rejected on breadcrumbs", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const breadcrumbs = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("devices")
    .doc(DEVICE_ID)
    .collection("location_breadcrumbs");

  await assertFails(
    breadcrumbs.doc("bad_zero").set({
      deviceId: DEVICE_ID,
      ownerUid: ALICE_UID,
      latitude: 0,
      longitude: 0,
      timestamp: 1700000000000,
    })
  );

  await assertFails(
    breadcrumbs.doc("bad_range").set({
      deviceId: DEVICE_ID,
      ownerUid: ALICE_UID,
      latitude: 120.0,
      longitude: 30.0,
      timestamp: 1700000000000,
    })
  );

  await assertFails(
    breadcrumbs.doc("bad_owner").set({
      deviceId: DEVICE_ID,
      ownerUid: BOB_UID,
      latitude: -1.9441,
      longitude: 30.0619,
      timestamp: 1700000000000,
    })
  );
});
