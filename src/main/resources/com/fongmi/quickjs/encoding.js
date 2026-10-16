// Optional UTF-8 host capability. Algorithms: https://encoding.spec.whatwg.org/
// Other Encoding Standard encodings are deliberately unsupported.
(() => {
    'use strict';
    const Uint8 = Uint8Array;
    const isView = ArrayBuffer.isView;
    const get = (prototype, name) => Object.getOwnPropertyDescriptor(prototype, name).get;
    const arrayBufferLength = get(ArrayBuffer.prototype, 'byteLength');
    const sharedBufferLength = typeof SharedArrayBuffer === 'undefined' ? null : get(SharedArrayBuffer.prototype, 'byteLength');
    const typedArray = Object.getPrototypeOf(Uint8.prototype);
    const typedBuffer = get(typedArray, 'buffer');
    const typedOffset = get(typedArray, 'byteOffset');
    const typedLength = get(typedArray, 'byteLength');
    const typedTag = get(typedArray, Symbol.toStringTag);
    const dataBuffer = get(DataView.prototype, 'buffer');
    const dataOffset = get(DataView.prototype, 'byteOffset');
    const dataLength = get(DataView.prototype, 'byteLength');
    const fromCodePoint = String.fromCodePoint;
    const encoders = new WeakSet();
    const decoders = new WeakMap();

    function domString(value) {
        if (typeof value === 'symbol') throw new TypeError('Cannot convert a Symbol to a string');
        return String(value);
    }

    function dictionary(value) {
        if (value == null) return {};
        if (typeof value !== 'object' && typeof value !== 'function')
            throw new TypeError('Options must be a dictionary');
        return value;
    }

    function encoder(receiver) {
        if (!encoders.has(receiver)) throw new TypeError('Illegal TextEncoder receiver');
    }

    function decoder(receiver) {
        const state = decoders.get(receiver);
        if (!state) throw new TypeError('Illegal TextDecoder receiver');
        return state;
    }

    function bufferLength(value) {
        try {
            return arrayBufferLength.call(value);
        } catch (error) {
            if (!sharedBufferLength) throw new TypeError('Input must be a BufferSource');
            return sharedBufferLength.call(value);
        }
    }

    function bytes(value, copy = false) {
        let buffer = value, offset, length;
        if (isView(value)) {
            const typed = typedTag.call(value) !== undefined;
            buffer = typed ? typedBuffer.call(value) : dataBuffer.call(value);
            // Web IDL's copy returns empty if options conversion detached the input.
            if (copy && bufferLength(buffer) === 0) return new Uint8(0);
            offset = typed ? typedOffset.call(value) : dataOffset.call(value);
            length = typed ? typedLength.call(value) : dataLength.call(value);
        } else {
            const length = bufferLength(value);
            if (copy && length === 0) return new Uint8(0);
        }
        const view = new Uint8(buffer, offset, length);
        return copy ? new Uint8(view) : view;
    }

    function reset(state) {
        state.codePoint = state.seen = state.needed = 0;
        state.lower = 0x80;
        state.upper = 0xbf;
    }

    function scalar(source, index) {
        const point = source.charCodeAt(index);
        if (point >= 0xd800 && point <= 0xdbff) {
            const next = source.charCodeAt(index + 1);
            if (next >= 0xdc00 && next <= 0xdfff)
                return 0x10000 + ((point - 0xd800) << 10) + next - 0xdc00;
            return 0xfffd;
        }
        return point >= 0xdc00 && point <= 0xdfff ? 0xfffd : point;
    }

    function width(point) {
        return point < 0x80 ? 1 : point < 0x800 ? 2 : point < 0x10000 ? 3 : 4;
    }

    function write(source, destination, capacity) {
        let read = 0, written = 0;
        while (read < source.length) {
            const point = scalar(source, read);
            const size = width(point);
            if (written + size > capacity) break;
            if (size === 1) destination[written++] = point;
            else {
                destination[written++] = (size === 2 ? 0xc0 : size === 3 ? 0xe0 : 0xf0) | (point >> ((size - 1) * 6));
                for (let shift = (size - 2) * 6; shift >= 0; shift -= 6)
                    destination[written++] = 0x80 | ((point >> shift) & 0x3f);
            }
            read += point > 0xffff ? 2 : 1;
        }
        return { read, written };
    }

    class TextEncoder {
        constructor() {
            encoders.add(this);
        }

        get encoding() {
            encoder(this);
            return 'utf-8';
        }

        encode(input = '') {
            encoder(this);
            const source = domString(input);
            let length = 0;
            for (let index = 0; index < source.length;) {
                const point = scalar(source, index);
                length += width(point);
                index += point > 0xffff ? 2 : 1;
            }
            const result = new Uint8(length);
            write(source, result, length);
            return result;
        }

        encodeInto(source, destination) {
            encoder(this);
            if (arguments.length < 2) throw new TypeError('encodeInto requires two arguments');
            source = domString(source);
            if (typedTag.call(destination) !== 'Uint8Array')
                throw new TypeError('Destination must be a Uint8Array');
            return write(source, destination, typedLength.call(destination));
        }
    }

    class TextDecoder {
        constructor(label = 'utf-8', options = {}) {
            label = domString(label);
            options = dictionary(options);
            const fatal = Boolean(options.fatal);
            const ignoreBOM = Boolean(options.ignoreBOM);
            label = label.replace(/^[\x09\x0a\x0c\x0d\x20]+|[\x09\x0a\x0c\x0d\x20]+$/g, '')
                .replace(/[A-Z]/g, letter => String.fromCharCode(letter.charCodeAt(0) + 0x20));
            if (!['unicode-1-1-utf-8', 'unicode11utf8', 'unicode20utf8', 'utf-8', 'utf8', 'x-unicode20utf8'].includes(label))
                throw new RangeError('Only UTF-8 TextDecoder labels are supported');
            const state = { fatal, ignoreBOM, stream: false, bomSeen: false, queue: new Uint8(0) };
            reset(state);
            decoders.set(this, state);
        }

        get encoding() {
            decoder(this);
            return 'utf-8';
        }

        get fatal() {
            return decoder(this).fatal;
        }

        get ignoreBOM() {
            return decoder(this).ignoreBOM;
        }

        decode(input = undefined, options = {}) {
            const state = decoder(this);
            if (input !== undefined) bytes(input); // Web IDL validates input before options.
            options = dictionary(options);
            const stream = Boolean(options.stream);
            const incoming = input === undefined ? new Uint8(0) : bytes(input, true);
            if (!state.stream) {
                reset(state);
                state.bomSeen = false;
                state.queue = new Uint8(0);
            }
            state.stream = stream;
            let data = incoming;
            if (state.queue.length) {
                data = new Uint8(state.queue.length + incoming.length);
                data.set(state.queue);
                data.set(incoming, state.queue.length);
            }
            state.queue = new Uint8(0);
            let output = '', bomSeen = state.bomSeen;
            const emit = point => {
                if (!state.ignoreBOM && !bomSeen) {
                    bomSeen = true;
                    if (point === 0xfeff) return;
                }
                output += fromCodePoint(point);
            };
            const error = index => {
                if (!state.fatal) return emit(0xfffd);
                // Retain the standard's I/O queue. Fatal continuation is discussed in
                // https://github.com/whatwg/encoding/issues/359; engines differ here.
                state.queue = new Uint8(data.length - index);
                for (let tail = index; tail < data.length; ++tail) state.queue[tail - index] = data[tail];
                throw new TypeError('The encoded data is not valid UTF-8');
            };
            for (let index = 0; index < data.length; ++index) {
                const byte = data[index];
                if (state.needed === 0) {
                    if (byte <= 0x7f) emit(byte);
                    else if (byte >= 0xc2 && byte <= 0xdf) {
                        state.needed = 1;
                        state.codePoint = byte & 0x1f;
                    } else if (byte >= 0xe0 && byte <= 0xef) {
                        if (byte === 0xe0) state.lower = 0xa0;
                        if (byte === 0xed) state.upper = 0x9f;
                        state.needed = 2;
                        state.codePoint = byte & 0xf;
                    } else if (byte >= 0xf0 && byte <= 0xf4) {
                        if (byte === 0xf0) state.lower = 0x90;
                        if (byte === 0xf4) state.upper = 0x8f;
                        state.needed = 3;
                        state.codePoint = byte & 7;
                    } else error(index + 1);
                    continue;
                }
                if (byte < state.lower || byte > state.upper) {
                    reset(state);
                    error(index);
                    --index; // Restore the invalid continuation byte for the next step.
                    continue;
                }
                state.lower = 0x80;
                state.upper = 0xbf;
                state.codePoint = (state.codePoint << 6) | (byte & 0x3f);
                if (++state.seen === state.needed) {
                    const point = state.codePoint;
                    reset(state);
                    emit(point);
                }
            }
            if (!stream && state.needed !== 0) {
                state.needed = 0;
                error(data.length);
            }
            state.bomSeen = bomSeen;
            return output;
        }
    }

    for (const constructor of [TextEncoder, TextDecoder]) {
        for (const name of Object.getOwnPropertyNames(constructor.prototype)) {
            if (name === 'constructor') continue;
            Object.defineProperty(constructor.prototype, name, { enumerable: true });
        }
        Object.defineProperty(constructor.prototype, Symbol.toStringTag, { value: constructor.name, configurable: true });
        const existing = Object.getOwnPropertyDescriptor(globalThis, constructor.name);
        if (existing && !existing.configurable) throw new TypeError('Cannot install ' + constructor.name);
    }
    Object.defineProperties(globalThis, {
        TextEncoder: { value: TextEncoder, writable: true, configurable: true },
        TextDecoder: { value: TextDecoder, writable: true, configurable: true }
    });
})();
void 0;
