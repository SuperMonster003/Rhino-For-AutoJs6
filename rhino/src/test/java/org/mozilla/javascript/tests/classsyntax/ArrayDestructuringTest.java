/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests.classsyntax;

import static org.mozilla.javascript.testutils.Utils.assertWithAllModes_ES6;

import org.junit.jupiter.api.Test;

/**
 * AutoJs6 (ROADMAP P6.12, appendix C Q25): the array patterns of declarations, assignments, catch
 * parameters and for heads iterate their values, and close the iterator when they end early, in
 * both modes. Not related to classes.
 */
class ArrayDestructuringTest {

    private static final String SETUP =
            """
            var log = [];
            // an iterable that logs its protocol steps
            function iter(values, tag) {
              return { [Symbol.iterator]() {
                log.push(tag + ':get');
                var i = 0;
                return {
                  next() { log.push(tag + ':next'); return i < values.length ? { value: values[i], done: false, i: i++ } : { done: true }; },
                  return() { log.push(tag + ':return'); return {}; }
                };
              } };
            }
            function run(f) { try { return String(f()); } catch (e) { return e.name; } }
            function take() { var r = log.join(); log = []; return r; }
            """;

    @Test
    void everyFormIterates() {
        assertWithAllModes_ES6(
                "1,2|3,4|5,6|7,8|9,10|11,12|13,14",
                SETUP
                        + """
                var r = [];
                var [a, b] = new Set([1, 2]); r.push([a, b]);
                let [c, d] = new Set([3, 4]); r.push([c, d]);
                const [e, f] = (function* () { yield 5; yield 6; })(); r.push([e, f]);
                var g, h; [g, h] = new Set([7, 8]); r.push([g, h]);
                try { throw new Set([9, 10]); } catch ([i, j]) { r.push([i, j]); }
                for (var [k, l] of [new Set([11, 12])]) r.push([k, l]);
                for (let [m, n] = new Set([13, 14]); ; ) { r.push([m, n]); break; }
                r.join('|');
                """);
    }

    @Test
    void holesRestAndNestedPatterns() {
        assertWithAllModes_ES6(
                "1,3|2,3|4,5,6|x,y,z|L:get,L:next,L:next,L:next,L:return",
                SETUP
                        + """
                var [a, , b] = new Set([1, 2, 3]);
                var [, ...c] = new Set([1, 2, 3]);
                var [[d, e], f] = [new Set([4, 5]), 6];
                var [...[g, h, k]] = 'xyz';
                var [m, , n] = iter([1, 2, 3, 4], 'L');
                [[a, b], c, [d, e, f], [g, h, k], take()].join('|');
                """);
    }

    @Test
    void theIteratorClosesWhenThePatternEndsEarly() {
        assertWithAllModes_ES6(
                "A:get,A:next,A:return|B:get,B:next,B:return|C:get,C:next,C:next,C:next|"
                        + "D:get,D:return|E:get,E:next,E:return",
                SETUP
                        + """
                var r = [];
                var [a] = iter([1, 2], 'A'); r.push(take());
                var [b] = iter([1], 'B'); r.push(take());
                var [...c] = iter([1, 2], 'C'); r.push(take());
                var [] = iter([1], 'D'); r.push(take());
                var x; [x] = iter([1, 2], 'E'); r.push(take());
                r.join('|');
                """);
    }

    @Test
    void anExceptionClosesTheIteratorsItLeaves() {
        // A default that throws closes the iterator, inner patterns first; an error of the
        // iterator itself does not; an error of return is ignored, the first exception wins.
        assertWithAllModes_ES6(
                "Error|T:get,T:next,T:return|Error|O:get,O:next,I:get,I:next,I:return,O:return|"
                        + "TypeError|N:get|Error|R:get,R:next",
                SETUP
                        + """
                var r = [];
                function boom() { throw new Error('boom'); }
                r.push(run(() => { var [a = boom()] = iter([undefined, 2], 'T'); })); r.push(take());
                r.push(run(() => { var [[b = boom()]] = iter([iter([undefined], 'I'), 2], 'O'); })); r.push(take());
                var broken = { [Symbol.iterator]() { log.push('N:get'); return { next() { return 1; }, return() { log.push('N:return'); } }; } };
                r.push(run(() => { var [c] = broken; })); r.push(take());
                var badReturn = { [Symbol.iterator]() { log.push('R:get'); return { next() { log.push('R:next'); return { value: undefined, done: false }; }, return() { throw new TypeError('return'); } }; } };
                r.push(run(() => { var [d = boom()] = badReturn; })); r.push(take());
                r.join('|');
                """);
    }

