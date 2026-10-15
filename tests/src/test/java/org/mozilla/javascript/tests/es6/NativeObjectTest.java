/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

/*
 * Tests for the Object.getOwnPropertyDescriptor(obj, prop), Object.keys, Object.values and Object.entries method
 */
package org.mozilla.javascript.tests.es6;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.TopLevel;
import org.mozilla.javascript.testutils.Utils;

/** Test for NativeObject. */
public class NativeObjectTest {

    @Test
    public void assignPropertyGetter() {
        assertWithAllModes_StrictAndNonStrict_ES6(
                "var obj = Object.defineProperty({}, 'propA', {\n"
                        + "                 enumerable: true,\n"
                        + "                 get: function () {\n"
                        + "                        Object.defineProperty(this, 'propB', {\n"
                        + "                                 value: 3,\n"
                        + "                                 enumerable: false\n"
                        + "                        });\n"
                        + "                      }\n"
                        + "          });\n"
                        + "Object.assign(obj, {propB: 2});\n"
                        + "Object.assign({propB: 1}, obj);\n"
                        + "'obj.propB = ' + obj.propB + ', enumerable = ' + Object.getOwnPropertyDescriptor(obj, 'propB').enumerable",
                "obj.propB = 3, enumerable = false");
    }

    @Test
    public void assignOneParameter() {
        assertWithAllModes_StrictAndNonStrict_ES6(
                "var obj = {};" + "res = Object.assign(obj);" + "res === obj;", Boolean.TRUE);
    }

    @Test
    public void assignMissingParameters() {
        assertWithAllModes_StrictAndNonStrict_ES6(
                "try { " + "  Object.assign();" + "} catch (e) { e.message }",
                "Cannot convert undefined to an object.");
    }

    @Test
    public void assignNumericPropertyGetter() {
        assertWithAllModes_StrictAndNonStrict_ES6(
                "var obj = Object.defineProperty({}, 1, {\n"
                        + "                 enumerable: true,\n"
                        + "                 get: function () {\n"
                        + "                        Object.defineProperty(this, 2, {\n"
                        + "                                 value: 3,\n"
                        + "                                 enumerable: false\n"
                        + "                        });\n"
                        + "                      }\n"
                        + "          });\n"
                        + "Object.assign(obj, {2: 2});\n"
                        + "Object.assign({2: 1}, obj);\n"
                        + "'obj[2] = ' + obj[2] + ', enumerable = ' + Object.getOwnPropertyDescriptor(obj, 2).enumerable",
                "obj[2] = 3, enumerable = false");
    }

    @Test
    public void assignUndefined() {
        assertWithAllModes_StrictAndNonStrict_ES6(
                "Object.keys(Object.assign({a:undefined}, {b:undefined})).join()", "a,b");
    }

    @Test
    public void assignInextensible() {
        assertWithAllModes_StrictAndNonStrict_ES6(
                "var obj = Object.freeze({});\n"
                        + "try {\n"
                        + "  Object.assign(obj, { a: 1 });\n"
                        + "  'success';\n"
                        + "} catch(e) {\n"
                        + "  'error';\n"
                        + "}",
                "error");
        assertWithAllModes_StrictAndNonStrict_ES6(
                "var obj = Object.freeze({});\n"
                        + "try {\n"
                        + "  Object.assign(obj, {});\n"
                        + "  'success';\n"
                        + "} catch(e) {\n"
                        + "  'error';\n"
                        + "}",
                "success");
    }

    @Test
    public void assignUnwritable() {
        assertWithAllModes_StrictAndNonStrict_ES6(
                "var src = Object.defineProperty({}, 1, {\n"
                        + "  enumerable: true,\n"
                        + "  value: 'v'\n"
                        + "});\n"
                        + "var dest = Object.defineProperty({}, 1, {\n"
                        + "  writable: false,\n"
                        + "  value: 'v'\n"
                        + "});\n"
                        + "try {\n"
                        + "  Object.assign(dest, src);\n"
                        + "  'success';\n"
                        + "} catch(e) {\n"
                        + "  'error';\n"
                        + "}",
                "error");
        assertWithAllModes_StrictAndNonStrict_ES6(
                "var src = Object.defineProperty({}, 1, {\n"
                        + "  enumerable: false,\n"
                        + "  value: 'v'\n"
                        + "});\n"
                        + "var dest = Object.defineProperty({}, 1, {\n"
                        + "  writable: false,\n"
                        + "  value: 'v'\n"
                        + "});\n"
                        + "try {\n"
                        + "  Object.assign(dest, src);\n"
                        + "  'success';\n"
                        + "} catch(e) {\n"
                        + "  'error';\n"
                        + "}",
                "success");
        assertWithAllModes_StrictAndNonStrict_ES6(
                "var src = Object.defineProperty({}, 1, {\n"
                        + "  enumerable: true,\n"
                        + "  value: 'v'\n"
                        + "});\n"
                        + "var destProto = Object.defineProperty({}, 1, {\n"
                        + "  writable: false,\n"
                        + "  value: 'v'\n"
                        + "});\n"
                        + "var dest = Object.create(destProto);\n"
                        + "try {\n"
                        + "  Object.assign(dest, src);\n"
                        + "  'success';\n"
                        + "} catch(e) {\n"
                        + "  'error';\n"
                        + "}",
                "error");
    }

