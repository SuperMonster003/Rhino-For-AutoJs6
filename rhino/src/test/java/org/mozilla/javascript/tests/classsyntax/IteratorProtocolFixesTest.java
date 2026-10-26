/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests.classsyntax;

import static org.mozilla.javascript.testutils.Utils.assertWithAllModes_ES6;

import org.junit.jupiter.api.Test;

/**
 * AutoJs6 (ROADMAP P6.10, appendix C Q21 / Q22): upstream deviations of the iterator protocol from
 * the spec, fixed in the fork, in both modes. Not related to classes.
 */
class IteratorProtocolFixesTest {

    private static final String SETUP =
            """
            var log = [];
            // an iterable of the values whose iterator logs reads of return and calls of it
            function iterable(values, tag) {
              return { [Symbol.iterator]() {
                var i = 0;
                var it = {
                  next() { return i < values.length ? { value: values[i++], done: false } : { value: undefined, done: true }; },
                  get return() { log.push(tag + ':get'); return function () { log.push(tag); return {}; }; },
                  [Symbol.iterator]() { return it; }
                };
                return it;
              } };
            }
            function setLike(values, tag) {
              return { size: values.length, has(v) { return values.indexOf(v) >= 0; },
                keys() { return iterable(values, tag)[Symbol.iterator](); } };
            }
            function args() { return Array.prototype.join.call(arguments, ','); }
            function run(f) { try { return String(f()); } catch (e) { return e.name; } }
            """;

    @Test
    void elementsAreReadThroughThePrototypeChain() {
        assertWithAllModes_ES6(
                "1,p,3,g:true,5|1,p,3,g:true,5|1,p,3,g:true,5|1,p,3,g:true,5|1,p,3,g:true,5|x,o"
                        + "|1,p|1,2",
                SETUP
                        + """
                var a = [1, , 3, , 5];
                Array.prototype[1] = 'p';
                Object.defineProperty(Array.prototype, 3,
                  { get() { return 'g:' + (this === a); }, configurable: true });
                Object.prototype[1] = 'o';
                var out = [];
                for (var x of a) out.push(x);
                var r = [[...a], Array.from(a), out, [...a.values()], args(...a),
                  [...Array.prototype.values.call({ length: 2, 0: 'x' })],
                  [...[, , ].entries()][1], [...new Int8Array([1, 2])]];
                delete Array.prototype[1];
                delete Array.prototype[3];
                delete Object.prototype[1];
                r.join('|');
                """);
    }

    @Test
    void normalCompletionDoesNotCloseTheIterator() {
        assertWithAllModes_ES6(
                "2|1,2|2|1|2|true|true|1|2|2|2|2|2|2|true|true|2|0,1,2|",
                SETUP
                        + """
                var r = [
                  [...iterable([1, 2], 'spread')].length,
                  args(...iterable([1, 2], 'call')),
                  Array.from(iterable([1, 2], 'from')).length,
                  new Map(iterable([[1, 2]], 'map')).size,
                  new Set(iterable([1, 2], 'set')).size,
                  new WeakMap(iterable([[{}, 1]], 'weakmap')) instanceof WeakMap,
                  new WeakSet(iterable([{}], 'weakset')) instanceof WeakSet,
                  Object.keys(Object.fromEntries(iterable([['k', 1]], 'fromEntries'))).length,
                  new AggregateError(iterable([1, 2], 'aggregate')).errors.length,
                  new Int8Array(iterable([1, 2], 'typed')).length,
                  Int8Array.from(iterable([1, 2], 'typedFrom')).length,
                  Object.keys(Object.groupBy(iterable([1, 2], 'groupBy'), x => x)).length,
                  Map.groupBy(iterable([1, 2], 'mapGroupBy'), x => x).size,
                  new Set([1]).union(setLike([2], 'union')).size,
                  Promise.all(iterable([1], 'all')) instanceof Promise,
                  Promise.race(iterable([1], 'race')) instanceof Promise,
                  (function () { var n = 0; for (var x of iterable([1, 2], 'forOf')) n++; return n; })(),
                  (([x, y, z]) => [x === 1, y === 2, z === undefined].indexOf(false) + 1)(iterable([1, 2], 'param'))
                    + ',' + Promise.allSettled(iterable([1], 'allSettled')).constructor.length
                    + ',' + (Promise.any(iterable([1], 'any')) instanceof Promise ? 2 : 0),
                  log.join()];
                r.join('|');
                """);
    }

