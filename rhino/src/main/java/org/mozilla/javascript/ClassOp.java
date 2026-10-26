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

    /**
     * A new private name ({@link PrivateNameKey}) for the class definition being evaluated: the
     * initial value of its hidden binding in the class scope. {@link #name} is the identifier.
     */
    public static final int PRIVATE_NAME = 9;

    /**
     * InitializeInstanceElements at the top of the constructor of a base class: the private methods
     * and the fields of the class are added to the this value.
     */
    public static final int INIT_FIELDS = 10;

    /**
     * The end of ClassDefinitionEvaluation, once the class binding is initialized: the static
     * private methods, static fields and static blocks of the class whose constructor the only
     * child evaluates to. The value is the constructor.
     */
    public static final int STATIC_INIT = 11;

    /**
     * A step of an array pattern outside a parameter list (ROADMAP P6.12, see {@link
     * ArrayDestructuring}): {@link #step} is the step, the node holds the {@link
     * Node#LOCAL_BLOCK_PROP} of the record of the pattern, and an {@link ArrayDestructuring#OPEN}
     * has the value as its child.
     */
    public static final int DESTRUCTURE = 12;

    /** Element kinds, in the low bits of the int {@link Node#CLASS_PROP} of an element. */
    public static final int ELEMENT_METHOD = 0, ELEMENT_GETTER = 1, ELEMENT_SETTER = 2;

    /** A field, or a static block with {@link #ELEMENT_BLOCK}. */
    public static final int ELEMENT_FIELD = 3;

    public static final int ELEMENT_KIND_MASK = 0x3;

    /** The element is static: it is defined on the constructor rather than on the prototype. */
    public static final int ELEMENT_STATIC = 0x4;

    /** The key is computed: the method gets its name once the key is known. */
    public static final int ELEMENT_COMPUTED = 0x8;

    /** The key is a private name. */
    public static final int ELEMENT_PRIVATE = 0x10;

    /**
     * A field whose initializer is an anonymous function or class definition with a computed key:
     * the value gets its name from the key at run time (NamedEvaluation).
     */
    public static final int ELEMENT_NAMED = 0x20;

    /** A field without an initializer: the element has no function. */
    public static final int ELEMENT_NO_VALUE = 0x40;

    /** A static block (with {@link #ELEMENT_FIELD} and {@link #ELEMENT_STATIC}). */
    public static final int ELEMENT_BLOCK = 0x80;

    final int kind;

    /** {@link #DEFINE}: whether the first child is the heritage expression. */
    boolean hasHeritage;

    /** {@link #SUPER_CALL}: the only child is an array holding the arguments. */
    boolean arrayArguments;

    /**
     * {@link #TDZ}: the name of the class binding or of the parameter. {@link #PARAM_SCOPE}: the
     * names that resolve outside the function, joined by commas. {@link #PRIVATE_NAME}: the
     * identifier of the private name, without {@code #}.
     */
    String name;

    /** {@link #TDZ}: a parameter referenced by the default value of an earlier parameter. */
    boolean parameter;

    /** {@link #ITERATOR_CLOSE}: the loop ends with an exception, which IteratorClose keeps. */
    boolean quiet;

    /** {@link #DESTRUCTURE}: the step, one of the constants of {@link ArrayDestructuring}. */
    int step;

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

    public int getStep() {
        return step;
    }
}
