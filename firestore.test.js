const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ADMIN_UID = "admin_uid_001";
const ADMIN_EMAIL = "kevinarias.8818@gmail.com";
const OP_UID = "operator_uid_002";
const OP_EMAIL = "garita.turno1@unidad.com";
const OTHER_UID = "outsider_uid_003";
const UNIT_ID = "UNIDAD_PRINCIPAL";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function pastDate(offsetMs = 2000) {
  return new Date(Date.now() - offsetMs);
}

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

async function seedUsers() {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    const ts = pastDate(10000);
    await db.collection("users").doc(ADMIN_UID).set({
      userId: ADMIN_UID,
      email: ADMIN_EMAIL,
      displayName: "Kevin Arias",
      role: "ADMIN",
      unitId: UNIT_ID,
      active: true,
      createdAt: ts,
      updatedAt: ts,
    });
    await db.collection("users").doc(OP_UID).set({
      userId: OP_UID,
      email: OP_EMAIL,
      displayName: "Operador Turno 1",
      role: "OPERATOR",
      unitId: UNIT_ID,
      active: true,
      createdAt: ts,
      updatedAt: ts,
    });
  });
}

test("Unauthenticated users cannot read or write users or entries", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("entries").where("unitId", "==", UNIT_ID).get());
  await assertFails(unauthDb.collection("users").doc(ADMIN_UID).get());
  await assertFails(
    unauthDb.collection("entries").doc("entry_unauth").set({
      unitId: UNIT_ID,
      entryDate: "2026-11-18",
      fullName: "Intruder",
      documentNumber: "999",
      plate: "",
      destinationUnit: "Torre A",
      visitorType: "VISITANTE",
      expectedTime: "09:00",
      source: "MANUAL",
      notes: "",
      status: "PENDIENTE",
      createdByUid: ADMIN_UID,
      createdByName: "Admin",
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );
});

