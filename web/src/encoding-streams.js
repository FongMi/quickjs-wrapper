export function encodingStreams(TransformStream) {
    const encoders = new WeakMap();
    const decoders = new WeakMap();
    function state(map, receiver) {
        const value = map.get(receiver);
        if (!value) throw new TypeError('Illegal encoding stream receiver');
        return value;
    }
    class TextEncoderStream {
        constructor() {
            const encoder = new TextEncoder();
            let pending = '';
            const stream = new TransformStream({
                transform(chunk, controller) {
                    let source = pending + `${chunk}`;
                    pending = '';
                    const last = source.charCodeAt(source.length - 1);
                    if (last >= 0xd800 && last <= 0xdbff) {
                        pending = source.slice(-1);
                        source = source.slice(0, -1);
                    }
                    const bytes = encoder.encode(source);
                    if (bytes.length) controller.enqueue(bytes);
                },
                flush(controller) {
                    if (pending) controller.enqueue(encoder.encode(pending));
                }
            });
            encoders.set(this, stream);
        }
        get encoding() { state(encoders, this); return 'utf-8'; }
        get readable() { return state(encoders, this).readable; }
        get writable() { return state(encoders, this).writable; }
    }
    class TextDecoderStream {
        constructor(label, options) {
            const decoder = new TextDecoder(label, options);
            const stream = new TransformStream({
                transform(chunk, controller) {
                    const text = decoder.decode(chunk, {stream: true});
                    if (text) controller.enqueue(text);
                },
                flush(controller) {
                    const text = decoder.decode();
                    if (text) controller.enqueue(text);
                }
            });
            decoders.set(this, {decoder, stream});
        }
        get encoding() { return state(decoders, this).decoder.encoding; }
        get fatal() { return state(decoders, this).decoder.fatal; }
        get ignoreBOM() { return state(decoders, this).decoder.ignoreBOM; }
        get readable() { return state(decoders, this).stream.readable; }
        get writable() { return state(decoders, this).stream.writable; }
    }
    for (const constructor of [TextEncoderStream, TextDecoderStream])
        Object.defineProperty(constructor.prototype, Symbol.toStringTag, {value: constructor.name, configurable: true});
    return {TextEncoderStream, TextDecoderStream};
}