    @Test
    void returnIsCheckedWhenThePatternEndsNormally() {
        assertWithAllModes_ES6(
                "TypeError|TypeError|ok",
                SETUP
                        + """
                function withReturn(ret) {
                  return { [Symbol.iterator]() { return { next() { return { value: 1, done: false }; }, return: ret }; } };
                }
                [run(() => { var [a] = withReturn(function () { return 1; }); }),
                  run(() => { var [a] = withReturn(1); }),
                  run(() => { var [a] = withReturn(undefined); return 'ok'; })].join('|');
                """);
    }

    @Test
    void defaultsRunInOrderAndOnlyForUndefined() {
        assertWithAllModes_ES6(
                "D:get,D:next,d1,D:next,D:next,d3,D:return|1,null,3",
                SETUP
                        + """
                function d(n) { log.push('d' + n); return n; }
                var [a = d(1), b = d(2), c = d(3)] = iter([undefined, null, undefined, 4], 'D');
                [take(), [a, b, c].map(String).join()].join('|');
                """);
    }

    @Test
    void nextIsReadOnceAndNotCalledAfterDone() {
        assertWithAllModes_ES6(
                "1|1|undefined,undefined",
                SETUP
                        + """
                var reads = 0, calls = 0;
                var it = { [Symbol.iterator]() {
                  var o = { get next() { reads++; return function () { calls++; return { done: true }; }; } };
                  return o;
                } };
                var [a, b] = it;
                [reads, calls, [a, b].map(String).join()].join('|');
                """);
    }

    @Test
    void forHeadsCloseTheirPatternsBeforeTheLoop() {
        assertWithAllModes_ES6(
                "Error|E:get,E:next,E:return,O:return|1:2,3:4|P:get,P:next,P:next,P:return",
                SETUP
                        + """
                var r = [];
                function boom() { throw new Error('boom'); }
                r.push(run(() => { for (var [a = boom()] of iter([iter([undefined], 'E')], 'O')) ; }));
                r.push(take().replace('O:get,O:next,', ''));
                var seen = [];
                outer: for (var [x, y] of [[1, 2], [3, 4], [5, 6]]) { seen.push(x + ':' + y); if (x == 3) break outer; continue outer; }
                r.push(seen.join());
                for (var [p, q] = iter([1, 2, 3], 'P'), n = 0; n < 1; n++) ;
                r.push(take());
                r.join('|');
                """);
    }

    @Test
    void aGeneratorThatReturnsDuringAYieldClosesThePattern() {
        assertWithAllModes_ES6(
                "G:get,G:next,G:return|42|H:get,H:next,H:return|v",
                SETUP
                        + """
                var r = [];
                function* g() { var [a = yield 'y', b] = iter([undefined, 2], 'G'); }
                var it = g(); it.next(); var ret = it.return(42); r.push(take()); r.push(ret.value);
                function* h() { var [a = yield 'y'] = iter([undefined], 'H'); return a; }
                it = h(); it.next(); var last = it.next('v'); r.push(take()); r.push(last.value);
                r.join('|');
                """);
    }

