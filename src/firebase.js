import { initializeApp } from 'firebase/app';
import { getAuth, GoogleAuthProvider } from 'firebase/auth';
import { getFirestore } from 'firebase/firestore';

// Configuración directa de Firebase Web para cliente (sin variables de entorno ni lectura de servidor).
// IMPORTANTE: Registra una app Web en Firebase Console (proyecto gen-lang-client-0597164698)
// y reemplaza el valor de appId con el App ID Web real ("1:757035548395:web:...") antes de compilar.
export const firebaseConfig = {
  apiKey: 'AIzaSyAPx94hISeiK-lmFttQM9JsV3LJ1-nlgIc',
  authDomain: 'gen-lang-client-0597164698.firebaseapp.com',
  projectId: 'gen-lang-client-0597164698',
  storageBucket: 'gen-lang-client-0597164698.firebasestorage.app',
  messagingSenderId: '757035548395',
  appId: '1:757035548395:web:REEMPLAZAR_CON_WEB_APP_ID',
};

// Base de datos Firestore provisionada para Control Acceso
export const FIRESTORE_DATABASE_ID =
  'ai-studio-android-controla-85365416-02eb-48c9-9aee-0fdd518a825c';

export const UNIT_ID = 'UNIDAD_PRINCIPAL';
export const BOOTSTRAP_ADMIN_EMAIL = 'kevinarias.8818@gmail.com';

export const app = initializeApp(firebaseConfig);
export const auth = getAuth(app);
export const googleProvider = new GoogleAuthProvider();
googleProvider.setCustomParameters({ prompt: 'select_account' });

// Conectar explícitamente a la base de datos nombrada (no usar getFirestore(app) sin segundo parámetro)
export const db = getFirestore(app, FIRESTORE_DATABASE_ID);
