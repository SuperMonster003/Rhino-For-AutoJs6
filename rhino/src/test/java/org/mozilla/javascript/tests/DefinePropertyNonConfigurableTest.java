/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests;

import org.junit.jupiter.api.Test;
import org.mozilla.javascript.testutils.Utils;

/**
 * ValidateAndApplyPropertyDescriptor only compares [[Enumerable]] of a non-configurable property
 * when the new descriptor has an enumerable field.
 */
public class DefinePropertyNonConfigurableTest {

    private static final String DESCRIBE =
            "function describe(o, k) {\n"
                    + "  var d = Object.getOwnPropertyDescriptor(o, k);\n"
                    + "  return 'get' in d\n"
                    + "      ? [typeof d.get, d.enumerable, d.configurable].join()\n"
                    + "      : [d.value, d.writable, d.enumerable, d.configurable].join();\n"
                    + "}\n"
                    + "function tryDefine(o, k, desc) {\n"
                    + "  try {\n"
                    + "    Object.defineProperty(o, k, desc);\n"
                    + "    return 'ok';\n"
                    + "  } catch (e) {\n"
                    + "    return e instanceof TypeError ? 'TypeError' : String(e);\n"
                    + "  }\n"
                    + "}\n";

    @Test
    public void valueOnlyKeepsEnumerableProperty() {
        Utils.assertWithAllModes_ES6(
                "ok|5,true,true,false|ok|6,true,true,false|true|7,true,true,false",
                DESCRIBE
                        + "var o = {};\n"
                        + "Object.defineProperty(o, 'k',"
                        + " { value: 1, writable: true, enumerable: true, configurable: false });\n"
                        + "var r = [tryDefine(o, 'k', { value: 5 }), describe(o, 'k')];\n"
                        + "r.push(tryDefine(o, 'k', { value: 6, enumerable: true }), describe(o, 'k'));\n"
                        + "r.push(Reflect.defineProperty(o, 'k', { value: 7 }), describe(o, 'k'));\n"
                        + "r.join('|')");
    }

    @Test
    public void genericDescriptorKeepsEnumerableProperty() {
        Utils.assertWithAllModes_ES6(
                "ok|ok|ok|1,false,true,false",
                DESCRIBE
                        + "var o = {};\n"
                        + "Object.defineProperty(o, 'k',"
                        + " { value: 1, writable: true, enumerable: true, configurable: false });\n"
                        + "[tryDefine(o, 'k', {}),\n"
                        + " tryDefine(o, 'k', { configurable: false }),\n"
                        + " tryDefine(o, 'k', { writable: false }),\n"
                        + " describe(o, 'k')].join('|')");
    }

    @Test
    public void changingEnumerableStillThrows() {
        Utils.assertWithAllModes_ES6(
                "TypeError|1,true,true,false|TypeError|false",
                DESCRIBE
                        + "var o = {};\n"
                        + "Object.defineProperty(o, 'k',"
                        + " { value: 1, writable: true, enumerable: true, configurable: false });\n"
                        + "[tryDefine(o, 'k', { value: 5, enumerable: false }),\n"
                        + " describe(o, 'k'),\n"
                        + " tryDefine(o, 'k', { enumerable: false }),\n"
                        + " Reflect.defineProperty(o, 'k', { enumerable: false })].join('|')");
    }

    @Test
    public void nonEnumerablePropertyKeepsItsState() {
        Utils.assertWithAllModes_ES6(
                "ok|5,true,false,false|TypeError|5,true,false,false",
                DESCRIBE
                        + "var o = {};\n"
                        + "Object.defineProperty(o, 'k',"
                        + " { value: 1, writable: true, enumerable: false, configurable: false });\n"
                        + "[tryDefine(o, 'k', { value: 5 }),\n"
                        + " describe(o, 'k'),\n"
                        + " tryDefine(o, 'k', { value: 6, enumerable: true }),\n"
                        + " describe(o, 'k')].join('|')");
    }

    @Test
    public void accessorWithoutEnumerable() {
        Utils.assertWithAllModes_ES6(
                "ok|function,true,false|TypeError",
                DESCRIBE
                        + "var o = {};\n"
                        + "function g() { return 1; }\n"
                        + "Object.defineProperty(o, 'k',"
                        + " { get: g, enumerable: true, configurable: false });\n"
                        + "[tryDefine(o, 'k', { get: g }),\n"
                        + " describe(o, 'k'),\n"
                        + " tryDefine(o, 'k', { get: g, enumerable: false })].join('|')");
    }

    @Test
    public void arrayIndexWithoutEnumerable() {
        Utils.assertWithAllModes_ES6(
                "ok|ok|5,true,true,false|TypeError",
                DESCRIBE
                        + "var a = [1, 2];\n"
                        + "[tryDefine(a, 0, { configurable: false }),\n"
                        + " tryDefine(a, 0, { value: 5 }),\n"
                        + " describe(a, 0),\n"
                        + " tryDefine(a, 0, { enumerable: false })].join('|')");
    }
}