    @Test
    void plainArraysReadTheirElementsAsTheArrayIteratorDoes() {
        // Holes read the prototype, the length counts at each step, a getter runs once.
        assertWithAllModes_ES6(
                "p,2|1,d,undefined|g:1",
                SETUP
                        + """
                Array.prototype[0] = 'p';
                var [a, b] = [, 2];
                delete Array.prototype[0];
                var arr = [1, undefined, 3];
                var [c, d = (arr.length = 1, 'd'), e] = arr;
                var gets = 0, withGetter = [0];
                Object.defineProperty(withGetter, 0, { get() { gets++; return 'g'; } });
                var [k] = withGetter;
                [[a, b], [c, d, e].map(String), k + ':' + gets].join('|');
                """);
    }

    @Test
    void plainArraysFollowChangesOfTheBuiltins() {
        // The guard of the realm (ArrayIterationGuard) is invalidated by these changes.
        assertWithAllModes_ES6(
                "1,2|it|nx|R:1|undefined,9",
                SETUP
                        + """
                var r = [];
                var [a, b] = [1, 2]; r.push([a, b]);
                var values = Array.prototype[Symbol.iterator];
                Array.prototype[Symbol.iterator] = function* () { yield 'it'; };
                var [c] = [1]; r.push(c);
                Array.prototype[Symbol.iterator] = values;
                var aip = Object.getPrototypeOf([][Symbol.iterator]());
                var next = aip.next;
                aip.next = function () { return { value: 'nx', done: false }; };
                var [d] = [1]; r.push(d);
                aip.next = next;
                var closes = 0;
                Object.prototype.return = function () { closes++; return {}; };
                var [e] = [1, 2]; r.push('R:' + closes);
                delete Object.prototype.return;
                var own = [1]; own[Symbol.iterator] = function* () { yield undefined; yield 9; };
                var [f, g] = own; r.push(String(f) + ',' + g);
                r.join('|');
                """);
    }

    @Test
    void aReturnAddedDuringThePatternIsFound() {
        assertWithAllModes_ES6(
                "1:2|1",
                SETUP
                        + """
                var r = [], closes = 0, at;
                var aip = Object.getPrototypeOf([][Symbol.iterator]());
                function addReturn() {
                  aip.return = function () { closes++; at = this.next().value; return {}; };
                }
                var [a = addReturn(), b] = [undefined, 2, 3, 4];
                r.push(closes + ':' + (at - 1));
                delete aip.return;
                var [c] = [1, 2];
                r.push(closes);
                r.join('|');
                """);
    }

    @Test
    void valuesThatAreNotIterableThrow() {
        assertWithAllModes_ES6(
                "TypeError|TypeError|TypeError|TypeError|TypeError",
                SETUP
                        + """
                [run(() => { var [a] = 5; }),
                  run(() => { var [a] = null; }),
                  run(() => { var [a] = { 0: 1, length: 1 }; }),
                  run(() => { var a; [a] = { [Symbol.iterator]: 1 }; }),
                  run(() => { var [a] = { [Symbol.iterator]() { return 1; } }; })].join('|');
                """);
    }

    @Test
    void theStatementsAroundThePatternsKeepWorking() {
        assertWithAllModes_ES6(
                "1,2|0,1,2|s|ok|1,2|7|5|0,1,2|3|1",
                SETUP
                        + """
                var r = [];
                var k = 0, w = []; while ([k] = [k + 1], k < 3) w.push(k); r.push(w);
                k = 0; w = []; do { w.push(k); } while (([k] = [k + 1])[0] < 3); r.push(w);
                var s; if ([s] = new Set(['s'])) r.push(s);
                switch ([s] = ['sw'], s) { case 'sw': r.push('ok'); }
                var o = { p: 0 }, q; with (o) { [q, p] = new Set([1, 2]); } r.push([q, o.p]);
                r.push(((v) => [v] = new Set([7]))() instanceof Set ? 7 : 0);
                r.push(eval('var [ea, eb] = new Set([2, 3]); ea + eb'));
                var fs = []; for (let [i] = [0]; i < 3; i++) fs.push(() => i); r.push(fs.map(f => f()));
                r.push((() => { try { throw [1, 2]; } catch ([a, b]) { return a + b; } })());
                let x = 1; { let [x] = [2]; } r.push(x);
                r.join('|');
                """);
    }
}
