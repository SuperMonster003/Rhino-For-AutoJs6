/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.mozilla.javascript.Token;

/**
 * AutoJs6 class syntax: AST node for a class declaration or class expression. Node type is {@link
 * Token#CLASS}.
 *
 * <pre><i>ClassDeclaration</i> :
 *   <b>class</b> BindingIdentifier ClassTail
 * <i>ClassExpression</i> :
 *   <b>class</b> [BindingIdentifier] ClassTail
 * <i>ClassTail</i> :
 *   [<b>extends</b> LeftHandSideExpression] <b>{</b> ClassBody <b>}</b></pre>
 *
 * <p>The node is a {@link Scope}: the heritage expression and computed keys are parsed inside it,
 * and the immutable inner binding of the class name lives in its symbol table. A class without a
 * name ({@link #isAnonymous()}) is the target of name inference (for example {@code var C = class
 * {}}); the inferred name is recorded with {@link #setInferredName(Name)}.
 */
public class ClassNode extends Scope {

    private Name className;
    private AstNode superClass;
    private final List<ClassElement> elements = new ArrayList<>();
    private boolean isStatement;
    private int extendsPosition = -1;
    private int lc = -1;
    private Name inferredName;

    {
        type = Token.CLASS;
    }

    public ClassNode() {}

    public ClassNode(int pos) {
        super(pos);
    }

    public ClassNode(int pos, int len) {
        super(pos, len);
    }

    /** Returns the class name, or {@code null} for an anonymous class expression. */
    public Name getClassName() {
        return className;
    }

    /** Sets the class name, and sets its parent to this node. */
    public void setClassName(Name className) {
        this.className = className;
        if (className != null) className.setParent(this);
    }

    /** Returns whether the class has no name of its own. */
    public boolean isAnonymous() {
        return className == null;
    }

    /** Returns the name inferred from the context of an anonymous class, or {@code null}. */
    public Name getInferredName() {
        return inferredName;
    }

    public void setInferredName(Name inferredName) {
        this.inferredName = inferredName;
    }

    /** Returns the {@code extends} expression, or {@code null} for a base class. */
    public AstNode getSuperClass() {
        return superClass;
    }

    /** Sets the {@code extends} expression, and sets its parent to this node. */
    public void setSuperClass(AstNode superClass) {
        this.superClass = superClass;
        if (superClass != null) superClass.setParent(this);
    }

    /** Returns whether the class has an {@code extends} clause. */
    public boolean isDerived() {
        return superClass != null;
    }

    /** Returns the class elements in source order. */
    public List<ClassElement> getElements() {
        return Collections.unmodifiableList(elements);
    }

    /** Appends a class element, and sets its parent to this node. */
    public void addElement(ClassElement element) {
        assertNotNull(element);
        elements.add(element);
        element.setParent(this);
    }

    /** Returns the explicit constructor element, or {@code null} if the class has none. */
    public ClassElement getConstructor() {
        for (ClassElement element : elements) {
            if (element.getKind() == ClassElement.Kind.CONSTRUCTOR) {
                return element;
            }
        }
        return null;
    }

    /** Returns whether this node is a class declaration rather than a class expression. */
    public boolean isStatement() {
        return isStatement;
    }

    public void setIsStatement(boolean isStatement) {
        this.isStatement = isStatement;
    }

    /** Returns the position of the {@code extends} keyword relative to this node, or -1. */
    public int getExtendsPosition() {
        return extendsPosition;
    }

    public void setExtendsPosition(int extendsPosition) {
        this.extendsPosition = extendsPosition;
    }

    /** Returns the position of the opening brace relative to this node, or -1. */
    public int getLc() {
        return lc;
    }

    public void setLc(int lc) {
        this.lc = lc;
    }

    @Override
    public boolean hasSideEffects() {
        return true;
    }

    @Override
    public String toSource(int depth) {
        StringBuilder sb = new StringBuilder();
        sb.append(makeIndent(depth));
        sb.append("class");
        if (className != null) {
            sb.append(" ");
            sb.append(className.toSource(0));
        }
        if (superClass != null) {
            sb.append(" extends ");
            sb.append(superClass.toSource(0));
        }
        sb.append(" {\n");
        for (ClassElement element : elements) {
            sb.append(element.toSource(depth));
        }
        sb.append(makeIndent(depth));
        sb.append("}");
        if (isStatement) {
            sb.append("\n");
        }
        return sb.toString();
    }

    /** Visits this node, the class name (if any), the heritage (if any) and every element. */
    @Override
    public void visit(NodeVisitor v) {
        if (v.visit(this)) {
            if (className != null) {
                className.visit(v);
            }
            if (superClass != null) {
                superClass.visit(v);
            }
            for (ClassElement element : elements) {
                element.visit(v);
            }
        }
    }
}
