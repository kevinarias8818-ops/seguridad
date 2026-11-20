import {
  collection,
  deleteDoc,
  deleteField,
  doc,
  getDoc,
  onSnapshot,
  query,
  serverTimestamp,
  setDoc,
  updateDoc,
  where,
  writeBatch,
} from 'firebase/firestore';
import {
  BOOTSTRAP_ADMIN_EMAIL,
  UNIT_ID,
  db,
} from './firebase.js';

export function todayIsoDate() {
  const now = new Date();
  const y = now.getFullYear();
  const m = String(now.getMonth() + 1).padStart(2, '0');
  const d = String(now.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

export function isValidIsoDate(value) {
  return /^\d{4}-\d{2}-\d{2}$/.test(value || '');
}

export function formatTimeHm(ts) {
  if (!ts) return '—';
  const date = typeof ts.toDate === 'function' ? ts.toDate() : new Date(ts);
  if (Number.isNaN(date.getTime())) return '—';
  return date.toLocaleTimeString('es-ES', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });
}

export function formatDateTimeShort(ts) {
  if (!ts) return '—';
  const date = typeof ts.toDate === 'function' ? ts.toDate() : new Date(ts);
  if (Number.isNaN(date.getTime())) return '—';
  return date.toLocaleString('es-ES', {
    day: '2-digit',
    month: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });
}

export function getTimestampMillis(ts) {
  if (!ts) return 0;
  if (typeof ts.toMillis === 'function') return ts.toMillis();
  if (typeof ts.toDate === 'function') return ts.toDate().getTime();
  return 0;
}

export function getEntryHour(entry) {
  if (entry.status === 'INGRESO' && entry.checkInAt) {
    const date =
      typeof entry.checkInAt.toDate === 'function'
        ? entry.checkInAt.toDate()
        : new Date(entry.checkInAt);
    if (!Number.isNaN(date.getTime())) return date.getHours();
  }
  const match = (entry.expectedTime || '').trim().match(/^(\d{1,2})[:h]/);
  if (match) {
    const hour = Number.parseInt(match[1], 10);
    if (hour >= 0 && hour <= 23) return hour;
  }
  if (entry.createdAt) {
    const date =
      typeof entry.createdAt.toDate === 'function'
        ? entry.createdAt.toDate()
        : new Date(entry.createdAt);
    if (!Number.isNaN(date.getTime())) return date.getHours();
  }
  return null;
}

export async function ensureUserProfile(firebaseUser) {
  const userRef = doc(db, 'users', firebaseUser.uid);
  const snap = await getDoc(userRef);

  const rawEmail = (firebaseUser.email || '').trim().toLowerCase();
  const safeEmail = rawEmail.length >= 3 ? rawEmail.slice(0, 200) : `${firebaseUser.uid}@usuario.local`;
  const rawName = (firebaseUser.displayName || '').trim();
  const safeName = (rawName || safeEmail.split('@')[0] || 'Usuario').slice(0, 100);

  if (snap.exists()) {
    const data = snap.data();
    return { id: snap.id, ...data };
  }

  const isBootAdmin =
    safeEmail === BOOTSTRAP_ADMIN_EMAIL.toLowerCase() && firebaseUser.emailVerified === true;

  const profilePayload = {
    userId: firebaseUser.uid,
    email: safeEmail,
    displayName: safeName,
    role: isBootAdmin ? 'ADMIN' : 'PENDING',
    unitId: UNIT_ID,
    active: isBootAdmin,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  };

  await setDoc(userRef, profilePayload);
  return { id: firebaseUser.uid, ...profilePayload };
}

export function subscribeUserProfile(uid, onData, onError) {
  const userRef = doc(db, 'users', uid);
  return onSnapshot(
    userRef,
    (snap) => {
      if (!snap.exists()) {
        onData(null);
        return;
      }
      onData({ id: snap.id, ...snap.data() });
    },
    onError
  );
}

export function subscribeEntries(unitId, dateIsoOrNull, onData, onError) {
  const colRef = collection(db, 'entries');
  const constraints = [where('unitId', '==', unitId)];
  if (dateIsoOrNull) {
    constraints.push(where('entryDate', '==', dateIsoOrNull));
  }
  const q = query(colRef, ...constraints);

  return onSnapshot(
    q,
    (snap) => {
      const items = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
      items.sort((a, b) => {
        if (a.status !== b.status) {
          return a.status === 'PENDIENTE' ? -1 : 1;
        }
        if ((a.entryDate || '') !== (b.entryDate || '')) {
          return (b.entryDate || '').localeCompare(a.entryDate || '');
        }
        if (a.status === 'PENDIENTE') {
          const tA = (a.expectedTime || '99:99').trim();
          const tB = (b.expectedTime || '99:99').trim();
          if (tA !== tB) return tA.localeCompare(tB);
        } else {
          const cA = getTimestampMillis(a.checkInAt);
          const cB = getTimestampMillis(b.checkInAt);
          if (cA !== cB) return cB - cA;
        }
        return getTimestampMillis(b.createdAt) - getTimestampMillis(a.createdAt);
      });
      onData(items);
    },
    onError
  );
}

export function subscribeAllUsers(onData, onError) {
  const q = query(collection(db, 'users'));
  return onSnapshot(
    q,
    (snap) => {
      const roleOrder = { PENDING: 0, ADMIN: 1, OPERATOR: 2 };
      const list = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
      list.sort((a, b) => {
        const rA = roleOrder[a.role] ?? 9;
        const rB = roleOrder[b.role] ?? 9;
        if (rA !== rB) return rA - rB;
        return (a.displayName || '').localeCompare(b.displayName || '', 'es');
      });
      onData(list);
    },
    onError
  );
}

function buildSanitizedCreatePayload(draft, currentUser) {
  const safeName = (draft.fullName || '').trim().slice(0, 120);
  if (safeName.length < 2) {
    throw new Error('El nombre debe tener al menos 2 caracteres.');
  }
  const entryDate = isValidIsoDate(draft.entryDate) ? draft.entryDate : todayIsoDate();
  const safeDoc = ((draft.documentNumber || '').trim() || 'S/D').slice(0, 40);
  const safeDest = ((draft.destinationUnit || '').trim() || 'Dependencia Principal').slice(0, 80);
  const safeCreatorName = ((currentUser.displayName || currentUser.email || 'Admin').trim()).slice(0, 120);

  return {
    unitId: currentUser.unitId || UNIT_ID,
    entryDate,
    rank: (draft.rank || '').trim().slice(0, 40),
    fullName: safeName,
    documentNumber: safeDoc,
    plate: (draft.plate || '').trim().toUpperCase().slice(0, 20),
    destinationUnit: safeDest,
    reason: (draft.reason || '').trim().slice(0, 160),
    visitorType: draft.visitorType || 'VISITANTE',
    expectedTime: (draft.expectedTime || '').trim().slice(0, 20),
    source: draft.source || 'MANUAL',
    notes: (draft.notes || '').trim().slice(0, 300),
    status: 'PENDIENTE',
    createdByUid: currentUser.userId,
    createdByName: safeCreatorName,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  };
}

export async function createEntry(draft, currentUser) {
  const newRef = doc(collection(db, 'entries'));
  const payload = buildSanitizedCreatePayload(draft, currentUser);
  await setDoc(newRef, payload);
  return newRef.id;
}

export async function importEntriesBatch(drafts, currentUser) {
  const chunkSize = 450;
  let total = 0;
  for (let i = 0; i < drafts.length; i += chunkSize) {
    const chunk = drafts.slice(i, i + chunkSize);
    const batch = writeBatch(db);
    for (const draft of chunk) {
      const ref = doc(collection(db, 'entries'));
      const payload = buildSanitizedCreatePayload(
        { ...draft, source: 'CORREO_INSTITUCIONAL' },
        currentUser
      );
      batch.set(ref, payload);
      total += 1;
    }
    await batch.commit();
  }
  return total;
}

export async function updateEntry(entry, draft, currentUser) {
  const safeName = (draft.fullName || '').trim().slice(0, 120);
  if (safeName.length < 2) {
    throw new Error('El nombre debe tener al menos 2 caracteres.');
  }
  const nextStatus = draft.status === 'INGRESO' ? 'INGRESO' : 'PENDIENTE';
  const entryDate = isValidIsoDate(draft.entryDate) ? draft.entryDate : entry.entryDate;

  const updates = {
    entryDate,
    rank: (draft.rank || '').trim().slice(0, 40),
    fullName: safeName,
    documentNumber: ((draft.documentNumber || '').trim() || 'S/D').slice(0, 40),
    plate: (draft.plate || '').trim().toUpperCase().slice(0, 20),
    destinationUnit: ((draft.destinationUnit || '').trim() || 'Dependencia Principal').slice(0, 80),
    reason: (draft.reason || '').trim().slice(0, 160),
    visitorType: draft.visitorType || 'VISITANTE',
    expectedTime: (draft.expectedTime || '').trim().slice(0, 20),
    notes: (draft.notes || '').trim().slice(0, 300),
    status: nextStatus,
    updatedAt: serverTimestamp(),
  };

  if (nextStatus === 'PENDIENTE' && entry.status === 'INGRESO') {
    updates.checkInAt = deleteField();
    updates.checkInByUid = deleteField();
    updates.checkInByName = deleteField();
  } else if (nextStatus === 'INGRESO' && entry.status === 'PENDIENTE') {
    updates.checkInAt = serverTimestamp();
    updates.checkInByUid = currentUser.userId;
    updates.checkInByName = ((currentUser.displayName || currentUser.email || 'Admin').trim()).slice(0, 120);
  }

  await updateDoc(doc(db, 'entries', entry.id), updates);
}

export async function markCheckInNow(entryId, currentUser) {
  const safeCheckerName = ((currentUser.displayName || currentUser.email || 'Operador').trim()).slice(0, 120);
  await updateDoc(doc(db, 'entries', entryId), {
    status: 'INGRESO',
    checkInAt: serverTimestamp(),
    checkInByUid: currentUser.userId,
    checkInByName: safeCheckerName,
    updatedAt: serverTimestamp(),
  });
}

export async function deleteEntry(entryId) {
  await deleteDoc(doc(db, 'entries', entryId));
}

export async function updateUserRoleAndStatus(targetUserId, role, active) {
  await updateDoc(doc(db, 'users', targetUserId), {
    role,
    active,
    updatedAt: serverTimestamp(),
  });
}

export function humanizeFirebaseError(error) {
  const code = error?.code || '';
  const msg = error?.message || '';
  if (code === 'permission-denied' || msg.includes('PERMISSION_DENIED')) {
    return 'Permisos insuficientes en Firestore. Verifica que tu usuario esté aprobado por el Administrador.';
  }
  if (code === 'auth/unauthorized-domain') {
    return 'Dominio no autorizado en Firebase Authentication. Agrega este dominio en Firebase Console > Authentication > Settings.';
  }
  if (code === 'auth/invalid-api-key' || msg.includes('appId') || msg.includes('REEMPLAZAR_CON_WEB_APP_ID')) {
    return 'Configura el App ID Web real en src/firebase.js desde Firebase Console (gen-lang-client-0597164698).';
  }
  if (
    code === 'auth/popup-closed-by-user' ||
    code === 'auth/redirect-cancelled-by-user'
  ) {
    return 'Inicio de sesión cancelado.';
  }
  if (code === 'auth/redirect-operation-pending') {
    return 'Ya hay una redirección de inicio de sesión en curso. Espera un momento.';
  }
  if (code === 'unavailable') {
    return 'Sin conexión con Firebase. Verifica tu acceso a internet.';
  }
  return msg || 'Ocurrió un error inesperado con Firebase.';
}
