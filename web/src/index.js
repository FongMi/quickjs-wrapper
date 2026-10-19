import {URL, URLSearchParams} from 'whatwg-url';
import structuredClone from 'core-js-pure/actual/structured-clone';
import {Event, EventTarget, setErrorHandler} from 'event-target-shim';
import * as streams from 'web-streams-polyfill';
import {Blob} from 'fetch-blob';
import {File} from 'fetch-blob/file.js';
import {FormData} from 'formdata-polyfill/esm.min.js';
import {AbortController, AbortSignal} from './abort.js';
import {encodingStreams} from './encoding-streams.js';
import {extendedDecoder} from './decoding.js';
import {cryptoCapabilities} from './crypto.js';
import {Headers} from './headers.js';
import {Response, requestClass} from './http.js';

const blobArrayBuffer = Blob.prototype.arrayBuffer;
Object.defineProperty(Blob.prototype, 'bytes', {configurable: true, writable: true, enumerable: true,
    value: async function() { return new Uint8Array(await blobArrayBuffer.call(this)); }
});

globalThis.__quickjsInstallWeb = function(host) {
    class CustomEvent extends Event {
        constructor(type, options = {}) {
            super(type, options);
            Object.defineProperty(this, 'detail', {value: options?.detail ?? null, enumerable: true});
        }
    }
    Object.defineProperty(CustomEvent.prototype, Symbol.toStringTag, {value: 'CustomEvent', configurable: true});
    setErrorHandler(error => {
        let message;
        try { message = String(error?.stack || error); }
        catch { message = 'Event listener threw an unprintable value'; }
        host.report(message);
    });
    const Request = requestClass(host.baseURL);
    Object.defineProperty(Request.prototype, Symbol.toStringTag, {value: 'Request', configurable: true});
    const values = {
        URL, URLSearchParams, structuredClone, Event, EventTarget, CustomEvent,
        TextDecoder: extendedDecoder(globalThis.TextDecoder),
        AbortController, AbortSignal, Blob, File, FormData, Headers, Request, Response,
        ...streams, ...encodingStreams(streams.TransformStream), ...cryptoCapabilities(host)
    };
    const previous = new Map();
    for (const name of Object.keys(values)) {
        const descriptor = Object.getOwnPropertyDescriptor(globalThis, name);
        if (descriptor && !descriptor.configurable) throw new TypeError('Cannot install ' + name);
        previous.set(name, descriptor);
    }
    for (const [name, value] of Object.entries(values))
        Object.defineProperty(globalThis, name, {value, configurable: true, writable: true});
    return {
        close() {
            for (const [name, value] of Object.entries(values)) {
                if (Object.getOwnPropertyDescriptor(globalThis, name)?.value !== value) continue;
                const descriptor = previous.get(name);
                if (descriptor) Object.defineProperty(globalThis, name, descriptor);
                else delete globalThis[name];
            }
        }
    };
};