    @Test
    public void assignVariousKeyTypes() {
        assertWithAllModes_StrictAndNonStrict_ES6(
                "var strKeys = Object.assign({}, {a: 1, b: 2});\n"
                        + "var arrayKeys = Object.assign({}, ['a', 'b']);\n"
                        + "var res = 'strKeys: ' + JSON.stringify(strKeys) + "
                        + "'  arrayKeys:' + JSON.stringify(arrayKeys);\n"
                        + "res",
                "strKeys: {\"a\":1,\"b\":2}  arrayKeys:{\"0\":\"a\",\"1\":\"b\"}");
    }

    @Test
    public void assignWithPrototypeStringKeys() {
        final String script =
                "var targetProto = { a: 1, b: 2 };\n"
                        + "var target = Object.create(targetProto);\n"
                        + "var source = { b: 4, c: 5 };\n"
                        + "var assigned = Object.assign(target, source);\n"
                        + "var res = 'targetProto: ' + JSON.stringify(targetProto) + "
                        + "'  target: ' + JSON.stringify(target) + "
                        + "'  assigned: ' + JSON.stringify(assigned);\n"
                        + "res";

        assertWithAllModes_StrictAndNonStrict_ES6(
                script,
                "targetProto: {\"a\":1,\"b\":2}  target: {\"b\":4,\"c\":5}  assigned: {\"b\":4,\"c\":5}");
    }

    @Test
    public void assignWithPrototypeNumericKeys() {
        final String script =
                "var targetProto = {0: 'a', 1: 'b'};\n"
                        + "var target = Object.create(targetProto);\n"
                        + "var source = {1: 'c', 2: 'd'};\n"
                        + "var assigned = Object.assign(target, source);\n"
                        + "var res = 'targetProto: ' + JSON.stringify(targetProto) + "
                        + "'  target: ' + JSON.stringify(target) + "
                        + "'  assigned: ' + JSON.stringify(assigned);\n"
                        + "res";

        assertWithAllModes_StrictAndNonStrict_ES6(
                script,
                "targetProto: {\"0\":\"a\",\"1\":\"b\"}  target: {\"1\":\"c\",\"2\":\"d\"}  assigned: {\"1\":\"c\",\"2\":\"d\"}");
    }

    @Test
    public void assignWithPrototypeWithGettersAndSetters() {
        final String script =
                "var targetProto = {"
                        + "_a: 1, get a() { return this._a }, set a(val) { this._a = val+10 },"
                        + " _b: 2, get b() { return this._b }, set b(val) { this._b = val+10 }"
                        + "};\n"
                        + "var target = Object.create(targetProto);\n"
                        + "var source = {"
                        + "_b: 4, get b() { return this._b * 4 }, set b(val) { this._b = val+10 },"
                        + " _c: 5, get c() { return this._c }, set c(val) { this._c = val+10 }};\n"
                        + "var assigned = Object.assign(target, source);\n"
                        + "var res = 'targetProto: ' + JSON.stringify(targetProto) + "
                        + "'  target: ' + JSON.stringify(target) + "
                        + "'  assigned: ' + JSON.stringify(assigned) + "
                        + "'  assigned.a: ' + assigned.a + "
                        + "'  assigned.b: ' + assigned.b + "
                        + "'  assigned.c: ' + assigned.c;\n"
                        + "res";

        assertWithAllModes_StrictAndNonStrict_ES6(
                script,
                "targetProto: {\"_a\":1,\"a\":1,\"_b\":2,\"b\":2}  target: {\"_b\":26,\"_c\":5,\"c\":5}  assigned: {\"_b\":26,\"_c\":5,\"c\":5}  assigned.a: 1  assigned.b: 26  assigned.c: 5");
    }

