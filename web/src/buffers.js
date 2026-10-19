const isView = ArrayBuffer.isView;
const getter = (prototype, name) => Object.getOwnPropertyDescriptor(prototype, name).get;
const arrayLength = getter(ArrayBuffer.prototype, 'byteLength');
const sharedLength = typeof SharedArrayBuffer === 'undefined' ? null : getter(SharedArrayBuffer.prototype, 'byteLength');
const typed = Object.getPrototypeOf(Uint8Array.prototype);
const typedTag = getter(typed, Symbol.toStringTag);
const typedBuffer = getter(typed, 'buffer');
const typedOffset = getter(typed, 'byteOffset');
const typedLength = getter(typed, 'byteLength');
const viewBuffer = getter(DataView.prototype, 'buffer');
const viewOffset = getter(DataView.prototype, 'byteOffset');
const viewLength = getter(DataView.prototype, 'byteLength');

function length(buffer, allowShared) {
    try { return arrayLength.call(buffer); }
    catch(error) {
        if (!allowShared || !sharedLength) throw new TypeError('Expected an ArrayBuffer');
        return sharedLength.call(buffer);
    }
}

export function bufferSource(value, allowShared = false) {
    const view = isView(value);
    const tag = view ? typedTag.call(value) : undefined;
    const getBuffer = tag === undefined ? viewBuffer : typedBuffer;
    const buffer = view ? getBuffer.call(value) : value;
    length(buffer, allowShared);
    new Uint8Array(buffer, 0, 0);
    return () => {
        const current = view ? getBuffer.call(value) : value;
        if (length(current, allowShared) === 0) return new Uint8Array(0);
        const offset = view ? (tag === undefined ? viewOffset : typedOffset).call(value) : 0;
        const size = view ? (tag === undefined ? viewLength : typedLength).call(value) : length(current, allowShared);
        return new Uint8Array(new Uint8Array(current, offset, size));
    };
}

export function bytes(value) { return bufferSource(value)(); }
export function integerView(value) {
    const tag = typedTag.call(value);
    if (!['Int8Array','Uint8Array','Uint8ClampedArray','Int16Array','Uint16Array','Int32Array','Uint32Array','BigInt64Array','BigUint64Array'].includes(tag))
        throw new DOMException('Expected an integer typed array', 'TypeMismatchError');
    return new Uint8Array(typedBuffer.call(value), typedOffset.call(value), typedLength.call(value));
}
