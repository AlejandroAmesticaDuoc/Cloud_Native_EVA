import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { App } from './app/app';

window.addEventListener('unhandledrejection', (e) => {
  document.body.innerHTML = `<pre style="color:#b91c1c;padding:24px;font-family:monospace;white-space:pre-wrap;">Unhandled Rejection:\n${e.reason?.stack || e.reason}</pre>`;
});

bootstrapApplication(App, appConfig).catch((err) => {
  console.error(err);
  document.body.innerHTML = `<pre style="color:#b91c1c;padding:24px;font-family:monospace;white-space:pre-wrap;">Bootstrap Error:\n${err?.stack || err}</pre>`;
});
