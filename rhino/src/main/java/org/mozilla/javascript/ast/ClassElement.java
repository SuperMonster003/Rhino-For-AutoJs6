/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.ast;

import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Token;

/**
 * AutoJs6 class syntax: AST node for one element of a class body. Node type is {@link
 * Token#CLASS_ELEMENT}.
 *
 * <p>The key is a {@link Name}, {@link StringLiteral}, {@link NumberLiteral}, {@link
 * BigIntLiteral}, {@link ComputedPropertyKey} or {@link PrivateName}; a static block has no key.
 * The value is a {@link FunctionNode} in method form:
 *
 * <ul>
 *   <li>constructor, method, getter, setter: the method function itself (generator and async
 *       methods are flagged on the function);
 *   <li>field: a synthetic function whose expression-closure body returns the initializer, or
 *       {@code null} when the field has no initializer (see {@link #getInitializer()});
 *   <li>static block: a synthetic function whose body is the block.
 * </ul>
 */
public class ClassElement extends AstNode {

    /** The kind of a class element. */
    public enum Kind {
        CONSTRUCTOR,
        METHOD,
        GETTER,
        SETTER,
        FIELD,
        STATIC_BLOCK
    }

    private Kind kind = Kind.METHOD;
    private boolean isStatic;
    private AstNode key;
    private FunctionNode value;

    {
        type = Token.CLASS_ELEMENT;
    }

    public ClassElement() {}

    public ClassElement(int pos) {
        super(pos);
    }

    public ClassElement(int pos, int len) {
        super(pos, len);
    }

    public Kind getKind() {
        return kind;
    }

    public void setKind(Kind kind) {
        assertNotNull(kind);
        this.kind = kind;
    }

    /** Returns whether the element has the {@code static} modifier (always true for blocks). */
    public boolean isStatic() {
        return isStatic;
    }

    public void setIsStatic(boolean isStatic) {
        this.isStatic = isStatic;
    }

    /** Returns the element key, or {@code null} for a static block. */
    public AstNode getKey() {
        return key;
    }

    /** Sets the element key, and sets its parent to this node. */
    public void setKey(AstNode key) {
        this.key = key;
        if (key != null) key.setParent(this);
    }

    /** Returns the element function, or {@code null} for a field without initializer. */
    public FunctionNode getValue() {
        return value;
    }

    /** Sets the element function, and sets its parent to this node. */
    public void setValue(FunctionNode value) {
        this.value = value;
        if (value != null) value.setParent(this);
    }

    /** Returns whether the key is a private name ({@code #x}). */
    public boolean isPrivate() {
        return key instanceof PrivateName;
    }

    /** Returns whether the key is computed ({@code [expr]}). */
    public boolean isComputed() {
        return key instanceof ComputedPropertyKey;
    }

    /**
     * Returns the property name the key stands for when it is not computed: the identifier of a
     * name, the value of a string literal, the canonical string of a numeric literal, or {@code
     * "#"} followed by the identifier of a private name. Returns {@code null} for computed keys and
     * static blocks.
     */
    public String getPropertyName() {
        if (key instanceof PrivateName) {
            return "#" + ((PrivateName) key).getIdentifier();
        } else if (key instanceof Name) {
            return ((Name) key).getIdentifier();
        } else if (key instanceof StringLiteral) {
            return ((StringLiteral) key).getValue();
        } else if (key instanceof NumberLiteral) {
            return ScriptRuntime.numberToString(((NumberLiteral) key).getNumber(), 10);
        } else if (key instanceof BigIntLiteral) {
            return ((BigIntLiteral) key).getBigInt().toString();
        }
        return null;
    }

    /** Returns the initializer expression of a field, or {@code null} if there is none. */
    public AstNode getInitializer() {
        if (kind != Kind.FIELD || value == null) {
            return null;
        }
        AstNode body = value.getBody();
        if (body != null && body.getLastChild() instanceof ReturnStatement) {
            return ((ReturnStatement) body.getLastChild()).getReturnValue();
        }
        return null;
    }

    @Override
    public String toSource(int depth) {
        StringBuilder sb = new StringBuilder();
        sb.append(makeIndent(depth + 1));
        if (kind == Kind.STATIC_BLOCK) {
            sb.append("static ");
            sb.append(value.getBody().toSource(depth + 1).trim());
            sb.append("\n");
            return sb.toString();
        }
        if (isStatic) {
            sb.append("static ");
        }
        if (kind == Kind.FIELD) {
            sb.append(key.toSource(0));
            AstNode initializer = getInitializer();
            if (initializer != null) {
                sb.append(" = ");
                sb.append(initializer.toSource(0));
            }
            sb.append(";\n");
            return sb.toString();
        }
        if (kind == Kind.GETTER) {
            sb.append("get ");
        } else if (kind == Kind.SETTER) {
            sb.append("set ");
        } else {
            if (value.isAsync()) {
                sb.append("async ");
            }
            if (value.isES6Generator()) {
                sb.append("*");
            }
        }
        sb.append(key.toSource(0));
        sb.append(value.toSource(depth + 1));
        return sb.toString();
    }

    /** Visits this node, the key (if any) and the value (if any). */
    @Override
    public void visit(NodeVisitor v) {
        if (v.visit(this)) {
            if (key != null) {
                key.visit(v);
            }
            if (value != null) {
                value.visit(v);
            }
        }
    }
}
