// kotoba-shell in-app provider bridge — the JavaScript half.
//
// This is what makes a shipped .ipa/.apk able to reach native capabilities.
// The CLI's `native-host provider` path runs providers on a *developer's*
// machine (`xcrun simctl spawn`, `adb shell`); nothing in it survives into a
// distributed build. This shim talks to a provider implementation compiled
// into the app itself.
//
// Injected before any page script runs:
//   - Apple: WKUserScript at .atDocumentStart
//   - Android: WebViewCompat.addDocumentStartJavaScript, falling back to
//     onPageStarted when the WebView provider is too old for that feature.
//
// The two platforms differ only in transport. Apple's
// WKScriptMessageHandlerWithReply already returns a promise from
// postMessage; Android's @JavascriptInterface is synchronous-only and must
// not block, so the native side takes a request id and calls back into
// __kotobaShellDeliver. Callers see one API either way.
(function () {
  "use strict";
  if (window.kotobaShell) { return; }

  var SCHEMA = "kotoba.shell.bridge.v0";
  var pending = Object.create(null);
  var counter = 0;

  function unavailable(command) {
    return Promise.resolve({
      schema: SCHEMA,
      command: command,
      ok: false,
      error: "bridge-unavailable",
      // Deliberately not thrown: a web bundle that also runs in a plain
      // browser (`shadow-cljs watch`, a Pages preview) should be able to
      // branch on this rather than crash.
      detail: "no kotoba-shell native host is attached to this WebView"
    });
  }

  window.__kotobaShellDeliver = function (id, payload) {
    var entry = pending[id];
    if (!entry) { return; }
    delete pending[id];
    try {
      entry.resolve(JSON.parse(payload));
    } catch (err) {
      entry.resolve({ schema: SCHEMA, ok: false, error: "malformed-reply", detail: String(err) });
    }
  };

  function appleInvoke(command, args) {
    return window.webkit.messageHandlers.kotobaShell
      .postMessage({ command: command, args: args || {} })
      .then(function (reply) { return reply; })
      .catch(function (err) {
        return { schema: SCHEMA, command: command, ok: false, error: "bridge-error", detail: String(err) };
      });
  }

  function androidInvoke(command, args) {
    var id = "req-" + (++counter);
    return new Promise(function (resolve) {
      pending[id] = { resolve: resolve };
      try {
        window.KotobaShellNative.request(id, command, JSON.stringify(args || {}));
      } catch (err) {
        delete pending[id];
        resolve({ schema: SCHEMA, command: command, ok: false, error: "bridge-error", detail: String(err) });
      }
    });
  }

  var transport = null;
  if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.kotobaShell) {
    transport = appleInvoke;
  } else if (window.KotobaShellNative && window.KotobaShellNative.request) {
    transport = androidInvoke;
  }

  window.kotobaShell = {
    schema: SCHEMA,
    available: transport !== null,
    // Substituted at scaffold time. Not read back off the native object:
    // addJavascriptInterface exposes annotated *methods* only, so a property
    // read there would silently be undefined.
    target: "{{TARGET}}",

    // invoke(command, args) -> Promise<{ok, value, audit, ...}>
    //
    // Never rejects. A denied command, a missing provider and a provider
    // failure all resolve with ok:false and a reason, because the caller has
    // to handle "the policy says no" anyway and a thrown exception makes that
    // path easy to forget.
    invoke: function (command, args) {
      if (!transport) { return unavailable(command); }
      return transport(command, args);
    }
  };
})();
