/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import java.io.Serial;
import java.io.Serializable;

/**
 * AutoJs6 class syntax: a Private Name (ROADMAP T5). Every evaluation of a class definition creates
 * one for each private name the class declares, held by the hidden binding of the name in the class
 * scope (see {@link ClassLowering}); it is the key of the private elements of an object ({@link
 * PrivateElements}) and never a property key.
 *
 * <p>A private method or accessor is not copied to every instance: the function lives here, and an
 * instance only gets the brand of the class ({@link #brand}, itself a key of kind {@link #BRAND}).
 * It is public for the compiled code; it is not an API.
 */
public final class PrivateNameKey implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    static final int FIELD = 0, METHOD = 1, ACCESSOR = 2, BRAND = 3;

    /** The identifier, without {@code #}; for a brand, the name of the class. */
    final String description;

    int kind = FIELD;

    /** A method or accessor: the brand that the objects it applies to have. */
    PrivateNameKey brand;

    /** A method: the function. */
    Object method;

    /** An accessor: the getter and the setter, either one may be {@code null}. */
    Object getter, setter;

    PrivateNameKey(String description) {
        this.description = description;
    }

    static PrivateNameKey newBrand(String className) {
        PrivateNameKey brand = new PrivateNameKey(className);
        brand.kind = BRAND;
        return brand;
    }

    @Override
    public String toString() {
        return kind == BRAND ? description : "#" + description;
    }
}