    @Test
    void earlyExitsStillCloseTheIterator() {
        assertWithAllModes_ES6(
                "1|1|TypeError|Error|false|1|forOf:get,forOf,param:get,param,map:get,map,"
                        + "from:get,from,disjoint:get,disjoint,return:get,return",
                SETUP
                        + """
                var r = [
                  (function () { for (var x of iterable([1, 2], 'forOf')) return x; })(),
                  (([x]) => x)(iterable([1, 2], 'param')),
                  run(() => new Map(iterable([1], 'map'))),
                  run(() => Array.from(iterable([1], 'from'), () => { throw new Error('x'); })),
                  new Set([1, 2, 3]).isDisjointFrom(setLike([1], 'disjoint')),
                  (function () { for (var x of iterable([1, 2], 'return')) { return 1; } })(),
                  log.join()];
                r.join('|');
                """);
    }

    @Test
    void returnIsLookedUpOnlyWhenClosing() {
        // Upstream read return when it got the iterator, so a return that is not callable threw
        // even when the iteration completed (appendix C Q22).
        assertWithAllModes_ES6(
                "2|2|2|TypeError|TypeError",
                SETUP
                        + """
                function bad(values) {
                  return { [Symbol.iterator]() {
                    var i = 0;
                    return { next() { return { value: values[i], done: i++ >= values.length }; }, return: 1 };
                  } };
                }
                [[...bad([1, 2])].length, Array.from(bad([1, 2])).length, new Set(bad([1, 2])).size,
                  run(() => { for (var x of bad([1, 2])) break; }),
                  run(() => new Map(bad([1])))].join('|');
                """);
    }

    @Test
    void errorsOfTheIteratorDoNotCloseIt() {
        // next(), done and value throwing come from the iterator itself, which is not closed;
        // an error of the consumer closes it (appendix C Q22).
        assertWithAllModes_ES6(
                "Error,Error,Error,Error|Error,Error,Error|TypeError|consumer:get,consumer",
                SETUP
                        + """
                function failing(kind) {
                  return { [Symbol.iterator]() {
                    return {
                      next() {
                        if (kind === 'next') throw new Error('next');
                        return kind === 'done'
                          ? { get done() { throw new Error('done'); } }
                          : { done: false, get value() { throw new Error('value'); } };
                      },
                      get return() { log.push(kind + ':get'); return function () { log.push(kind); return {}; }; }
                    };
                  } };
                }
                var r1 = [run(() => [...failing('next')]), run(() => args(...failing('done'))),
                  run(() => Array.from(failing('value'))), run(() => new Set(failing('next')))];
                var r2 = [run(() => new Map(failing('done'))), run(() => [...failing('value')]),
                  run(() => Object.fromEntries(failing('next')))];
                var r3 = run(() => new Map(iterable([1], 'consumer')));
                [r1, r2, r3, log.join()].join('|');
                """);
    }

    @Test
    void iteratorResultsHaveOwnDataProperties() {
        // Upstream wrote value and done of a result with putProperty, which ran setters of
        // Object.prototype and skipped read-only properties there.
        assertWithAllModes_ES6(
                "1,2|1,2|a,b|m,s|1|value,done|true,true,true|0",
                SETUP
                        + """
                var sets = 0;
                Object.defineProperty(Object.prototype, 'value',
                  { get() { return 'proto'; }, set(v) { sets++; }, configurable: true });
                Object.defineProperty(Object.prototype, 'done',
                  { value: true, writable: false, configurable: true });
                var a = [1, 2];
                var r = [[...a], [...a.values()], [...'ab'],
                  [...new Map([[1, 'm']]).values(), ...new Set(['s']).keys()],
                  (function* () { yield 1; })().next().value];
                var res = [].values().next();
                var d = Object.getOwnPropertyDescriptor([1].values().next(), 'value');
                r.push(Object.getOwnPropertyNames(res), [d.writable, d.enumerable, d.configurable], sets);
                delete Object.prototype.value;
                delete Object.prototype.done;
                r.join('|');
                """);
    }
}
