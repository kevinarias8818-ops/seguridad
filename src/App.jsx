import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  getRedirectResult,
  onAuthStateChanged,
  signInWithPopup,
  signInWithRedirect,
  signOut,
} from 'firebase/auth';
import {
  AlertCircle,
  Camera,
  CheckCircle2,
  Clock,
  CloudOff,
  Download,
  Edit3,
  Eye,
  FileSpreadsheet,
  Gem,
  Image as ImageIcon,
  LogIn,
  LogOut,
  Mail,
  Plus,
  RefreshCw,
  RotateCcw,
  Search,
  Shield,
  ShieldAlert,
  Trash2,
  UserCheck,
  Users,
  WifiOff,
  X,
  ZoomIn,
} from 'lucide-react';
import { auth, firebaseConfig, googleProvider, UNIT_ID } from './firebase.js';
import {
  attachPlateImage,
  compressPlateImageFile,
  createEntry,
  currentTimeHm,
  deleteUserProfile,
  ensureUserProfile,
  fetchAllEntriesForUnit,
  findActiveOnSiteDuplicates,
  formatDateTimeShort,
  formatDocumentNumberInput,
  formatEntryTimeDigits,
  formatPlateInput,
  formatTimeHm,
  getEntryHour,
  humanizeFirebaseError,
  importEntriesBatch,
  isSafePlateImageDataUrl,
  isValidEntryTime,
  isValidIsoDate,
  isWebOnline,
  markCheckInNow,
  markCheckOutNow,
  moveEntryToTrash,
  normalizeExistingTime,
  permanentlyDeleteFromTrash,
  restoreEntryFromTrash,
  subscribeAllUsers,
  subscribeEntries,
  subscribeOnSiteEntries,
  subscribeTrashedEntries,
  subscribeUserProfile,
  todayIsoDate,
  updateEntry,
  updateUserRoleAndStatus,
} from './accessService.js';

const GUSEF_LOGO_SRC = './assets/aistudio/file_00000000b39881f5a56b20e2dfa9f789_53c9de7d.png';
import {
  REPORT_PERIODS,
  buildAccessExcelBlob,
  downloadBlobFile,
} from './excelExport.js';
import {
  SAMPLE_INSTITUTIONAL_EMAIL,
  VISITOR_TYPES,
  parseInstitutionalEmail,
} from './emailParser.js';

const EMPTY_DRAFT = {
  entryDate: todayIsoDate(),
  rank: '',
  fullName: '',
  documentNumber: '',
  plate: '',
  plateImageName: '',
  plateImageData: '',
  destinationUnit: '',
  reason: '',
  visitorType: 'VISITANTE',
  expectedTime: '',
  notes: '',
  status: 'PENDIENTE',
};

function visitorLabel(type) {
  return VISITOR_TYPES.find((t) => t.value === type)?.label || type;
}

