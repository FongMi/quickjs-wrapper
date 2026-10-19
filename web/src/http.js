import {URL} from 'whatwg-url';
import {TransformStream} from 'web-streams-polyfill';
import {Headers, byteString, cloneHeaders, defaultHeader, freezeHeaders} from './headers.js';
import {Body, bodyState, setBody, extractBody, cloneBody, unusable} from './body.js';
import {AbortController, dependentSignal, signalState} from './abort.js';

const requests = new WeakMap();
const responses = new WeakMap();
const redirects = new Set([301, 302, 303, 307, 308]);
const nullBodies = new Set([101, 103, 204, 205, 304]);
const methodToken = /^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/;

function state(map, value) {
    const data = map.get(value);
    if (!data) throw new TypeError('Illegal receiver');
    return data;
}

function choice(value, choices) {
    const result = `${value}`;
    if (!choices.includes(result)) throw new TypeError('Invalid request option: ' + result);
    return result;
}

function dictionary(value) {
    if (value == null) return {};
    if (typeof value !== 'object' && typeof value !== 'function') throw new TypeError('Options must be a dictionary');
    return value;
}

function makeResponse(data, body) {
    const result = Object.create(Response.prototype);
    responses.set(result, data);
    setBody(result, {...body, headers: data.headers});
    return result;
}

function responseInit(value) {
    const init = dictionary(value);
    const headers = new Headers(init.headers);
    const inputStatus = init.status;
    const status = inputStatus === undefined ? 200 : +inputStatus & 65535;
    const inputText = init.statusText;
    const statusText = inputText === undefined ? '' : byteString(inputText);
    if (status < 200 || status > 599) throw new RangeError('Invalid response status');
    if (/[\x00-\x08\x0a-\x1f\x7f]/.test(statusText)) throw new TypeError('Invalid status text');
    return {status, statusText, headers};
}

function initializeResponse(response, body, init) {
    const extracted = extractBody(body);
    if (extracted.stream && nullBodies.has(init.status)) throw new TypeError('Status cannot have a body');
    if (extracted.type) defaultHeader(init.headers, 'content-type', extracted.type);
    responses.set(response, {...init, type: 'default', url: '', redirected: false});
    setBody(response, {...extracted, headers: init.headers});
    return response;
}

export class Response extends Body {
    constructor(body = null, init = {}) {
        super();
        initializeResponse(this, body, responseInit(init));
    }
    get status() { return state(responses, this).status; }
    get statusText() { return state(responses, this).statusText; }
    get headers() { return state(responses, this).headers; }
    get type() { return state(responses, this).type; }
    get url() { return state(responses, this).url; }
    get redirected() { return state(responses, this).redirected; }
    get ok() { const status = state(responses, this).status; return status >= 200 && status <= 299; }
    clone() {
        const data = state(responses, this);
        return makeResponse({...data, headers: cloneHeaders(data.headers)}, cloneBody(this));
    }
    static error() {
        const headers = new Headers();
        freezeHeaders(headers);
        return makeResponse({status: 0, statusText: '', headers, type: 'error', url: '', redirected: false}, {stream: null});
    }
    static redirect(url, status = 302) {
        const location = new URL(url).href;
        status = +status & 65535;
        if (!redirects.has(status)) throw new RangeError('Invalid redirect status');
        const result = new Response(null, {status, headers: {location}});
        freezeHeaders(state(responses, result).headers);
        return result;
    }
    static json(data, init = {}) {
        if (!arguments.length) throw new TypeError('json requires data');
        const options = responseInit(init);
        const text = JSON.stringify(data);
        if (text === undefined) throw new TypeError('Data is not JSON serializable');
        defaultHeader(options.headers, 'content-type', 'application/json');
        return initializeResponse(Object.create(Response.prototype), text, options);
    }
}

export function requestClass(baseURL) {
    return class Request extends Body {
        constructor(input, init = {}) {
            super();
            if (!arguments.length) throw new TypeError('Request requires an input');
            init = dictionary(init);
            const {body: inputBody, cache: inputCache, credentials: inputCredentials, headers: inputHeaders,
                method: inputMethod, mode: inputMode, redirect: inputRedirect, signal: inputSignal} = init;
            const original = requests.get(input);
            const url = new URL(original ? original.url : `${input}`, baseURL ?? undefined);
            if (url.username || url.password) throw new TypeError('Request URL cannot contain credentials');
            let method = inputMethod === undefined ? original?.method ?? 'GET' : byteString(inputMethod);
            if (!methodToken.test(method) || /^(CONNECT|TRACE|TRACK)$/i.test(method)) throw new TypeError('Invalid HTTP method');
            if (/^(DELETE|GET|HEAD|OPTIONS|POST|PUT)$/i.test(method)) method = method.toUpperCase();
            const headers = inputHeaders === undefined && original ? cloneHeaders(original.headers, false) : new Headers(inputHeaders);
            const credentials = choice(inputCredentials === undefined ? original?.credentials ?? 'same-origin' : inputCredentials, ['omit', 'same-origin', 'include']);
            const redirect = choice(inputRedirect === undefined ? original?.redirect ?? 'follow' : inputRedirect, ['follow', 'error', 'manual']);
            const mode = choice(inputMode === undefined ? original?.mode ?? 'cors' : inputMode, ['cors', 'no-cors', 'same-origin']);
            const cache = choice(inputCache === undefined ? original?.cache ?? 'default' : inputCache, ['default', 'no-store', 'reload', 'no-cache', 'force-cache', 'only-if-cached']);
            if (cache === 'only-if-cached' && mode !== 'same-origin') throw new TypeError('only-if-cached requires same-origin mode');
            const parent = inputSignal === undefined ? original?.signal : inputSignal;
            if (parent != null) signalState(parent);
            let body = extractBody(inputBody);
            if (inputBody == null && original) {
                if (unusable(input)) throw new TypeError('Body has already been used');
                const old = bodyState(input);
                body = {stream: old.stream, type: old.type};
            }
            if ((method === 'GET' || method === 'HEAD') && body.stream) throw new TypeError('GET and HEAD cannot have a body');
            if (body.stream?.locked || body.stream?._disturbed) throw new TypeError('Body has already been used');
            if (body.type) defaultHeader(headers, 'content-type', body.type);
            if (original && inputBody == null && body.stream) body.stream = body.stream.pipeThrough(new TransformStream());
            const signal = parent == null ? new AbortController().signal : dependentSignal([parent]);
            requests.set(this, {url: url.href, method, headers, credentials, redirect, mode, cache, signal});
            setBody(this, {...body, headers});
        }
        get url() { return state(requests, this).url; }
        get method() { return state(requests, this).method; }
        get headers() { return state(requests, this).headers; }
        get credentials() { return state(requests, this).credentials; }
        get redirect() { return state(requests, this).redirect; }
        get mode() { return state(requests, this).mode; }
        get cache() { return state(requests, this).cache; }
        get signal() { return state(requests, this).signal; }
        get duplex() { state(requests, this); return 'half'; }
        clone() {
            const data = state(requests, this);
            const headers = cloneHeaders(data.headers);
            const body = cloneBody(this);
            const result = Object.create(Object.getPrototypeOf(this));
            requests.set(result, {...data, headers, signal: dependentSignal([data.signal])});
            setBody(result, {...body, headers});
            return result;
        }
    };
}

Object.defineProperty(Response.prototype, Symbol.toStringTag, {value: 'Response', configurable: true});
