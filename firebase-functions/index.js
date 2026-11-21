const { onDocumentWritten } = require('firebase-functions/v2/firestore');
const { initializeApp } = require('firebase-admin/app');
const { getFirestore } = require('firebase-admin/firestore');
const { getMessaging } = require('firebase-admin/messaging');

initializeApp();

const DATABASE_ID = 'ai-studio-android-controla-85365416-02eb-48c9-9aee-0fdd518a825c';
const MAX_MULTICAST_TOKENS = 500;

exports.notifyAdminsOnEntryEvent = onDocumentWritten(
  {
    document: 'entries/{entryId}',
    database: DATABASE_ID,
    region: 'us-central1',
  },
  async (event) => {
    const before = event.data?.before?.data() || null;
    const after = event.data?.after?.data() || null;
    if (!after) return;

    const isNewEntry = !before;
    const becameCheckedIn = Boolean(
      before && before.status === 'PENDIENTE' && after.status === 'INGRESO'
    );
    const becameCheckedOut = Boolean(
      before && before.status !== 'SALIDA' && after.status === 'SALIDA'
    );
    if (!isNewEntry && !becameCheckedIn && !becameCheckedOut) return;

    const unitId = after.unitId || 'UNIDAD_PRINCIPAL';
    const personLabel = [after.rank, after.fullName].filter(Boolean).join(' ').trim() || 'Personal';
    const actorUid = becameCheckedOut
      ? after.checkOutByUid
      : becameCheckedIn
      ? after.checkInByUid
      : after.createdByUid;

    const statusLabel =
      after.status === 'SALIDA'
        ? 'Ya salió'
        : after.status === 'INGRESO'
        ? 'Ya ingresó'
        : 'Pendiente';

    const eventType = becameCheckedOut
      ? 'CHECK_OUT'
      : becameCheckedIn
      ? 'CHECK_IN'
      : 'NEW_ENTRY';

    const title = becameCheckedOut
      ? `Salida registrada: ${personLabel}`
      : becameCheckedIn
      ? `Nuevo ingreso registrado: ${personLabel}`
      : `Nuevo ingreso autorizado: ${personLabel}`;

    const body = becameCheckedOut
      ? `${after.destinationUnit || 'Unidad'} · Marcado por ${after.checkOutByName || 'Operador'}`
      : becameCheckedIn
      ? `${after.destinationUnit || 'Unidad'} · Marcado por ${after.checkInByName || 'Operador'}`
      : `${after.destinationUnit || 'Unidad'} · Estado: ${statusLabel}`;

    // Administradores: reciben nuevos ingresos y salidas.
    // Operadores: reciben únicamente nuevos ingresos (no salidas).
    const targetRoles = becameCheckedOut ? ['ADMIN'] : ['ADMIN', 'OPERATOR'];

    const db = getFirestore(DATABASE_ID);
    const recipientsSnap = await db
      .collection('users')
      .where('unitId', '==', unitId)
      .where('active', '==', true)
      .where('role', 'in', targetRoles)
      .get();

    const seenTokens = new Set();
    const tokens = [];
    const tokenRefs = [];

    for (const userDoc of recipientsSnap.docs) {
      if (actorUid && userDoc.id === actorUid) continue;
      const tokensSnap = await userDoc.ref.collection('fcmTokens').get();
      for (const tokenDoc of tokensSnap.docs) {
        const token = tokenDoc.data()?.token;
        if (typeof token === 'string' && token.length >= 10 && !seenTokens.has(token)) {
          seenTokens.add(token);
          tokens.push(token);
          tokenRefs.push(tokenDoc.ref);
        }
      }
    }

    if (tokens.length === 0) return;

    const messaging = getMessaging();
    const cleanupPromises = [];
    const notificationTag = `${event.params.entryId}_${after.status || eventType}`;
    const accentColor = becameCheckedOut ? '#0284C7' : '#059669';

    for (let start = 0; start < tokens.length; start += MAX_MULTICAST_TOKENS) {
      const tokenChunk = tokens.slice(start, start + MAX_MULTICAST_TOKENS);
      const refChunk = tokenRefs.slice(start, start + MAX_MULTICAST_TOKENS);

      const response = await messaging.sendEachForMulticast({
        tokens: tokenChunk,
        notification: { title, body },
        data: {
          entryId: event.params.entryId,
          eventType,
          fullName: personLabel,
          destinationUnit: after.destinationUnit || '',
          status: after.status || 'PENDIENTE',
          title,
          body,
        },
        android: {
          priority: 'high',
          notification: {
            channelId: 'ingresos_cenop',
            icon: 'ic_stat_cenop',
            color: accentColor,
            tag: notificationTag,
          },
        },
      });

      response.responses.forEach((res, idx) => {
        if (!res.success) {
          const code = res.error?.code || '';
          if (
            code === 'messaging/registration-token-not-registered' ||
            code === 'messaging/invalid-registration-token'
          ) {
            cleanupPromises.push(refChunk[idx].delete());
          }
        }
      });
    }

    if (cleanupPromises.length > 0) {
      await Promise.allSettled(cleanupPromises);
    }
  }
);