export default function App() {
  const [authUser, setAuthUser] = useState(undefined);
  const [profileReadyUid, setProfileReadyUid] = useState('');
  const [profile, setProfile] = useState(null);
  const [profileLoading, setProfileLoading] = useState(false);
  const [authBusy, setAuthBusy] = useState(false);
  const [errorMsg, setErrorMsg] = useState('');
  const [toastMsg, setToastMsg] = useState('');

  const [activeTab, setActiveTab] = useState('INGRESOS');
  const [simulateOperator, setSimulateOperator] = useState(false);

  const [isOnline, setIsOnline] = useState(() => isWebOnline());
  const [entries, setEntries] = useState([]);
  const [onSiteEntries, setOnSiteEntries] = useState([]);
  const [entriesLoading, setEntriesLoading] = useState(false);
  const [trashedEntries, setTrashedEntries] = useState([]);
  const [trashedLoading, setTrashedLoading] = useState(false);
  const [isFromCache, setIsFromCache] = useState(false);
  const [pendingSyncIds, setPendingSyncIds] = useState(() => new Set());
  const [users, setUsers] = useState([]);

  // Filters
  const [searchTerm, setSearchTerm] = useState('');
  const [statusFilter, setStatusFilter] = useState('TODOS'); // TODOS | PENDIENTE | INGRESO
  const [dateScope, setDateScope] = useState('HOY'); // HOY | HISTORIAL
  const [hourSlot, setHourSlot] = useState('TODAS'); // TODAS | MANANA | TARDE | NOCHE

  // Modals
  const [editorEntry, setEditorEntry] = useState(null); // null | { mode: 'create' | 'edit', item? }
  const [showImportModal, setShowImportModal] = useState(false);
  const [showExcelModal, setShowExcelModal] = useState(false);
  const [busyId, setBusyId] = useState('');
  const [stagedPlateByEntry, setStagedPlateByEntry] = useState({});
  const [previewImageModal, setPreviewImageModal] = useState(null);

  function showToast(msg) {
    setToastMsg(msg);
    setTimeout(() => {
      setToastMsg((prev) => (prev === msg ? '' : prev));
    }, 3200);
  }

  // Monitoreo de conectividad en tiempo real para Modo Offline en la guardia
  useEffect(() => {
    if (typeof window === 'undefined') return undefined;
    const handleOnline = () => setIsOnline(true);
    const handleOffline = () => setIsOnline(false);
    window.addEventListener('online', handleOnline);
    window.addEventListener('offline', handleOffline);
    return () => {
      window.removeEventListener('online', handleOnline);
      window.removeEventListener('offline', handleOffline);
    };
  }, []);

  // 0. Procesar resultado de redirección de Google Sign-In al volver a la app
  useEffect(() => {
    let active = true;
    const hadRedirect =
      typeof window !== 'undefined' &&
      window.sessionStorage.getItem('ca_google_redirect') === '1';
    if (hadRedirect) {
      setAuthBusy(true);
    }

    getRedirectResult(auth)
      .then((result) => {
        if (!active) return;
        if (typeof window !== 'undefined') {
          window.sessionStorage.removeItem('ca_google_redirect');
        }
        if (result?.user) {
          setErrorMsg('');
          setAuthBusy(false);
        } else if (hadRedirect && !auth.currentUser) {
          setAuthBusy(false);
          setErrorMsg(
            'El navegador o WebView bloqueó la sesión al regresar de Google (cookies de terceros restringidas o dominio sin autorizar). Autoriza este dominio en Firebase Console o usa la opción con ventana emergente.'
          );
        }
      })
      .catch((err) => {
        if (!active) return;
        if (typeof window !== 'undefined') {
          window.sessionStorage.removeItem('ca_google_redirect');
        }
        setErrorMsg(humanizeFirebaseError(err));
        setAuthBusy(false);
      });
    return () => {
      active = false;
    };
  }, []);

  // 1. Auth listener
  useEffect(() => {
    const unsub = onAuthStateChanged(auth, async (user) => {
      setAuthUser(user || null);
      setErrorMsg('');
      if (!user) {
        setProfileReadyUid('');
        setProfile(null);
        setEntries([]);
        setTrashedEntries([]);
        setUsers([]);
        setSimulateOperator(false);
        return;
      }
      setProfileLoading(true);
      setProfileReadyUid('');
      try {
        const ensured = await ensureUserProfile(user);
        setProfile(ensured);
        setProfileReadyUid(user.uid);
      } catch (err) {
        setErrorMsg(humanizeFirebaseError(err));
      } finally {
        setProfileLoading(false);
      }
    });
    return () => unsub();
  }, []);

  // 2. Real-time profile subscription (espera a que ensureUserProfile termine)
  useEffect(() => {
    if (!authUser || profileReadyUid !== authUser.uid) return undefined;
    const unsub = subscribeUserProfile(
      authUser.uid,
      (nextProfile) => {
        setProfile(nextProfile || null);
        setProfileLoading(false);
      },
      (err) => {
        setErrorMsg(humanizeFirebaseError(err));
        setProfileLoading(false);
      }
    );
    return () => unsub();
  }, [authUser, profileReadyUid]);

  const isRealAdmin = profile?.role === 'ADMIN' && profile?.active === true;
  const effectiveIsAdmin = isRealAdmin && !simulateOperator;
  const isActiveDiamanteAlma = profile?.active === true && profile?.role === 'DIAMANTE_ALMA';
  const isStaffActive =
    profile?.active === true &&
    ['ADMIN', 'OPERATOR', 'DIAMANTE_ALMA'].includes(profile?.role);
  const canCreateEntries = effectiveIsAdmin || isActiveDiamanteAlma;
  const canSendToTrash = effectiveIsAdmin || isActiveDiamanteAlma;
  const canUseFullHistory = effectiveIsAdmin || isActiveDiamanteAlma;

  // 3. Real-time entries subscription (strictly gated behind active staff)
  useEffect(() => {
    if (!isStaffActive) {
      setEntries([]);
      setIsFromCache(false);
      setPendingSyncIds(new Set());
      return undefined;
    }
    setEntriesLoading(true);
    const scopeDate = canUseFullHistory && dateScope === 'HISTORIAL' ? null : todayIsoDate();
    const unsub = subscribeEntries(
      profile.unitId || UNIT_ID,
      scopeDate,
      (snapshot) => {
        setEntries(snapshot.items);
        setIsFromCache(snapshot.fromCache);
        setPendingSyncIds(snapshot.pendingSyncIds);
        setEntriesLoading(false);
      },
      (err) => {
        setErrorMsg(humanizeFirebaseError(err));
        setEntriesLoading(false);
      }
    );
    return () => unsub();
  }, [isStaffActive, canUseFullHistory, dateScope, profile?.unitId]);

  useEffect(() => {
    if (!canCreateEntries) {
      setOnSiteEntries([]);
      return undefined;
    }
    const unsub = subscribeOnSiteEntries(
      profile?.unitId || UNIT_ID,
      (items) => setOnSiteEntries(items),
      () => setOnSiteEntries([])
    );
    return () => unsub();
  }, [canCreateEntries, profile?.unitId]);

  // 4. Real-time trash subscription (strictly Admin-only; blocked visually & logically for all other roles)
  useEffect(() => {
    if (!effectiveIsAdmin) {
      setTrashedEntries([]);
      setTrashedLoading(false);
      if (activeTab === 'PAPELERA') setActiveTab('INGRESOS');
      return undefined;
    }
    setTrashedLoading(true);
    const unsub = subscribeTrashedEntries(
      profile.unitId || UNIT_ID,
      (items) => {
        setTrashedEntries(items);
        setTrashedLoading(false);
      },
      (err) => {
        setErrorMsg(humanizeFirebaseError(err));
        setTrashedLoading(false);
      }
    );
    return () => unsub();
  }, [effectiveIsAdmin, profile?.unitId, activeTab]);

  // 5. Real-time users subscription (strictly Admin-only)
  useEffect(() => {
    if (!effectiveIsAdmin) {
      setUsers([]);
      if (activeTab === 'USUARIOS') setActiveTab('INGRESOS');
      return undefined;
    }
    const unsub = subscribeAllUsers(
      (list) => setUsers(list),
      (err) => setErrorMsg(humanizeFirebaseError(err))
    );
    return () => unsub();
  }, [effectiveIsAdmin, activeTab]);

  async function handleGoogleSignInRedirectFallback() {
    setAuthBusy(true);
    setErrorMsg('');
    try {
      if (typeof window !== 'undefined') {
        window.sessionStorage.setItem('ca_google_redirect', '1');
      }
      await signInWithRedirect(auth, googleProvider);
    } catch (err) {
      if (typeof window !== 'undefined') {
        window.sessionStorage.removeItem('ca_google_redirect');
      }
      setErrorMsg(humanizeFirebaseError(err));
      setAuthBusy(false);
    }
  }

  async function handleGoogleSignIn() {
    setAuthBusy(true);
    setErrorMsg('');
    try {
      await signInWithPopup(auth, googleProvider);
      setAuthBusy(false);
    } catch (err) {
      const code = err?.code || '';
      if (
        code === 'auth/popup-blocked' ||
        code === 'auth/operation-not-supported-in-this-environment'
      ) {
        await handleGoogleSignInRedirectFallback();
        return;
      }
      setErrorMsg(humanizeFirebaseError(err));
      setAuthBusy(false);
    }
  }

  async function handleSignOut() {
    setErrorMsg('');
    await signOut(auth);
  }

  async function handleCheckIn(entry) {
    if (!profile) return;
    setBusyId(entry.id);
    setErrorMsg('');
    try {
      const staged = stagedPlateByEntry[entry.id] || null;
      const res = await markCheckInNow(entry.id, profile, staged);
      setStagedPlateByEntry((prev) => {
        if (!prev[entry.id]) return prev;
        const next = { ...prev };
        delete next[entry.id];
        return next;
      });
      if (res?.queuedOffline) {
        showToast(
          `Ingreso guardado offline: ${entry.fullName}. Se sincronizará automáticamente al volver el internet.`
        );
      } else {
        showToast(
          staged
            ? `Ingreso y placa guardados: ${entry.fullName}`
            : `Ingreso marcado: ${entry.fullName}`
        );
      }
    } catch (err) {
      setErrorMsg(humanizeFirebaseError(err));
    } finally {
      setBusyId('');
    }
  }

  async function handleAttachEntryPlate(entry, attachment) {
    setBusyId(entry.id);
    setErrorMsg('');
    try {
      const res = await attachPlateImage(entry.id, attachment);
      showToast(
        res?.queuedOffline
          ? `Placa guardada offline: ${attachment.fileName}. Se subirá al reconectar.`
          : `Imagen de placa guardada: ${attachment.fileName}`
      );
    } catch (err) {
      setErrorMsg(humanizeFirebaseError(err));
    } finally {
      setBusyId('');
    }
  }

  async function handleCheckOut(entry) {
    if (!profile) return;
    setBusyId(entry.id);
    setErrorMsg('');
    try {
      const res = await markCheckOutNow(entry.id, profile);
      showToast(
        res?.queuedOffline
          ? `Salida guardada offline: ${entry.fullName}. Se sincronizará al volver el internet.`
          : `Salida marcada: ${entry.fullName}`
      );
    } catch (err) {
      setErrorMsg(humanizeFirebaseError(err));
    } finally {
      setBusyId('');
    }
  }

  async function handleMoveToTrash(entry) {
    if (!canSendToTrash || !profile) {
      setErrorMsg('No tienes permiso para enviar ingresos a la papelera.');
      return;
    }
    if (
      !window.confirm(
        `¿Enviar el ingreso de ${entry.fullName} a la papelera? Dejará de mostrarse en los ingresos y contadores.`
      )
    ) {
      return;
    }
    setBusyId(entry.id);
    setErrorMsg('');
    try {
      const res = await moveEntryToTrash(entry, profile);
      showToast(
        res?.queuedOffline
          ? `Envío a papelera guardado offline (${entry.fullName}).`
          : `Ingreso enviado a la papelera: ${entry.fullName}`
      );
    } catch (err) {
      setErrorMsg(humanizeFirebaseError(err));
    } finally {
      setBusyId('');
    }
  }

  async function handleRestoreFromTrash(trashedItem) {
    if (!effectiveIsAdmin || !profile) {
      setErrorMsg('Solo el Administrador puede restaurar registros de la papelera.');
      return;
    }
    setBusyId(trashedItem.id);
    setErrorMsg('');
    try {
      const res = await restoreEntryFromTrash(trashedItem, profile);
      showToast(
        res?.queuedOffline
          ? `Restauración guardada offline (${trashedItem.fullName}).`
          : `Ingreso restaurado: ${trashedItem.fullName}`
      );
    } catch (err) {
      setErrorMsg(humanizeFirebaseError(err));
    } finally {
      setBusyId('');
    }
  }

  async function handlePermanentDeleteFromTrash(trashedItem) {
    if (!effectiveIsAdmin || !profile) {
      setErrorMsg('Solo el Administrador puede eliminar definitivamente de la papelera.');
      return;
    }
    if (
      !window.confirm(
        `¿Eliminar definitivamente el registro de ${trashedItem.fullName}? Esta acción no se puede deshacer.`
      )
    ) {
      return;
    }
    setBusyId(trashedItem.id);
    setErrorMsg('');
    try {
      const res = await permanentlyDeleteFromTrash(trashedItem.id);
      showToast(
        res?.queuedOffline
          ? 'Eliminación definitiva en cola offline.'
          : 'Registro eliminado definitivamente de la papelera'
      );
    } catch (err) {
      setErrorMsg(humanizeFirebaseError(err));
    } finally {
      setBusyId('');
    }
  }

  async function handleRoleChange(targetUser, role, active) {
    if (!effectiveIsAdmin) {
      setErrorMsg('Solo el Administrador puede gestionar roles.');
      return;
    }
    setBusyId(targetUser.userId);
    setErrorMsg('');
    try {
      const res = await updateUserRoleAndStatus(targetUser.userId, role, active);
      showToast(
        res?.queuedOffline
          ? `Cambio de rol para ${targetUser.displayName} en cola offline.`
          : `Permisos actualizados para ${targetUser.displayName}`
      );
    } catch (err) {
      setErrorMsg(humanizeFirebaseError(err));
    } finally {
      setBusyId('');
    }
  }

  async function handleDeleteUser(targetUser) {
    if (!effectiveIsAdmin) {
      setErrorMsg('Solo el Administrador puede eliminar usuarios.');
      return;
    }
    if (!targetUser?.userId || targetUser.userId === profile?.userId) {
      setErrorMsg('No puedes eliminar tu propio usuario administrador.');
      return;
    }
    if (
      !window.confirm(
        `¿Eliminar a ${targetUser.displayName} (${targetUser.email}) de la lista de usuarios?\n\nEsto NO bloquea su cuenta de Google: podrá volver a ingresar con el mismo correo cuando lo requiera.`
      )
    ) {
      return;
    }
    setBusyId(targetUser.userId);
    setErrorMsg('');
    try {
      const res = await deleteUserProfile(targetUser.userId, profile?.userId);
      showToast(
        res?.queuedOffline
          ? `Eliminación de ${targetUser.displayName} en cola offline.`
          : `Usuario ${targetUser.displayName} eliminado. Puede volver a ingresar con el mismo correo.`
      );
    } catch (err) {
      setErrorMsg(humanizeFirebaseError(err));
    } finally {
      setBusyId('');
    }
  }

  const filteredEntries = useMemo(() => {
    const q = searchTerm.trim().toLowerCase();
    return entries.filter((entry) => {
      if (statusFilter !== 'TODOS' && entry.status !== statusFilter) return false;

      if (hourSlot !== 'TODAS') {
        const hr = getEntryHour(entry);
        if (hr === null) return false;
        if (hourSlot === 'MANANA' && !(hr >= 0 && hr < 12)) return false;
        if (hourSlot === 'TARDE' && !(hr >= 12 && hr < 18)) return false;
        if (hourSlot === 'NOCHE' && !(hr >= 18 && hr <= 23)) return false;
      }

      if (!q) return true;
      const haystack = [
        entry.rank,
        entry.fullName,
        entry.documentNumber,
        entry.plate,
        entry.destinationUnit,
        entry.reason,
        entry.notes,
      ]
        .join(' ')
        .toLowerCase();
      return haystack.includes(q);
    });
  }, [entries, searchTerm, statusFilter, hourSlot]);

  const pendingCount = useMemo(
    () => entries.filter((e) => e.status === 'PENDIENTE').length,
    [entries]
  );
  const enteredCount = useMemo(
    () => entries.filter((e) => e.status === 'INGRESO').length,
    [entries]
  );
  const exitedCount = useMemo(
    () => entries.filter((e) => e.status === 'SALIDA').length,
    [entries]
  );
  const pendingUsersCount = useMemo(
    () => users.filter((u) => u.role === 'PENDING').length,
    [users]
  );

  // Loading auth
  if (authUser === undefined || (authUser && profileLoading && !profile)) {
    return (
      <div className="center-screen">
        <div className="auth-card" style={{ textAlign: 'center' }}>
          <div className="brand-crest" style={{ margin: '0 auto 14px', width: 64, height: 64 }}>
            <img src={GUSEF_LOGO_SRC} alt="Escudo GUSEF del CENOP" />
          </div>
          <h1 className="auth-title">Control GUSEF</h1>
          <p className="auth-desc">Conectando con Firebase en tiempo real...</p>
        </div>
      </div>
    );
  }

  const isWebAppIdPlaceholder = (firebaseConfig.appId || '').includes('REEMPLAZAR');

  // Unauthenticated view
  if (!authUser) {
    return (
      <div className="center-screen">
        <div className="auth-card">
          <div className="auth-hero">
            <img
              src="./assets/aistudio/images_6f89d2da.jpeg"
              alt="Plaza principal e instalaciones del CENOP"
              className="auth-hero-photo"
            />
            <div className="auth-live-pill">
              <span className="auth-live-dot" />
              CENOP · TURNO EN VIVO
            </div>
            <div className="auth-hero-overlay">
              <div className="brand-crest">
                <img src={GUSEF_LOGO_SRC} alt="Escudo GUSEF del CENOP" />
              </div>
              <div>
                <h1 className="auth-title" style={{ margin: 0, color: '#fff' }}>
                  Control GUSEF
                </h1>
                <p style={{ margin: '2px 0 0', fontSize: '0.82rem', color: '#cbd5e1' }}>
                  Seguridad e Ingresos de Unidad
                </p>
              </div>
            </div>
          </div>

          <div className="auth-accent-bar" />
          <h2 style={{ margin: '8px 0 4px', fontSize: '1.15rem', fontWeight: 800 }}>
            Acceso Institucional
          </h2>
          <p className="auth-desc" style={{ marginBottom: 14 }}>
            Consola operativa para control diario de ingresos, salidas y verificación vehicular en
            tiempo real.
          </p>

          <div className="auth-roles-grid">
            <div className="auth-role-tile">
              <div className="auth-role-title">Administrador</div>
              <div className="time-col-sub">Gestión total, carga, Excel y roles.</div>
            </div>
            <div className="auth-role-tile">
              <div className="auth-role-title">Operador</div>
              <div className="time-col-sub">Registro en garita de entradas y salidas.</div>
            </div>
          </div>

          {isWebAppIdPlaceholder && (
            <div className="error-banner" role="alert">
              <AlertCircle size={18} style={{ flexShrink: 0, marginTop: 2 }} />
              <span>
                Falta configurar el <strong>appId Web</strong> real en <code>src/firebase.js</code>{' '}
                desde Firebase Console (proyecto <code>{firebaseConfig.projectId}</code>) y autorizar
                este dominio en Authentication.
              </span>
            </div>
          )}

          {errorMsg && (
            <div className="error-banner" role="alert">
              <AlertCircle size={18} style={{ flexShrink: 0, marginTop: 2 }} />
              <span>{errorMsg}</span>
            </div>
          )}

          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            <button
              type="button"
              className="btn btn-primary"
              style={{ width: '100%' }}
              onClick={handleGoogleSignIn}
              disabled={authBusy}
            >
              <LogIn size={18} />
              {authBusy ? 'Conectando con Google...' : 'Iniciar sesión con Google'}
            </button>
            <button
              type="button"
              className="btn btn-outline btn-sm"
              style={{ width: '100%' }}
              onClick={handleGoogleSignInRedirectFallback}
              disabled={authBusy}
            >
              Alternativa: ingresar por redirección
            </button>
          </div>

          <p className="auth-footer-note">
            Acceso protegido · Habilitación requerida por Administrador
          </p>
        </div>
      </div>
    );
  }

  // Pending / Inactive / Removed user view (No access to /entries)
  if (!isStaffActive) {
    const isRemoved = profile === null;
    const isPending = isRemoved || profile.role === 'PENDING';
    return (
      <div className="center-screen">
        <div className="auth-card">
          <div className="brand-crest" style={{ margin: '0 auto 14px', width: 64, height: 64 }}>
            <img src={GUSEF_LOGO_SRC} alt="Escudo GUSEF del CENOP" />
          </div>
          <h1 className="auth-title">
            {isRemoved
              ? 'Perfil retirado de la lista'
              : isPending
              ? 'Cuenta pendiente de aprobación'
              : 'Acceso desactivado'}
          </h1>
          <p className="auth-desc">
            {isRemoved
              ? 'Tu usuario fue eliminado de la lista por el Administrador, pero tu correo sigue habilitado para ingresar. Pulsa "Volver a ingresar" para registrarte nuevamente.'
              : isPending
              ? 'Tu sesión está activa, pero aún no tienes permiso para ver los ingresos de la unidad. Solicita al Administrador que te asigne un rol autorizado (Operador o Diamante Alma).'
              : 'Tu acceso como personal autorizado está actualmente suspendido. Contacta al Administrador.'}
          </p>

          <div className="meta-box" style={{ marginBottom: 16 }}>
            <div className="meta-label">Usuario conectado</div>
            <div className="meta-val">{profile?.displayName || authUser.displayName || 'Usuario'}</div>
            <div className="time-col-sub">{profile?.email || authUser.email}</div>
          </div>

          {errorMsg && (
            <div className="error-banner" role="alert">
              <AlertCircle size={18} style={{ flexShrink: 0, marginTop: 2 }} />
              <span>{errorMsg}</span>
            </div>
          )}

          <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
            <button
              type="button"
              className="btn btn-outline"
              style={{ flex: 1 }}
              onClick={async () => {
                setErrorMsg('');
                try {
                  const ensured = await ensureUserProfile(authUser);
                  setProfile(ensured);
                } catch (e) {
                  setErrorMsg(humanizeFirebaseError(e));
                }
              }}
            >
              <RefreshCw size={17} />
              {isRemoved ? 'Volver a ingresar' : 'Verificar estado'}
            </button>
            <button
              type="button"
              className="btn btn-danger-outline"
              style={{ flex: 1 }}
              onClick={handleSignOut}
            >
              <LogOut size={17} />
              Cerrar sesión
            </button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="topbar-inner">
          <div className="brand-wrap">
            <div className="brand-crest">
              <img src={GUSEF_LOGO_SRC} alt="Escudo GUSEF del CENOP" />
            </div>
            <div>
              <h1 className="brand-title">Control GUSEF</h1>
              <p className="brand-sub">
                {profile.displayName} · Unidad {profile.unitId || UNIT_ID}
                {!isOnline ? ' · Sin internet (Modo Offline)' : ''}
              </p>
            </div>
          </div>

          <div className="topbar-actions">
            {!isOnline && (
              <span className="pill pill-offline-sync">
                <WifiOff size={13} />
                OFFLINE
              </span>
            )}
            <span
              className={`pill ${
                effectiveIsAdmin
                  ? 'pill-admin'
                  : isActiveDiamanteAlma
                  ? 'pill-diamante'
                  : 'pill-operator'
              }`}
            >
              {effectiveIsAdmin
                ? 'ADMIN'
                : simulateOperator
                ? 'VISTA OPERADOR'
                : isActiveDiamanteAlma
                ? 'DIAMANTE ALMA'
                : 'OPERADOR'}
            </span>

            {isRealAdmin && (
              <button
                type="button"
                className={`btn btn-sm ${simulateOperator ? 'btn-amber-outline' : 'btn-outline'}`}
                onClick={() => {
                  const next = !simulateOperator;
                  setSimulateOperator(next);
                  if (next) setActiveTab('INGRESOS');
                }}
                title="Cambiar entre vista Admin y vista Operador"
              >
                <Eye size={15} />
                {simulateOperator ? 'Salir vista Operador' : 'Simular Operador'}
              </button>
            )}

            <button
              type="button"
              className="btn btn-sm btn-outline"
              onClick={handleSignOut}
              aria-label="Cerrar sesión"
            >
              <LogOut size={15} />
              Salir
            </button>
          </div>
        </div>
      </header>

      <main className="main-content">
        {(!isOnline || isFromCache || pendingSyncIds.size > 0) && (
          <div
            className={`sync-banner ${!isOnline || isFromCache ? 'is-offline' : 'is-syncing'}`}
            role="status"
          >
            {!isOnline || isFromCache ? (
              <CloudOff size={18} style={{ flexShrink: 0, marginTop: 2 }} />
            ) : (
              <RefreshCw size={18} style={{ flexShrink: 0, marginTop: 2 }} />
            )}
            <div>
              <strong>
                {!isOnline
                  ? 'Modo Offline activo (Sin internet)'
                  : isFromCache
                  ? 'Mostrando datos guardados localmente'
                  : `Sincronizando ${pendingSyncIds.size} cambio(s) con Firebase`}
              </strong>
              <div style={{ marginTop: 2, fontSize: '0.82rem', opacity: 0.92 }}>
                {!isOnline || isFromCache
                  ? pendingSyncIds.size > 0
                    ? `Puedes seguir registrando ingresos y salidas. Tienes ${pendingSyncIds.size} registro(s) pendientes que se subirán automáticamente al volver la conexión.`
                    : 'Los registros en caché local siguen operativos. Todo cambio que realices se guardará en el dispositivo y se sincronizará al volver el internet.'
                  : 'Subiendo los cambios realizados sin conexión al servidor en la nube.'}
              </div>
            </div>
          </div>
        )}

        {errorMsg && (
          <div className="error-banner" role="alert">
            <AlertCircle size={18} style={{ flexShrink: 0, marginTop: 2 }} />
            <span>{errorMsg}</span>
          </div>
        )}

        {effectiveIsAdmin && (
          <div className="nav-tabs" role="tablist">
            <button
              type="button"
              role="tab"
              aria-selected={activeTab === 'INGRESOS'}
              className={`tab-btn ${activeTab === 'INGRESOS' ? 'active' : ''}`}
              onClick={() => setActiveTab('INGRESOS')}
            >
              <Clock size={17} />
              Ingresos ({entries.length})
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={activeTab === 'PAPELERA'}
              className={`tab-btn ${activeTab === 'PAPELERA' ? 'active' : ''}`}
              onClick={() => setActiveTab('PAPELERA')}
            >
              <Trash2 size={17} />
              Papelera ({trashedEntries.length})
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={activeTab === 'USUARIOS'}
              className={`tab-btn ${activeTab === 'USUARIOS' ? 'active' : ''}`}
              onClick={() => setActiveTab('USUARIOS')}
            >
              <Users size={17} />
              Usuarios y Roles
              {pendingUsersCount > 0 && (
                <span className="pill pill-pending">{pendingUsersCount} nuevos</span>
              )}
            </button>
          </div>
        )}

        {activeTab === 'INGRESOS' ? (
          <>
            <section className="kpi-grid" aria-label="Resumen de turno">
              <button
                type="button"
                className={`kpi-card kpi-pending is-clickable ${
                  statusFilter === 'PENDIENTE' ? 'is-selected' : ''
                }`}
                onClick={() =>
                  setStatusFilter((prev) => (prev === 'PENDIENTE' ? 'TODOS' : 'PENDIENTE'))
                }
              >
                <div className="kpi-label">Pendientes</div>
                <div className="kpi-value">{pendingCount}</div>
              </button>
              <button
                type="button"
                className={`kpi-card kpi-ingreso is-clickable ${
                  statusFilter === 'INGRESO' ? 'is-selected' : ''
                }`}
                onClick={() =>
                  setStatusFilter((prev) => (prev === 'INGRESO' ? 'TODOS' : 'INGRESO'))
                }
              >
                <div className="kpi-label">En sitio</div>
                <div className="kpi-value">{enteredCount}</div>
              </button>
              <button
                type="button"
                className={`kpi-card kpi-salida is-clickable ${
                  statusFilter === 'SALIDA' ? 'is-selected' : ''
                }`}
                onClick={() =>
                  setStatusFilter((prev) => (prev === 'SALIDA' ? 'TODOS' : 'SALIDA'))
                }
              >
                <div className="kpi-label">Salieron</div>
                <div className="kpi-value">{exitedCount}</div>
              </button>
              <button
                type="button"
                className={`kpi-card is-clickable ${statusFilter === 'TODOS' ? 'is-selected' : ''}`}
                onClick={() => setStatusFilter('TODOS')}
              >
                <div className="kpi-label">
                  {canUseFullHistory && dateScope === 'HISTORIAL' ? 'Historial' : 'Hoy'}
                </div>
                <div className="kpi-value">{entries.length}</div>
              </button>
            </section>

            {(canCreateEntries || effectiveIsAdmin) && (
              <div className="action-bar">
                {canCreateEntries && (
                  <>
                    <button
                      type="button"
                      className="btn btn-primary"
                      onClick={() =>
                        setEditorEntry({
                          mode: 'create',
                          item: { ...EMPTY_DRAFT, entryDate: todayIsoDate() },
                        })
                      }
                    >
                      <Plus size={18} />
                      Nuevo ingreso
                    </button>
                    <button
                      type="button"
                      className="btn btn-outline"
                      onClick={() => setShowImportModal(true)}
                    >
                      <Mail size={18} />
                      Importar correo institucional
                    </button>
                  </>
                )}
                {effectiveIsAdmin && (
                  <button
                    type="button"
                    className="btn btn-outline"
                    onClick={() => setShowExcelModal(true)}
                  >
                    <FileSpreadsheet size={18} />
                    Descargar Excel (24h / 7 días)
                  </button>
                )}
              </div>
            )}

            <section className="controls-panel" aria-label="Buscador y filtros">
              <div className="search-row">
                <div className="search-box">
                  <Search size={18} className="search-icon" />
                  <input
                    type="search"
                    className="input search-input"
                    placeholder="Buscar por grado, nombre, cédula, placa, dependencia o motivo..."
                    value={searchTerm}
                    onChange={(e) => setSearchTerm(e.target.value)}
                  />
                </div>
              </div>

              <div className="filters-row">
                <div className="chip-group" role="group" aria-label="Filtro por estado">
                  {[
                    { id: 'TODOS', label: 'Todos' },
                    { id: 'INGRESO', label: `En sitio (${enteredCount})`, onSite: true },
                    { id: 'PENDIENTE', label: 'Pendiente por ingresar' },
                    { id: 'SALIDA', label: 'Ya salió' },
                  ].map((f) => (
                    <button
                      key={f.id}
                      type="button"
                      className={`chip ${f.onSite ? 'chip-onsite' : ''} ${
                        statusFilter === f.id ? 'active' : ''
                      }`}
                      onClick={() => setStatusFilter(f.id)}
                    >
                      {f.onSite && <CheckCircle2 size={14} />}
                      {f.label}
                    </button>
                  ))}
                </div>

                <div className="chip-group">
                  {canUseFullHistory && (
                    <>
                      <button
                        type="button"
                        className={`chip ${dateScope === 'HOY' ? 'active' : ''}`}
                        onClick={() => setDateScope('HOY')}
                      >
                        Hoy
                      </button>
                      <button
                        type="button"
                        className={`chip ${dateScope === 'HISTORIAL' ? 'active' : ''}`}
                        onClick={() => setDateScope('HISTORIAL')}
                      >
                        Historial completo
                      </button>
                    </>
                  )}

                  <select
                    aria-label="Filtrar por franja horaria"
                    className="select"
                    style={{ minHeight: 40, width: 'auto', padding: '6px 12px', fontSize: '0.82rem' }}
                    value={hourSlot}
                    onChange={(e) => setHourSlot(e.target.value)}
                  >
                    <option value="TODAS">Hora: Todas</option>
                    <option value="MANANA">Mañana (00:00 - 11:59)</option>
                    <option value="TARDE">Tarde (12:00 - 17:59)</option>
                    <option value="NOCHE">Noche (18:00 - 23:59)</option>
                  </select>
                </div>
              </div>
            </section>

            {entriesLoading ? (
              <div className="empty-state">Cargando registros de ingresos en tiempo real...</div>
            ) : filteredEntries.length === 0 ? (
              <div className="empty-state">
                <p style={{ margin: 0, fontWeight: 700, color: '#e2e8f0' }}>
                  No hay ingresos que coincidan con el filtro actual.
                </p>
                <p style={{ margin: '6px 0 0', fontSize: '0.88rem' }}>
                  {canCreateEntries
                    ? 'Usa "Nuevo ingreso" o "Importar correo institucional" para cargar registros.'
                    : 'Los registros cargados aparecerán aquí automáticamente.'}
                </p>
              </div>
            ) : (
              <section className="entries-grid" aria-label="Lista de ingresos">
                {filteredEntries.map((entry) => {
                  const isPending = entry.status === 'PENDIENTE';
                  const isEntered = entry.status === 'INGRESO';
                  const isExited = entry.status === 'SALIDA';
                  const hasEntered = isEntered || isExited;
                  const isBusy = busyId === entry.id;
                  const isPendingSync = pendingSyncIds.has(entry.id);
                  return (
                    <article
                      key={entry.id}
                      className={`entry-card ${
                        isPending ? 'is-pending' : isExited ? 'is-exited' : 'is-entered'
                      } ${isPendingSync ? 'has-pending-sync' : ''}`}
                    >
                      <div className="entry-top">
                        <span
                          className={`pill ${
                            isPending ? 'pill-pending' : isExited ? 'pill-salida' : 'pill-ingreso'
                          }`}
                        >
                          {isPending ? (
                            <Clock size={13} />
                          ) : isExited ? (
                            <LogOut size={13} />
                          ) : (
                            <CheckCircle2 size={13} />
                          )}
                          {isPending
                            ? 'PENDIENTE POR INGRESAR'
                            : isExited
                            ? 'YA SALIÓ'
                            : 'YA INGRESÓ'}
                        </span>
                        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                          {isPendingSync && (
                            <span className="pill pill-offline-sync">
                              <CloudOff size={12} />
                              Pendiente de sincronizar
                            </span>
                          )}
                          <span className="pill pill-neutral">{visitorLabel(entry.visitorType)}</span>
                          <span className="pill pill-neutral">
                            {entry.source === 'CORREO_INSTITUCIONAL' ? 'Correo' : 'Manual'}
                          </span>
                        </div>
                      </div>

                      <div>
                        <h2 className="entry-name">
                          {entry.rank && <span className="entry-rank">{entry.rank}</span>}
                          {entry.fullName}
                        </h2>
                        <div className="time-col-sub">Fecha autorizada: {entry.entryDate}</div>
                      </div>

                      <div className="entry-meta-grid">
                        <div className="meta-box">
                          <div className="meta-label">Cédula / Documento</div>
                          <div className="meta-val mono">{entry.documentNumber || 'S/D'}</div>
                        </div>
                        <div className="meta-box">
                          <div className="meta-label">Placa / Acceso</div>
                          <div className="meta-val mono">{entry.plate || 'Peatonal'}</div>
                        </div>
                        <div className="meta-box" style={{ gridColumn: '1 / -1' }}>
                          <div className="meta-label">Dependencia / Unidad destino</div>
                          <div className="meta-val">{entry.destinationUnit}</div>
                          {entry.reason && (
                            <div className="time-col-sub" style={{ marginTop: 4 }}>
                              <strong>Motivo:</strong> {entry.reason}
                            </div>
                          )}
                        </div>
                      </div>

                      <div className="time-compare time-compare-3">
                        <div>
                          <div className="time-col-label">Hora programada</div>
                          <div className="time-col-val mono">
                            {entry.expectedTime ? entry.expectedTime : 'Sin hora fija'}
                          </div>
                        </div>
                        <div>
                          <div className="time-col-label">Hora real de ingreso</div>
                          <div
                            className="time-col-val mono"
                            style={{ color: !hasEntered ? '#94a3b8' : '#34d399' }}
                          >
                            {!hasEntered ? 'Pendiente' : formatTimeHm(entry.checkInAt)}
                          </div>
                          {hasEntered && entry.checkInByName && (
                            <div className="time-col-sub">
                              Marcó: {entry.checkInByName} ({formatDateTimeShort(entry.checkInAt)})
                            </div>
                          )}
                        </div>
                        <div>
                          <div className="time-col-label">Hora de salida</div>
                          <div
                            className="time-col-val mono"
                            style={{ color: isExited ? '#38bdf8' : '#94a3b8' }}
                          >
                            {isExited ? formatTimeHm(entry.checkOutAt) : isEntered ? 'En sitio' : '—'}
                          </div>
                          {isExited && entry.checkOutByName && (
                            <div className="time-col-sub">
                              Marcó: {entry.checkOutByName} ({formatDateTimeShort(entry.checkOutAt)})
                            </div>
                          )}
                        </div>
                      </div>

                      {entry.notes && (
                        <div className="notes-box">
                          <strong>Observaciones:</strong> {entry.notes}
                        </div>
                      )}

                      {(() => {
                        const staged = stagedPlateByEntry[entry.id] || null;
                        const rawPlateUrl = staged?.dataUrl || entry.plateImageData || '';
                        const plateImgUrl = isSafePlateImageDataUrl(rawPlateUrl) ? rawPlateUrl : '';
                        const plateImgName = staged?.fileName || entry.plateImageName || 'placa_vehiculo.jpg';
                        return (
                          <>
                            {plateImgUrl && (
                              <PlateImagePreview
                                fileName={plateImgName}
                                dataUrl={plateImgUrl}
                                subtitle={
                                  staged
                                    ? 'Lista para guardar al marcar ingreso'
                                    : 'Foto de placa adjunta (toca para ampliar)'
                                }
                                onZoom={() =>
                                  setPreviewImageModal({ fileName: plateImgName, dataUrl: plateImgUrl })
                                }
                                onRemove={
                                  staged
                                    ? () =>
                                        setStagedPlateByEntry((prev) => {
                                          const next = { ...prev };
                                          delete next[entry.id];
                                          return next;
                                        })
                                    : undefined
                                }
                              />
                            )}

                            {isPending && (
                              <div className="checkin-action-row">
                                <button
                                  type="button"
                                  className="btn btn-checkin"
                                  disabled={isBusy}
                                  onClick={() => handleCheckIn(entry)}
                                >
                                  <CheckCircle2 size={20} />
                                  {isBusy ? 'Registrando...' : 'Marcar ingreso ahora'}
                                </button>
                                <PlateImageAttachTrigger
                                  disabled={isBusy}
                                  title="Adjuntar imagen de la placa"
                                  onSelected={(attachment) =>
                                    setStagedPlateByEntry((prev) => ({
                                      ...prev,
                                      [entry.id]: attachment,
                                    }))
                                  }
                                  onError={(msg) => setErrorMsg(msg)}
                                />
                              </div>
                            )}

                            {isEntered && (
                              <div className="checkin-action-row">
                                <button
                                  type="button"
                                  className="btn btn-checkout"
                                  disabled={isBusy}
                                  onClick={() => handleCheckOut(entry)}
                                >
                                  <LogOut size={20} />
                                  {isBusy ? 'Registrando salida...' : 'Marcar salida ahora'}
                                </button>
                                <PlateImageAttachTrigger
                                  disabled={isBusy}
                                  title={
                                    plateImgUrl
                                      ? 'Cambiar imagen de la placa'
                                      : 'Adjuntar imagen de la placa'
                                  }
                                  onSelected={(attachment) =>
                                    handleAttachEntryPlate(entry, attachment)
                                  }
                                  onError={(msg) => setErrorMsg(msg)}
                                />
                              </div>
                            )}
                          </>
                        );
                      })()}

                      {(effectiveIsAdmin || canSendToTrash) && (
                        <div className="entry-footer-actions">
                          {effectiveIsAdmin && (
                            <button
                              type="button"
                              className="btn btn-sm btn-outline"
                              style={{ flex: 1 }}
                              onClick={() =>
                                setEditorEntry({
                                  mode: 'edit',
                                  item: { ...entry },
                                })
                              }
                            >
                              <Edit3 size={15} />
                              Editar
                            </button>
                          )}
                          {canSendToTrash && (
                            <button
                              type="button"
                              className="btn btn-sm btn-danger-outline"
                              style={!effectiveIsAdmin ? { flex: 1 } : undefined}
                              disabled={isBusy}
                              onClick={() => handleMoveToTrash(entry)}
                            >
                              <Trash2 size={15} />
                              Enviar a papelera
                            </button>
                          )}
                        </div>
                      )}
                    </article>
                  );
                })}
              </section>
            )}
          </>
        ) : activeTab === 'PAPELERA' && effectiveIsAdmin ? (
          <TrashEntriesSection
            items={trashedEntries}
            loading={trashedLoading}
            busyId={busyId}
            onRestore={handleRestoreFromTrash}
            onPermanentDelete={handlePermanentDeleteFromTrash}
          />
        ) : effectiveIsAdmin ? (
          <UsersManagementSection
            users={users}
            currentUid={profile.userId}
            busyId={busyId}
            onChangeRole={handleRoleChange}
            onDeleteUser={handleDeleteUser}
          />
        ) : null}
      </main>

      {editorEntry &&
        ((editorEntry.mode === 'create' && canCreateEntries) ||
          (editorEntry.mode === 'edit' && effectiveIsAdmin)) && (
          <EntryEditorModal
            mode={editorEntry.mode}
            initialData={editorEntry.item}
            activeOnSiteEntries={onSiteEntries}
            onClose={() => setEditorEntry(null)}
            onSave={async (formValues) => {
              if (editorEntry.mode === 'create') {
                if (!canCreateEntries) throw new Error('Permisos insuficientes.');
                const res = await createEntry(formValues, profile);
                showToast(
                  res?.queuedOffline
                    ? 'Ingreso guardado offline. Se sincronizará al volver el internet.'
                    : 'Ingreso creado'
                );
              } else {
                if (!effectiveIsAdmin) throw new Error('Solo el Administrador puede editar.');
                const res = await updateEntry(editorEntry.item, formValues, profile);
                showToast(
                  res?.queuedOffline
                    ? 'Actualización guardada offline. Se sincronizará al volver el internet.'
                    : 'Ingreso actualizado'
                );
              }
              setEditorEntry(null);
            }}
          />
        )}

      {showImportModal && canCreateEntries && (
        <EmailImportModal
          onClose={() => setShowImportModal(false)}
          onImport={async (drafts) => {
            if (!canCreateEntries) throw new Error('Permisos insuficientes.');
            const res = await importEntriesBatch(drafts, profile);
            showToast(
              res?.queuedOffline
                ? `${res.count} ingreso(s) guardados offline. Se subirán al volver el internet.`
                : `${res.count} ingreso(s) importados`
            );
            setShowImportModal(false);
          }}
        />
      )}

      {showExcelModal && effectiveIsAdmin && (
        <ExcelExportModal
          profile={profile}
          onClose={() => setShowExcelModal(false)}
          onExported={(res) =>
            showToast(
              `Excel generado (${res.entryCount} registros, ${res.imageCount} fotos): ${res.fileName}`
            )
          }
        />
      )}

      {previewImageModal && isSafePlateImageDataUrl(previewImageModal.dataUrl) && (
        <div
          className="modal-backdrop"
          role="dialog"
          aria-modal="true"
          onClick={() => setPreviewImageModal(null)}
        >
          <div className="modal-card" style={{ maxWidth: 520 }} onClick={(e) => e.stopPropagation()}>
            <div className="modal-header">
              <h2 className="modal-title">{previewImageModal.fileName || 'Imagen de placa'}</h2>
              <button
                type="button"
                className="btn btn-sm btn-outline"
                onClick={() => setPreviewImageModal(null)}
              >
                <X size={16} />
              </button>
            </div>
            <img
              src={previewImageModal.dataUrl}
              alt="Vista ampliada de la placa"
              style={{
                width: '100%',
                maxHeight: '70dvh',
                objectFit: 'contain',
                borderRadius: 12,
                background: '#0a1222',
              }}
            />
          </div>
        </div>
      )}

      {toastMsg && (
        <div className="toast" role="status" aria-live="polite">
          {toastMsg}
        </div>
      )}
    </div>
  );
}

function ExcelExportModal({ profile, onClose, onExported }) {
  const [busyPeriod, setBusyPeriod] = useState('');
  const [err, setErr] = useState('');

  async function handleDownload(periodKey) {
    if (!profile || profile.role !== 'ADMIN' || profile.active !== true) {
      setErr('Solo el rol Administrador puede descargar reportes Excel.');
      return;
    }
    setErr('');
    setBusyPeriod(periodKey);
    try {
      const allEntries = await fetchAllEntriesForUnit(profile.unitId || UNIT_ID);
      const result = buildAccessExcelBlob(allEntries, periodKey, profile);
      downloadBlobFile(result.blob, result.fileName);
      onExported(result);
    } catch (error) {
      setErr(humanizeFirebaseError(error));
    } finally {
      setBusyPeriod('');
    }
  }

  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true">
      <div className="modal-card" style={{ maxWidth: 520 }}>
        <div className="modal-header">
          <h2 className="modal-title" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <FileSpreadsheet size={20} color="#38bdf8" />
            Descargar Excel de ingresos y salidas
          </h2>
          <button
            type="button"
            className="btn btn-sm btn-outline"
            disabled={Boolean(busyPeriod)}
            onClick={onClose}
          >
            <X size={16} />
          </button>
        </div>

        <p style={{ margin: '0 0 14px', fontSize: '0.88rem', color: 'var(--text-muted)' }}>
          Incluye todos los datos de ingresos y salidas junto con las fotografías de placas
          adjuntas incrustadas dentro del archivo <code>.xlsx</code>. Disponible únicamente para el
          rol <strong>Administrador</strong>.
        </p>

        {err && (
          <div className="error-banner" role="alert">
            <AlertCircle size={17} />
            <span>{err}</span>
          </div>
        )}

        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          {[REPORT_PERIODS.LAST_24H, REPORT_PERIODS.LAST_7D].map((p) => {
            const isWorking = busyPeriod === p.id;
            return (
              <button
                key={p.id}
                type="button"
                className="btn btn-outline"
                disabled={Boolean(busyPeriod)}
                onClick={() => handleDownload(p.id)}
                style={{
                  width: '100%',
                  justifyContent: 'space-between',
                  textAlign: 'left',
                  padding: '12px 14px',
                  height: 'auto',
                }}
              >
                <div>
                  <div style={{ fontWeight: 800, color: '#fff' }}>{p.title}</div>
                  <div className="time-col-sub" style={{ marginTop: 2 }}>
                    {p.subtitle}
                  </div>
                </div>
                <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, color: '#38bdf8' }}>
                  <Download size={18} />
                  {isWorking ? 'Generando...' : '.XLSX'}
                </span>
              </button>
            );
          })}
        </div>

        <div className="modal-footer">
          <button
            type="button"
            className="btn btn-outline"
            disabled={Boolean(busyPeriod)}
            onClick={onClose}
          >
            Cerrar
          </button>
        </div>
      </div>
    </div>
  );
}

