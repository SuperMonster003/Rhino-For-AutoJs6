/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests.classsyntax;

import static org.mozilla.javascript.testutils.Utils.assertWithAllModes_ES6;

import org.junit.jupiter.api.Test;

/**
 * AutoJs6 (ROADMAP P6.11, appendix C Q23 / Q24): the keys iterator of a set-like argument of the
 * Set methods, and the entries of {@code Object.fromEntries}, as the spec has them, in both modes.
 * Not related to classes.
 */
class SetMethodsAndFromEntriesTest {

    private static final String SETUP =
            """
            var log = [];
            // a set-like whose keys() returns an iterator with only next and return
            function setLike(values, tag) {
              return { size: values.length, has(v) { return values.indexOf(v) >= 0; },
                keys() {
                  var i = 0;
                  return {
                    next() { return i < values.length ? { value: values[i++], done: false } : { done: true }; },
                    return() { log.push(tag); return {}; }
                  };
                } };
            }
            function keysReturning(value) {
              return { size: 1, has() { return false; }, keys() { return value; } };
            }
            function list(set) { return [...set].join(); }
            function run(f) { try { return String(f()); } catch (e) { return e.name; } }
            """;

    @Test
    void theResultOfKeysIsTheIterator() {
        // The sizes make every method iterate the keys of its argument; only the early result of
        // isDisjointFrom closes them.
        assertWithAllModes_ES6(
                "1,2,3|2|1,4|1,3|true|false|true|disjoint",
                SETUP
                        + """
                [list(new Set([1, 2]).union(setLike([2, 3], 'union'))),
                  list(new Set([1, 2, 4]).intersection(setLike([2], 'intersection'))),
                  list(new Set([1, 2, 4]).difference(setLike([2], 'difference'))),
                  list(new Set([1, 2]).symmetricDifference(setLike([2, 3], 'symmetric'))),
                  new Set([1, 2, 3]).isSupersetOf(setLike([1, 2], 'superset')),
                  new Set([1, 2, 3]).isDisjointFrom(setLike([3], 'disjoint')),
                  new Set([1, 2, 3]).isDisjointFrom(setLike([4], 'disjoint2')),
                  log.join()].join('|');
                """);
    }

    @Test
    void symbolIteratorOfTheKeysIsNotCalled() {
        assertWithAllModes_ES6(
                "1,2|0",
                SETUP
                        + """
                var calls = 0;
                var other = { size: 1, has() { return false; }, keys() {
                  var done = false;
                  return { next() { var r = { value: 2, done }; done = true; return r; },
                    [Symbol.iterator]() { calls++; return [9].values(); } };
                } };
                [list(new Set([1]).union(other)), calls].join('|');
                """);
    }

    @Test
    void keysThatAreNotObjectsThrow() {
        assertWithAllModes_ES6(
                "TypeError|TypeError|TypeError|TypeError|TypeError|TypeError|TypeError",
                SETUP
                        + """
                var big = new Set([1, 2, 3]);
                [run(() => big.union(keysReturning(1))),
                  run(() => big.intersection(keysReturning('ab'))),
                  run(() => big.difference(keysReturning(undefined))),
                  run(() => big.symmetricDifference(keysReturning(null))),
                  run(() => big.isSupersetOf(keysReturning(true))),
                  run(() => big.isDisjointFrom(keysReturning(Symbol()))),
                  run(() => big.union(keysReturning({})))].join('|');
                """);
    }

    @Test
    void earlyResultsCloseTheKeysIterator() {
        assertWithAllModes_ES6(
                "false|false|superset,disjoint",
                SETUP
                        + """
                [new Set([1, 2, 3]).isSupersetOf(setLike([1, 9], 'superset')),
                  new Set([1, 2, 3]).isDisjointFrom(setLike([3, 4], 'disjoint')),
                  log.join()].join('|');
                """);
    }

    @Test
    void entriesAreReadThroughThePrototypeChain() {
        assertWithAllModes_ES6(
                "p|v:true|k,v|0,1|1",
                SETUP
                        + """
                Array.prototype[1] = 'p';
                var a = Object.fromEntries([['a', , ]]).a;
                delete Array.prototype[1];
                var proto = { get 1() { return 'v:' + (this === entry); } };
                var entry = Object.create(proto);
                entry[0] = 'b';
                var b = Object.fromEntries([entry]).b;
                var reads = [];
                var tracked = { get 0() { reads.push('k'); return 'c'; }, get 1() { reads.push('v'); return 1; } };
                Object.fromEntries([tracked]);
                var traps = [];
                var proxy = new Proxy(['d', 1], { get(t, k) { if (k === '0' || k === '1') traps.push(k); return t[k]; } });
                var d = Object.fromEntries([proxy]).d;
                [a, b, reads.join(), traps.join(), d].join('|');
                """);
    }

    @Test
    void errorsReadingEntriesCloseTheIterator() {
        assertWithAllModes_ES6(
                "Error|TypeError|entries,primitive",
                SETUP
                        + """
                function entries(items, tag) {
                  return { [Symbol.iterator]() {
                    var i = 0;
                    return { next() { return i < items.length ? { value: items[i++], done: false } : { done: true }; },
                      return() { log.push(tag); return {}; } };
                  } };
                }
                var bad = { get 0() { throw new Error('key'); } };
                [run(() => Object.fromEntries(entries([bad], 'entries'))),
                  run(() => Object.fromEntries(entries([1], 'primitive'))),
                  log.join()].join('|');
                """);
    }
}
