# Security Summary

**Reviewed Files**: 28
**Issues Resolved**: 3

### Issues Resolved
| Area | File | Line | What Was Happening | Impact | Status | How It Was Fixed |
|---|---|---|---|---|---|---|
| Image Data URL Validation | `firestore.rules` | 86 | Plate attachments accepted any `data:image/...` prefix (`matches('^data:image/.*')`), which could allow `data:image/svg+xml` payloads containing embedded script markup to be stored and rendered. | High | Fixed | Restricted `plateImageData` in `firestore.rules`, `AccessRepository.kt`, `ExcelReportGenerator.kt`, `src/accessService.js`, `src/App.jsx`, and `src/excelExport.js` to strict Base64 raster prefixes (`data:image/jpeg;base64,`, `data:image/png;base64,`, `data:image/webp;base64,`) between 29 and 250,000 characters. |
| Android Backup & Transfer Exposure | `app/src/main/AndroidManifest.xml` | 10 | `android:allowBackup="true"` permitted cloud backup and device-to-device transfer of the app's local SQLite Firestore cache, preferences, and temporary files containing personal identity numbers and access logs. | Medium | Fixed | Set `android:allowBackup="false"` in `AndroidManifest.xml` and excluded `database`, `sharedpref`, `file`, and `external` domains in `backup_rules.xml` and `data_extraction_rules.xml`. |
| Role State Invariant | `firestore.rules` | 65 | User profile updates could theoretically write `role: "PENDING"` alongside `active: true`, leaving an inconsistent approval record if an admin update misfired. | Low | Fixed | Enforced `(data.role != 'PENDING' || data.active == false)` in `isValidUserProfile` within `firestore.rules`, and forced `active = false` whenever `PENDING` is written in `AccessRepository.kt` and `src/accessService.js`. |

### Safeguards Verified
| Area | File | How It's Protected |
|---|---|---|
| Authentication & Session Trust | `app/src/main/java/com/example/auth/GoogleAuthHelper.kt`, `src/App.jsx` | Exclusively authenticates via Firebase Google Sign-In (`GetSignInWithGoogleOption` on Android via Credential Manager; `signInWithRedirect` / `signInWithPopup` on Web) with no anonymous auth or hardcoded credentials. |
| Role-Based Access Control | `firestore.rules` | Uses a global default-deny catch-all (`match /{document=**} { allow read, write: if false; }`). Newly registered users self-create strictly as `role: 'PENDING', active: false` (except the verified bootstrapped admin email). `/entries` reads require `isActiveStaff()`, creations/deletions require `isAdmin()`, and operators can only perform scoped check-in/check-out/plate transitions (`PENDIENTE -> INGRESO -> SALIDA`). |
| Multi-Tenant Unit Isolation | `firestore.rules`, `firebase-functions/index.js` | Every `/entries`, `/users`, and `/users/{uid}/fcmTokens` rule verifies `unitId == userUnitId()` (`UNIDAD_PRINCIPAL`), preventing cross-unit data reads or writes. Cloud Functions (`us-central1`) independently verify recipient `ADMIN`, `active == true`, and matching `unitId` before sending push notifications. |
| Spreadsheet XML & Formula Safety | `app/src/main/java/com/example/util/ExcelReportGenerator.kt`, `src/excelExport.js` | All user-controlled strings are written using OOXML `t="inlineStr"` cells passed through XML entity escaping (`escapeXml`) and stripped of invalid XML control characters, preventing XML structure corruption and formula evaluation. |
| Client & Transport Configuration | `vite.config.js`, `capacitor.config.json`, `app/src/main/AndroidManifest.xml` | Production Web builds disable sourcemaps (`sourcemap: false`), Capacitor enforces HTTPS (`allowMixedContent: false`), `FileProvider` and FCM services have `exported="false"`, and no server-side secrets appear in client bundles. |

## PoC Verification

### 1. Non-Raster SVG Data URL Upload Blocked Across Rules, Client Previews, and Excel Export

#### Summary
| Field | Value |
|---|---|
| Issue Type | Unsafe Data URI Type Acceptance (`data:image/svg+xml`) |
| Impact | High |
| Affected File | `firestore.rules:86`, `src/accessService.js:28`, `app/src/main/java/com/example/data/AccessRepository.kt:738` |
| Test Scenario | Submitting `"data:image/svg+xml;base64,PHN2ZyBvbmxvYWQ9YWxlcnQoMSk+PC9zdmc+"` as `plateImageData` in an entry create/update, UI preview render, or Excel `.xlsx` generation request. |

