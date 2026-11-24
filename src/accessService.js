import {
  collection,
  deleteDoc,
  deleteField,
  doc,
  getDoc,
  getDocFromCache,
  getDocs,
  getDocsFromCache,
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

const ONLINE_WRITE_TIMEOUT_MS = 4500;
const OFFLINE_WRITE_GRACE_MS = 350;

export function isWebOnline() {
  return typeof navigator === 'undefined' ? true : navigator.onLine !== false;
}

export function isSafePlateImageDataUrl(raw) {
  if (typeof raw !== 'string') return false;
  const clean = raw.trim();
  if (clean.length < 29 || clean.length > 250000) return false;
  return (
    clean.startsWith('data:image/jpeg;base64,') ||
    clean.startsWith('data:image/png;base64,') ||
    clean.startsWith('data:image/webp;base64,')
  );
}

async function withTimeout(promise, ms) {
  let timerId;
  try {
    return await Promise.race([
      promise,
      new Promise((_, reject) => {
        timerId = setTimeout(() => {
          const err = new Error('OFFLINE_TIMEOUT');
          err.code = 'deadline-exceeded';
          reject(err);
        }, ms);
      }),
    ]);
  } finally {
    if (timerId !== undefined) clearTimeout(timerId);
  }
}

async function awaitWriteWithOfflineGrace(promise) {
  const graceMs = isWebOnline() ? ONLINE_WRITE_TIMEOUT_MS : OFFLINE_WRITE_GRACE_MS;
  let queuedOffline = false;
  let timerId;
  await Promise.race([
    promise,
    new Promise((resolve) => {
      timerId = setTimeout(() => {
        queuedOffline = true;
        resolve();
      }, graceMs);
    }),
  ]);
  if (timerId !== undefined) clearTimeout(timerId);
  return { queuedOffline };
}

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

export function currentTimeHm(now = new Date()) {
  const h = String(now.getHours()).padStart(2, '0');
  const m = String(now.getMinutes()).padStart(2, '0');
  return `${h}:${m}`;
}

export function formatDocumentNumberInput(raw = '') {
  return String(raw).replace(/\D/g, '').slice(0, 20);
}

export function formatPlateInput(raw = '') {
  return String(raw)
    .toUpperCase()
    .replace(/[^A-Z0-9]/g, '')
    .slice(0, 10);
}

export function formatEntryTimeDigits(previous = '', rawNext = '') {
  const digits = String(rawNext).replace(/\D/g, '').slice(0, 4);
  const isDeleting = String(rawNext).length < String(previous).length;
  if (!digits) return '';
  if (digits.length === 1) return digits;
  if (digits.length === 2) {
    if (isDeleting && String(previous).endsWith(':')) {
      return digits.slice(0, 1);
    }
    return `${digits}:`;
  }
  return `${digits.slice(0, 2)}:${digits.slice(2)}`;
}

export function normalizeExistingTime(raw = '') {
  const trimmed = String(raw || '').trim();
  if (!trimmed) return '';
  const digits = trimmed.replace(/\D/g, '');
  if (digits.length === 3) return `0${digits.slice(0, 1)}:${digits.slice(1)}`;
  if (digits.length === 4) return `${digits.slice(0, 2)}:${digits.slice(2)}`;
  return trimmed.slice(0, 5);
}

export function isValidEntryTime(raw = '') {
  const trimmed = String(raw || '').trim();
  if (!trimmed) return true;
  return /^([01]\d|2[0-3]):[0-5]\d$/.test(trimmed);
}

function normalizeDocumentForMatch(raw = '') {
  const digits = String(raw || '').replace(/\D/g, '');
  return digits || String(raw || '').trim().toUpperCase();
}

function normalizePlateForMatch(raw = '') {
  return String(raw || '')
    .toUpperCase()
    .replace(/[^A-Z0-9]/g, '');
}

export function findActiveOnSiteDuplicates(
  activeEntries = [],
  documentNumber = '',
  plate = '',
  excludeEntryId = null
) {
  const cleanDoc = normalizeDocumentForMatch(documentNumber);
  const cleanPlate = normalizePlateForMatch(plate);
  if (!cleanDoc && !cleanPlate) return [];

  const matches = [];
  for (const entry of activeEntries) {
    if (!entry || entry.status !== 'INGRESO') continue;
    if (excludeEntryId && entry.id === excludeEntryId) continue;
    const entryDoc = normalizeDocumentForMatch(entry.documentNumber || '');
    const entryPlate = normalizePlateForMatch(entry.plate || '');
    const matchedByDocument = Boolean(cleanDoc && entryDoc === cleanDoc);
    const matchedByPlate = Boolean(cleanPlate && entryPlate === cleanPlate);
    if (matchedByDocument || matchedByPlate) {
      matches.push({ entry, matchedByDocument, matchedByPlate });
    }
  }
  return matches;
}

export function subscribeOnSiteEntries(unitId, onData, onError) {
  const q = query(collection(db, 'entries'), where('unitId', '==', unitId || UNIT_ID));
  return onSnapshot(
    q,
    { includeMetadataChanges: true },
    (snap) => {
      const items = snap.docs
        .map((d) => ({ id: d.id, ...d.data() }))
        .filter((e) => e.status === 'INGRESO');
      onData(items);
    },
    onError
  );
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
  if ((entry.status === 'INGRESO' || entry.status === 'SALIDA') && entry.checkInAt) {
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
  let snap = null;

  if (!isWebOnline()) {
    try {
      snap = await getDocFromCache(userRef);
    } catch {
      snap = null;
    }
  } else {
    try {
      snap = await withTimeout(getDoc(userRef), 4000);
    } catch (err) {
      if (err?.code === 'unavailable' || err?.code === 'deadline-exceeded') {
        try {
          snap = await getDocFromCache(userRef);
        } catch {
          snap = null;
        }
      } else {
        throw err;
      }
    }
  }

  const rawEmail = (firebaseUser.email || '').trim().toLowerCase();
  const safeEmail = rawEmail.length >= 3 ? rawEmail.slice(0, 200) : `${firebaseUser.uid}@usuario.local`;
  const rawName = (firebaseUser.displayName || '').trim();
  const safeName = (rawName || safeEmail.split('@')[0] || 'Usuario').slice(0, 100);

  if (snap && snap.exists()) {
    const data = snap.data();
    return { id: snap.id, ...data };
  }

  if (!isWebOnline()) {
    throw new Error(
      'Sin conexión a internet para registrar tu perfil por primera vez. Conéctate una vez para habilitar el modo offline en la guardia.'
    );
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
    { includeMetadataChanges: true },
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
    { includeMetadataChanges: true },
    (snap) => {
      const pendingSyncIds = new Set();
      const items = snap.docs.map((d) => {
        if (d.metadata?.hasPendingWrites) {
          pendingSyncIds.add(d.id);
        }
        return { id: d.id, ...d.data() };
      });
      const statusOrder = { PENDIENTE: 0, INGRESO: 1, SALIDA: 2 };
      items.sort((a, b) => {
        const sA = statusOrder[a.status] ?? 9;
        const sB = statusOrder[b.status] ?? 9;
        if (sA !== sB) {
          return sA - sB;
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
      onData({
        items,
        fromCache: Boolean(snap.metadata?.fromCache),
        pendingSyncIds,
      });
    },
    onError
  );
}

export async function fetchAllEntriesForUnit(unitId) {
  const q = query(collection(db, 'entries'), where('unitId', '==', unitId || UNIT_ID));
  let snap;
  if (!isWebOnline()) {
    snap = await getDocsFromCache(q);
  } else {
    try {
      snap = await withTimeout(getDocs(q), 5000);
    } catch (err) {
      if (err?.code === 'unavailable' || err?.code === 'deadline-exceeded') {
        snap = await getDocsFromCache(q);
      } else {
        throw err;
      }
    }
  }
  return snap.docs.map((d) => ({ id: d.id, ...d.data() }));
}

export function subscribeAllUsers(onData, onError) {
  const q = query(collection(db, 'users'));
  return onSnapshot(
    q,
    { includeMetadataChanges: true },
    (snap) => {
      const roleOrder = { PENDING: 0, ADMIN: 1, DIAMANTE_ALMA: 2, OPERATOR: 3 };
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

const MAX_PLATE_DIM_PX = 640;
const MAX_PLATE_DATA_URL_CHARS = 220000;

function validatePlateAttachment(plateImageName, plateImageData) {
  const cleanData = (plateImageData || '').trim();
  if (!cleanData) return null;
  if (!isSafePlateImageDataUrl(cleanData)) {
    throw new Error('La imagen de la placa debe ser JPEG, PNG o WEBP y no superar el tamaño permitido.');
  }
  const cleanName = (plateImageName || '').trim().slice(0, 160) || 'placa.jpg';
  return { fileName: cleanName, dataUrl: cleanData };
}

export function compressPlateImageFile(file) {
  return new Promise((resolve, reject) => {
    const fileType = (file?.type || '').toLowerCase();
    const isLikelyImage =
      fileType.startsWith('image/') ||
      (!fileType && /\.(jpe?g|png|webp|heic|heif|bmp|gif)$/i.test(file?.name || ''));
    if (!file || (fileType && !fileType.startsWith('image/')) || !isLikelyImage) {
      reject(new Error('Selecciona un archivo de imagen válido (JPG, PNG, WEBP o foto).'));
      return;
    }

    const loadImageFromSrc = (src, revokeFn) => {
      const img = new Image();
      img.onerror = () => {
        revokeFn?.();
        reject(
          new Error(
            'El navegador no pudo abrir esta imagen. En iPhone/iPad o PC prueba con formato JPG/PNG/WEBP.'
          )
        );
      };
      img.onload = () => {
        try {
          const scale = Math.min(1, MAX_PLATE_DIM_PX / Math.max(img.width || 1, img.height || 1));
          const targetW = Math.max(1, Math.round((img.width || 1) * scale));
          const targetH = Math.max(1, Math.round((img.height || 1) * scale));
          const canvas = document.createElement('canvas');
          canvas.width = targetW;
          canvas.height = targetH;
          const ctx = canvas.getContext('2d');
          if (!ctx) {
            reject(new Error('No se pudo procesar la imagen en este navegador.'));
            return;
          }
          ctx.drawImage(img, 0, 0, targetW, targetH);
          let chosenUrl = '';
          for (const q of [0.78, 0.64, 0.5, 0.36]) {
            const candidate = canvas.toDataURL('image/jpeg', q);
            if (candidate.length <= MAX_PLATE_DATA_URL_CHARS) {
              chosenUrl = candidate;
              break;
            }
          }
          if (!chosenUrl) {
            reject(new Error('La imagen sigue siendo muy pesada después de comprimir.'));
            return;
          }
          const baseName = (file.name || 'placa_vehiculo.jpg').trim().slice(0, 160) || 'placa_vehiculo.jpg';
          const safeName = baseName.replace(/\.(heic|heif)$/i, '.jpg');
          resolve({ fileName: safeName, dataUrl: chosenUrl });
        } finally {
          revokeFn?.();
        }
      };
      img.src = src;
    };

    if (typeof URL !== 'undefined' && typeof URL.createObjectURL === 'function') {
      const objectUrl = URL.createObjectURL(file);
      loadImageFromSrc(objectUrl, () => URL.revokeObjectURL(objectUrl));
      return;
    }

    const reader = new FileReader();
    reader.onerror = () => reject(new Error('No se pudo leer el archivo seleccionado.'));
    reader.onload = () => loadImageFromSrc(String(reader.result || ''), null);
    reader.readAsDataURL(file);
  });
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

  const payload = {
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

  const validImage = validatePlateAttachment(draft.plateImageName, draft.plateImageData);
  if (validImage) {
    payload.plateImageName = validImage.fileName;
    payload.plateImageData = validImage.dataUrl;
  }

  return payload;
}

export async function createEntry(draft, currentUser) {
  const newRef = doc(collection(db, 'entries'));
  const payload = buildSanitizedCreatePayload(draft, currentUser);
  const { queuedOffline } = await awaitWriteWithOfflineGrace(setDoc(newRef, payload));
  return { id: newRef.id, queuedOffline };
}

export async function importEntriesBatch(drafts, currentUser) {
  const chunkSize = 450;
  let total = 0;
  let anyQueuedOffline = false;
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
    const { queuedOffline } = await awaitWriteWithOfflineGrace(batch.commit());
    if (queuedOffline) anyQueuedOffline = true;
  }
  return { count: total, queuedOffline: anyQueuedOffline };
}

export async function updateEntry(entry, draft, currentUser) {
  const safeName = (draft.fullName || '').trim().slice(0, 120);
  if (safeName.length < 2) {
    throw new Error('El nombre debe tener al menos 2 caracteres.');
  }
  const nextStatus =
    draft.status === 'SALIDA'
      ? 'SALIDA'
      : draft.status === 'INGRESO'
      ? 'INGRESO'
      : 'PENDIENTE';
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

  const validImage = validatePlateAttachment(draft.plateImageName, draft.plateImageData);
  if (validImage) {
    updates.plateImageName = validImage.fileName;
    updates.plateImageData = validImage.dataUrl;
  } else if (entry.plateImageData || entry.plateImageName) {
    updates.plateImageName = deleteField();
    updates.plateImageData = deleteField();
  }

  const actorName = ((currentUser.displayName || currentUser.email || 'Admin').trim()).slice(0, 120);

  if (nextStatus === 'PENDIENTE') {
    if (entry.checkInAt || entry.checkInByUid || entry.checkInByName) {
      updates.checkInAt = deleteField();
      updates.checkInByUid = deleteField();
      updates.checkInByName = deleteField();
    }
    if (entry.checkOutAt || entry.checkOutByUid || entry.checkOutByName) {
      updates.checkOutAt = deleteField();
      updates.checkOutByUid = deleteField();
      updates.checkOutByName = deleteField();
    }
  } else if (nextStatus === 'INGRESO') {
    if (!entry.checkInAt || !entry.checkInByUid || !entry.checkInByName) {
      updates.checkInAt = serverTimestamp();
      updates.checkInByUid = currentUser.userId;
      updates.checkInByName = actorName;
    }
    if (entry.checkOutAt || entry.checkOutByUid || entry.checkOutByName) {
      updates.checkOutAt = deleteField();
      updates.checkOutByUid = deleteField();
      updates.checkOutByName = deleteField();
    }
  } else if (nextStatus === 'SALIDA') {
    if (!entry.checkInAt || !entry.checkInByUid || !entry.checkInByName) {
      updates.checkInAt = serverTimestamp();
      updates.checkInByUid = currentUser.userId;
      updates.checkInByName = actorName;
    }
    if (entry.status !== 'SALIDA' || !entry.checkOutAt) {
      updates.checkOutAt = serverTimestamp();
      updates.checkOutByUid = currentUser.userId;
      updates.checkOutByName = actorName;
    }
  }

  return awaitWriteWithOfflineGrace(updateDoc(doc(db, 'entries', entry.id), updates));
}

export async function markCheckInNow(entryId, currentUser, plateAttachment = null) {
  const safeCheckerName = ((currentUser.displayName || currentUser.email || 'Operador').trim()).slice(0, 120);
  const updates = {
    status: 'INGRESO',
    checkInAt: serverTimestamp(),
    checkInByUid: currentUser.userId,
    checkInByName: safeCheckerName,
    updatedAt: serverTimestamp(),
  };
  if (plateAttachment) {
    const validImage = validatePlateAttachment(plateAttachment.fileName, plateAttachment.dataUrl);
    if (validImage) {
      updates.plateImageName = validImage.fileName;
      updates.plateImageData = validImage.dataUrl;
    }
  }
  return awaitWriteWithOfflineGrace(updateDoc(doc(db, 'entries', entryId), updates));
}

export async function attachPlateImage(entryId, plateAttachment) {
  const validImage = validatePlateAttachment(plateAttachment?.fileName, plateAttachment?.dataUrl);
  if (!validImage) {
    throw new Error('Selecciona una imagen de placa válida.');
  }
  return awaitWriteWithOfflineGrace(
    updateDoc(doc(db, 'entries', entryId), {
      plateImageName: validImage.fileName,
      plateImageData: validImage.dataUrl,
      updatedAt: serverTimestamp(),
    })
  );
}

export async function markCheckOutNow(entryId, currentUser) {
  const safeCheckerName = ((currentUser.displayName || currentUser.email || 'Operador').trim()).slice(0, 120);
  return awaitWriteWithOfflineGrace(
    updateDoc(doc(db, 'entries', entryId), {
      status: 'SALIDA',
      checkOutAt: serverTimestamp(),
      checkOutByUid: currentUser.userId,
      checkOutByName: safeCheckerName,
      updatedAt: serverTimestamp(),
    })
  );
}

function buildTrashedPayload(entry, currentUser) {
  const safeUid = currentUser?.userId || entry.createdByUid || 'unknown';
  const safeActorName = ((currentUser?.displayName || currentUser?.email || 'Usuario autorizado').trim()).slice(0, 120);
  const safeStatus = entry.status === 'SALIDA' ? 'SALIDA' : entry.status === 'INGRESO' ? 'INGRESO' : 'PENDIENTE';
  const safeEntryDate = isValidIsoDate(entry.entryDate) ? entry.entryDate : todayIsoDate();
  const safeFullName = ((entry.fullName || '').trim() || 'Sin nombre').slice(0, 120);
  const safeCreatorUid = (entry.createdByUid || safeUid).slice(0, 128);
  const safeCreatorName = ((entry.createdByName || safeActorName).trim() || safeActorName).slice(0, 120);

  const payload = {
    unitId: entry.unitId || currentUser?.unitId || UNIT_ID,
    entryDate: safeEntryDate,
    rank: (entry.rank || '').trim().slice(0, 40),
    fullName: safeFullName.length >= 2 ? safeFullName : 'Sin nombre',
    documentNumber: ((entry.documentNumber || '').trim() || 'S/D').slice(0, 40),
    plate: (entry.plate || '').trim().toUpperCase().slice(0, 20),
    destinationUnit: ((entry.destinationUnit || '').trim() || 'Dependencia Principal').slice(0, 80),
    reason: (entry.reason || '').trim().slice(0, 160),
    visitorType: ['PERSONAL', 'VISITANTE', 'PROVEEDOR', 'CONTRATISTA'].includes(entry.visitorType)
      ? entry.visitorType
      : 'VISITANTE',
    expectedTime: (entry.expectedTime || '').trim().slice(0, 20),
    source: entry.source === 'CORREO_INSTITUCIONAL' ? 'CORREO_INSTITUCIONAL' : 'MANUAL',
    notes: (entry.notes || '').trim().slice(0, 300),
    status: safeStatus,
    createdByUid: safeCreatorUid,
    createdByName: safeCreatorName,
    createdAt: entry.createdAt || serverTimestamp(),
    updatedAt: serverTimestamp(),
    deletedAt: serverTimestamp(),
    deletedByUid: safeUid,
    deletedByName: safeActorName,
  };

  const validImage = validatePlateAttachment(entry.plateImageName, entry.plateImageData);
  if (validImage) {
    payload.plateImageName = validImage.fileName;
    payload.plateImageData = validImage.dataUrl;
  }
  if (entry.restoredAt) {
    payload.restoredAt = entry.restoredAt;
  }

  if (safeStatus === 'INGRESO' || safeStatus === 'SALIDA') {
    payload.checkInAt = entry.checkInAt || serverTimestamp();
    payload.checkInByUid = (entry.checkInByUid || safeCreatorUid).slice(0, 128);
    payload.checkInByName = ((entry.checkInByName || safeCreatorName).trim() || safeCreatorName).slice(0, 120);
  }
  if (safeStatus === 'SALIDA') {
    payload.checkOutAt = entry.checkOutAt || serverTimestamp();
    payload.checkOutByUid = (entry.checkOutByUid || safeUid).slice(0, 128);
    payload.checkOutByName = ((entry.checkOutByName || safeActorName).trim() || safeActorName).slice(0, 120);
  }
  return payload;
}

function buildRestoredEntryPayload(trashed, currentUser) {
  const safeUid = currentUser?.userId || trashed.createdByUid || 'unknown';
  const safeActorName = ((currentUser?.displayName || currentUser?.email || 'Administrador').trim()).slice(0, 120);
  const safeStatus = trashed.status === 'SALIDA' ? 'SALIDA' : trashed.status === 'INGRESO' ? 'INGRESO' : 'PENDIENTE';
  const safeEntryDate = isValidIsoDate(trashed.entryDate) ? trashed.entryDate : todayIsoDate();
  const safeFullName = ((trashed.fullName || '').trim() || 'Sin nombre').slice(0, 120);
  const safeCreatorUid = (trashed.createdByUid || safeUid).slice(0, 128);
  const safeCreatorName = ((trashed.createdByName || safeActorName).trim() || safeActorName).slice(0, 120);

  const payload = {
    unitId: trashed.unitId || currentUser?.unitId || UNIT_ID,
    entryDate: safeEntryDate,
    rank: (trashed.rank || '').trim().slice(0, 40),
    fullName: safeFullName.length >= 2 ? safeFullName : 'Sin nombre',
    documentNumber: ((trashed.documentNumber || '').trim() || 'S/D').slice(0, 40),
    plate: (trashed.plate || '').trim().toUpperCase().slice(0, 20),
    destinationUnit: ((trashed.destinationUnit || '').trim() || 'Dependencia Principal').slice(0, 80),
    reason: (trashed.reason || '').trim().slice(0, 160),
    visitorType: ['PERSONAL', 'VISITANTE', 'PROVEEDOR', 'CONTRATISTA'].includes(trashed.visitorType)
      ? trashed.visitorType
      : 'VISITANTE',
    expectedTime: (trashed.expectedTime || '').trim().slice(0, 20),
    source: trashed.source === 'CORREO_INSTITUCIONAL' ? 'CORREO_INSTITUCIONAL' : 'MANUAL',
    notes: (trashed.notes || '').trim().slice(0, 300),
    status: safeStatus,
    createdByUid: safeCreatorUid,
    createdByName: safeCreatorName,
    createdAt: trashed.createdAt || serverTimestamp(),
    updatedAt: serverTimestamp(),
    restoredAt: serverTimestamp(),
  };

  const validImage = validatePlateAttachment(trashed.plateImageName, trashed.plateImageData);
  if (validImage) {
    payload.plateImageName = validImage.fileName;
    payload.plateImageData = validImage.dataUrl;
  }

  if (safeStatus === 'INGRESO' || safeStatus === 'SALIDA') {
    payload.checkInAt = trashed.checkInAt || serverTimestamp();
    payload.checkInByUid = (trashed.checkInByUid || safeCreatorUid).slice(0, 128);
    payload.checkInByName = ((trashed.checkInByName || safeCreatorName).trim() || safeCreatorName).slice(0, 120);
  }
  if (safeStatus === 'SALIDA') {
    payload.checkOutAt = trashed.checkOutAt || serverTimestamp();
    payload.checkOutByUid = (trashed.checkOutByUid || safeUid).slice(0, 128);
    payload.checkOutByName = ((trashed.checkOutByName || safeActorName).trim() || safeActorName).slice(0, 120);
  }
  return payload;
}

export function subscribeTrashedEntries(unitId, onData, onError) {
  const q = query(collection(db, 'trashedEntries'), where('unitId', '==', unitId || UNIT_ID));
  return onSnapshot(
    q,
    { includeMetadataChanges: true },
    (snap) => {
      const items = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
      items.sort((a, b) => getTimestampMillis(b.deletedAt) - getTimestampMillis(a.deletedAt));
      onData(items);
    },
    onError
  );
}

export async function moveEntryToTrash(entry, currentUser) {
  const batch = writeBatch(db);
  const trashedRef = doc(db, 'trashedEntries', entry.id);
  const entryRef = doc(db, 'entries', entry.id);
  batch.set(trashedRef, buildTrashedPayload(entry, currentUser));
  batch.delete(entryRef);
  return awaitWriteWithOfflineGrace(batch.commit());
}

export async function restoreEntryFromTrash(trashedEntry, currentUser) {
  const batch = writeBatch(db);
  const entryRef = doc(db, 'entries', trashedEntry.id);
  const trashedRef = doc(db, 'trashedEntries', trashedEntry.id);
  batch.set(entryRef, buildRestoredEntryPayload(trashedEntry, currentUser));
  batch.delete(trashedRef);
  return awaitWriteWithOfflineGrace(batch.commit());
}

export async function permanentlyDeleteFromTrash(entryId) {
  return awaitWriteWithOfflineGrace(deleteDoc(doc(db, 'trashedEntries', entryId)));
}

export async function updateUserRoleAndStatus(targetUserId, role, active) {
  const safeActive = role === 'PENDING' ? false : Boolean(active);
  return awaitWriteWithOfflineGrace(
    updateDoc(doc(db, 'users', targetUserId), {
      role,
      active: safeActive,
      updatedAt: serverTimestamp(),
    })
  );
}

export async function deleteUserProfile(targetUserId, currentUserId) {
  if (!targetUserId) {
    throw new Error('ID de usuario inválido.');
  }
  if (currentUserId && targetUserId === currentUserId) {
    throw new Error('No puedes eliminar tu propio usuario administrador.');
  }
  return awaitWriteWithOfflineGrace(deleteDoc(doc(db, 'users', targetUserId)));
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
