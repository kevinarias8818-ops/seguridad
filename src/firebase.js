import { initializeApp } from 'firebase/app';
import { getAuth, GoogleAuthProvider } from 'firebase/auth';
import {
  getFirestore,
  initializeFirestore,
  persistentLocalCache,
  persistentMultipleTabManager,
} from 'firebase/firestore';

// Configuración directa de Firebase Web para cliente (proyecto gen-lang-client-0597164698).
export const firebaseConfig = {
  apiKey: 'AIzaSyAPx94hISeiK-lmFttQM9JsV3LJ1-nlgIc',
  authDomain: 'gen-lang-client-0597164698.firebaseapp.com',
  projectId: 'gen-lang-client-0597164698',
  storageBucket: 'gen-lang-client-0597164698.firebasestorage.app',
  messagingSenderId: '757035548395',
  appId: '1:757035548395:web:51805d611bbd0ef2adc8ae',
};

// Base de datos Firestore provisionada para Control GUSEF
export const FIRESTORE_DATABASE_ID =
  'ai-studio-android-controla-85365416-02eb-48c9-9aee-0fdd518a825c';

export const UNIT_ID = 'UNIDAD_PRINCIPAL';
export const BOOTSTRAP_ADMIN_EMAIL = 'kevinarias.8818@gmail.com';

export const app = initializeApp(firebaseConfig);
export const auth = getAuth(app);
export const googleProvider = new GoogleAuthProvider();
googleProvider.setCustomParameters({ prompt: 'select_account' });

// Conectar explícitamente a la base de datos nombrada con persistencia offline en IndexedDB
let firestoreInstance;
try {
  firestoreInstance = initializeFirestore(
    app,
    {
      localCache: persistentLocalCache({
        tabManager: persistentMultipleTabManager(),
      }),
    },
    FIRESTORE_DATABASE_ID
  );
} catch {
  firestoreInstance = getFirestore(app, FIRESTORE_DATABASE_ID);
}

export const db = firestoreInstance;
