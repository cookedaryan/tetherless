import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import './theme.css';
import './app.css';
import { applyPreferences, loadPreferences } from './preferences';

// Before the first render, so a light-theme user never sees a dark flash on launch.
applyPreferences(loadPreferences());

const host = document.getElementById('root');
if (!host) {
  throw new Error('index.html is missing its root element');
}

createRoot(host).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
