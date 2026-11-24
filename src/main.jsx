import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App.jsx';
import './styles.css';

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);

if (
  import.meta.env?.PROD &&
  typeof window !== 'undefined' &&
  'serviceWorker' in navigator &&
  !window.Capacitor?.isNativePlatform?.()
) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('./sw.js').catch(() => {
      // Service worker registration is optional; Firestore offline cache remains active.
    });
  });
}
