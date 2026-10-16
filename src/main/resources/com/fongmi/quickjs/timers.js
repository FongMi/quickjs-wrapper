'use strict';
{
    const schedule = globalThis.$nameSet;
    const cancel = globalThis.$nameClear;
    const apply = Reflect.apply;
    const evaluate = eval;
    let closed = false;
    function create(handler, delay, repeat, args) {
        const callable = typeof handler === 'function';
        const source = callable ? undefined : `${handler}`;
        delay = Math.max(0, delay >> 0);
        const callback = callable ? () => apply(handler, globalThis, args) : () => evaluate(source);
        return schedule(callback, delay, repeat);
    }
    const timers = {
        close() { closed = true; },
        async fire(callback) { await callback(); },
        setTimeout(handler, delay = 0, ...args) {
            if (arguments.length === 0) throw new TypeError('setTimeout requires a handler');
            return create(handler, delay, false, args);
        },
        setInterval(handler, delay = 0, ...args) {
            if (arguments.length === 0) throw new TypeError('setInterval requires a handler');
            return create(handler, delay, true, args);
        },
        clearTimeout(id = 0) { id >>= 0; if (!closed) cancel(id); },
        clearInterval(id = 0) { id >>= 0; if (!closed) cancel(id); }
    };
    Object.defineProperty(globalThis, '$name', {value: timers, configurable: true});
    for (const method of ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'])
        globalThis[method] = timers[method];
    delete globalThis.$nameSet;
    delete globalThis.$nameClear;
}
void 0;
