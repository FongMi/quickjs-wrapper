const slots = new WeakMap();
const token = /^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/;
const apply = Reflect.apply;

export function byteString(value) {
    const text = `${value}`;
    for (let i = 0; i < text.length; i++) if (text.charCodeAt(i) > 255) throw new TypeError('Expected a ByteString');
    return text;
}

function name(value) {
    const text = byteString(value);
    if (!token.test(text)) throw new TypeError('Invalid HTTP header name');
    return text.toLowerCase();
}

function value(input) {
    const text = byteString(input).replace(/^[\t\n\r ]+|[\t\n\r ]+$/g, '');
    if (/[\0\r\n]/.test(text)) throw new TypeError('Invalid HTTP header value');
    return text;
}

function state(headers) {
    const data = slots.get(headers);
    if (!data) throw new TypeError('Illegal Headers receiver');
    return data;
}

function mutable(headers) {
    const data = state(headers);
    if (data.immutable) throw new TypeError('Headers are immutable');
    data.sorted = null;
    return data;
}

export function freezeHeaders(headers) { state(headers).immutable = true; }
export function headerValue(headers, key) {
    const values = state(headers).list.filter(([name]) => name === key).map(([, value]) => value);
    return values.length ? values.join(', ') : null;
}
export function defaultHeader(headers, key, input) {
    if (headerValue(headers, key) === null) mutable(headers).list.push([key, input]);
}
export function cloneHeaders(headers, immutable = state(headers).immutable) {
    const copy = new Headers();
    state(copy).list = state(headers).list.map(pair => [...pair]);
    state(copy).immutable = immutable;
    return copy;
}

export function headerEntries(headers) {
    const data = state(headers);
    if (data.sorted) return data.sorted;
    const list = [...data.list].sort((a, b) => a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0);
    const result = [];
    for (const [name, value] of list) {
        const last = result[result.length - 1];
        if (name !== 'set-cookie' && last?.[0] === name) last[1] += ', ' + value;
        else result.push([name, value]);
    }
    return data.sorted = result;
}

function iterate(headers, kind) {
    state(headers);
    return (function*() {
        let index = 0;
        while (true) {
            const current = headerEntries(headers);
            if (index >= current.length) return;
            const pair = current[index++];
            yield kind === 'keys' ? pair[0] : kind === 'values' ? pair[1] : [...pair];
        }
    })();
}

export class Headers {
    constructor(input) {
        slots.set(this, {list: [], immutable: false, sorted: null});
        if (input === undefined) return;
        if (input === null || typeof input !== 'object' && typeof input !== 'function') throw new TypeError('Invalid Headers initializer');
        const iterator = input[Symbol.iterator];
        if (iterator !== undefined && iterator !== null) {
            if (typeof iterator !== 'function') throw new TypeError('Invalid header iterator');
            for (const entry of {[Symbol.iterator]: () => apply(iterator, input, [])}) {
                const next = entry?.[Symbol.iterator];
                if (typeof next !== 'function') throw new TypeError('Header entry must be iterable');
                const pair = Array.from({[Symbol.iterator]: () => apply(next, entry, [])});
                if (pair.length !== 2) throw new TypeError('Header entries require two values');
                state(this).list.push([name(pair[0]), value(pair[1])]);
            }
        } else {
            for (const key of Reflect.ownKeys(input)) {
                if (Object.getOwnPropertyDescriptor(input, key)?.enumerable)
                    state(this).list.push([name(key), value(input[key])]);
            }
        }
    }
    append(key, input) {
        if (arguments.length < 2) throw new TypeError('append requires two arguments');
        const pair = [name(key), value(input)];
        mutable(this).list.push(pair);
    }
    delete(key) {
        if (arguments.length === 0) throw new TypeError('delete requires a name');
        const normalized = name(key);
        const data = mutable(this);
        data.list = data.list.filter(([key]) => key !== normalized);
    }
    get(key) {
        if (arguments.length === 0) throw new TypeError('get requires a name');
        const normalized = name(key);
        return headerValue(this, normalized);
    }
    has(key) {
        if (arguments.length === 0) throw new TypeError('has requires a name');
        const normalized = name(key);
        return state(this).list.some(([key]) => key === normalized);
    }
    set(key, input) {
        if (arguments.length < 2) throw new TypeError('set requires two arguments');
        const normalized = name(key);
        const text = value(input);
        const data = mutable(this);
        const index = data.list.findIndex(([key]) => key === normalized);
        data.list = data.list.filter(([key]) => key !== normalized);
        data.list.splice(index < 0 ? data.list.length : index, 0, [normalized, text]);
    }
    getSetCookie() { return state(this).list.filter(([key]) => key === 'set-cookie').map(([, value]) => value); }
    entries() { return iterate(this, 'entries'); }
    keys() { return iterate(this, 'keys'); }
    values() { return iterate(this, 'values'); }
    forEach(callback, receiver) {
        state(this);
        if (typeof callback !== 'function') throw new TypeError('Callback must be a function');
        for (const [key, value] of iterate(this, 'entries')) apply(callback, receiver, [value, key, this]);
    }
    [Symbol.iterator]() { return iterate(this, 'entries'); }
}

Object.defineProperty(Headers.prototype, Symbol.toStringTag, {value: 'Headers', configurable: true});
