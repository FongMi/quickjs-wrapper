(({host, connect, begin, detach}) => {
    const modules = new WeakMap(), instances = new WeakMap(), memories = new WeakMap(), tables = new WeakMap(), globals = new WeakMap(), functions = new WeakMap();
    const nativeObjects = new Map(), states = new Map(), bufferOwners = new WeakMap();
    const frames = [], cells = new Map(), objectCells = new WeakMap();
    const cellCleanup = new FinalizationRegistry(id => cells.delete(id));
    let nextCell = 0;
    function errorType(name) {
        function WasmError(message) { return Reflect.construct(Error, arguments, new.target || WasmError); }
        Object.setPrototypeOf(WasmError, Error);
        Object.setPrototypeOf(WasmError.prototype, Error.prototype);
        Object.defineProperty(WasmError, 'name', {value: name, configurable: true});
        Object.defineProperty(WasmError.prototype, 'name', {value: name, writable: true, configurable: true});
        return WasmError;
    }
    const CompileError = errorType('CompileError'), LinkError = errorType('LinkError'), RuntimeError = errorType('RuntimeError');
    const errors = {CompileError, LinkError, RuntimeError, RangeError};
    const cleanup = new FinalizationRegistry(id => { nativeObjects.delete(id); states.delete(id); host('release', id); });
    function run(op, ...args) {
        const [error, value] = host(op, ...args);
        if (error === 'host') throw frames[frames.length - 1].failure;
        if (error) throw new errors[error](value);
        return value;
    }
    function slot(map, object) {
        const value = map.get(object);
        if (!value) throw new TypeError('Invalid WebAssembly receiver');
        return value;
    }
    function own(object, id, map, info = {}) {
        const value = {id, ...info};
        map.set(object, value);
        nativeObjects.set(id, new WeakRef(object));
        cleanup.register(object, id, object);
        return value;
    }
    function bytes(value) {
        if (value instanceof ArrayBuffer) return value.slice(0);
        if (ArrayBuffer.isView(value)) return value.buffer.slice(value.byteOffset, value.byteOffset + value.byteLength);
        throw new TypeError('WASM bytes require an ArrayBuffer or TypedArray');
    }
    function index(value, max = 0xffffffff) {
        const number = +value;
        if (!Number.isFinite(number) || number < 0 || Math.trunc(number) > max) throw new TypeError('Invalid WASM index');
        return Math.trunc(number);
    }
    function limits(descriptor, max) {
        if (descriptor === null || (typeof descriptor !== 'object' && typeof descriptor !== 'function')) throw new TypeError('WASM descriptor requires an object');
        const address = descriptor.address;
        if (address !== undefined && String(address) !== 'i32') throw new TypeError('Only i32 WASM addresses are supported');
        const initial = index(descriptor.initial), upper = descriptor.maximum;
        const maximum = upper === undefined ? max : index(upper);
        if (initial > max || maximum > max) throw new RangeError('WASM size exceeds supported maximum');
        if (initial > maximum) throw new RangeError('WASM initial exceeds maximum');
        return [initial, maximum];
    }
    function valueType(value, reference = false) {
        const type = String(value);
        if (!['i32', 'i64', 'f32', 'f64', 'externref', ...(reference ? ['anyfunc', 'funcref'] : [])].includes(type)) throw new TypeError('Invalid WASM value type');
        return type === 'anyfunc' ? 'funcref' : type;
    }
    function ref(value) {
        if (value === null) return -1;
        let cell = value && (typeof value === 'object' || typeof value === 'function') ? objectCells.get(value) : undefined;
        if (!cell) {
            if (++nextCell >= 0x7fffffff) throw new RangeError('WASM reference budget exhausted');
            cell = {id: nextCell, value};
            cells.set(cell.id, new WeakRef(cell));
            cellCleanup.register(cell, cell.id);
            if (value && (typeof value === 'object' || typeof value === 'function')) objectCells.set(value, cell);
        }
        if (frames.length) frames[frames.length - 1].roots.push(cell);
        return cell.id;
    }
    function rootReference(id) {
        if (id === -1) return null;
        const cell = cells.get(id)?.deref();
        if (!cell) throw new RuntimeError('WASM reference is no longer available');
        return cell;
    }
    function cellValue(id) { const cell = rootReference(id); return cell === null ? null : cell.value; }
    function encode(type, value) {
        if (type === 'externref') return ref(value);
        if (type === 'i64') {
            return BigInt.asIntN(64, value);
        }
        const number = +value;
        return type === 'i32' ? number | 0 : type === 'f32' ? Math.fround(number) : number;
    }
    function decode(type, value) { return type === 'externref' ? cellValue(value) : value; }
    function retain(value, frame = frames[frames.length - 1]) {
        if (frame) frame.roots.push(value);
    }
    function referenceSlots(state) {
        const slots = new Map();
        for (const object of state.resources) {
            const table = tables.get(object), global = globals.get(object);
            if (table || global?.type === 'externref') slots.set((table || global).id, {table, global});
        }
        return slots;
    }
    function retainSlots(slots, frame) {
        for (const {table, global} of slots.values()) retain(table ? table.roots : global.root, frame);
    }
    function refreshSlots(slots, frame) {
        if (!slots.size) return;
        retainSlots(slots, frame);
        const requests = [...slots].map(([id, {table}]) => [id, table?.revision ?? -1n]);
        const updates = run('references', requests).map(([id, revision, value]) => {
            const slot = slots.get(id);
            const roots = slot.table ? value.map(ref => slot.table.type === 'externref' ? rootReference(ref[1]) : tableFunction(ref)) : rootReference(value);
            return {slot, revision, roots};
        });
        for (const {slot, revision, roots} of updates) {
            retain(roots, frame);
            if (slot.table) { slot.table.roots = roots; slot.table.revision = revision; }
            else slot.global.root = roots;
        }
    }
    function refresh(state) { refreshSlots(referenceSlots(state), frames[frames.length - 1]); }
    function synchronize(frame) {
        const slots = new Map();
        for (const state of frame.states) {
            loadResources(state, run('resources', state.id));
            for (const [id, slot] of referenceSlots(state)) slots.set(id, slot);
        }
        refreshSlots(slots, frame);
    }
    function discard(frame) {
        const index = frames.indexOf(frame);
        if (index !== -1) frames.splice(index, 1);
        frame.roots.length = 0;
        frame.states.clear();
    }
    function finish(frame) {
        for (const id of run('end', frame.id)) states.delete(id);
        discard(frame);
    }
    function invoke(state, action) {
        if (state.closed) throw new Error('WASM instance is closed');
        const frame = {roots: [], states: new Set([state])};
        frame.id = begin(frame, state.id);
        frames.push(frame);
        retainSlots(referenceSlots(state), frame);
        try { return action(frame.id); }
        finally {
            try {
                synchronize(frame);
                finish(frame);
            }
            finally {
                const index = frames.indexOf(frame);
                if (index !== -1) frames.splice(index, 1);
            }
        }
    }
    function tableFunction([id, index]) {
        if (index === -1) return null;
        const state = states.get(id)?.deref();
        if (!state) throw new RuntimeError('WASM function instance is no longer available');
        return state.functions.get(index) || makeFunction(state, run('function', id, index));
    }
    function functionRef(value) {
        if (value === null) return [0, -1];
        const fn = functions.get(value);
        if (!fn) throw new TypeError('WASM table requires an exported WASM function');
        return [fn.state.id, fn.index];
    }
    function makeFunction(state, descriptor) {
        const [, id, index, params, results] = descriptor;
        if (id !== state.id) state = states.get(id)?.deref();
        if (!state) throw new RuntimeError('WASM function instance is no longer available');
        if (state.functions.has(index)) return state.functions.get(index);
        const fn = (...args) => invoke(state, execution => {
            const values = run('call', execution, state.id, index, params.map((type, i) => encode(type, args[i])));
            const decoded = results.map((type, i) => decode(type, values[i]));
            return decoded.length > 1 ? decoded : decoded[0];
        });
        Object.defineProperties(fn, {length: {value: params.length}, name: {value: String(index)}});
        functions.set(fn, {state, index, params, results});
        state.functions.set(index, fn);
        return fn;
    }
    function resource(descriptor) {
        const [kind, id, type, mutable] = descriptor;
        const previous = nativeObjects.get(id)?.deref();
        if (previous) return previous;
        const constructor = kind === 'memory' ? Memory : kind === 'table' ? Table : Global;
        const object = Object.create(constructor.prototype);
        own(object, id, kind === 'memory' ? memories : kind === 'table' ? tables : globals, {type, mutable, roots: []});
        return object;
    }
    class Module {
        constructor(binary) {
            const [id, imports, exports] = run('compile', bytes(binary));
            own(this, id, modules, {imports, exports});
        }
        static customSections(module, name) { return run('custom', slot(modules, module).id, String(name)); }
        static imports(module) { return slot(modules, module).imports.map(([module, name, kind]) => ({module, name, kind})); }
        static exports(module) { return slot(modules, module).exports.map(([name, kind]) => ({name, kind})); }
    }
    class Memory {
        constructor(descriptor) {
            const [initial, maximum] = limits(descriptor, 65536);
            if (descriptor.shared) throw new TypeError('Shared WASM memory is disabled');
            const [, id] = run('memory', initial, maximum);
            own(this, id, memories);
        }
        get buffer() {
            const state = slot(memories, this);
            if (!state.buffer) {
                state.buffer = run('buffer', state.id);
                bufferOwners.set(state.buffer, this);
            }
            return state.buffer;
        }
        grow(delta) {
            const state = slot(memories, this);
            const result = run('growMemory', state.id, index(delta));
            if (result < 0) throw new RangeError('WASM memory cannot grow');
            return result;
        }
    }
    class Table {
        constructor(descriptor, value) {
            const type = valueType(descriptor?.element, true);
            const [initial, maximum] = limits(descriptor, 0xffffffff);
            if (type === 'funcref' && value === undefined) value = null;
            if (type !== 'funcref' && type !== 'externref') throw new TypeError('Invalid WASM table element type');
            const encoded = type === 'externref' ? [0, ref(value)] : functionRef(value);
            const [, id] = run('table', type, initial, maximum, encoded);
            const root = type === 'externref' ? rootReference(encoded[1]) : value;
            own(this, id, tables, {type, roots: Array(initial).fill(root)});
        }
        get length() { return run('tableSize', slot(tables, this).id); }
        get(position) {
            const state = slot(tables, this), i = index(position);
            if (i >= run('tableSize', state.id)) throw new RangeError('WASM table index out of bounds');
            const value = run('tableGet', state.id, i);
            return state.type === 'externref' ? cellValue(value[1]) : tableFunction(value);
        }
        set(position, value) {
            const state = slot(tables, this), i = index(position);
            if (state.type === 'funcref' && value === undefined) value = null;
            if (i >= run('tableSize', state.id)) throw new RangeError('WASM table index out of bounds');
            const encoded = state.type === 'externref' ? [0, ref(value)] : functionRef(value);
            retain(state.roots[i]);
            run('tableSet', state.id, i, encoded);
            state.roots[i] = state.type === 'externref' ? rootReference(encoded[1]) : value;
        }
        grow(delta, value) {
            const state = slot(tables, this);
            if (state.type === 'funcref' && value === undefined) value = null;
            const count = index(delta);
            const encoded = state.type === 'externref' ? [0, ref(value)] : functionRef(value);
            const result = run('growTable', state.id, count, encoded);
            if (result < 0) throw new RangeError('WASM table cannot grow');
            state.roots.length = run('tableSize', state.id);
            state.roots.fill(state.type === 'externref' ? rootReference(encoded[1]) : value, result);
            return result;
        }
    }
    class Global {
        constructor(descriptor, value) {
            const type = valueType(descriptor.value), mutable = !!descriptor.mutable;
            if (value === undefined) value = type === 'i64' ? 0n : type === 'externref' ? undefined : 0;
            const encoded = encode(type, value);
            const [, id] = run('global', type, mutable, encoded);
            own(this, id, globals, {type, mutable, root: type === 'externref' ? rootReference(encoded) : null});
        }
        get value() { const state = slot(globals, this); return decode(state.type, run('globalGet', state.id)); }
        set value(value) {
            const state = slot(globals, this);
            if (!state.mutable) throw new TypeError('WASM global is immutable');
            const encoded = encode(state.type, value);
            retain(state.root);
            run('globalSet', state.id, encoded);
            if (state.type === 'externref') state.root = rootReference(encoded);
        }
        valueOf() { return this.value; }
    }
    function loadResources(state, descriptors) { for (const descriptor of descriptors) state.resources.add(resource(descriptor)); }
    function linkImports(state, imports) {
        const info = slot(modules, state.module);
        for (let i = 0; i < info.imports.length; i++) {
            const [namespace, name, kind, type, mutable] = info.imports[i];
            const container = imports?.[namespace];
            if (container === null || (typeof container !== 'object' && typeof container !== 'function')) throw new TypeError(state.legacy && kind === 'function' ? 'Missing WASM function import: ' + namespace + '.' + name : 'Missing WASM import module: ' + namespace);
            let value = container[name], link;
            if (kind === 'function') {
                if (typeof value !== 'function') throw new LinkError('Missing WASM function import: ' + namespace + '.' + name);
                const fn = functions.get(value);
                link = fn ? [fn.state.id, fn.index] : [];
            } else {
                const map = kind === 'memory' ? memories : kind === 'table' ? tables : globals;
                if (kind === 'global' && !mutable && !map.has(value)) {
                    if (type !== 'externref' && typeof value !== (type === 'i64' ? 'bigint' : 'number')) throw new LinkError('Invalid WASM global import: ' + namespace + '.' + name);
                    value = new Global({value: type}, value);
                }
                const imported = map.get(value);
                if (!imported) throw new LinkError('Invalid WASM ' + kind + ' import: ' + namespace + '.' + name);
                link = [imported.id];
                state.resources.add(value);
            }
            state.imports.push(value);
            run('link', state.id, i, link);
        }
    }
    function prepareFunctions(state) {
        const signatures = state.legacy ? Object.create(null) : null;
        for (const [name, descriptor] of run('functions', state.id)) {
            const fn = makeFunction(state, descriptor);
            state.exports[name] = fn;
            if (signatures) {
                const {params, results} = functions.get(fn);
                signatures[name] = Object.freeze({params: Object.freeze(params), results: Object.freeze(results)});
            }
        }
        if (signatures) state.signatures = Object.freeze(signatures);
    }
    function create(module, imports, legacy = false) {
        const info = slot(modules, module);
        if (imports !== undefined && (imports === null || (typeof imports !== 'object' && typeof imports !== 'function'))) throw new TypeError('WASM imports require an object');
        const id = run('instance', info.id);
        const state = {id, module, imports: [], resources: new Set(), functions: new Map(), exports: Object.create(null), closed: false};
        states.set(id, new WeakRef(state));
        cleanup.register(state, id, state);
        try {
            if (legacy) state.legacy = legacyInstance(state);
            linkImports(state, imports);
            loadResources(state, run('prepare', id));
            prepareFunctions(state);
        } catch (error) {
            state.closed = true;
            cleanup.unregister(state);
            states.delete(id);
            run('abort', id);
            throw error;
        }
        // Initialization can publish functions through imported tables even when start traps.
        const [exports, resources] = invoke(state, execution => run('start', execution, id));
        const result = Object.create(null);
        loadResources(state, resources);
        for (const [name, descriptor] of exports) Object.defineProperty(result, name, {value: descriptor[0] === 'function' ? makeFunction(state, descriptor) : resource(descriptor), enumerable: true});
        state.exports = Object.freeze(result);
        refresh(state);
        return state;
    }
    class Instance {
        constructor(module, imports) { instances.set(this, create(module, imports)); }
        get exports() { return slot(instances, this).exports; }
    }
    connect((operation, id, index, values) => {
        if (operation === 'enter') {
            const state = states.get(index)?.deref();
            if (!state) throw new RuntimeError('WASM instance is no longer available');
            loadResources(state, values);
            id.states.add(state);
            retainSlots(referenceSlots(state), id);
            return [];
        }
        if (operation === 'settle' || operation === 'discard') {
            if (operation === 'settle') for (const frame of id) synchronize(frame);
            for (const frame of id) operation === 'settle' ? finish(frame) : discard(frame);
            return [];
        }
        if (operation === 'grow') {
            for (const frame of frames) synchronize(frame);
            const object = nativeObjects.get(id)?.deref();
            if (object) {
                const state = memories.get(object);
                if (state.buffer) {
                    detach({buffer: state.buffer});
                    bufferOwners.delete(state.buffer);
                    state.buffer = undefined;
                }
            }
            return [];
        }
        const state = states.get(id)?.deref();
        try {
            if (!state) throw new RuntimeError('WASM instance is no longer available');
            for (const frame of frames) synchronize(frame);
            const spec = modules.get(state.module).imports[index];
            const [, , , params, results] = spec;
            const args = params.map((type, i) => decode(type, values[i]));
            const value = state.legacy ? state.imports[index](state.legacy, ...args) : state.imports[index](...args);
            if (state.legacy && value && typeof value.then === 'function') throw new TypeError('WASM imports must be synchronous');
            const returned = results.length === 0 ? [] : results.length === 1 ? [value] : Array.from(value);
            if (returned.length !== results.length) throw new TypeError('Invalid WASM result count');
            return results.map((type, i) => encode(type, returned[i]));
        } catch (reason) { frames[frames.length - 1].failure = reason; return null; }
    });
    const webAssembly = {Module, Instance, Memory, Table, Global, CompileError, LinkError, RuntimeError,
        validate(binary) {
            let module;
            try { module = new Module(binary); return true; }
            catch (error) { if (error instanceof CompileError) return false; throw error; }
            finally { if (module) { const {id} = modules.get(module); cleanup.unregister(module); nativeObjects.delete(id); run('release', id); } }
        },
        async compile(binary) { return new Module(binary); },
        async instantiate(binary, imports) {
            if (modules.has(binary)) return new Instance(binary, imports);
            const module = new Module(binary);
            return {module, instance: new Instance(module, imports)};
        }
    };
    for (const value of [Instance, Table, Global, webAssembly.instantiate]) Object.defineProperty(value, 'length', {value: 1, configurable: true});
    for (const [name, value] of Object.entries({Module, Instance, Memory, Table, Global})) Object.defineProperty(value.prototype, Symbol.toStringTag, {value: 'WebAssembly.' + name, configurable: true});
    Object.defineProperty(webAssembly, Symbol.toStringTag, {value: 'WebAssembly', configurable: true});
    for (const key of ['Module', 'Instance', 'Memory', 'Table', 'Global', 'CompileError', 'LinkError', 'RuntimeError']) Object.defineProperty(webAssembly, key, {enumerable: false});
    for (const constructor of [Module, Instance, Memory, Table, Global]) for (const key of Object.getOwnPropertyNames(constructor.prototype)) {
        if (key !== 'constructor') Object.defineProperty(constructor.prototype, key, {enumerable: true});
    }
    for (const key of ['imports', 'exports', 'customSections']) Object.defineProperty(Module, key, {enumerable: true});
    Object.defineProperty(globalThis, 'WebAssembly', {value: webAssembly, writable: true, configurable: true});
    function instantiate(binary, imports = {}) {
        return create(new Module(binary), imports, true).legacy;
    }
    function legacyInstance(state) {
        state.signatures = Object.freeze(Object.create(null));
        return Object.freeze({
            get exports() { return state.signatures; },
            call(name, ...args) {
                const fn = state.exports?.[name];
                if (typeof fn !== 'function') throw new TypeError('Unknown WASM function: ' + name);
                if (state.closed) throw new Error('WASM instance is closed');
                if (functions.get(fn).params.length !== args.length) throw new TypeError('Invalid WASM argument count');
                return fn(...args);
            },
            read(offset, length) {
                const id = memory(state), start = range(offset), count = range(length), size = run('size', id);
                if (start > size || count > size - start) throw new RangeError('WASM memory access is out of bounds');
                return new Uint8Array(run('read', id, start, count));
            },
            write(offset, value) {
                const id = memory(state), start = range(offset), content = bytes(value), size = run('size', id);
                if (start > size || content.byteLength > size - start) throw new RangeError('WASM memory access is out of bounds');
                run('write', id, start, content);
            },
            get byteLength() { return run('size', memory(state)); },
            close() {
                if (!state.closed) {
                    state.closed = true;
                    cleanup.unregister(state);
                    if (run('release', state.id)) states.delete(state.id);
                }
            }
        });
    }
    function range(value) {
        if (!Number.isSafeInteger(value) || value < 0 || value > 0x7fffffff) throw new RangeError('Invalid WASM memory range');
        return value;
    }
    function memory(state) {
        if (state.closed) throw new Error('WASM instance is closed');
        let object;
        for (const value of state.resources) if (memories.has(value)) { object = value; break; }
        if (!object) {
            object = resource(run('sessionMemory', state.id));
            state.resources.add(object);
        }
        return memories.get(object).id;
    }
    return Object.freeze({instantiate});
})
