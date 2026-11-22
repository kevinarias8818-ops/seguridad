import { createRequire } from "node:module";
import { test, before, after, beforeEach } from "node:test";
import fs from "node:fs";

const require = createRequire(import.meta.url);
const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ADMIN_UID = "admin_123";
const OPERATOR_UID = "operator_456";
const DIAMANTE_UID = "diamante_789";
const PENDING_UID = "pending_999";

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

async function seedUsers() {
  const past = new Date(Date.now() - 10000);
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("users").doc(ADMIN_UID).set({
      userId: ADMIN_UID,
      email: "admin@unidad.local",
      displayName: "Admin Principal",
      role: "ADMIN",
      unitId: "UNIDAD_PRINCIPAL",
      active: true,
      createdAt: past,
      updatedAt: past,
    });
    await db.collection("users").doc(OPERATOR_UID).set({
      userId: OPERATOR_UID,
      email: "operador@unidad.local",
      displayName: "Operador Garita",
      role: "OPERATOR",
      unitId: "UNIDAD_PRINCIPAL",
      active: true,
      createdAt: past,
      updatedAt: past,
    });
    await db.collection("users").doc(DIAMANTE_UID).set({
      userId: DIAMANTE_UID,
      email: "diamante@unidad.local",
      displayName: "Diamante Alma",
      role: "DIAMANTE_ALMA",
      unitId: "UNIDAD_PRINCIPAL",
      active: true,
      createdAt: past,
      updatedAt: past,
    });
    await db.collection("users").doc(PENDING_UID).set({
      userId: PENDING_UID,
      email: "pendiente@unidad.local",
      displayName: "Usuario Pendiente",
      role: "PENDING",
      unitId: "UNIDAD_PRINCIPAL",
      active: false,
      createdAt: past,
      updatedAt: past,
    });
  });
}

test("Unauthenticated user cannot read or delete users", async () => {
  await seedUsers();
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(OPERATOR_UID).get());
  await assertFails(unauthDb.collection("users").doc(OPERATOR_UID).delete());
});

test("Admin can delete other users, cannot delete self, and deleted user can re-register with same email", async () => {
  await seedUsers();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();
  const operatorDb = testEnv.authenticatedContext(OPERATOR_UID).firestore();

  // Non-admin roles cannot delete any user
  const diamanteDb = testEnv.authenticatedContext(DIAMANTE_UID).firestore();
  await assertFails(diamanteDb.collection("users").doc(OPERATOR_UID).delete());
  await assertFails(operatorDb.collection("users").doc(PENDING_UID).delete());

  // Admin cannot delete themselves
  await assertFails(adminDb.collection("users").doc(ADMIN_UID).delete());

  // Admin deletes operator profile
  await assertSucceeds(adminDb.collection("users").doc(OPERATOR_UID).delete());

  // Deleted user can sign back in with the same UID/email and re-register as PENDING
  const now = new Date(Date.now() - 1000);
  await assertSucceeds(
    operatorDb.collection("users").doc(OPERATOR_UID).set({
      userId: OPERATOR_UID,
      email: "operador@unidad.local",
      displayName: "Operador Garita",
      role: "PENDING",
      unitId: "UNIDAD_PRINCIPAL",
      active: false,
      createdAt: now,
      updatedAt: now,
    })
  );
});

test("Deleted user loses staff access to /entries until re-approved by Admin", async () => {
  await seedUsers();
  const past = new Date(Date.now() - 10000);
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("entries").doc("entry_1").set({
      unitId: "UNIDAD_PRINCIPAL",
      entryDate: "2026-11-22",
      rank: "Cabo",
      fullName: "Juan Perez",
      documentNumber: "10203040",
      plate: "ABC123",
      destinationUnit: "Comandancia",
      reason: "Relevo",
      visitorType: "VISITANTE",
      expectedTime: "08:00",
      source: "MANUAL",
      notes: "",
      status: "PENDIENTE",
      createdByUid: ADMIN_UID,
      createdByName: "Admin Principal",
      createdAt: past,
      updatedAt: past,
    });
  });

  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();
  const operatorDb = testEnv.authenticatedContext(OPERATOR_UID).firestore();

  // Before deletion, active operator can list entries for unitId
  await assertSucceeds(
    operatorDb.collection("entries").where("unitId", "==", "UNIDAD_PRINCIPAL").get()
  );

  // Admin deletes operator
  await assertSucceeds(adminDb.collection("users").doc(OPERATOR_UID).delete());

  // Immediately after deletion, operator cannot list entries
  await assertFails(
    operatorDb.collection("entries").where("unitId", "==", "UNIDAD_PRINCIPAL").get()
  );
});
