@file:OptIn(ExperimentalWasmJsInterop::class)
@file:Suppress("unused")

package tech.ryadom.jabbit.internal

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.js

internal fun isOnline(): Boolean =
    js("(typeof navigator === 'undefined') || navigator.onLine !== false")

internal fun isServiceWorkerScope(): Boolean =
    js("typeof ServiceWorkerGlobalScope !== 'undefined' && self instanceof ServiceWorkerGlobalScope")

internal fun isDocumentHidden(): Boolean =
    js("typeof document === 'undefined' || document.visibilityState === 'hidden'")

internal fun meteredState(): Int = js(
    "(function(){" +
            "var c = (typeof navigator === 'undefined') ? null : (navigator.connection || navigator.mozConnection);" +
            "if (!c) return -1;" +
            "if (c.saveData === true) return 1;" +
            "if (c.type) return c.type === 'cellular' ? 1 : 0;" +
            "if (c.effectiveType) return (c.effectiveType === 'slow-2g' || c.effectiveType === '2g') ? 1 : 0;" +
            "return -1;" +
            "})()"
)

internal fun addGlobalListener(type: String, listener: () -> Unit): Unit =
    js("self.addEventListener(type, function(){ listener(); })")

internal fun readBatteryStatus(
    onStatus: (Boolean, Double) -> Unit,
    onUnavailable: () -> Unit
): Unit = js(
    "(function(){" +
            "if (typeof navigator === 'undefined' || !navigator.getBattery) { onUnavailable(); return; }" +
            "navigator.getBattery().then(function(b){" +
            "b.addEventListener('chargingchange', function(){ onStatus(b.charging === true, b.level); });" +
            "b.addEventListener('levelchange', function(){ onStatus(b.charging === true, b.level); });" +
            "onStatus(b.charging === true, b.level);" +
            "}).catch(function(){ onUnavailable(); });" +
            "})()"
)

internal fun readStorageEstimate(
    onEstimate: (Double, Double) -> Unit,
    onUnavailable: () -> Unit
): Unit = js(
    "(function(){" +
            "if (typeof navigator === 'undefined' || !navigator.storage || !navigator.storage.estimate) {" +
            "onUnavailable(); return; }" +
            "navigator.storage.estimate().then(function(e){" +
            "onEstimate((e.quota || 0), (e.usage || 0));" +
            "}).catch(function(){ onUnavailable(); });" +
            "})()"
)

internal fun supportsWebLocks(): Boolean =
    js("typeof navigator !== 'undefined' && !!navigator.locks && !!navigator.locks.request")

internal fun holdExclusiveLock(name: String, onAcquired: () -> Unit): Unit = js(
    "navigator.locks.request(name, { mode: 'exclusive' }, function(){" +
            "onAcquired();" +
            "return new Promise(function(){});" +
            "})"
)

internal fun idbRead(
    databaseName: String,
    storeName: String,
    key: String,
    onValue: (String?) -> Unit,
    onError: (String) -> Unit
): Unit = js(
    "(function(){" +
            "try {" +
            "var open = indexedDB.open(databaseName, 1);" +
            "open.onupgradeneeded = function(){ open.result.createObjectStore(storeName); };" +
            "open.onerror = function(){ onError('' + open.error); };" +
            "open.onsuccess = function(){" +
            "try {" +
            "var db = open.result;" +
            "var request = db.transaction(storeName, 'readonly').objectStore(storeName).get(key);" +
            "request.onsuccess = function(){" +
            "var value = request.result;" +
            "onValue((value === undefined || value === null) ? null : ('' + value));" +
            "db.close();" +
            "};" +
            "request.onerror = function(){ onError('' + request.error); db.close(); };" +
            "} catch (e) { onError('' + e); }" +
            "};" +
            "} catch (e) { onError('' + e); }" +
            "})()"
)

