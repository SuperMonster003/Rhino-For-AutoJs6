/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests.classsyntax;

import static org.mozilla.javascript.testutils.Utils.assertWithAllModes_ES6;

import org.junit.jupiter.api.Test;

/**
 * AutoJs6 (ROADMAP P6.13, appendix C Q32): a member expression target of an array assignment
 * pattern evaluates its reference before the iterator steps, in both modes. Not related to classes.
 */
class ArrayMemberTargetsTest {

    private static final String SETUP =
            """
            var log = [], o = {};
            function it(values) {
              var i = 0;
              return {
                [Symbol.iterator]() { return this; },
                next() { log.push('next'); return i < values.length ? { value: values[i++], done: false } : { done: true }; },
                return() { log.push('return'); return {}; }
              };
            }
            function obj() { log.push('obj'); return o; }
            function key(k) { log.push('key'); return k; }
            function take() { var r = log.join(); log = []; return r; }
            function run(f) { try { f(); } catch (e) { log.push(e.name); } return take(); }
            """;

    @Test
    void theReferenceComesBeforeTheStep() {
        assertWithAllModes_ES6(
                "obj,next,return:1|obj,key,next,return:2|obj,key,next,next,next:3,4"
                        + "|obj,next,return:5|obj,key,next,next:6",
                SETUP
                        + """
                [run(() => [obj().a] = it([1])) + ':' + o.a,
                  run(() => [obj()[key('b')]] = it([2])) + ':' + o.b,
                  run(() => [...obj()[key('c')]] = it([3, 4])) + ':' + o.c,
                  run(() => [obj().d = 9] = it([5])) + ':' + o.d,
                  run(() => [obj()[key('e')], ...o.f] = it([6])) + ':' + o.e].join('|');
                """);
    }

    @Test
    void defaultsOfMemberTargets() {
        assertWithAllModes_ES6(
                "1|2:|3,4|true",
                SETUP
                        + """
                var r = {}, called = [], arr = [undefined];
                [r.a = 1] = [undefined];
                [r['b'] = called.push('d')] = [2];
                [[r.c] = [3], { x: r.d } = { x: 4 }] = [];
                [r.a, r.b + ':' + called, r.c + ',' + r.d, ([r.e] = arr) === arr].join('|');
                """);
    }

    @Test
    void errorsCloseTheIterator() {
        // An error evaluating the reference closes the iterator before it steps; an error putting
        // the value (a null base) comes after the step.
        assertWithAllModes_ES6(
                "obj,key,return,Error|next,return,TypeError|key,next,next,TypeError",
                SETUP
                        + """
                [run(() => [obj()[key(1), (() => { throw new Error(); })()]] = it([1])),
                  run(() => [null.x] = it([1])),
                  run(() => { var n = null; [...n[key('z')]] = it([1]); })].join('|');
                """);
    }

    @Test
    void returnDuringAYieldInTheReferenceClosesTheIterator() {
        assertWithAllModes_ES6(
                ":|return|7",
                SETUP
                        + """
                function* g(values) { [o[yield 'k']] = it(values); }
                var gen = g([1]);
                gen.next();
                var out = [take() + ':'];
                gen.return();
                out.push(take());
                var gen2 = g([7]);
                gen2.next();
                gen2.next('k2');
                take();
                out.push(o.k2);
                out.join('|');
                """);
    }

    @Test
    void privateNamesThisAndSuper() {
        assertWithAllModes_ES6(
                "5|6|3",
                """
                class C {
                  #x;
                  set(values) { [this.#x] = values; return this.#x; }
                }
                var p = { m() { [super.y] = [3]; return this.y; } };
                Object.setPrototypeOf(p, {});
                [new C().set([5]), ([this.q] = [6], this.q), p.m()].join('|');
                """);
    }
}
