import {TextDecoder as LegacyDecoder, normalizeEncoding} from '@exodus/bytes/encoding.js';
import {bufferSource} from './buffers.js';

function dictionary(value) {
    if (value == null) return {};
    if (typeof value !== 'object' && typeof value !== 'function') throw new TypeError('Options must be a dictionary');
    return value;
}

export function extendedDecoder(Original) {
    const decoders = new WeakMap();
    const decode = Original.prototype.decode;
    const original = Object.fromEntries(['encoding','fatal','ignoreBOM'].map(name => [name, Object.getOwnPropertyDescriptor(Original.prototype, name).get]));
    return class TextDecoder extends Original {
        constructor(label = 'utf-8', options = {}) {
            label = `${label}`;
            options = dictionary(options);
            const converted = {fatal: Boolean(options.fatal), ignoreBOM: Boolean(options.ignoreBOM)};
            super('utf-8', converted);
            const encoding = normalizeEncoding(label);
            if (!encoding || encoding === 'replacement') throw new RangeError('Unsupported encoding label');
            if (encoding !== 'utf-8') decoders.set(this, new LegacyDecoder(encoding, converted));
        }
        get encoding() {
            const value = original.encoding.call(this);
            return decoders.get(this)?.encoding ?? value;
        }
        get fatal() { const value = original.fatal.call(this); return decoders.get(this)?.fatal ?? value; }
        get ignoreBOM() { const value = original.ignoreBOM.call(this); return decoders.get(this)?.ignoreBOM ?? value; }
        decode(input, options = {}) {
            const decoder = decoders.get(this);
            if (!decoder) return decode.call(this, input, options);
            const source = input === undefined ? () => new Uint8Array(0) : bufferSource(input, true);
            const stream = Boolean(dictionary(options).stream);
            return decoder.decode(source(), {stream});
        }
    };
}
