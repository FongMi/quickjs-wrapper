import {Blob} from 'fetch-blob';
import {File} from 'fetch-blob/file.js';
import {FormData, formDataToBlob} from 'formdata-polyfill/esm.min.js';
import {iterateMultipart} from '@web3-storage/multipart-parser';
import {ReadableStream} from 'web-streams-polyfill';
import {URLSearchParams} from 'whatwg-url';
import {bytes} from './buffers.js';
import {headerValue} from './headers.js';

const bodies = new WeakMap();

export function streamBytes(data) {
    let offset = 0;
    return new ReadableStream({
        type: 'bytes',
        pull(controller) {
            if (offset === data.length) {
                controller.close();
                controller.byobRequest?.respond(0);
                return;
            }
            const end = Math.min(offset + 65536, data.length);
            controller.enqueue(data.slice(offset, end));
            offset = end;
        }
    }, {highWaterMark: 0});
}

export function extractBody(input) {
    if (input == null) return {stream: null, type: null};
    if (input instanceof ReadableStream) {
        if (input.locked || input._disturbed) throw new TypeError('Body has already been used');
        return {stream: input, type: null};
    }
    let blob;
    if (input instanceof Blob) blob = input;
    else if (input instanceof FormData) blob = formDataToBlob(input, Blob);
    if (blob) return {stream: blob.stream(), type: blob.type || null};
    if (input instanceof URLSearchParams) return {stream: streamBytes(new TextEncoder().encode(input.toString())), type: 'application/x-www-form-urlencoded;charset=UTF-8'};
    if (ArrayBuffer.isView(input) || input instanceof ArrayBuffer) return {stream: streamBytes(bytes(input)), type: null};
    if (typeof SharedArrayBuffer !== 'undefined' && input instanceof SharedArrayBuffer) throw new TypeError('Shared body buffers are unsupported');
    return {stream: streamBytes(new TextEncoder().encode(`${input}`)), type: 'text/plain;charset=UTF-8'};
}

export function bodyState(value) {
    const state = bodies.get(value);
    if (!state) throw new TypeError('Illegal body receiver');
    return state;
}

export function setBody(value, body) {
    bodies.set(value, body);
}

export function unusable(value) {
    const stream = bodyState(value).stream;
    return stream !== null && (stream.locked || stream._disturbed);
}

export function cloneBody(value) {
    if (unusable(value)) throw new TypeError('Body has already been used');
    const state = bodyState(value);
    if (!state.stream) return {...state};
    const branches = state.stream.tee();
    state.stream = branches[0];
    return {stream: branches[1], type: state.type};
}

async function consume(value) {
    if (unusable(value)) throw new TypeError('Body has already been used');
    const stream = bodyState(value).stream;
    if (!stream) return new Uint8Array();
    const reader = stream.getReader();
    const chunks = [];
    let length = 0;
    try {
        while (true) {
            const item = await reader.read();
            if (item.done) break;
            if (!(item.value instanceof Uint8Array)) throw new TypeError('Body stream must contain Uint8Array chunks');
            const chunk = bytes(item.value);
            length += chunk.length;
            chunks.push(chunk);
        }
    } finally {
        reader.releaseLock();
    }
    const output = new Uint8Array(length);
    let offset = 0;
    for (const chunk of chunks) {
        output.set(chunk, offset);
        offset += chunk.length;
    }
    return output;
}

export class Body {
    get body() { return bodyState(this).stream; }
    get bodyUsed() { return bodyState(this).stream?._disturbed ?? false; }
    async arrayBuffer() { return (await consume(this)).buffer; }
    async bytes() { return consume(this); }
    async text() { return new TextDecoder('utf-8').decode(await consume(this)); }
    async json() { return JSON.parse(new TextDecoder('utf-8').decode(await consume(this))); }
    async blob() { return new Blob([await consume(this)], {type: headerValue(bodyState(this).headers, 'content-type') || ''}); }
    async formData() {
        const data = await consume(this);
        const type = headerValue(bodyState(this).headers, 'content-type') || '';
        const essence = type.split(';', 1)[0].trim().toLowerCase();
        if (essence === 'application/x-www-form-urlencoded') {
            const result = new FormData();
            const text = new TextDecoder('utf-8').decode(data);
            for (const [name, value] of new URLSearchParams(text)) result.append(name, value);
            return result;
        }
        if (essence !== 'multipart/form-data') throw new TypeError('Unsupported form data content type');
        const match = /;\s*boundary=(?:"([^"]*)"|([^;\s]*))/i.exec(type);
        const boundary = match?.[1] ?? match?.[2];
        if (!boundary) throw new TypeError('Missing multipart boundary');
        const result = new FormData();
        for await (const part of iterateMultipart(streamBytes(data), boundary)) {
            result.append(part.name, part.filename === undefined ? new TextDecoder().decode(part.data)
                : new File([part.data], part.filename, {type: part.contentType}));
        }
        return result;
    }
}
