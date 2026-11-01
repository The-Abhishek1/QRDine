importScripts(
  "https://www.gstatic.com/firebasejs/11.10.0/firebase-app-compat.js",
  "https://www.gstatic.com/firebasejs/11.10.0/firebase-messaging-compat.js",
  "/firebase-config.js",
);

if (self.__QRDINE_FIREBASE_CONFIG?.apiKey && self.__QRDINE_FIREBASE_CONFIG?.appId) {
  firebase.initializeApp(self.__QRDINE_FIREBASE_CONFIG);
  firebase.messaging().onBackgroundMessage((payload) => {
    const data = payload.data || {};
    self.registration.showNotification(data.title || "QR Dine restaurant alert", {
      body: data.body || "A new restaurant update needs your attention.",
      icon: "/icons/icon-192.png",
      badge: "/icons/icon-192.png",
      tag: data.order_id || data.request_id || data.review_id || "qrdine-restaurant",
      data: { url: data.kind === "online_order_update" ? "/explore" : "/restaurant" },
    });
  });
}

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const url = event.notification.data?.url || "/restaurant";
  event.waitUntil(self.clients.matchAll({ type: "window", includeUncontrolled: true }).then((clients) => {
    const existing = clients.find((client) => "focus" in client && new URL(client.url).origin === self.location.origin);
    if (existing) {
      existing.navigate(url);
      return existing.focus();
    }
    return self.clients.openWindow(url);
  }));
});