function PlateImageAttachTrigger({ disabled = false, title = 'Adjuntar imagen de la placa', onSelected, onError }) {
  const [menuOpen, setMenuOpen] = useState(false);
  const [processing, setProcessing] = useState(false);
  const [isTouchDevice, setIsTouchDevice] = useState(() => {
    if (typeof window === 'undefined') return false;
    return Boolean(
      window.matchMedia?.('(pointer: coarse)')?.matches ||
        (typeof navigator !== 'undefined' && navigator.maxTouchPoints > 0)
    );
  });
  const wrapRef = useRef(null);
  const cameraInputRef = useRef(null);
  const galleryInputRef = useRef(null);

  useEffect(() => {
    if (typeof window === 'undefined' || !window.matchMedia) return undefined;
    const mq = window.matchMedia('(pointer: coarse)');
    const sync = () =>
      setIsTouchDevice(Boolean(mq.matches || (typeof navigator !== 'undefined' && navigator.maxTouchPoints > 0)));
    sync();
    mq.addEventListener?.('change', sync);
    return () => mq.removeEventListener?.('change', sync);
  }, []);

  useEffect(() => {
    if (!menuOpen) return undefined;
    const handlePointerDown = (event) => {
      if (wrapRef.current && !wrapRef.current.contains(event.target)) {
        setMenuOpen(false);
      }
    };
    const handleKeyDown = (event) => {
      if (event.key === 'Escape') setMenuOpen(false);
    };
    document.addEventListener('pointerdown', handlePointerDown);
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('pointerdown', handlePointerDown);
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [menuOpen]);

  async function handleFileChange(e) {
    const file = e.target.files?.[0];
    e.target.value = '';
    setMenuOpen(false);
    if (!file) return;
    setProcessing(true);
    try {
      const attachment = await compressPlateImageFile(file);
      onSelected(attachment);
    } catch (err) {
      onError?.(err?.message || 'No se pudo procesar la imagen.');
    } finally {
      setProcessing(false);
    }
  }

  function handleTriggerClick() {
    if (!isTouchDevice) {
      // En escritorio (PC/Mac), abre directamente el selector estándar de archivos sin bloquear con menú móvil
      galleryInputRef.current?.click();
      return;
    }
    setMenuOpen((prev) => !prev);
  }

  return (
    <div ref={wrapRef} className="plate-attach-wrap">
      <input
        ref={cameraInputRef}
        type="file"
        accept="image/*"
        capture="environment"
        style={{ display: 'none' }}
        onChange={handleFileChange}
      />
      <input
        ref={galleryInputRef}
        type="file"
        accept="image/*"
        style={{ display: 'none' }}
        onChange={handleFileChange}
      />
      <button
        type="button"
        className="btn btn-icon-attach"
        title={title}
        aria-label={title}
        aria-expanded={isTouchDevice ? menuOpen : undefined}
        disabled={disabled || processing}
        onClick={handleTriggerClick}
      >
        <Camera size={20} />
      </button>
      {menuOpen && isTouchDevice && (
        <div className="plate-source-menu" role="menu">
          <button
            type="button"
            className="btn btn-sm btn-primary"
            onClick={() => galleryInputRef.current?.click()}
          >
            <ImageIcon size={15} />
            Galería / Archivo
          </button>
          <button
            type="button"
            className="btn btn-sm btn-outline"
            onClick={() => cameraInputRef.current?.click()}
          >
            <Camera size={15} />
            Tomar foto ahora
          </button>
        </div>
      )}
    </div>
  );
}

function PlateImagePreview({ fileName, dataUrl, subtitle, onZoom, onRemove }) {
  const safeSrc = isSafePlateImageDataUrl(dataUrl) ? dataUrl : '';
  return (
    <div className="plate-preview-card">
      <button
        type="button"
        className="plate-preview-thumb-btn"
        onClick={onZoom}
        title="Ampliar foto de la placa"
      >
        {safeSrc ? (
          <img src={safeSrc} alt={`Placa ${fileName}`} className="plate-preview-thumb" />
        ) : (
          <ImageIcon size={20} />
        )}
        <span className="plate-preview-zoom-badge">
          <ZoomIn size={12} />
        </span>
      </button>
      <div className="plate-preview-meta">
        <div className="plate-preview-name">{fileName || 'placa_vehiculo.jpg'}</div>
        <div className="time-col-sub">{subtitle || 'Foto de placa adjunta'}</div>
      </div>
      {onRemove && (
        <button
          type="button"
          className="btn btn-sm btn-danger-outline"
          onClick={onRemove}
          aria-label="Quitar imagen adjunta"
        >
          <X size={15} />
        </button>
      )}
    </div>
  );
}

function TrashEntriesSection({ items, loading, busyId, onRestore, onPermanentDelete }) {
  const [search, setSearch] = useState('');
  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return items;
    return items.filter((item) =>
      [
        item.rank,
        item.fullName,
        item.documentNumber,
        item.plate,
        item.destinationUnit,
        item.deletedByName,
      ]
        .join(' ')
        .toLowerCase()
        .includes(q)
    );
  }, [items, search]);

  return (
    <section aria-label="Papelera de ingresos">
      <div className="info-banner" style={{ borderColor: 'var(--danger-border)', background: 'var(--danger-soft)', color: '#fecaca' }}>
        <strong>Papelera exclusiva de Administrador:</strong> Los ingresos eliminados aquí no aparecen en la lista principal, contadores ni reportes Excel. Puedes restaurarlos o eliminarlos definitivamente.
      </div>

      <div className="controls-panel">
        <div className="search-box">
          <Search size={18} className="search-icon" />
          <input
            type="search"
            className="input search-input"
            placeholder="Buscar en papelera por nombre, cédula, placa o quién lo eliminó..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </div>
      </div>

      {loading ? (
        <div className="empty-state">Cargando papelera en tiempo real...</div>
      ) : filtered.length === 0 ? (
        <div className="empty-state">
          <p style={{ margin: 0, fontWeight: 700, color: '#e2e8f0' }}>
            {items.length === 0 ? 'La papelera está vacía' : 'Sin coincidencias en la papelera'}
          </p>
          <p style={{ margin: '6px 0 0', fontSize: '0.88rem' }}>
            {items.length === 0
              ? 'Cuando un Administrador o Diamante Alma envíe un ingreso a la papelera aparecerá aquí con fecha y responsable.'
              : 'Prueba con otro criterio de búsqueda.'}
          </p>
        </div>
      ) : (
        <div className="entries-grid">
          {filtered.map((item) => {
            const isBusy = busyId === item.id;
            return (
              <article key={item.id} className="entry-card" style={{ borderLeft: '5px solid var(--danger)' }}>
                <div className="entry-top">
                  <span className="pill pill-neutral">Estado previo: {item.status}</span>
                  <span className="pill pill-neutral">{item.entryDate}</span>
                </div>

                <div>
                  <h3 className="entry-name">
                    {item.rank && <span className="entry-rank">{item.rank}</span>}
                    {item.fullName}
                  </h3>
                  <div className="time-col-sub">
                    C.C. {item.documentNumber || 'S/D'} · {item.plate || 'Peatonal'}
                  </div>
                </div>

                <div className="meta-box">
                  <div className="meta-label">Dependencia / Unidad destino</div>
                  <div className="meta-val">{item.destinationUnit}</div>
                </div>

                <div className="error-banner" style={{ marginBottom: 0 }}>
                  <Trash2 size={16} style={{ flexShrink: 0, marginTop: 2 }} />
                  <div>
                    <div>
                      <strong>Eliminado por:</strong> {item.deletedByName || 'Usuario autorizado'}
                    </div>
                    <div className="time-col-sub" style={{ color: '#fecaca', marginTop: 2 }}>
                      Fecha de eliminación: {formatDateTimeShort(item.deletedAt)}
                    </div>
                  </div>
                </div>

                <div className="entry-footer-actions">
                  <button
                    type="button"
                    className="btn btn-sm btn-primary"
                    style={{ flex: 1, background: 'var(--emerald-strong)', borderColor: '#34d399' }}
                    disabled={isBusy}
                    onClick={() => onRestore(item)}
                  >
                    <RotateCcw size={15} />
                    Restaurar
                  </button>
                  <button
                    type="button"
                    className="btn btn-sm btn-danger-outline"
                    style={{ flex: 1 }}
                    disabled={isBusy}
                    onClick={() => onPermanentDelete(item)}
                  >
                    <Trash2 size={15} />
                    Eliminar definitivo
                  </button>
                </div>
              </article>
            );
          })}
        </div>
      )}
    </section>
  );
}

