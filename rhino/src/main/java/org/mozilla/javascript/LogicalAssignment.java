/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import java.util.ArrayList;
import java.util.List;
import org.mozilla.javascript.ast.AstNode;
import org.mozilla.javascript.ast.Scope;
import org.mozilla.javascript.ast.WithStatement;

/**
 * AutoJs6: the short circuit of the logical assignments {@code a ||= b}, {@code a &&= b} and {@code
 * a ??= b} (ROADMAP P6.7, appendix C Q17). Not related to classes; a fix of an upstream gap, which
 * always wrote the value back.
 *
 * <p>{@link #lower} turns the assignment into the logical operator applied to a read of the
 * reference and an ordinary assignment of the right side to it, {@code a || (a = b)}, so neither
 * backend changes and the assignment keeps its checks (const bindings, strict mode, setters,
 * private names). The object and the key of a property reference are evaluated once: those that are
 * not constant are kept in let bindings of a let expression around the operator, named so that no
 * identifier can refer to them. {@code this}, {@code super} and constant keys are evaluated again
 * instead; private names are constant keys. With a key that is not constant, the read first
 * requires the object to be coercible ({@link ClassOp#REQUIRE_OBJECT}): GetValue converts a null or
 * undefined base before the key, which the element read of Rhino does the other way round.
 *
 * <p>A name is resolved once (ROADMAP P6.17, appendix C Q42): the operator is wrapped in a {@link
 * ClassOp#LOGICAL_NAME}, whose read and write use the scope of one BINDNAME: a {@code with} object
 * is asked for the binding once, and the value goes to the scope found before the read even when a
 * getter removed the binding there. Only where a {@code with} scope can be on the scope chain;
 * elsewhere the name is looked up for the read and for the write as before.
 */
final class LogicalAssignment {

    private LogicalAssignment() {}

    /**
     * Returns the IR of a logical assignment, or null for the other assignments and for a reference
     * this class leaves to the upstream code (a reference of a special property or of E4X), which
     * then always writes the value back.
     */
    static Node lower(Parser parser, int assignType, Node left, Node right) {
        int op;
        switch (assignType) {
            case Token.ASSIGN_LOGICAL_OR:
                op = Token.OR;
                break;
            case Token.ASSIGN_LOGICAL_AND:
                op = Token.AND;
                break;
            case Token.ASSIGN_NULLISH:
                op = Token.NULLISH_COALESCING;
                break;
            default:
                return null;
        }
        switch (left.getType()) {
            case Token.NAME:
                {
                    boolean inWith = mayBeInWith(parser, left);
                    Node read = parser.createName(left.getString());
                    read.setLineColumnNumber(left.getLineno(), left.getColumn());
                    Node write = parser.simpleAssignment(left, right);
                    if (!inWith) {
                        return new Node(op, read, write);
                    }
                    ClassOp.markLogicalRead(read);
                    ClassOp.markBoundBind(write.getFirstChild());
                    Node logical = ClassLowering.newClassNode(ClassOp.LOGICAL_NAME);
                    logical.addChildToBack(new Node(op, read, write));
                    return logical;
                }
            case Token.GETPROP:
            case Token.GETELEM:
                return member(parser, op, left, right);
            case Token.CALL:
                // A call is not a simple assignment target; unlike = and the compound assignments,
                // the logical ones have no web compatibility exception for it.
                parser.reportError("msg.bad.assign.left");
                return right;
            default:
                return null;
        }
    }

    /**
     * Whether a {@code with} scope can be on the scope chain of a name: it is in a {@code with}
     * statement, also one around its function, or in eval code, which runs in the scope of the
     * caller. Elsewhere looking the name up twice is not observable.
     */
    private static boolean mayBeInWith(Parser parser, Node name) {
        if (!(name instanceof AstNode) || parser.compilerEnv.isInEval()) {
            return true;
        }
        for (AstNode n = ((AstNode) name).getParent(); n != null; n = n.getParent()) {
            if (n instanceof WithStatement) {
                return true;
            }
        }
        return false;
    }

