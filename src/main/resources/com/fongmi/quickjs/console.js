{
    const counts = new Map();
    const timers = new Map();
    const now = performance.now.bind(performance);
    let depth = 0;
    const output = {
        stdout() { throw new Error('Console.stdout is not configured'); },
        print(level, ...values) { write(level, values.map(globalThis.format).join(', ')); }
    };
    function write(level, message) { output.stdout(level, '  '.repeat(depth) + message); }
    function label(value) { return `${value}`; }
    function time(name, values, remove) {
        name = label(name);
        if (!timers.has(name)) {
            write('warn', `Timer '${name}' does not exist`);
            return;
        }
        const elapsed = now() - timers.get(name);
        if (remove) timers.delete(name);
        write('info', `${name}: ${elapsed.toFixed(3)} ms` + (values.length ? ' ' + values.map(globalThis.format).join(', ') : ''));
    }
    for (const level of ['log','debug','info','warn','error'])
        output[level] = function(...values) { this.print(level, ...values); };
    Object.assign(output, {
        assert(condition, ...values) {
            if (!condition) write('error', 'Assertion failed' + (values.length ? ': ' + values.map(globalThis.format).join(', ') : ''));
        },
        dir(value) { this.print('log', value); },
        dirxml(...values) { this.print('log', ...values); },
        count(name = 'default') {
            name = label(name);
            const count = (counts.get(name) || 0) + 1;
            counts.set(name, count);
            write('info', `${name}: ${count}`);
        },
        countReset(name = 'default') {
            name = label(name);
            if (counts.has(name)) counts.set(name, 0);
            else write('warn', `Count for '${name}' does not exist`);
        },
        time(name = 'default') {
            name = label(name);
            if (timers.has(name)) write('warn', `Timer '${name}' already exists`);
            else timers.set(name, now());
        },
        timeLog(name = 'default', ...values) { time(name, values, false); },
        timeEnd(name = 'default') { time(name, [], true); },
        trace(...values) {
            const message = 'Trace' + (values.length ? ': ' + values.map(globalThis.format).join(', ') : '');
            write('debug', message + '\n' + new Error().stack);
        },
        group(...values) {
            if (values.length) this.print('log', ...values);
            depth++;
        },
        groupCollapsed(...values) { this.group(...values); },
        groupEnd() { depth = Math.max(0, depth - 1); },
        clear() { depth = 0; },
        table(data, properties) {
            if (data === null || typeof data !== 'object') {
                this.print('log', data);
                return;
            }
            const entries = data instanceof Map ? [...data].map(([Key, Values], index) => [index, {Key, Values}])
                : data instanceof Set ? [...data].map((Values, index) => [index, {Values}]) : Object.entries(data);
            const columns = properties === undefined ? [] : Array.from(properties, label);
            if (properties === undefined) {
                for (const [, row] of entries) {
                    const keys = row !== null && typeof row === 'object' ? Object.keys(row) : ['Values'];
                    for (const key of keys) if (!columns.includes(key)) columns.push(key);
                }
            }
            const rows = [['(index)', ...columns], ...entries.map(([index, row]) => [String(index), ...columns.map(key => {
                const value = row !== null && typeof row === 'object' ? row[key] : key === 'Values' ? row : undefined;
                return value === undefined ? '' : String(globalThis.format(value));
            })])];
            const widths = rows[0].map(cell => cell.length);
            for (const row of rows) for (let index = 0; index < row.length; index++) widths[index] = Math.max(widths[index], row[index].length);
            write('log', rows.map(row => row.map((cell, index) => cell.padEnd(widths[index])).join(' | ')).join('\n'));
        }
    });
    for (const key of Object.keys(output)) if (typeof output[key] === 'function') output[key] = output[key].bind(output);
    globalThis.console = output;
}