function UsersManagementSection({ users, currentUid, busyId, onChangeRole, onDeleteUser }) {
  return (
    <section aria-label="Gestión de usuarios y roles">
      <div className="info-banner">
        <strong>Control estricto de permisos:</strong> Ningún usuario recibe rol al registrarse.
        Solo tú como Administrador puedes asignar Operador, Diamante Alma (registra ingresos y envía
        a papelera) o Administrador, suspender cuentas o eliminar usuarios (sin bloquear su correo
        para que puedan volver a ingresar).
      </div>

      {users.length === 0 ? (
        <div className="empty-state">No hay usuarios registrados aún.</div>
      ) : (
        <div className="users-grid">
          {users.map((u) => {
            const isSelf = u.userId === currentUid;
            const isBusy = busyId === u.userId;
            return (
              <article key={u.userId} className="user-card">
                <div className="entry-top">
                  <div>
                    <h3 style={{ margin: 0, fontSize: '1rem', fontWeight: 800 }}>
                      {u.displayName} {isSelf && '(Tú)'}
                    </h3>
                    <div className="time-col-sub">{u.email}</div>
                  </div>
                  <span
                    className={`pill ${
                      u.role === 'ADMIN'
                        ? 'pill-admin'
                        : u.role === 'DIAMANTE_ALMA'
                        ? 'pill-diamante'
                        : u.role === 'OPERATOR'
                        ? 'pill-operator'
                        : 'pill-pending'
                    }`}
                  >
                    {u.role === 'DIAMANTE_ALMA' ? 'Diamante Alma' : u.role}
                  </span>
                </div>

                <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                  <span className={`pill ${u.active ? 'pill-ingreso' : 'pill-danger'}`}>
                    {u.active ? 'Cuenta Activa' : 'Sin Acceso / Suspendida'}
                  </span>
                </div>

                {!isSelf && (
                  <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                    <button
                      type="button"
                      className="btn btn-sm btn-primary"
                      style={{ flex: 1 }}
                      disabled={isBusy || (u.role === 'OPERATOR' && u.active)}
                      onClick={() => onChangeRole(u, 'OPERATOR', true)}
                    >
                      <UserCheck size={15} />
                      Operador
                    </button>

                    <button
                      type="button"
                      className="btn btn-sm btn-outline"
                      style={{ flex: 1 }}
                      disabled={isBusy || (u.role === 'DIAMANTE_ALMA' && u.active)}
                      onClick={() => onChangeRole(u, 'DIAMANTE_ALMA', true)}
                    >
                      <Gem size={15} />
                      Diamante Alma
                    </button>

                    <button
                      type="button"
                      className="btn btn-sm btn-outline"
                      disabled={isBusy || (u.role === 'ADMIN' && u.active)}
                      onClick={() => onChangeRole(u, 'ADMIN', true)}
                    >
                      <Shield size={15} />
                      Admin
                    </button>

                    <button
                      type="button"
                      className="btn btn-sm btn-danger-outline"
                      disabled={isBusy}
                      onClick={() =>
                        onChangeRole(
                          u,
                          u.active ? 'PENDING' : u.role === 'PENDING' ? 'OPERATOR' : u.role,
                          !u.active
                        )
                      }
                    >
                      {u.active ? 'Suspender' : 'Reactivar'}
                    </button>

                    <button
                      type="button"
                      className="btn btn-sm btn-danger-outline"
                      disabled={isBusy}
                      onClick={() => onDeleteUser(u)}
                    >
                      <Trash2 size={15} />
                      Eliminar
                    </button>
                  </div>
                )}
              </article>
            );
          })}
        </div>
      )}
    </section>
  );
}

