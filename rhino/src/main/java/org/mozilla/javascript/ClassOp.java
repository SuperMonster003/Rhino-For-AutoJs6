/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

/**
 * AutoJs6 class syntax: the data of a {@link Token#CLASS} IR node, held by {@link Node#CLASS_PROP}
 * (see {@link ClassLowering}). It is public for the compiler in {@code
 * org.mozilla.javascript.optimizer}; it is not an API.
 */
public final class ClassOp {

    /** A class definition: [heritage], constructor, elements. */
    public static final int DEFINE = 0;

    /** {@code super(...)}: the arguments, or one array holding them. */
    public static final int SUPER_CALL = 1;

    /** {@code this} in a derived constructor or in an arrow function inside it. */
    public static final int THIS = 2;

    /** A reference to the class binding while the class is defined: always a ReferenceError. */
    public static final int TDZ = 3;

    /** The [[Call]] check at the top of a class constructor. */
    public static final int CTOR_CHECK = 4;

    /** The rest element of an array binding pattern: the remaining values of the iterator. */
    public static final int ITERATOR_REST = 5;

    /** RequireObjectCoercible of the value bound to an empty object pattern. */
    public static final int REQUIRE_OBJECT = 6;

    /**
     * IteratorClose of a for-of loop that ends early, see {@link BindingLowering#forOf}. The node
     * holds the {@link Node#LOCAL_BLOCK_PROP} of the enumeration of the loop.
     */
    public static final int ITERATOR_CLOSE = 7;

    /**
     * The object of the with scope around the initialization of the parameters, see {@link
     * BindingLowering#paramScope}.
     */
    public static final int PARAM_SCOPE = 8;

    /** Element kinds, in the low bits of the int {@link Node#CLASS_PROP} of an element. */
    public static final int ELEMENT_METHOD = 0, ELEMENT_GETTER = 1, ELEMENT_SETTER = 2;

    public static final int ELEMENT_KIND_MASK = 0x3;

    /** The element is static: it is defined on the constructor rather than on the prototype. */
    public static final int ELEMENT_STATIC = 0x4;

    /** The key is computed: the method gets its name once the key is known. */
    public static final int ELEMENT_COMPUTED = 0x8;

    final int kind;

    /** {@link #DEFINE}: whether the first child is the heritage expression. */
    boolean hasHeritage;

    /** {@link #SUPER_CALL}: the only child is an array holding the arguments. */
    boolean arrayArguments;

    /**
     * {@link #TDZ}: the name of the class binding or of the parameter. {@link #PARAM_SCOPE}: the
     * names that resolve outside the function, joined by commas.
     */
    String name;

    /** {@link #TDZ}: a parameter referenced by the default value of an earlier parameter. */
    boolean parameter;

    /** {@link #ITERATOR_CLOSE}: the loop ends with an exception, which IteratorClose keeps. */
    boolean quiet;

    ClassOp(int kind) {
        this.kind = kind;
    }

    public static ClassOp of(Node node) {
        return (ClassOp) node.getProp(Node.CLASS_PROP);
    }

    public int getKind() {
        return kind;
    }

    public boolean hasHeritage() {
        return hasHeritage;
    }

    public boolean hasArrayArguments() {
        return arrayArguments;
    }

    public String getName() {
        return name;
    }

    public boolean isParameter() {
        return parameter;
    }

    public boolean isQuiet() {
        return quiet;
    }
}