#### How It Was Fixed
Replaced loose `data:image/` prefix checks with an exact raster whitelist (`data:image/jpeg;base64,`, `data:image/png;base64,`, `data:image/webp;base64,`) in Firestore Security Rules, both client repositories, Web `<img src>` render guards, and both Android/Web OOXML Excel media decoders.

#### Step-by-Step Trace
| Step | What Happens in the Code | Outcome |
|---|---|---|
| 1 | Test payload arrives at `validatePlateAttachment` (`src/accessService.js:255`) or `AccessRepository.markCheckInNow` (`AccessRepository.kt:556`) | Routed to `isSafePlateImageDataUrl` |
| 2 | `isSafePlateImageDataUrl` checks exact prefix slice against `jpeg`, `png`, and `webp` headers (`src/accessService.js:32`, `AccessRepository.kt:741`) | Returns `false` and throws validation error |
| 3 | If bypassed directly against Firestore REST/SDK, `isValidEntry` checks `data.plateImageData[0:23]` / `[0:22]` (`firestore.rules:91-93`) | Server rejects write (`PERMISSION_DENIED`) |
| 4 | Even if legacy/corrupted data were read in cache, `PlateImagePreview` (`src/App.jsx:1282`) and `decodePlateImageBytes` (`ExcelReportGenerator.kt:216`, `src/excelExport.js:126`) re-check `isSafePlateImageDataUrl` | Unsafe SVG URI is omitted from DOM and `.xlsx` ZIP media |

#### Conclusion
**Fix Verified** - Untrusted SVG/XML data URIs are rejected at the client validator, server security rules, UI preview renderer, and Excel exporter.

### 2. Android Local Offline Cache Protected From Cloud Backup & Device Transfer

#### Summary
| Field | Value |
|---|---|
| Issue Type | Sensitive Offline Cache Included in Unencrypted App Backup |
| Impact | Medium |
| Affected File | `app/src/main/AndroidManifest.xml:10`, `app/src/main/res/xml/backup_rules.xml`, `app/src/main/res/xml/data_extraction_rules.xml` |
| Test Scenario | Triggering Android Auto Backup or USB/cloud device transfer to extract the 100 MB Firestore persistent SQLite cache (`PersistentCacheSettings`). |

#### How It Was Fixed
Disabled `android:allowBackup="false"` in `AndroidManifest.xml` and explicitly excluded `database`, `sharedpref`, `file`, and `external` storage domains in both Android backup and data-extraction XML policies.

#### Step-by-Step Trace
| Step | What Happens in the Code | Outcome |
|---|---|---|
| 1 | Backup manager inspects application manifest (`AndroidManifest.xml:10`) | Reads `android:allowBackup="false"` |
| 2 | Backup/transfer rules evaluate `backup_rules.xml:3-6` and `data_extraction_rules.xml:4-13` | All `database`, `sharedpref`, `file`, and `external` directories are excluded |
| 3 | Final result | Firestore offline SQLite database and temp captures stay confined to the device's app-private sandbox |

#### Conclusion
**Fix Verified** - Cached access logs and personnel documents cannot be extracted via Android backup or device-transfer manifests.

### 3. Pending User Role Cannot Self-Activate or Read Protected Entries

#### Summary
| Field | Value |
|---|---|
| Issue Type | Privilege Escalation / Inconsistent `PENDING` State |
| Impact | Medium |
| Affected File | `firestore.rules:65`, `firestore.rules:141`, `firestore.rules:198` |
| Test Scenario | A newly signed-in Google account attempts to create `/users/{uid}` with `role: "OPERATOR"` or `role: "PENDING", active: true`, or list `/entries` while unapproved. |

#### How It Was Fixed
Added `(data.role != 'PENDING' || data.active == false)` in `isValidUserProfile` (`firestore.rules:65`) alongside the create/update allowlists (`firestore.rules:133-159`) and mirrored `PENDING -> active = false` normalization in both client repositories.

#### Step-by-Step Trace
| Step | What Happens in the Code | Outcome |
|---|---|---|
| 1 | Non-admin user attempts write on `/users/{uid}` with `role: 'PENDING', active: true` or `role: 'OPERATOR'` | Reaches `match /users/{userId}` `allow create`/`update` |
| 2 | `isValidUserProfile(incoming())` (`firestore.rules:65`) rejects `PENDING` + `active: true`; create rule (`firestore.rules:141-143`) rejects non-`PENDING` for non-bootstrapped accounts | Server rejects write (`PERMISSION_DENIED`) |
| 3 | Unapproved user queries `/entries` (`firestore.rules:198-200`) | `isActiveStaff()` (`firestore.rules:41-46`) evaluates `false`; query denied |

#### Conclusion
**Fix Verified** - Only an active `ADMIN` can promote or activate accounts, and unapproved accounts cannot read or mutate unit access records.