test("New users register only as PENDING and cannot read entries until Admin assigns OPERATOR", async () => {
  const ts = pastDate();
  const attackerDb = testEnv
    .authenticatedContext(OTHER_UID, { email: ADMIN_EMAIL, email_verified: false })
    .firestore();

  // Spoofed unverified admin email cannot self-assign ADMIN or OPERATOR
  await assertFails(
    attackerDb.collection("users").doc(OTHER_UID).set({
      userId: OTHER_UID,
      email: ADMIN_EMAIL,
      displayName: "Spoofed Admin",
      role: "ADMIN",
      unitId: UNIT_ID,
      active: true,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Regular user CANNOT self-assign OPERATOR on sign-up
  const opDb = testEnv
    .authenticatedContext(OP_UID, { email: OP_EMAIL, email_verified: true })
    .firestore();
  await assertFails(
    opDb.collection("users").doc(OP_UID).set({
      userId: OP_UID,
      email: OP_EMAIL,
      displayName: "Operador Turno 1",
      role: "OPERATOR",
      unitId: UNIT_ID,
      active: true,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Regular user self-registers strictly as PENDING (active: false)
  await assertSucceeds(
    opDb.collection("users").doc(OP_UID).set({
      userId: OP_UID,
      email: OP_EMAIL,
      displayName: "Operador Turno 1",
      role: "PENDING",
      unitId: UNIT_ID,
      active: false,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // PENDING user CANNOT read entries or escalate their own role
  await assertFails(
    opDb.collection("entries").where("unitId", "==", UNIT_ID).where("entryDate", "==", "2026-11-18").get()
  );
  await assertFails(
    opDb.collection("users").doc(OP_UID).update({
      role: "OPERATOR",
      active: true,
      updatedAt: pastDate(500),
    })
  );
  await assertFails(opDb.collection("users").get());

  // Verified bootstrapped admin can self-register as ADMIN
  const adminDb = testEnv
    .authenticatedContext(ADMIN_UID, { email: ADMIN_EMAIL, email_verified: true })
    .firestore();
  await assertSucceeds(
    adminDb.collection("users").doc(ADMIN_UID).set({
      userId: ADMIN_UID,
      email: ADMIN_EMAIL,
      displayName: "Kevin Arias",
      role: "ADMIN",
      unitId: UNIT_ID,
      active: true,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Admin can list users and approve PENDING user as active OPERATOR
  await assertSucceeds(adminDb.collection("users").get());
  await assertSucceeds(
    adminDb.collection("users").doc(OP_UID).update({
      role: "OPERATOR",
      active: true,
      updatedAt: pastDate(500),
    })
  );

  // Now approved OPERATOR can query today's entries
  await assertSucceeds(
    opDb.collection("entries").where("unitId", "==", UNIT_ID).where("entryDate", "==", "2026-11-18").get()
  );
});

test("Admin can create, edit, query by unitId, and delete entries; unfiltered query fails", async () => {
  await seedUsers();
  const adminDb = testEnv
    .authenticatedContext(ADMIN_UID, { email: ADMIN_EMAIL, email_verified: true })
    .firestore();

  const ts = pastDate(5000);
  const validCreate = {
    unitId: UNIT_ID,
    entryDate: "2026-11-18",
    rank: "Tte.",
    fullName: "Carlos Mendoza",
    documentNumber: "1020304050",
    plate: "KLM-482",
    destinationUnit: "Comandancia - Bloque B",
    reason: "Reunión de coordinación",
    visitorType: "VISITANTE",
    expectedTime: "08:30",
    source: "CORREO_INSTITUCIONAL",
    notes: "Autorizado por gerencia",
    status: "PENDIENTE",
    createdByUid: ADMIN_UID,
    createdByName: "Kevin Arias",
    createdAt: ts,
    updatedAt: ts,
  };

  await assertSucceeds(adminDb.collection("entries").doc("entry_001").set(validCreate));

  // Ghost field / @DocumentId 'id' field rejected
  await assertFails(
    adminDb.collection("entries").doc("entry_002").set({
      ...validCreate,
      id: "entry_002",
    })
  );

  // Unfiltered query fails; unitId-filtered query succeeds
  await assertFails(adminDb.collection("entries").get());
  await assertSucceeds(adminDb.collection("entries").where("unitId", "==", UNIT_ID).get());

  // Admin partial update succeeds; createdAt mutation fails
  await assertSucceeds(
    adminDb.collection("entries").doc("entry_001").update({
      rank: "Cap.",
      plate: "KLM-999",
      reason: "Inspección técnica",
      updatedAt: pastDate(1000),
    })
  );
  await assertFails(
    adminDb.collection("entries").doc("entry_001").update({
      createdAt: pastDate(500),
      updatedAt: pastDate(500),
    })
  );

  // Admin can delete
  await assertSucceeds(adminDb.collection("entries").doc("entry_001").delete());
});

test("Operator can query today's entries and mark check-in once, but cannot create, edit fields, or delete", async () => {
  await seedUsers();
  const ts = pastDate(5000);
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("entries").doc("entry_op_1").set({
      unitId: UNIT_ID,
      entryDate: "2026-11-18",
      fullName: "Laura Gómez",
      documentNumber: "55443322",
      plate: "",
      destinationUnit: "Recepción Piso 2",
      visitorType: "PERSONAL",
      expectedTime: "07:45",
      source: "MANUAL",
      notes: "",
      status: "PENDIENTE",
      createdByUid: ADMIN_UID,
      createdByName: "Kevin Arias",
      createdAt: ts,
      updatedAt: ts,
    });
  });

  const opDb = testEnv
    .authenticatedContext(OP_UID, { email: OP_EMAIL, email_verified: true })
    .firestore();

  // Operator query for today's entries succeeds
  await assertSucceeds(
    opDb
      .collection("entries")
      .where("unitId", "==", UNIT_ID)
      .where("entryDate", "==", "2026-11-18")
      .get()
  );

  // Operator cannot create or delete
  await assertFails(
    opDb.collection("entries").doc("entry_op_create").set({
      unitId: UNIT_ID,
      entryDate: "2026-11-18",
      fullName: "No Permitido",
      documentNumber: "111",
      plate: "",
      destinationUnit: "Torre A",
      visitorType: "VISITANTE",
      expectedTime: "10:00",
      source: "MANUAL",
      notes: "",
      status: "PENDIENTE",
      createdByUid: OP_UID,
      createdByName: "Operador",
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );
  await assertFails(opDb.collection("entries").doc("entry_op_1").delete());

  // Operator cannot edit name or notes
  await assertFails(
    opDb.collection("entries").doc("entry_op_1").update({
      fullName: "Nombre Alterado",
      updatedAt: pastDate(1000),
    })
  );

  // Operator cannot spoof checkInByUid
  await assertFails(
    opDb.collection("entries").doc("entry_op_1").update({
      status: "INGRESO",
      checkInAt: pastDate(1000),
      checkInByUid: ADMIN_UID,
      checkInByName: "Kevin Arias",
      updatedAt: pastDate(1000),
    })
  );

  // Operator valid check-in succeeds
  await assertSucceeds(
    opDb.collection("entries").doc("entry_op_1").update({
      status: "INGRESO",
      checkInAt: pastDate(1000),
      checkInByUid: OP_UID,
      checkInByName: "Operador Turno 1",
      updatedAt: pastDate(1000),
    })
  );

  // Terminal state lock: Operator cannot re-check-in or revert an already checked-in entry
  await assertFails(
    opDb.collection("entries").doc("entry_op_1").update({
      status: "PENDIENTE",
      updatedAt: pastDate(500),
    })
  );
});