    @Test
    public void getOwnPropertyDescriptorSetPropertyIsAlwaysDefined() {
        assertWithAllModes_StrictAndNonStrict_ES6(
                "var obj = Object.defineProperty({}, 'prop', {\n"
                        + "                 get: function() {}\n"
                        + "          });\n"
                        + "var desc = Object.getOwnPropertyDescriptor(obj, 'prop');\n"
                        + "'' + obj.prop"
                        + "+ ' ' + desc.get"
                        + "+ ' ' + desc.set"
                        + "+ ' [' + Object.getOwnPropertyNames(desc) + ']'",
                "undefined function() {} undefined [get,set,enumerable,configurable]");
    }

    @Test
    public void setPrototypeOfNull() {
        assertWithAllModes_StrictAndNonStrict_ES6(
                "try { "
                        + "  Object.setPrototypeOf(null, new Object());"
                        + "} catch (e) { e.message }",
                "Object.prototype.setPrototypeOf method called on null or undefined");
    }

    @Test
    public void setPrototypeOfUndefined() {
        assertWithAllModes_StrictAndNonStrict_ES6(
                "try { "
                        + "  Object.setPrototypeOf(undefined, new Object());"
                        + "} catch (e) { e.message }",
                "Object.prototype.setPrototypeOf method called on null or undefined");
    }

    @Test
    public void setPrototypeOfMissingParameters() {
        assertWithAllModes_StrictAndNonStrict_ES6(
                "try { " + "  Object.setPrototypeOf();" + "} catch (e) { e.message }",
                "Object.setPrototypeOf: At least 2 arguments required, but only 0 passed");

        assertWithAllModes_StrictAndNonStrict_ES6(
                "try { " + "  Object.setPrototypeOf({});" + "} catch (e) { e.message }",
                "Object.setPrototypeOf: At least 2 arguments required, but only 1 passed");
    }

    @Test
    public void keysMissingParameter() {
        assertWithAllModes_StrictAndNonStrict_ES6(
                "try { " + "  Object.keys();" + "} catch (e) { e.message }",
                "Cannot convert undefined to an object.");
    }

    @Test
    public void keysOnObjectParameter() {
        assertWithAllModes_StrictAndNonStrict_ES6(
                "Object.keys({'foo':'bar', 2: 'y', 1: 'x'}).join()", "1,2,foo");
    }

    @Test
    public void keysOnArray() {
        assertWithAllModes_StrictAndNonStrict_ES6("Object.keys(['x','y','z']).join()", "0,1,2");
    }

    @Test
    public void keysOnArrayWithProp() {
        assertWithAllModes_StrictAndNonStrict_ES6(
                "var arr = ['x','y','z'];\n" + "arr['foo'] = 'bar';\n" + "Object.keys(arr).join()",
                "0,1,2,foo");
    }

    @Test
    public void valuesMissingParameter() {
        assertWithAllModes_StrictAndNonStrict_ES6(
                "try { " + "  Object.values();" + "} catch (e) { e.message }",
                "Cannot convert undefined to an object.");
    }

    @Test
    public void valuesOnObjectParameter() {
        assertWithAllModes_StrictAndNonStrict_ES6(
                "Object.values({'foo':'bar', 2: 'y', 1: 'x'}).join()", "x,y,bar");
    }

    @Test
    public void valuesOnArray() {
        assertWithAllModes_StrictAndNonStrict_ES6("Object.values(['x','y','z']).join()", "x,y,z");
    }

    @Test
    public void valuesOnArrayWithProp() {
        assertWithAllModes_StrictAndNonStrict_ES6(
                "var arr = [3,4,5];\n" + "arr['foo'] = 'bar';\n" + "Object.values(arr).join();",
                "3,4,5,bar");
    }

    @Test
    public void entriesMissingParameter() {
        assertWithAllModes_StrictAndNonStrict_ES6(
                "try { " + "  Object.entries();" + "} catch (e) { e.message }",
                "Cannot convert undefined to an object.");
    }

    @Test
    public void entriesOnObjectParameter() {
        assertWithAllModes_StrictAndNonStrict_ES6(
                "Object.entries({'foo':'bar', 2: 'y', 1: 'x'}).join()", "1,x,2,y,foo,bar");
    }

    @Test
    public void entriesOnArray() {
        assertWithAllModes_StrictAndNonStrict_ES6(
                "Object.entries(['x','y','z']).join()", "0,x,1,y,2,z");
    }

