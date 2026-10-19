import {bytes, integerView} from './buffers.js';

const token = Symbol();
const keys = new WeakMap();
const hashes = ['SHA-1','SHA-256','SHA-384','SHA-512'];

function algorithm(value) {
    const name = `${typeof value === 'object' && value !== null ? value.name : value}`.toUpperCase();
    if (![...hashes,'HMAC','AES-GCM','AES-CBC'].includes(name)) throw new DOMException('Unsupported algorithm: ' + name, 'NotSupportedError');
    return name;
}

function hash(value) {
    const name = algorithm(value);
    if (!hashes.includes(name)) throw new DOMException('Expected a hash algorithm', 'NotSupportedError');
    return name;
}

function info(key) {
    const value = keys.get(key);
    if (!value) throw new TypeError('Expected a CryptoKey');
    return value;
}

export class CryptoKey {
    constructor(key, value) {
        if (key !== token) throw new TypeError('Illegal constructor');
        keys.set(this, value);
    }
    get type() { info(this); return 'secret'; }
    get extractable() { return info(this).extractable; }
    get algorithm() {
        const value = info(this).algorithm;
        return value.hash ? {...value, hash: {...value.hash}} : {...value};
    }
    get usages() { return [...info(this).usages]; }
}

function usages(values, name) {
    const requested = Array.from(values, value => `${value}`);
    const allowed = name === 'HMAC' ? ['sign','verify'] : ['encrypt','decrypt','wrapKey','unwrapKey'];
    if (!requested.length || requested.some(value => !allowed.includes(value))) throw new DOMException('Invalid key usages', 'SyntaxError');
    return requested;
}

function keyInfo(key, name, usage) {
    const value = info(key);
    if (value.algorithm.name !== name || !value.usages.includes(usage)) throw new DOMException('Key algorithm or usage does not match', 'InvalidAccessError');
    return value;
}

function keyAlgorithm(value, generate = false) {
    const name = algorithm(value);
    if (!['HMAC','AES-GCM','AES-CBC'].includes(name)) throw new DOMException('Unsupported key algorithm', 'NotSupportedError');
    const options = typeof value === 'object' && value !== null ? value : {};
    const result = {name};
    if (name === 'HMAC') result.hash = {name: hash(options.hash)};
    if (generate || name === 'HMAC') {
        const length = options.length;
        if (generate && name !== 'HMAC' && length === undefined) throw new TypeError('AES key length is required');
        if (length !== undefined) result.length = +length;
    }
    return result;
}

function cipherAlgorithm(value) {
    const name = algorithm(value);
    if (!['AES-GCM','AES-CBC'].includes(name)) throw new DOMException('Expected an AES algorithm', 'NotSupportedError');
    const iv = bytes(value.iv);
    if (name === 'AES-CBC') {
        if (iv.length !== 16) throw new DOMException('AES-CBC requires a 16-byte IV', 'OperationError');
        return {name, iv};
    }
    const inputTag = value.tagLength;
    const tagLength = inputTag === undefined ? 128 : +inputTag & 255;
    if (![96,104,112,120,128].includes(tagLength))
        throw new DOMException('Supported GCM tag lengths are 96 through 128 bits', 'NotSupportedError');
    const additionalData = value.additionalData;
    const additional = additionalData === undefined ? new Uint8Array() : bytes(additionalData);
    return {name, iv, tagLength, additional};
}

function jwkAlgorithm(value) {
    return value.algorithm.name === 'HMAC' ? 'HS' + value.algorithm.hash.name.slice(4)
        : 'A' + value.algorithm.length + (value.algorithm.name === 'AES-GCM' ? 'GCM' : 'CBC');
}

