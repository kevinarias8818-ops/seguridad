const { onDocumentWritten } = require('firebase-functions/v2/firestore');
const { initializeApp } = require('firebase-admin/app');
const { getFirestore } = require('firebase-admin/firestore');
const { getMessaging } = require('firebase-admin/messaging');

initializeApp();

const DATABASE_ID = 'ai-studio-android-controla-85365416-02eb-48c9-9aee-0fdd518a825c';

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
    const becameCheckedIn = before && before.status !== 'INGRESO' && after.status === 'INGRESO';
    if (!isNewEntry && !becameCheckedIn) return;

    const unitId = after.unitId || 'UNIDAD_PRINCIPAL';
    const personLabel = [after.rank, after.fullName].filter(Boolean).join(' ').trim();
    const actorUid = becameCheckedIn ? after.checkInByUid : after.createdByUid;

    const title = becameCheckedIn
      ? `Nuevo ingreso registrado: ${personLabel}`
      : `Nuevo registro autorizado: ${personLabel}`;
    const body = becameCheckedIn
      ? `${after.destinationUnit} · Marcado por ${after.checkInByName || 'Operador'}`
      : `${after.destinationUnit} · Estado: ${after.status === 'INGRESO' ? 'Ya ingresó' : 'Pendiente'}`;

    const db = getFirestore(DATABASE_ID);
    const adminUsersSnap = await db
      .collection('users')
      .where('role', '==', 'ADMIN')
      .where('active', '==', true)
      .where('unitId', '==', unitId)
      .get();

    const tokens = [];
    const tokenRefs = [];

    for (const userDoc of adminUsersSnap.docs) {
      if (userDoc.id === actorUid) continue;
      const tokensSnap = await userDoc.ref.collection('fcmTokens').get();
      for (const tokenDoc of tokensSnap.docs) {
        const token = tokenDoc.data()?.token;
        if (typeof token === 'string' && token.length >= 10) {
          tokens.push(token);
          tokenRefs.push(tokenDoc.ref);
        }
      }
    }

    if (tokens.length === 0) return;

    const response = await getMessaging().sendEachForMulticast({
      tokens,
      notification: { title, body },
      data: {
        entryId: event.params.entryId,
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
          color: '#059669',
        },
      },
    });

    const cleanupPromises = [];
    response.responses.forEach((res, idx) => {
      if (!res.success) {
        const code = res.error?.code || '';
        if (
          code === 'messaging/registration-token-not-registered' ||
          code === 'messaging/invalid-registration-token'
        ) {
          cleanupPromises.push(tokenRefs[idx].delete());
        }
      }
    });
    if (cleanupPromises.length > 0) {
      await Promise.allSettled(cleanupPromises);
    }
  }
);
