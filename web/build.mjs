import fs from 'node:fs';
import path from 'node:path';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';

const directory = path.dirname(fileURLToPath(import.meta.url));
const modules = path.resolve(process.argv[2] || path.join(directory, 'node_modules'));
const require = createRequire(path.join(modules, '.resolve.cjs'));
const esbuild = require('esbuild');
const root = path.dirname(directory);
const outfile = path.join(root, 'src/main/resources/com/fongmi/quickjs/web.js');

function adaptEventTarget(source) {
    const changes = [
        ['signal: (_a = options.signal) !== null && _a !== void 0 ? _a : undefined,', 'signal: signalHooks.validate((_a = options.signal) !== null && _a !== void 0 ? _a : undefined),'],
        ['(signal === null || signal === void 0 ? void 0 : signal.aborted)', '(signal && signalHooks.aborted(signal))'],
        ['signalListener = removeListener.bind(null, list, callback, capture);\n        signal.addEventListener("abort", signalListener);', 'signalListener = signalHooks.subscribe(signal, removeListener.bind(null, list, callback, capture));'],
        ['listener.signal.removeEventListener("abort", listener.signalListener);', 'listener.signalListener();'],
        ['        internalDataMap$2.set(this, createListenerListMap());', '        const listeners = createListenerListMap();\n        listeners[targetOwner] = new WeakRef(this);\n        internalDataMap$2.set(this, listeners);'],
        ['        listeners: [],', '        listeners: [],\n        owner: listenerMap[targetOwner],'],
        ['    return listener;\n}', '    signalHooks.changed(list.owner.deref());\n    return listener;\n}'],
        ['        list.listeners = list.listeners.filter((_, i) => i !== index);', '        list.listeners = list.listeners.filter((_, i) => i !== index);\n        signalHooks.changed(list.owner.deref());'],
        ['    list.listeners.splice(index, 1);', '    list.listeners.splice(index, 1);\n    signalHooks.changed(list.owner.deref());'],
        ['        const list = $$2(this)[String(e.type)];\n        if (list == null) {\n            return true;\n        }\n        const event = e instanceof Event ? e : EventWrapper.wrap(e);\n        const eventData = $(event, "event");',
            '        if (!(e instanceof Event)) throw new TypeError("Expected an Event");\n        const event = e;\n        const eventData = $(event, "event");\n        const list = ensureListenerList($$2(this), eventData.type);'],
        ['        eventData.target = null;\n        eventData.currentTarget = null;', '        eventData.currentTarget = null;']
    ];
    for (const [before, after] of changes) {
        if (source.split(before).length !== 2) throw new Error('event-target-shim abort integration changed');
        source = source.replace(before, () => after);
    }
    return source + `
let signalHooks;
const targetOwner = Symbol();
export function configureSignalHooks(hooks) { signalHooks = hooks; }
export function hasAbortListeners(target) { return (internalDataMap$2.get(target)?.abort?.listeners.length ?? 0) !== 0; }
`;
}
const result = await esbuild.build({
    entryPoints: [path.join(directory, 'src/index.js')],
    outfile,
    bundle: true,
    platform: 'neutral',
    format: 'iife',
    target: 'es2022',
    nodePaths: [modules],
    mainFields: ['module', 'main'],
    conditions: ['import'],
    legalComments: 'inline',
    minify: true,
    keepNames: true,
    metafile: true,
    plugins: [{
        name: 'quickjs-platform',
        setup(build) {
            build.onResolve({filter: /^fetch-blob(?:\/file\.js)?$/}, args => ({
                path: path.join(modules, 'fetch-blob', args.path.endsWith('/file.js') ? 'file.js' : 'index.js')
            }));
            build.onLoad({filter: /(?:fetch-blob[/\\]index\.js|formdata-polyfill[/\\]esm\.min\.js|event-target-shim[/\\]index\.mjs|core-js-pure[/\\]modules[/\\]web\.structured-clone\.js)$/}, args => {
                let source = fs.readFileSync(args.path, 'utf8');
                if (args.path.includes('fetch-blob')) {
                    const start = source.indexOf('if (!globalThis.ReadableStream)');
                    const end = source.indexOf('// 64 KiB', start);
                    if (start < 0 || end < start) throw new Error('fetch-blob bootstrap changed');
                    source = source.slice(0, start) + source.slice(end);
                    const type = "this.#type = /^[\\x20-\\x7E]*$/.test(type) ? type : ''";
                    if (!source.includes(type)) throw new Error('fetch-blob MIME normalization changed');
                    source = source.replace(type, "this.#type = /^[\\x20-\\x7E]*$/.test(type) ? type.toLowerCase() : ''");
                    const close = 'chunk.done ? ctrl.close() : ctrl.enqueue(chunk.value)';
                    if (!source.includes(close)) throw new Error('fetch-blob stream close changed');
                    source = source.replace(close, 'if (chunk.done) { ctrl.close(); ctrl.byobRequest?.respond(0) } else ctrl.enqueue(chunk.value)');
                } else if (args.path.includes('formdata-polyfill')) {
                    for (const [before, after] of [
                        ["import C from 'fetch-blob'", "import {Blob as C} from 'fetch-blob'"],
                        ["import F from 'fetch-blob/file.js'", "import {File as F} from 'fetch-blob/file.js'"]
                    ]) {
                        if (!source.includes(before)) throw new Error('formdata-polyfill imports changed');
                        source = source.replace(before, after);
                    }
                } else if (args.path.includes('event-target-shim')) {
                    source = adaptEventTarget(source);
                } else {
                    const before = 'else clone = value;';
                    if (source.split(before).length !== 2) throw new Error('SharedArrayBuffer clone branch changed');
                    source = source.replace(before, "else throwUnpolyfillable('SharedArrayBuffer');");
                }
                return {contents: source, loader: 'js', resolveDir: path.dirname(args.path)};
            });
        }
    }]
});
const bundle = fs.readFileSync(outfile, 'utf8');
fs.writeFileSync(outfile, bundle.replace(/\r?\n/g, '\r\n'));
const packages = new Map();
for (const input of Object.keys(result.metafile.inputs)) {
    let location = path.dirname(path.resolve(input));
    while (location !== path.dirname(location)) {
        const metadata = path.join(location, 'package.json');
        if (location.includes('node_modules') && fs.existsSync(metadata)) {
            const info = JSON.parse(fs.readFileSync(metadata, 'utf8'));
            if (info.name && info.version) {
                packages.set(info.name, {directory: location, ...info});
                break;
            }
        }
        location = path.dirname(location);
    }
}
let licenses = '';
for (const [name, info] of [...packages].sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0)) {
    const licenseDirectory = name === '@web3-storage/multipart-parser' ? path.join(directory, 'licenses/multipart-parser') : info.directory;
    const files = fs.readdirSync(licenseDirectory).filter(file => /^(?:license|licence|copying)(?:[.\-_]|$)/i.test(file));
    if (!files.length) throw new Error('Missing license: ' + name);
    licenses += `${name}@${info.version}\n${'='.repeat(64)}\n`;
    for (const file of files.sort()) licenses += fs.readFileSync(path.join(licenseDirectory, file), 'utf8') + '\n';
}
const licenseFile = path.join(root, 'src/main/resources/META-INF/licenses/web.txt');
fs.mkdirSync(path.dirname(licenseFile), {recursive: true});
fs.writeFileSync(licenseFile, licenses.trimEnd().replace(/\r?\n/g, '\r\n') + '\r\n');
console.log(JSON.stringify({bytes: fs.statSync(outfile).size, packages: [...packages.keys()].sort()}));
