import { getApps, initializeApp } from "@firebase/app";
import { deleteToken, getMessaging, getToken, isSupported, onMessage, type MessagePayload } from "@firebase/messaging";

const firebaseConfig = {
  apiKey: process.env.NEXT_PUBLIC_FIREBASE_API_KEY,
  authDomain: process.env.NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN,
  projectId: process.env.NEXT_PUBLIC_FIREBASE_PROJECT_ID,
  storageBucket: process.env.NEXT_PUBLIC_FIREBASE_STORAGE_BUCKET,
  messagingSenderId: process.env.NEXT_PUBLIC_FIREBASE_MESSAGING_SENDER_ID,
  appId: process.env.NEXT_PUBLIC_FIREBASE_APP_ID,
};
const vapidKey = process.env.NEXT_PUBLIC_FIREBASE_VAPID_KEY;

export function isWebPushConfigured(): boolean {
  return Boolean(firebaseConfig.apiKey && firebaseConfig.authDomain && firebaseConfig.projectId &&
    firebaseConfig.messagingSenderId && firebaseConfig.appId && vapidKey);
}

async function waitForActivation(registration: ServiceWorkerRegistration): Promise<void> {
  const worker = registration.installing ?? registration.waiting ?? registration.active;
  if (!worker) throw new Error("The notification service worker could not be started.");
  if (worker.state === "activated") return;
  await new Promise<void>((resolve, reject) => {
    const handleStateChange = () => {
      if (worker.state === "activated" || worker.state === "redundant") {
        worker.removeEventListener("statechange", handleStateChange);
        if (worker.state === "activated") resolve();
        else reject(new Error("The notification service worker failed to activate. Refresh and try again."));
      }
    };
    worker.addEventListener("statechange", handleStateChange);
    handleStateChange();
  });
}

function firebaseMessaging() {
  const app = getApps().find((candidate) => candidate.name === "qrdine-web")
    ?? initializeApp(firebaseConfig, "qrdine-web");
  return getMessaging(app);
}

export async function registerWebPush(): Promise<string> {
  if (!isWebPushConfigured()) throw new Error("Background alerts need the Firebase web app settings and Web Push certificate key configured for this deployment.");
  if (!("serviceWorker" in navigator) || !("Notification" in window)) {
    throw new Error("This browser does not support background notifications.");
  }
  if (!await isSupported()) throw new Error("This browser does not support Firebase background notifications.");
  const permission = Notification.permission === "default" ? await Notification.requestPermission() : Notification.permission;
  if (permission !== "granted") throw new Error("Notification permission was not granted. Change it in your browser's site settings.");
  const registration = await navigator.serviceWorker.register(
    "/firebase-messaging/firebase-messaging-sw.js",
    { scope: "/firebase-messaging/" },
  );
  await waitForActivation(registration);
  const token = await getToken(firebaseMessaging(), { vapidKey, serviceWorkerRegistration: registration });
  if (!token) throw new Error("Firebase did not return a notification token. Check the Firebase web app and VAPID configuration.");
  return token;
}

export async function unregisterWebPush(): Promise<void> {
  if (!isWebPushConfigured() || !("serviceWorker" in navigator) || !await isSupported()) return;
  await deleteToken(firebaseMessaging());
  const registration = await navigator.serviceWorker.getRegistration("/firebase-messaging/");
  await registration?.unregister();
}

export async function listenForWebPush(onPush: (payload: MessagePayload) => void): Promise<() => void> {
  if (!isWebPushConfigured() || !("serviceWorker" in navigator) || !await isSupported()) return () => {};
  return onMessage(firebaseMessaging(), onPush);
}
