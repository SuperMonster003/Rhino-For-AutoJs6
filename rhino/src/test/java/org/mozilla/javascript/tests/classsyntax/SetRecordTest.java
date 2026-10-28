/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests.classsyntax;

import static org.mozilla.javascript.testutils.Utils.assertWithAllModes_ES6;

import org.junit.jupiter.api.Test;

/**
 * AutoJs6 (ROADMAP P6.13, appendix C Q26): GetSetRecord and the steps of {@code union}, {@code
 * isSupersetOf} and {@code symmetricDifference} as the spec has them, in both modes. Not related to
 * classes.
 */
class SetRecordTest {

    private static final String SETUP =
            """
            var log = [];
            function observed(size, values) {
              return {
                get size() { log.push('size'); return { valueOf() { log.push('ToNumber'); return size; } }; },
                get has() { log.push('has'); return function (v) { log.push('call has'); return values.indexOf(v) >= 0; }; },
                get keys() { log.push('keys'); return function () { log.push('call keys'); return values.values(); }; }
              };
            }
            function list(set) { return [...set].join(); }
            function run(f) { try { return String(f()); } catch (e) { return e.name; } }
            """;

    @Test
    void sizeIsConvertedBeforeHasAndKeysAreRead() {
        String[] methods = {
            "union",
            "intersection",
            "difference",
            "symmetricDifference",
            "isSubsetOf",
            "isSupersetOf",
            "isDisjointFrom"
        };
        StringBuilder script = new StringBuilder(SETUP).append("var out = [];\n");
        for (String m : methods) {
            script.append("log = []; new Set([1])." + m + "(observed(1, [1]));")
                    .append(" out.push(log.slice(0, 4).join());\n");
        }
        script.append("out.join('|');");
        String order = "size,ToNumber,has,keys";
        assertWithAllModes_ES6(
                String.join("|", java.util.Collections.nCopies(7, order)), script.toString());
    }

    @Test
    void sizeAndMethodsAreChecked() {
        assertWithAllModes_ES6(
                "TypeError|TypeError|RangeError|RangeError|1|TypeError:size|TypeError:size,has",
                SETUP
                        + """
                var s = new Set([1]);
                var has = function () { return false; }, keys = function () { return [].values(); };
                [run(() => s.union({ size: NaN, has, keys })),
                  run(() => s.union({ has, keys })),
                  run(() => s.union({ size: -1, has, keys })),
                  run(() => s.union({ size: -Infinity, has, keys })),
                  run(() => list(s.union({ size: -0.5, has, keys }))),
                  (log = [], run(() => s.union({ get size() { log.push('size'); return 1; }, has: 1,
                      get keys() { log.push('keys'); return keys; } })) + ':' + log.join()),
                  (log = [], run(() => s.union({ get size() { log.push('size'); return 1; },
                      get has() { log.push('has'); return has; }, keys: {} })) + ':' + log.join())
                ].join('|');
                """);
    }

    @Test
    void unionCopiesThisAfterGettingTheKeysIterator() {
        // keys() runs before the copy and sees its addition; next() runs after it.
        assertWithAllModes_ES6(
                "1,2,8,3|1,2,8,9",
                SETUP
                        + """
                var s = new Set([1, 2]);
                var other = { size: 1, has() { throw new Error('has'); },
                  keys() {
                    s.add(8);
                    var done = false;
                    return { next() { s.add(9); var r = { value: 3, done }; done = true; return r; } };
                  } };
                [list(s.union(other)), list(s)].join('|');
                """);
    }

    @Test
    void isSupersetOfIsFalseWhenThisIsSmaller() {
        assertWithAllModes_ES6(
                "false:size,ToNumber,has,keys|true:size,ToNumber,has,keys,call keys",
                SETUP
                        + """
                var out = [];
                log = []; out.push(new Set([1]).isSupersetOf(observed(2, [1, 2])) + ':' + log.join());
                log = []; out.push(new Set([1, 2]).isSupersetOf(observed(2, [1, 2])) + ':' + log.join());
                out.join('|');
                """);
    }

    @Test
    void symmetricDifferenceUsesOnlyKeys() {
        // Values in both are removed, values only in the argument are added once, -0 is +0, and
        // has is never called.
        assertWithAllModes_ES6(
                "1,3,4,0|1,3,4,0|1,2,0,3",
                SETUP
                        + """
                var out = [];
                log = []; var r = new Set([1, 2, 3]).symmetricDifference(observed(9, [2, 4, 4, -0, 2]));
                out.push(list(r));
                out.push(String([...r].map(v => Object.is(v, -0) ? '-0' : v)));
                out.push(list(new Set([1, 2]).symmetricDifference(observed(1, [0, 3]))));
                if (log.indexOf('call has') >= 0) throw new Error('has was called');
                out.join('|');
                """);
    }
}
