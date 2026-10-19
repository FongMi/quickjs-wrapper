import {Event, EventTarget, defineEventAttribute, configureSignalHooks, hasAbortListeners} from 'event-target-shim';

const token = Symbol();
const signals = new WeakMap();
const controllers = new WeakMap();
const dispatchEvent = EventTarget.prototype.dispatchEvent;
const apply = Reflect.apply;
const cleanupRegistry = new FinalizationRegistry(cleanup => {
    for (const unsubscribe of cleanup) unsubscribe();
});

export function signalState(signal) {
    const state = signals.get(signal);
    if (!state) throw new TypeError('Expected an AbortSignal');
    return state;
}

export function onAbort(signal, algorithm) {
    const state = signalState(signal);
    if (state.aborted) {
        algorithm(state.reason);
        return () => {};
    }
    state.algorithms.add(algorithm);
    retainSignal(signal);
    const target = new WeakRef(state);
    const callback = new WeakRef(algorithm);
    return () => {
        const state = target.deref();
        const algorithm = callback.deref();
        if (!state || !algorithm) return;
        state.algorithms.delete(algorithm);
        const signal = state.reference.deref();
        if (signal) retainSignal(signal);
    };
}

export function throwIfAborted(signal) {
    const state = signalState(signal);
    if (state.aborted) throw state.reason;
}

function retainSignal(signal) {
    const state = signals.get(signal);
    if (!state) return;
    const retained = !state.aborted && (state.algorithms.size !== 0 || state.retainedDependents !== 0 || hasAbortListeners(signal));
    for (const link of state.links) {
        if (retained === (link.retained !== null)) continue;
        link.retained = retained ? signal : null;
        const owner = link.owner?.deref();
        if (owner) {
            signalState(owner).retainedDependents += retained ? 1 : -1;
            retainSignal(owner);
        }
    }
}

function releaseSources(state) {
    cleanupRegistry.unregister(state);
    for (const unsubscribe of state.cleanup) unsubscribe();
    state.cleanup.length = 0;
    state.links.length = 0;
    state.sources.clear();
}

function abort(signal, reason) {
    const state = signalState(signal);
    if (state.aborted) return;
    state.aborted = true;
    state.reason = reason === undefined ? new DOMException('The operation was aborted', 'AbortError') : reason;
    const pending = [signal];
    for (const link of state.dependents) {
        const target = link.retained ?? link.reference.deref();
        if (!target) continue;
        const dependent = signalState(target);
        if (dependent.aborted) continue;
        dependent.aborted = true;
        dependent.reason = state.reason;
        pending.push(target);
    }
    state.dependents.clear();
    for (const target of pending) runAbortSteps(target);
}

function runAbortSteps(signal) {
    const state = signalState(signal);
    retainSignal(signal);
    releaseSources(state);
    const algorithms = [...state.algorithms];
    state.algorithms.clear();
    for (const algorithm of algorithms) algorithm(state.reason);
    apply(dispatchEvent, signal, [new Event('abort')]);
}

export function dependentSignal(sources) {
    for (const source of sources) signalState(source);
    const signal = new AbortSignal(token);
    for (const source of sources) {
        const state = signalState(source);
        if (state.aborted) {
            abort(signal, state.reason);
            return signal;
        }
    }
    const state = signalState(signal);
    state.dependent = true;
    const roots = new Set();
    for (const source of sources) {
        const parent = signalState(source);
        if (!parent.dependent) roots.add(source);
        else for (const reference of parent.sources) {
            const root = reference.deref();
            if (root) roots.add(root);
        }
    }
    for (const source of roots) {
        const parent = signalState(source);
        const link = {reference: state.reference, retained: null, owner: parent.reference};
        parent.dependents.add(link);
        state.sources.add(parent.reference);
        state.links.push(link);
        const dependents = new WeakRef(parent.dependents);
        const entry = new WeakRef(link);
        state.cleanup.push(() => dependents.deref()?.delete(entry.deref()));
    }
    if (state.cleanup.length) {
        cleanupRegistry.register(signal, state.cleanup, state);
        retainSignal(signal);
    }
    return signal;
}

export class AbortSignal extends EventTarget {
    constructor(key) {
        if (key !== token) throw new TypeError('Illegal constructor');
        super();
        signals.set(this, {aborted: false, reason: undefined, algorithms: new Set(),
            reference: new WeakRef(this), cleanup: [], links: [], dependent: false,
            sources: new Set(), dependents: new Set(), retainedDependents: 0});
    }
    get aborted() { return signalState(this).aborted; }
    get reason() { return signalState(this).reason; }
    throwIfAborted() { throwIfAborted(this); }
    static abort(reason) {
        const signal = new AbortSignal(token);
        abort(signal, reason);
        return signal;
    }
    static timeout(milliseconds) {
        milliseconds = +milliseconds;
        if (!Number.isFinite(milliseconds) || milliseconds < 0 || milliseconds >= 2 ** 64)
            throw new TypeError('Timeout is outside the unsigned long long range');
        milliseconds = Math.trunc(milliseconds);
        const signal = new AbortSignal(token);
        const state = signalState(signal);
        const link = {reference: state.reference, retained: null};
        state.links.push(link);
        const schedule = setTimeout;
        const cancel = clearTimeout;
        let id;
        function step() {
            const delay = Math.min(milliseconds, 2147483647);
            milliseconds -= delay;
            id = schedule(() => {
                const target = link.retained ?? link.reference.deref();
                if (target) {
                    if (milliseconds) step();
                    else abort(target, new DOMException('The operation timed out', 'TimeoutError'));
                }
            }, delay);
        }
        state.cleanup.push(() => cancel(id));
        cleanupRegistry.register(signal, state.cleanup, state);
        step();
        return signal;
    }
    static any(values) {
        if (values === null || typeof values !== 'object' && typeof values !== 'function') throw new TypeError('Signals must be iterable');
        const iterator = values[Symbol.iterator];
        if (typeof iterator !== 'function') throw new TypeError('Signals must be iterable');
        const sources = [];
        for (const signal of {[Symbol.iterator]: () => apply(iterator, values, [])}) {
            signalState(signal);
            sources.push(signal);
        }
        return dependentSignal(sources);
    }
}

defineEventAttribute(AbortSignal.prototype, 'abort');
Object.defineProperty(AbortSignal.prototype, Symbol.toStringTag, {value: 'AbortSignal', configurable: true});

export class AbortController {
    constructor() { controllers.set(this, new AbortSignal(token)); }
    get signal() {
        const signal = controllers.get(this);
        if (!signal) throw new TypeError('Illegal AbortController receiver');
        return signal;
    }
    abort(reason) {
        const signal = controllers.get(this);
        if (!signal) throw new TypeError('Illegal AbortController receiver');
        abort(signal, reason);
    }
}

Object.defineProperty(AbortController.prototype, Symbol.toStringTag, {value: 'AbortController', configurable: true});

configureSignalHooks({
    validate(signal) {
        if (signal !== undefined) signalState(signal);
        return signal;
    },
    aborted: signal => signalState(signal).aborted,
    subscribe: onAbort,
    changed: retainSignal
});