    @Test
    public void entriesOnArrayWithProp() {
        assertWithAllModes_StrictAndNonStrict_ES6(
                "var arr = [3,4,5];\n" + "arr['foo'] = 'bar';\n" + "Object.entries(arr).join()",
                "0,3,1,4,2,5,foo,bar");
    }

    @Test
    public void fromEntriesMissingParameter() {
        assertWithAllModes_StrictAndNonStrict_ES6(
                "try { " + "  Object.fromEntries();" + "} catch (e) { e.message }",
                "Cannot convert undefined to an object.");
    }

    @Test
    public void fromEntriesOnArray() {
        assertWithAllModes_StrictAndNonStrict_ES6(
                "var obj = Object.fromEntries(Object.entries(['x','y','z']));"
                        + "[obj[0], obj[1], obj[2]].join()",
                "x,y,z");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "0",
                "6",
                "'0'",
                "'6'",
                "-0",
                "'-0'",
                "-1",
                "'-1'",
                "1.5",
                "'06'",
                "'6.0'",
                "2147483647",
                "'2147483647'",
                "2147483648",
                "'2147483648'"
            })
    public void fromEntriesPropertyKeys(String keyExpression) {
        assertWithAllModes_StrictAndNonStrict_ES6(
                """
                var key = %s;
                var name = String(key);
                var obj = Object.fromEntries([[key, true]]);
                var expected = {};
                expected[key] = true;
                var desc = Object.getOwnPropertyDescriptor(obj, name);
                obj[key] === true && obj[name] === true
                    && Object.prototype.hasOwnProperty.call(obj, name)
                    && desc !== undefined && desc.value === true
                    && desc.writable && desc.enumerable && desc.configurable
                    && Object.keys(obj).length === 1 && Object.keys(obj)[0] === name
                    && JSON.stringify(obj) === JSON.stringify(expected);
                """
                        .formatted(keyExpression),
                Boolean.TRUE);
    }

    @Test
    public void fromEntriesDuplicateNumericKeys() {
        assertWithAllModes_StrictAndNonStrict_ES6(
                """
                var obj = Object.fromEntries([[6, 'first'], ['6', 'second'], [6, 'last']]);
                obj[6] === 'last' && obj['6'] === 'last'
                    && Object.keys(obj).join() === '6'
                    && Object.getOwnPropertyDescriptor(obj, '6').value === 'last';
                """,
                Boolean.TRUE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sym", "Object(sym)"})
    public void fromEntriesSymbolKeys(String keyExpression) {
        assertWithAllModes_StrictAndNonStrict_ES6(
                """
                var sym = Symbol('key');
                var obj = Object.fromEntries([[%s, true]]);
                var desc = Object.getOwnPropertyDescriptor(obj, sym);
                obj[sym] === true && Object.prototype.hasOwnProperty.call(obj, sym)
                    && Object.keys(obj).length === 0
                    && Object.getOwnPropertySymbols(obj).length === 1
                    && Object.getOwnPropertySymbols(obj)[0] === sym
                    && desc.value === true && desc.writable && desc.enumerable && desc.configurable;
                """
                        .formatted(keyExpression),
                Boolean.TRUE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"6", "'6'", "sym"})
    public void fromEntriesConvertsPropertyKeys(String primitiveExpression) {
        assertWithAllModes_StrictAndNonStrict_ES6(
                """
                var sym = Symbol('key');
                var primitive = %s;
                var hints = [];
                var key = {
                    [Symbol.toPrimitive]: function(hint) {
                        hints.push(hint);
                        return primitive;
                    }
                };
                var obj = Object.fromEntries([[key, true]]);
                obj[primitive] === true
                    && Object.getOwnPropertyDescriptor(obj, primitive).value === true
                    && hints.join() === 'string';
                """
                        .formatted(primitiveExpression),
                Boolean.TRUE);
    }

    @Test
    public void fromEntriesDoesNotCoercePrimitiveSymbols() {
        assertWithAllModes_StrictAndNonStrict_ES6(
                """
                var sym = Symbol('key');
                Object.defineProperty(Symbol.prototype, Symbol.toPrimitive, {
                    value: function() { throw new Error('Unexpected symbol coercion'); }
                });
                var obj = Object.fromEntries([[sym, true]]);
                obj[sym] === true && Object.getOwnPropertySymbols(obj)[0] === sym;
                """,
                Boolean.TRUE);
    }

    @Test
    public void fromEntriesDefinesProtoDataProperty() {
        assertWithAllModes_StrictAndNonStrict_ES6(
                """
                var value = {};
                var obj = Object.fromEntries([['__proto__', value]]);
                var desc = Object.getOwnPropertyDescriptor(obj, '__proto__');
                Object.getPrototypeOf(obj) === Object.prototype
                    && Object.prototype.hasOwnProperty.call(obj, '__proto__')
                    && obj['__proto__'] === value
                    // Dot access retains this fork's legacy prototype behavior.
                    && obj.__proto__ === Object.prototype
                    && desc.value === value && desc.writable && desc.enumerable && desc.configurable;
                """,
                Boolean.TRUE);
    }

    @Test
    public void issue943() {
        assertWithAllModes_StrictAndNonStrict_ES6(
                "var foo = function e() {}\n"
                        + "var fooProto = foo.prototype;\n"
                        + "var descProp = Object.getOwnPropertyDescriptor(fooProto, 'constructor');"
                        + "descProp.hasOwnProperty('value');\n",
                Boolean.TRUE);
    }

    @Test
    public void issue943Realm() {
        final String script =
                "realm.Object.getOwnPropertyDescriptor(realm.Object, 'getOwnPropertyDescriptor').__proto__ === ({}).__proto__;";

        String[] prefixes = {"", "'use strict;'\n"};
        for (final String prefix : prefixes) {
            Utils.runWithAllModes(
                    cx -> {
                        cx.setLanguageVersion(Context.VERSION_ES6);
                        TopLevel scope = cx.initStandardObjects();

                        TopLevel realm = cx.initStandardObjects();
                        scope.put("realm", scope, realm);

                        Object result = cx.evaluateString(scope, prefix + script, "test", 1, null);
                        assertEquals(Boolean.FALSE, result);
                        return null;
                    });
        }
    }

    private static void assertWithAllModes_StrictAndNonStrict_ES6(
            final String script, final Object expected) {
        String[] prefixes = {"", "'use strict;'\n"};
        for (final String prefix : prefixes) {
            Utils.assertWithAllModes_ES6(expected, prefix + script);
        }
    }

    /**
     * @throws Exception if the test fails
     */
    @Test
    public void definePropertyUsingConsString() throws Exception {
        final String script =
                "  'use strict';\n"
                        + "  var log = '';\n"
                        + "  var f = function () {};\n"
                        + "  var a1='proto';\n"
                        + "  var p = a1 + 'type';\n"
                        + "  log = log + 'before: ' + f.prototype;\n"
                        + "  Object.defineProperty(f, p, {});\n"
                        + "  log = log + ' / after: ' + f.prototype;\n"
                        + "  var p = new f();\n"
                        + "  log = log + ' / ' + (p instanceof f);";

        Utils.runWithAllModes(
                cx -> {
                    cx.setLanguageVersion(Context.VERSION_ES6);
                    TopLevel scope = cx.initStandardObjects();

                    TopLevel realm = cx.initStandardObjects();
                    scope.put("realm", scope, realm);

                    Object result = cx.evaluateString(scope, script, "test", 1, null);
                    assertEquals("before: [object Object] / after: [object Object] / true", result);
                    return null;
                });
    }

    @Test
    public void symbolInObjectLiteral() {
        final String script = "var s = Symbol('foo'); var o = {[s]: 'bar'}; o[s];";
        assertWithAllModes_StrictAndNonStrict_ES6(script, "bar");
    }

    @Test
    public void addSymbolProperty() {
        final String script = "var s = Symbol('foo'); var o = {}; o[s] = 'bar'; o[s];";
        assertWithAllModes_StrictAndNonStrict_ES6(script, "bar");
    }

    @Test
    public void objectWrapperForSymbolAsKey() {
        final String script =
                "var s = Symbol('foo'); var o = Object(s); var obj = {[o]: 'bar'}; obj[s]";
        assertWithAllModes_StrictAndNonStrict_ES6(script, "bar");
    }

    @Test
    public void objectAssignCopiesSymbols() {
        final String script =
                "var s1 = Symbol('foo');"
                        + "var s2 = Symbol('bar');"
                        + "var source = { [s1]: 'val1', [Object(s2)]: 'val2' };"
                        + "var target = {};"
                        + "Object.assign(target, source);"
                        + "target[s1] + '-' + target[s2];";
        assertWithAllModes_StrictAndNonStrict_ES6(script, "val1-val2");
    }
}
