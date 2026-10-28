/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests.classsyntax;

import static org.mozilla.javascript.testutils.Utils.assertWithAllModes_ES6;

import org.junit.jupiter.api.Test;

/**
 * AutoJs6 (ROADMAP P6.13, appendix C Q30): a destructuring default applies whatever the target held
 * before, in both modes. Upstream assigned it only when the target was still undefined. Not related
 * to classes.
 */
class DestructuringDefaultsTest {

    @Test
    void targetsThatAlreadyHaveValues() {
        assertWithAllModes_ES6(
                "1|4|3|f0|d",
                """
                var x = 5, z = 9, w = 9, f0 = 1, e = 'old';
                [x = 1] = [undefined];
                ({ z = 3 } = { z: 4 });
                ({ w = 3 } = {});
                [f0 = function () {}] = [];
                try { throw []; } catch ([e = 'd']) { var caught = e; }
                [x, z, w, f0.name, caught].join('|');
                """);
    }

    @Test
    void declarationsThatRunAgain() {
        assertWithAllModes_ES6(
                "1,2,1|7,5,7|1,2|8,6",
                """
                var a = [], b = [], c = [], d = [];
                for (var k of [undefined, 2, undefined]) { var [y = 1] = [k]; a.push(y); }
                for (var k of [undefined, 5, undefined]) { var { v = 7 } = { v: k }; b.push(v); }
                var t = 9;
                for ([t = 1] of [[], [2]]) c.push(t);
                function f(n) { var [m = 8] = [n]; return m; }
                d.push(f(), f(6));
                [a, b, c, d].join('|');
                """);
    }

    @Test
    void parameterDefaultsAreUnchanged() {
        assertWithAllModes_ES6(
                "1,1,3|5,5,3|5,6,0|1,1,3|1|2",
                """
                function f([a = 1, b = a] = [], { c = 3 } = {}) { return [a, b, c].join(); }
                function g([a = 1]) { var a; return a; }
                [f(), f([5]), f([5, 6], { c: 0 }), f(), g([]), g([2])].join('|');
                """);
    }
}