internal fun idbWrite(
    databaseName: String,
    storeName: String,
    key: String,
    value: String,
    onDone: () -> Unit,
    onError: (String) -> Unit
): Unit = js(
    "(function(){" +
            "try {" +
            "var open = indexedDB.open(databaseName, 1);" +
            "open.onupgradeneeded = function(){ open.result.createObjectStore(storeName); };" +
            "open.onerror = function(){ onError('' + open.error); };" +
            "open.onsuccess = function(){" +
            "try {" +
            "var db = open.result;" +
            "var transaction = db.transaction(storeName, 'readwrite');" +
            "transaction.objectStore(storeName).put(value, key);" +
            "transaction.oncomplete = function(){ onDone(); db.close(); };" +
            "transaction.onerror = function(){ onError('' + transaction.error); db.close(); };" +
            "} catch (e) { onError('' + e); }" +
            "};" +
            "} catch (e) { onError('' + e); }" +
            "})()"
)

internal fun localStorageRead(key: String): String? =
    js("(typeof localStorage === 'undefined') ? null : localStorage.getItem(key)")

internal fun localStorageWrite(key: String, value: String): Unit =
    js("(function(){ if (typeof localStorage !== 'undefined') localStorage.setItem(key, value); })()")

internal fun addDocumentListener(type: String, listener: () -> Unit): Unit = js(
    "(function(){" +
            "if (typeof document !== 'undefined') document.addEventListener(type, function(){ listener(); });" +
            "})()"
)

internal external interface BroadcastHandle

internal fun openBroadcast(name: String, onMessage: (String) -> Unit): BroadcastHandle? = js(
    "(function(){" +
            "if (typeof BroadcastChannel === 'undefined') return null;" +
            "try {" +
            "var channel = new BroadcastChannel(name);" +
            "channel.onmessage = function(event){ onMessage('' + event.data); };" +
            "return channel;" +
            "} catch (e) { return null; }" +
            "})()"
)

internal fun postBroadcast(channel: BroadcastHandle, message: String): Unit =
    js("(function(){ try { channel.postMessage(message); } catch (e) { } })()")

internal fun registerOneShotSync(tag: String, onResult: (Boolean) -> Unit): Unit = js(
    "(function(){" +
            "if (typeof navigator === 'undefined' || !navigator.serviceWorker) { onResult(false); return; }" +
            "navigator.serviceWorker.ready.then(function(registration){" +
            "if (!registration.sync) { onResult(false); return; }" +
            "registration.sync.register(tag)" +
            ".then(function(){ onResult(true); })" +
            ".catch(function(){ onResult(false); });" +
            "}).catch(function(){ onResult(false); });" +
            "})()"
)

internal fun registerPeriodicSync(
    tag: String,
    minIntervalMillis: Double,
    onResult: (Boolean) -> Unit
): Unit = js(
    "(function(){" +
            "if (typeof navigator === 'undefined' || !navigator.serviceWorker) { onResult(false); return; }" +
            "navigator.serviceWorker.ready.then(function(registration){" +
            "if (!registration.periodicSync) { onResult(false); return; }" +
            "registration.periodicSync.register(tag, { minInterval: minIntervalMillis })" +
            ".then(function(){ onResult(true); })" +
            ".catch(function(){ onResult(false); });" +
            "}).catch(function(){ onResult(false); });" +
            "})()"
)

internal fun listenExtendableEvent(type: String, tag: String, onEvent: (Int) -> Unit): Unit = js(
    "(function(){" +
            "self.addEventListener(type, function(event){" +
            "if (tag.length > 0 && event.tag && event.tag !== tag) return;" +
            "var state = (self.__jabbitEvents = self.__jabbitEvents || { next: 1, pending: {} });" +
            "var id = state.next++;" +
            "event.waitUntil(new Promise(function(resolve){ state.pending[id] = resolve; }));" +
            "onEvent(id);" +
            "});" +
            "})()"
)

internal fun completeExtendableEvent(id: Int): Unit = js(
    "(function(){" +
            "var state = self.__jabbitEvents;" +
            "if (state && state.pending[id]) { state.pending[id](); delete state.pending[id]; }" +
            "})()"
)

internal fun hasActiveClients(onResult: (Boolean) -> Unit): Unit = js(
    "(function(){" +
            "if (typeof self === 'undefined' || !self.clients || !self.clients.matchAll) {" +
            "onResult(false); return; }" +
            "self.clients.matchAll({ type: 'window', includeUncontrolled: true })" +
            ".then(function(clients){ onResult(clients.length > 0); })" +
            ".catch(function(){ onResult(false); });" +
            "})()"
)
