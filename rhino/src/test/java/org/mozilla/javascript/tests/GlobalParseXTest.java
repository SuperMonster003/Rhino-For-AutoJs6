/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

/** */
package org.mozilla.javascript.tests;

import org.junit.Test;
import org.mozilla.javascript.testutils.Utils;

/**
 * Tests for global functions parseFloat and parseInt.
 *
 * @author Marc Guillemot
 */
public class GlobalParseXTest {

    /**
     * Test for bug #501972 https://bugzilla.mozilla.org/show_bug.cgi?id=501972 Leading whitespaces
     * should be ignored with following white space chars (see ECMA spec 15.1.2.3) <TAB>, <SP>,
     * <NBSP>, <FF>, <VT>, <CR>, <LF>, <LS>, <PS>, <USP>
     */
    @Test
    public void parseFloatAndIntWhiteSpaces() {
        testParseFloatWhiteSpaces("\\u00A0 "); // <NBSP>

        testParseFloatWhiteSpaces("\\t ");
        testParseFloatWhiteSpaces("\\u00A0 "); // <NBSP>
        testParseFloatWhiteSpaces("\\u000C "); // <FF>
        testParseFloatWhiteSpaces("\\u000B "); // <VT>
        testParseFloatWhiteSpaces("\\u000D "); // <CR>
        testParseFloatWhiteSpaces("\\u000A "); // <LF>
        testParseFloatWhiteSpaces("\\u2028 "); // <LS>
        testParseFloatWhiteSpaces("\\u2029 "); // <PS>
    }

    private void testParseFloatWhiteSpaces(final String prefix) {
        Utils.assertWithAllModes("789", "String(parseInt('" + prefix + "789 '))");
        Utils.assertWithAllModes("7.89", "String(parseFloat('" + prefix + "7.89 '))");
    }

    /**
     * Test for bug #531436 https://bugzilla.mozilla.org/show_bug.cgi?id=531436 Trailing noise
     * should be ignored (see ECMA spec 15.1.2.3)
     */
    @Test
    public void parseFloatTrailingNoise() {
        testParseFloat("7890", "789e1");
        testParseFloat("7890", "789E1");
        testParseFloat("7890", "789E+1");
        testParseFloat("7890", "789E+1e");
        testParseFloat("789", "7890E-1");
        testParseFloat("789", "7890E-1e");

        testParseFloat("789", "789hello");
        testParseFloat("789", "789e");
        testParseFloat("789", "789E");
        testParseFloat("789", "789e+");
        testParseFloat("789", "789Efgh");
        testParseFloat("789", "789efgh");
        testParseFloat("789", "789e-");
        testParseFloat("789", "789e-hello");
        testParseFloat("789", "789e+hello");
        testParseFloat("789", "789+++hello");
        testParseFloat("789", "789-e-+hello");
        testParseFloat("789", "789e+e++hello");
        testParseFloat("789", "789e-e++hello");
    }

    private static void testParseFloat(final String expected, final String value) {
        Utils.assertWithAllModes(expected, "String(parseFloat('" + value + "'))");
    }
}
