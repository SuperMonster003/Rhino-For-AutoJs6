/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

/**
 * AutoJs6: [[Get]] of a Proxy without a get trap, and the property lookup along a prototype chain
 * that reaches a Proxy (ROADMAP P6.15, appendix C Q34). Not related to classes.
 *
 * <p>Upstream's Proxy without a get trap read the own properties of its target with the target as
 * the receiver, so a getter of the target got the target as this instead of the Proxy, and the
 * lookup went on with the prototype from the getPrototypeOf trap of the Proxy. The target now looks
 * up the whole chain itself with the receiver unchanged, and the lookup that reached the Proxy
 * stops there.
 */
final class OrdinaryGet {

    private OrdinaryGet() {}

    /** The next object of a lookup along the prototype chain: none after a Proxy. */
    static Scriptable next(Scriptable obj) {
        return obj instanceof NativeProxy ? null : obj.getPrototype();
    }

    /** target.[[Get]](name, start) for a Proxy without a get trap. */
    static Object get(Scriptable target, String name, Scriptable start) {
        Object result;
        Scriptable obj = target;
        do {
            result = obj.get(name, start);
            if (result != Scriptable.NOT_FOUND) {
                break;
            }
            obj = next(obj);
        } while (obj != null);
        return result;
    }

    /** target.[[Get]](index, start) for a Proxy without a get trap. */
    static Object get(Scriptable target, int index, Scriptable start) {
        Object result;
        Scriptable obj = target;
        do {
            result = obj.get(index, start);
            if (result != Scriptable.NOT_FOUND) {
                break;
            }
            obj = next(obj);
        } while (obj != null);
        return result;
    }

    /** target.[[Get]](key, start) for a Proxy without a get trap. */
    static Object get(Scriptable target, Symbol key, Scriptable start) {
        Object result;
        Scriptable obj = target;
        do {
            result = ScriptableObject.ensureSymbolScriptable(obj).get(key, start);
            if (result != Scriptable.NOT_FOUND) {
                break;
            }
            obj = next(obj);
        } while (obj != null);
        return result;
    }
}