export function cryptoCapabilities(host) {
    const call = async (...args) => {
        try { return await host.crypto(...args); }
        catch(error) { throw new DOMException(error?.message || 'Cryptographic operation failed', 'OperationError'); }
    };
    function crypt(operation, options, key, input, usage = operation) {
        const state = keyInfo(key, options.name, usage);
        const data = bytes(input);
        return call(operation, options.name, state.bytes.buffer, data.buffer, options.iv.buffer,
            options.additional?.buffer ?? null, options.tagLength ?? 0);
    }
    const methods = {
        async digest(value, input) {
            const data = bytes(input);
            return call('digest', hash(value), null, data.buffer);
        },
        async importKey(format, input, value, extractable, requestedUsages) {
            format = `${format}`;
            const data = format === 'jwk' ? null : bytes(input);
            const normalized = keyAlgorithm(value);
            const name = normalized.name;
            const requested = usages(requestedUsages, name);
            let raw;
            if (format === 'raw') raw = data;
            else if (format === 'jwk') {
                const {kty, k, ext, key_ops, use, alg} = input ?? {};
                input = {kty, k, ext, key_ops, use, alg};
                if (kty !== 'oct' || typeof k !== 'string') throw new DOMException('Expected an octet JWK', 'DataError');
                try { raw = Uint8Array.fromBase64(k, {alphabet: 'base64url'}); }
                catch(error) { throw new DOMException('Invalid JWK key data', 'DataError'); }
                if (input.ext !== undefined && !Boolean(input.ext) && extractable) throw new DOMException('JWK is not extractable', 'DataError');
                if (input.key_ops !== undefined) {
                    const operations = Array.from(input.key_ops, operation => `${operation}`);
                    if (new Set(operations).size !== operations.length || requested.some(usage => !operations.includes(usage)))
                        throw new DOMException('JWK key usages do not match', 'DataError');
                }
                const expectedUse = name === 'HMAC' ? 'sig' : 'enc';
                if (input.use !== undefined && input.use !== expectedUse) throw new DOMException('JWK use does not match', 'DataError');
            } else throw new DOMException('Supported key formats are raw and jwk', 'NotSupportedError');
            const descriptor = {name, length: raw.length * 8};
            if (name === 'HMAC') {
                descriptor.hash = {...normalized.hash};
                if (raw.length === 0) throw new DOMException('HMAC key must not be empty', 'DataError');
                if (normalized.length !== undefined && normalized.length !== descriptor.length) throw new DOMException('Only byte-aligned complete HMAC keys are supported', 'NotSupportedError');
            } else if (![128,192,256].includes(descriptor.length)) throw new DOMException('Invalid AES key length', 'DataError');
            const state = {algorithm: descriptor, bytes: raw, extractable: Boolean(extractable), usages: requested};
            if (format === 'jwk' && input.alg !== undefined && input.alg !== jwkAlgorithm(state)) throw new DOMException('JWK algorithm does not match', 'DataError');
            return new CryptoKey(token, state);
        },
        async exportKey(format, key) {
            format = `${format}`;
            const state = info(key);
            if (!state.extractable) throw new DOMException('Key is not extractable', 'InvalidAccessError');
            if (format === 'raw') return new Uint8Array(state.bytes).buffer;
            if (format === 'jwk') return {kty: 'oct', k: state.bytes.toBase64({alphabet: 'base64url', omitPadding: true}), alg: jwkAlgorithm(state), ext: true, key_ops: [...state.usages]};
            throw new DOMException('Supported key formats are raw and jwk', 'NotSupportedError');
        },
        async generateKey(value, extractable, requestedUsages) {
            const normalized = keyAlgorithm(value, true);
            const name = normalized.name;
            let length = normalized.length;
            if (name === 'HMAC') {
                if (length === undefined) length = ['SHA-384','SHA-512'].includes(normalized.hash.name) ? 1024 : 512;
            } else if (![128,192,256].includes(length)) throw new DOMException('Invalid AES key length', 'OperationError');
            if (!Number.isSafeInteger(length) || length <= 0 || length % 8 !== 0 || length > 524288)
                throw new DOMException('Unsupported key length', 'OperationError');
            const data = host.random(length / 8);
            return importKey('raw', data, normalized, extractable, requestedUsages);
        },
        async sign(value, key, input) {
            const data = bytes(input);
            const name = algorithm(value);
            if (name !== 'HMAC') throw new DOMException('Only HMAC signing is supported', 'NotSupportedError');
            const state = keyInfo(key, name, 'sign');
            return call('sign', state.algorithm.hash.name, state.bytes.buffer, data.buffer);
        },
        async verify(value, key, signature, input) {
            const expected = bytes(signature);
            const data = bytes(input);
            const name = algorithm(value);
            if (name !== 'HMAC') throw new DOMException('Only HMAC verification is supported', 'NotSupportedError');
            const state = keyInfo(key, name, 'verify');
            return call('verify', state.algorithm.hash.name, state.bytes.buffer, data.buffer, null, null, 0, expected.buffer);
        },
        async encrypt(value, key, input) { return crypt('encrypt', cipherAlgorithm(value), key, input); },
        async decrypt(value, key, input) { return crypt('decrypt', cipherAlgorithm(value), key, input); },
        async wrapKey(format, key, wrappingKey, wrapAlgorithm) {
            format = `${format}`;
            const options = cipherAlgorithm(wrapAlgorithm);
            keyInfo(wrappingKey, options.name, 'wrapKey');
            const exported = await exportKey(format, key);
            const raw = format === 'jwk' ? new TextEncoder().encode(JSON.stringify(exported)) : exported;
            return crypt('encrypt', options, wrappingKey, raw, 'wrapKey');
        },
        async unwrapKey(format, wrapped, unwrappingKey, unwrapAlgorithm, unwrappedAlgorithm, extractable, requestedUsages) {
            format = `${format}`;
            const options = cipherAlgorithm(unwrapAlgorithm);
            const normalized = keyAlgorithm(unwrappedAlgorithm);
            const requested = usages(requestedUsages, normalized.name);
            extractable = Boolean(extractable);
            const raw = await crypt('decrypt', options, unwrappingKey, wrapped, 'unwrapKey');
            let value = raw;
            if (format === 'jwk') {
                try { value = JSON.parse(new TextDecoder('utf-8', {fatal: true}).decode(raw)); }
                catch(error) { throw new DOMException('Invalid wrapped JWK', 'DataError'); }
            }
            return importKey(format, value, normalized, extractable, requested);
        }
    };
    const importKey = methods.importKey;
    const exportKey = methods.exportKey;
    const subtleObjects = new WeakSet();
    const cryptoObjects = new WeakSet();
    class SubtleCrypto {
        constructor(key) {
            if (key !== token) throw new TypeError('Illegal constructor');
            subtleObjects.add(this);
        }
    }
    for (const [name, method] of Object.entries(methods)) Object.defineProperty(SubtleCrypto.prototype, name, {
        enumerable: true, configurable: true, writable: true,
        value: function(...args) {
            if (!subtleObjects.has(this)) return Promise.reject(new TypeError('Illegal SubtleCrypto receiver'));
            return method(...args);
        }
    });
    const subtle = new SubtleCrypto(token);
    class Crypto {
        constructor(key) {
            if (key !== token) throw new TypeError('Illegal constructor');
            cryptoObjects.add(this);
        }
        get subtle() {
            if (!cryptoObjects.has(this)) throw new TypeError('Illegal Crypto receiver');
            return subtle;
        }
        getRandomValues(value) {
            if (!cryptoObjects.has(this)) throw new TypeError('Illegal Crypto receiver');
            const target = integerView(value);
            if (target.length > 65536) throw new DOMException('Random data exceeds 65536 bytes', 'QuotaExceededError');
            target.set(new Uint8Array(host.random(target.length)));
            return value;
        }
        randomUUID() {
            if (!cryptoObjects.has(this)) throw new TypeError('Illegal Crypto receiver');
            return host.uuid();
        }
    }
    const crypto = new Crypto(token);
    Object.defineProperty(Crypto.prototype, Symbol.toStringTag, {value: 'Crypto', configurable: true});
    Object.defineProperty(SubtleCrypto.prototype, Symbol.toStringTag, {value: 'SubtleCrypto', configurable: true});
    Object.defineProperty(CryptoKey.prototype, Symbol.toStringTag, {value: 'CryptoKey', configurable: true});
    return {crypto, CryptoKey, Crypto, SubtleCrypto};
}