function EntryEditorModal({ mode, initialData, activeOnSiteEntries = [], onClose, onSave }) {
  const [form, setForm] = useState({
    entryDate: initialData.entryDate || todayIsoDate(),
    rank: initialData.rank || '',
    fullName: initialData.fullName || '',
    documentNumber: initialData.documentNumber || '',
    plate: formatPlateInput(initialData.plate || ''),
    plateImageName: initialData.plateImageName || '',
    plateImageData: initialData.plateImageData || '',
    destinationUnit: initialData.destinationUnit || '',
    reason: initialData.reason || '',
    visitorType: initialData.visitorType || 'VISITANTE',
    expectedTime: normalizeExistingTime(initialData.expectedTime || ''),
    notes: initialData.notes || '',
    status: initialData.status || 'PENDIENTE',
  });
  const [saving, setSaving] = useState(false);
  const [err, setErr] = useState('');
  const [duplicateConfirmed, setDuplicateConfirmed] = useState(false);
  const [zoomPhoto, setZoomPhoto] = useState(false);

  const duplicateMatches = useMemo(
    () =>
      findActiveOnSiteDuplicates(
        activeOnSiteEntries,
        form.documentNumber,
        form.plate,
        initialData.id || null
      ),
    [activeOnSiteEntries, form.documentNumber, form.plate, initialData.id]
  );

  async function handleSubmit(e) {
    e.preventDefault();
    setErr('');
    if (form.fullName.trim().length < 2) {
      setErr('Ingresa el nombre completo (mínimo 2 caracteres).');
      return;
    }
    if (!form.documentNumber.trim()) {
      setErr('Ingresa el número de cédula (solo números).');
      return;
    }
    if (!form.destinationUnit.trim()) {
      setErr('Ingresa la dependencia o unidad destino.');
      return;
    }
    if (!isValidIsoDate(form.entryDate)) {
      setErr('Selecciona una fecha válida (YYYY-MM-DD).');
      return;
    }
    if (!isValidEntryTime(form.expectedTime)) {
      setErr('La hora debe tener formato 24h HH:MM (ej. 08:30).');
      return;
    }
    if (mode === 'create' && duplicateMatches.length > 0 && !duplicateConfirmed) {
      setDuplicateConfirmed(true);
      setErr(
        'Advertencia: ya existe un ingreso "En sitio" con esa cédula o placa. Verifica o presiona "Registrar de todos modos".'
      );
      return;
    }
    setSaving(true);
    try {
      await onSave({
        ...form,
        plate: formatPlateInput(form.plate),
        expectedTime: form.expectedTime.trim(),
      });
    } catch (error) {
      setErr(humanizeFirebaseError(error));
      setSaving(false);
    }
  }

  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true">
      <div className="modal-card">
        <div className="modal-header">
          <h2 className="modal-title">
            {mode === 'create' ? 'Nuevo ingreso autorizado' : 'Editar registro de ingreso'}
          </h2>
          <button type="button" className="btn btn-sm btn-outline" onClick={onClose}>
            <X size={16} />
          </button>
        </div>

        {err && (
          <div className="error-banner" role="alert">
            <AlertCircle size={17} />
            <span>{err}</span>
          </div>
        )}

        <form onSubmit={handleSubmit}>
          <div className="form-grid">
            <div className="form-field">
              <label className="form-label">Grado / Tratamiento</label>
              <input
                className="input"
                placeholder="Ej. Tte., Ing., Sr."
                maxLength={40}
                value={form.rank}
                onChange={(e) => setForm({ ...form, rank: e.target.value })}
              />
            </div>

            <div className="form-field">
              <label className="form-label">Nombre completo *</label>
              <input
                className="input"
                required
                minLength={2}
                maxLength={120}
                placeholder="Ej. Carlos Mendoza"
                value={form.fullName}
                onChange={(e) => setForm({ ...form, fullName: e.target.value })}
              />
            </div>

            <div className="form-field">
              <label className="form-label">Cédula / Documento * (solo números)</label>
              <input
                className="input mono"
                inputMode="numeric"
                pattern="[0-9]*"
                maxLength={20}
                placeholder="Ej. 1020458912"
                value={form.documentNumber}
                onChange={(e) => {
                  setDuplicateConfirmed(false);
                  setErr('');
                  setForm({ ...form, documentNumber: formatDocumentNumberInput(e.target.value) });
                }}
              />
            </div>

            <div className="form-field">
              <label className="form-label">Placa (auto-mayúsculas) y foto</label>
              <div className="plate-input-row">
                <input
                  className="input mono"
                  autoCapitalize="characters"
                  maxLength={10}
                  placeholder="Ej. ABC123"
                  style={{ textTransform: 'uppercase' }}
                  value={form.plate}
                  onChange={(e) => {
                    setDuplicateConfirmed(false);
                    setErr('');
                    setForm({ ...form, plate: formatPlateInput(e.target.value) });
                  }}
                />
                <PlateImageAttachTrigger
                  disabled={saving}
                  title="Adjuntar imagen de la placa"
                  onSelected={(attachment) => {
                    setErr('');
                    setForm((prev) => ({
                      ...prev,
                      plateImageName: attachment.fileName,
                      plateImageData: attachment.dataUrl,
                    }));
                  }}
                  onError={(msg) => setErr(msg)}
                />
              </div>
            </div>

            {duplicateMatches.length > 0 && (
              <div className="form-field full">
                <div className="warning-banner" style={{ marginBottom: 0 }} role="status">
                  <AlertCircle size={18} style={{ flexShrink: 0, marginTop: 2, color: '#fbbf24' }} />
                  <div>
                    <strong>Posible duplicado: ya figura &quot;En sitio&quot;</strong>
                    {duplicateMatches.slice(0, 2).map(({ entry, matchedByDocument, matchedByPlate }) => {
                      const reasons = [
                        matchedByDocument ? `C.C. ${entry.documentNumber}` : '',
                        matchedByPlate ? `Placa ${entry.plate}` : '',
                      ]
                        .filter(Boolean)
                        .join(' y ');
                      return (
                        <div key={entry.id} style={{ marginTop: 3 }}>
                          {entry.rank ? `${entry.rank} ` : ''}
                          {entry.fullName} ({reasons}) ingresó a {entry.destinationUnit} y aún no marca salida.
                        </div>
                      );
                    })}
                  </div>
                </div>
              </div>
            )}

            {isSafePlateImageDataUrl(form.plateImageData) && (
              <div className="form-field full">
                <PlateImagePreview
                  fileName={form.plateImageName}
                  dataUrl={form.plateImageData}
                  subtitle="Se guardará junto con este ingreso"
                  onZoom={() => setZoomPhoto(true)}
                  onRemove={() =>
                    setForm((prev) => ({
                      ...prev,
                      plateImageName: '',
                      plateImageData: '',
                    }))
                  }
                />
              </div>
            )}

            <div className="form-field">
              <label className="form-label">Dependencia / Unidad destino *</label>
              <input
                className="input"
                maxLength={80}
                placeholder="Ej. Comandancia - Bloque B"
                value={form.destinationUnit}
                onChange={(e) => setForm({ ...form, destinationUnit: e.target.value })}
              />
            </div>

            <div className="form-field">
              <label className="form-label">Tipo de ingreso</label>
              <select
                className="select"
                value={form.visitorType}
                onChange={(e) => setForm({ ...form, visitorType: e.target.value })}
              >
                {VISITOR_TYPES.map((t) => (
                  <option key={t.value} value={t.value}>
                    {t.label}
                  </option>
                ))}
              </select>
            </div>

            <div className="form-field full">
              <label className="form-label">Motivo de ingreso</label>
              <input
                className="input"
                maxLength={160}
                placeholder="Ej. Reunión operativa / Mantenimiento"
                value={form.reason}
                onChange={(e) => setForm({ ...form, reason: e.target.value })}
              />
            </div>

            <div className="form-field">
              <label className="form-label">Fecha autorizada</label>
              <input
                type="date"
                className="input"
                value={form.entryDate}
                onChange={(e) => setForm({ ...form, entryDate: e.target.value })}
              />
            </div>

            <div className="form-field">
              <label className="form-label">Hora de ingreso (auto-números 24h)</label>
              <div className="plate-input-row">
                <input
                  className="input mono"
                  inputMode="numeric"
                  maxLength={5}
                  placeholder="08:30"
                  value={form.expectedTime}
                  onChange={(e) => {
                    setErr('');
                    setForm((prev) => ({
                      ...prev,
                      expectedTime: formatEntryTimeDigits(prev.expectedTime, e.target.value),
                    }));
                  }}
                />
                <button
                  type="button"
                  className="btn btn-sm btn-outline"
                  style={{ minHeight: 48 }}
                  onClick={() => {
                    setErr('');
                    setForm((prev) => ({ ...prev, expectedTime: currentTimeHm() }));
                  }}
                >
                  Ahora
                </button>
              </div>
            </div>

            {mode === 'edit' && (
              <div className="form-field full">
                <label className="form-label">Estado del registro</label>
                <select
                  className="select"
                  value={form.status}
                  onChange={(e) => setForm({ ...form, status: e.target.value })}
                >
                  <option value="PENDIENTE">Pendiente por ingresar</option>
                  <option value="INGRESO">Ya ingresó</option>
                  <option value="SALIDA">Ya salió</option>
                </select>
              </div>
            )}

            <div className="form-field full">
              <label className="form-label">Observaciones</label>
              <textarea
                className="textarea"
                style={{ minHeight: 88 }}
                maxLength={300}
                placeholder="Notas para la garita..."
                value={form.notes}
                onChange={(e) => setForm({ ...form, notes: e.target.value })}
              />
            </div>
          </div>

          <div className="modal-footer">
            <button type="button" className="btn btn-outline" onClick={onClose}>
              Cancelar
            </button>
            <button type="submit" className="btn btn-primary" disabled={saving}>
              {saving
                ? 'Guardando...'
                : mode === 'create'
                ? duplicateMatches.length > 0 && duplicateConfirmed
                  ? 'Registrar de todos modos'
                  : 'Guardar ingreso'
                : 'Actualizar'}
            </button>
          </div>
        </form>

        {zoomPhoto && isSafePlateImageDataUrl(form.plateImageData) && (
          <div
            className="modal-backdrop"
            role="dialog"
            aria-modal="true"
            onClick={() => setZoomPhoto(false)}
          >
            <div className="modal-card" style={{ maxWidth: 500 }} onClick={(e) => e.stopPropagation()}>
              <div className="modal-header">
                <h3 className="modal-title">{form.plateImageName || 'Imagen de placa'}</h3>
                <button
                  type="button"
                  className="btn btn-sm btn-outline"
                  onClick={() => setZoomPhoto(false)}
                >
                  <X size={16} />
                </button>
              </div>
              <img
                src={form.plateImageData}
                alt="Vista ampliada de placa"
                style={{ width: '100%', maxHeight: '65dvh', objectFit: 'contain', borderRadius: 12 }}
              />
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

function EmailImportModal({ onClose, onImport }) {
  const [rawText, setRawText] = useState('');
  const [targetDate, setTargetDate] = useState(todayIsoDate());
  const [saving, setSaving] = useState(false);
  const [err, setErr] = useState('');

  const parsedDrafts = useMemo(
    () => parseInstitutionalEmail(rawText, targetDate),
    [rawText, targetDate]
  );

  async function handleImport() {
    if (!isValidIsoDate(targetDate)) {
      setErr('Selecciona una fecha de ingreso válida.');
      return;
    }
    if (parsedDrafts.length === 0) {
      setErr('No se detectaron registros válidos en el texto pegado.');
      return;
    }
    setErr('');
    setSaving(true);
    try {
      await onImport(parsedDrafts);
    } catch (error) {
      setErr(humanizeFirebaseError(error));
      setSaving(false);
    }
  }

  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true">
      <div className="modal-card">
        <div className="modal-header">
          <h2 className="modal-title">Importar desde correo institucional</h2>
          <button type="button" className="btn btn-sm btn-outline" onClick={onClose}>
            <X size={16} />
          </button>
        </div>

        <p style={{ margin: '0 0 12px', fontSize: '0.88rem', color: 'var(--text-muted)' }}>
          Pega el contenido del correo institucional con bloques (<code>Grado:</code>,{' '}
          <code>Nombre:</code>, <code>Cédula:</code>, <code>Placa:</code>,{' '}
          <code>Dependencia:</code>, <code>Motivo:</code>, <code>Hora programada:</code>) o filas
          separadas por <code>|</code> o <code>;</code>.
        </p>

        {err && (
          <div className="error-banner" role="alert">
            <AlertCircle size={17} />
            <span>{err}</span>
          </div>
        )}

        <div className="form-grid" style={{ marginBottom: 12 }}>
          <div className="form-field">
            <label className="form-label">Fecha de ingreso</label>
            <input
              type="date"
              className="input"
              value={targetDate}
              onChange={(e) => setTargetDate(e.target.value)}
            />
          </div>
          <div className="form-field" style={{ justifyContent: 'flex-end' }}>
            <button
              type="button"
              className="btn btn-outline"
              onClick={() => setRawText(SAMPLE_INSTITUTIONAL_EMAIL)}
            >
              Cargar ejemplo de correo
            </button>
          </div>
        </div>

        <div className="form-field">
          <label className="form-label">Contenido del correo</label>
          <textarea
            className="textarea"
            style={{ minHeight: 200 }}
            placeholder="Pega aquí el texto del correo institucional..."
            value={rawText}
            onChange={(e) => setRawText(e.target.value)}
          />
        </div>

        <div className="info-banner" style={{ marginTop: 12, marginBottom: 0 }}>
          Registros detectados listos para cargar: <strong>{parsedDrafts.length}</strong>
          {parsedDrafts.length > 0 && (
            <ul style={{ margin: '8px 0 0', paddingLeft: 18 }}>
              {parsedDrafts.slice(0, 5).map((d, idx) => (
                <li key={idx}>
                  {d.rank ? `${d.rank} ` : ''}
                  {d.fullName} · CC {d.documentNumber} · {d.destinationUnit}{' '}
                  {d.expectedTime ? `(${d.expectedTime})` : ''}
                </li>
              ))}
              {parsedDrafts.length > 5 && <li>... y {parsedDrafts.length - 5} más</li>}
            </ul>
          )}
        </div>

        <div className="modal-footer">
          <button type="button" className="btn btn-outline" onClick={onClose}>
            Cancelar
          </button>
          <button
            type="button"
            className="btn btn-primary"
            disabled={saving || parsedDrafts.length === 0 || !isValidIsoDate(targetDate)}
            onClick={handleImport}
          >
            <Mail size={17} />
            {saving ? 'Importando...' : `Importar ${parsedDrafts.length} registro(s)`}
          </button>
        </div>
      </div>
    </div>
  );
}