    private static Node member(Parser parser, int op, Node left, Node right) {
        int type = left.getType();
        Node obj = left.getFirstChild();
        Node id = left.getLastChild();
        boolean superAccess = left.getIntProp(Node.SUPER_PROPERTY_ACCESS, 0) == 1;
        Node idCopy = copyConstant(id);
        Node objCopy = superAccess || idCopy != null ? copyStable(obj) : null;
        if (superAccess && objCopy == null) {
            return null;
        }
        Temps temps = new Temps(parser);
        Node objRead = obj;
        Node objWrite = objCopy;
        if (objCopy == null) {
            objRead = temps.add(obj);
            objWrite = temps.read(objRead);
        } else if (idCopy == null && obj.getType() == Token.COMMA) {
            // super[key] in a derived constructor: this is read before the key is evaluated.
            temps.add(copyStable(obj.getFirstChild()));
        }
        Node idRead = id;
        Node idWrite = idCopy;
        if (idCopy == null) {
            idRead = temps.add(id);
            idWrite = temps.read(idRead);
            if (!superAccess) {
                // GetValue converts a null or undefined base before the key.
                Node check = ClassLowering.newClassNode(ClassOp.REQUIRE_OBJECT);
                check.addChildToBack(objRead);
                objRead = check;
            }
        }
        Node read = new Node(type, objRead, idRead);
        Node target = new Node(type, objWrite, idWrite);
        if (superAccess) {
            read.putIntProp(Node.SUPER_PROPERTY_ACCESS, 1);
            target.putIntProp(Node.SUPER_PROPERTY_ACCESS, 1);
        }
        Node write = parser.simpleAssignment(target, right);
        if (superAccess) {
            write.putIntProp(Node.SUPER_PROPERTY_ACCESS, 1);
        }
        return temps.wrap(left, new Node(op, read, write));
    }

    /** A copy of an object that evaluates to the same value again: this or super. */
    private static Node copyStable(Node node) {
        switch (node.getType()) {
            case Token.THIS:
            case Token.SUPER:
                return new Node(node.getType());
            case Token.CLASS:
                return ClassOp.of(node).getKind() == ClassOp.THIS
                        ? ClassLowering.newClassNode(ClassOp.THIS)
                        : null;
            case Token.COMMA:
                {
                    Node first = node.getFirstChild();
                    Node second = first.getNext();
                    if (second == null || second.getNext() != null) {
                        return null;
                    }
                    Node a = copyStable(first);
                    Node b = copyStable(second);
                    return a == null || b == null ? null : new Node(Token.COMMA, a, b);
                }
            default:
                return null;
        }
    }

    /** A copy of a constant key: a string, a number or a private name. */
    private static Node copyConstant(Node node) {
        switch (node.getType()) {
            case Token.STRING:
                return Node.newString(node.getString());
            case Token.NUMBER:
                return Node.newNumber(node.getDouble());
            case Token.NAME:
                return ClassParser.isPrivateBinding(node.getString())
                        ? Node.newString(Token.NAME, node.getString())
                        : null;
            default:
                return null;
        }
    }

    /** The let bindings that hold the object and the key. */
    static final class Temps {
        private final Parser parser;
        private final List<String> names = new ArrayList<>();
        private final List<Node> values = new ArrayList<>();

        Temps(Parser parser) {
            this.parser = parser;
        }

        /** Adds a binding initialized with the value and returns a read of it. */
        Node add(Node value) {
            String name = "%" + parser.currentScriptOrFn.getNextTempName();
            names.add(name);
            values.add(value);
            return Node.newString(Token.NAME, name);
        }

        Node read(Node name) {
            return Node.newString(Token.NAME, name.getString());
        }

        Node wrap(Node position, Node body) {
            if (names.isEmpty()) {
                return body;
            }
            Scope scope =
                    parser.createScopeNode(
                            Token.LETEXPR, position.getLineno(), position.getColumn());
            Node vars = new Node(Token.LET);
            parser.pushScope(scope);
            try {
                for (int i = 0; i < names.size(); i++) {
                    parser.defineSymbol(Token.LET, names.get(i), true);
                    vars.addChildToBack(Node.newString(Token.NAME, names.get(i)));
                    vars.getLastChild().addChildToBack(values.get(i));
                }
            } finally {
                parser.popScope();
            }
            scope.addChildToBack(vars);
            scope.addChildToBack(body);
            return scope;
        }
    }
}
