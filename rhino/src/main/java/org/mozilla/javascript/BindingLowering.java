/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.mozilla.javascript.ast.ArrayLiteral;
import org.mozilla.javascript.ast.Assignment;
import org.mozilla.javascript.ast.AstNode;
import org.mozilla.javascript.ast.ClassNode;
import org.mozilla.javascript.ast.ForInLoop;
import org.mozilla.javascript.ast.FunctionNode;
import org.mozilla.javascript.ast.Name;
import org.mozilla.javascript.ast.NodeVisitor;
import org.mozilla.javascript.ast.ObjectLiteral;
import org.mozilla.javascript.ast.ObjectProperty;
import org.mozilla.javascript.ast.ParenthesizedExpression;
import org.mozilla.javascript.ast.Spread;
import org.mozilla.javascript.ast.SpreadObjectProperty;

/**
 * AutoJs6 class syntax: corrections to the lowering of bindings, that is destructuring patterns and
 * parameter lists (ROADMAP P2.5). The ES2015 core of the test262 class directories runs every
 * binding form through class methods, which brought these gaps of the shared lowering to light;
 * ordinary functions and generators get the same corrections.
 *
 * <ul>
 *   <li>Elisions of an array pattern step the iterator, and a rest element takes the values the
 *       iterator has left ({@link ClassOp#ITERATOR_REST}) instead of slicing the argument.
 *   <li>An empty object pattern still requires an object ({@link ClassOp#REQUIRE_OBJECT}).
 *   <li>An anonymous function or class used as the default of a single name binding gets the name
 *       of the binding (NamedEvaluation).
 *   <li>The destructuring of generator parameters runs when the generator is created, together with
 *       the default values.
 *   <li>The default value of a parameter cannot reference the parameter itself or a later one
 *       ({@link ClassOp#TDZ} with {@link ClassOp#parameter}).
 *   <li>{@code arguments} in a parameter list requires the activation that holds it.
 *   <li>A for-of loop that ends early closes its iterator ({@link ClassOp#ITERATOR_CLOSE}).
 *   <li>The initialization of the parameters does not see the declarations of the body ({@link
 *       ClassOp#PARAM_SCOPE}).
 *   <li>A member expression as the target of an object pattern property is evaluated before the
 *       property is read (ROADMAP P3.5).
 * </ul>
 *
 * <p>{@link Parser} and {@link IRFactory} keep one-line hooks into this class; they are listed in
 * {@code docs/autojs6/class-syntax/HOOKS.md}.
 */
final class BindingLowering {

    private static final Pattern TEMP_NAME = Pattern.compile("\\$\\d+");

    private final Parser parser;

    BindingLowering(Parser parser) {
        this.parser = parser;
    }

    /** Whether a destructuring takes its values from an iterator (Parser.destructuringArray). */
    boolean iterates(boolean isFunctionParameter) {
        return isFunctionParameter
                && parser.compilerEnv.getLanguageVersion() >= Context.VERSION_ES6;
    }

    /** An elision of an iterator destructuring: IteratorStep, without reading the value. */
    void step(Node parent, String iterator, String lastResult) {
        Node next = new Node(Token.GETPROP, parser.createName(iterator), Node.newString("next"));
        Node target = parser.createName(Token.BINDNAME, lastResult, null);
        parent.addChildToBack(new Node(Token.SETNAME, target, new Node(Token.CALL, next)));
    }

    /** The rest element of an iterator destructuring: the values the iterator has left. */
    Node rest(String iterator) {
        Node rest = ClassLowering.newClassNode(ClassOp.ITERATOR_REST);
        rest.addChildToBack(parser.createName(iterator));
        return rest;
    }

    /**
     * Whether the value of an object pattern property is a member expression assigned directly,
     * {@code o.x = value.p} (Parser.destructuringObject): the reference to the target, {@code o}
     * and the key, is evaluated before the property is read (the DestructuringAssignmentTarget
     * before GetV), so that {@code ({a: this.#x} = obj)} in a derived constructor fails before the
     * getter runs. Declarations report the target elsewhere.
     */
    boolean assignsMember(AstNode value, int variableType) {
        return variableType == -1
                && (value.getType() == Token.GETPROP || value.getType() == Token.GETELEM);
    }

    /** An empty object pattern: RequireObjectCoercible of the value. */
    void requireObject(Node parent, String tempName) {
        Node check = ClassLowering.newClassNode(ClassOp.REQUIRE_OBJECT);
        check.addChildToBack(parser.createName(tempName));
        parent.addChildToBack(check);
    }

    /**
     * The default of a single name binding (Parser.processDestructuringDefaults): an anonymous
     * function or class gets the name of the binding. A value that is not transformed yet is the
     * default of a parameter; it is marked, and {@link #named} infers the name once {@link
     * IRFactory} transforms it.
     */
    void inferName(Node target, Node value, boolean transformed) {
        if (!(target instanceof Name)) {
            return;
        }
        if (transformed) {
            infer((Name) target, value);
            return;
        }
        AstNode node = (AstNode) value;
        while (node instanceof ParenthesizedExpression) {
            node = ((ParenthesizedExpression) node).getExpression();
        }
        if (node instanceof FunctionNode || node instanceof ClassNode) {
            value.putProp(Node.DEFAULT_NAME_PROP, target);
        }
    }

    /** Transforms the default of a parameter binding, see {@link #inferName}. */
    Node named(IRFactory factory, AstNode value) {
        Node node = factory.transform(value);
        Object name = value.getProp(Node.DEFAULT_NAME_PROP);
        if (name != null) {
            infer((Name) name, node);
        }
        return node;
    }

    private void infer(Name target, Node value) {
        if (parser.compilerEnv.getLanguageVersion() < Context.VERSION_ES6) {
            return;
        }
        // A fresh node: the target stays where it is in the pattern.
        Name name = new Name(0, target.getIdentifier());
        if (ClassLowering.isClassValue(value)) {
            ClassLowering.inferName(parser, value, name, null);
        } else if (value.getType() == Token.FUNCTION) {
            int index = value.getExistingIntProp(Node.FUNCTION_PROP);
            FunctionNode function = parser.currentScriptOrFn.getFunctionNode(index);
            if (function.getFunctionName() == null) {
                function.setFunctionName(name);
            }
        }
    }

    /**
     * The destructuring of the parameters of a generator runs with the default values when the
     * generator is created (FunctionDeclarationInstantiation), before Icode.GENERATOR. Returns the
     * destructuring when it stays at the top of the body.
     */
    static Node genParams(FunctionNode fn, Node destructuring) {
        if (destructuring == null || !fn.isGenerator()) {
            return destructuring;
        }
        Node block = fn.getGeneratorParamInitBlock();
        if (block == null) {
            block = new Node(Token.BLOCK);
            fn.setGeneratorParamInitBlock(block);
        }
        AstNode body = fn.getBody();
        block.addChildToFront(
                new Node(Token.EXPR_VOID, destructuring, body.getLineno(), body.getColumn()));
        // The destructuring needs NodeTransformer (LETEXPR), so the block goes into the tree in
        // front of the body; the code generators take the body from the last child.
        fn.addChildToFront(block);
        return null;
    }

    /**
     * The initialization of parameter {@code name} from its default value: a reference to the
     * parameter itself or to a later one throws a ReferenceError (they are still uninitialized).
     */
    static void paramTdz(FunctionNode fn, String name, Node paramInit) {
        Set<String> uninitialized = new HashSet<>();
        boolean found = false;
        for (AstNode param : fn.getParams()) {
            if (param instanceof Name && ((Name) param).getIdentifier().equals(name)) {
                found = true;
            }
            if (found) {
                boundNames(param, uninitialized);
            }
        }
        Node assignment = findAssignment(paramInit, name);
        if (assignment != null && !uninitialized.isEmpty()) {
            replaceReferences(assignment.getFirstChild().getNext(), assignment, uninitialized);
        }
    }

    private static void boundNames(AstNode pattern, Set<String> names) {
        if (pattern instanceof Name) {
            names.add(((Name) pattern).getIdentifier());
        } else if (pattern instanceof Assignment) {
            boundNames(((Assignment) pattern).getLeft(), names);
        } else if (pattern instanceof Spread) {
            boundNames(((Spread) pattern).getExpression(), names);
        } else if (pattern instanceof ArrayLiteral) {
            for (AstNode element : ((ArrayLiteral) pattern).getElements()) {
                boundNames(element, names);
            }
        } else if (pattern instanceof ObjectLiteral) {
            for (var property : ((ObjectLiteral) pattern).getElements()) {
                if (property instanceof SpreadObjectProperty) {
                    Spread spread = ((SpreadObjectProperty) property).getSpreadNode();
                    boundNames(spread.getExpression(), names);
                } else if (property instanceof ObjectProperty) {
                    boundNames(((ObjectProperty) property).getValue(), names);
                }
            }
        }
    }

    private static Node findAssignment(Node node, String name) {
        if (node.getType() == Token.SETNAME
                && node.getFirstChild().getType() == Token.BINDNAME
                && name.equals(node.getFirstChild().getString())) {
            return node;
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            Node found = findAssignment(child, name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** Nested functions are FUNCTION leaves in the tree, so their bodies are not visited. */
    private static void replaceReferences(Node node, Node parent, Set<String> names) {
        if ((node.getType() == Token.NAME || node.getType() == Token.TYPEOFNAME)
                && names.contains(node.getString())) {
            Node tdz = ClassLowering.newClassNode(ClassOp.TDZ);
            ClassOp op = ClassOp.of(tdz);
            op.name = node.getString();
            op.parameter = true;
            parent.replaceChild(node, tdz);
            return;
        }
        Node child = node.getFirstChild();
        while (child != null) {
            Node next = child.getNext();
            replaceReferences(child, node, names);
            child = next;
        }
    }

    /**
     * A for-of loop closes its iterator when it ends before the iterator is done
     * (ForIn/OfBodyEvaluation): {@code try { loop } catch ($e if CLOSE_QUIET) {} finally { CLOSE
     * }}. The catch condition closes the iterator, ignoring the errors of its {@code return}
     * method, and is false, so that the exception is rethrown as it is; the finally block closes
     * the iterator on break, return and continue to an outer label. Neither does anything once the
     * iterator is done.
     *
     * <p>Only a for-of statement: the loops of the JavaScript 1.7 comprehensions keep the old
     * behavior (an array comprehension is an expression, which cannot hold a try).
     */
    Node forOf(IRFactory factory, AstNode ast, Node localBlock, Node loop) {
        if (ast.getClass() != ForInLoop.class || !((ForInLoop) ast).isForOf()) {
            return loop;
        }
        int lineno = loop.getLineno(), column = loop.getColumn();
        // GetIterator stays in the loop, whose scope shadows the head bindings while the iterated
        // expression is evaluated, so it is inside the try as well: the handlers find no
        // enumeration when it throws, and the compiled code clears the local up front
        // (ClassBodyCodegen.clearsLocal).
        localBlock.putIntProp(Node.CLASS_PROP, 1);
        Node catchVar = parser.createName(parser.currentScriptOrFn.getNextTempName());
        Node catchBlocks = new Node(Token.BLOCK);
        catchBlocks.addChildToBack(
                new Node(
                        Token.CATCH,
                        catchVar,
                        iteratorClose(localBlock, true),
                        new Node(Token.BLOCK),
                        lineno,
                        column));
        Node finallyBlock =
                new Node(Token.BLOCK, new Node(Token.EXPR_VOID, iteratorClose(localBlock, false)));
        Node tryNode =
                factory.createTryCatchFinally(
                        new Node(Token.BLOCK, loop), catchBlocks, finallyBlock, lineno, column);
        return new Node(Token.BLOCK, tryNode);
    }

    private static Node iteratorClose(Node localBlock, boolean quiet) {
        Node close = ClassLowering.newClassNode(ClassOp.ITERATOR_CLOSE);
        ClassOp.of(close).quiet = quiet;
        close.putProp(Node.LOCAL_BLOCK_PROP, localBlock);
        return close;
    }

    /**
     * The parameters of a function with parameter expressions live in a scope of their own, which
     * the declarations of the body do not reach (FunctionDeclarationInstantiation, step 28): a
     * default value, and a closure created by one, that names a variable which the body declares as
     * well sees the variable outside the function. The function keeps one activation, so the
     * initialization of the parameters (the statements that end up in front of the transformed
     * body, or the parameter block of a generator) runs in a with scope whose object resolves those
     * names outside the function ({@link ClassRuntime#paramScope}).
     *
     * <p>IRFactory.transformFunction creates it once the body is transformed, before the parameter
     * expressions are (their trees change), and applies it at the end.
     */
    static final class ParamScope {
        private final FunctionNode fn;
        private final Node body;
        private final Node bodyStart;
        private final Set<String> names;

        ParamScope(FunctionNode fn, Node body) {
            this.fn = fn;
            this.body = body;
            bodyStart = body.getFirstChild();
            names = bodyNamesUsedByParameters(fn);
        }

        void apply() {
            if (!names.isEmpty()) {
                paramScope(fn, body, bodyStart, names);
            }
        }
    }

    private static void paramScope(FunctionNode fn, Node body, Node bodyStart, Set<String> names) {
        Node container = body;
        Node end = bodyStart;
        if (fn.isGenerator()) {
            container = fn.getGeneratorParamInitBlock();
            end = null;
            if (container == null) {
                return;
            }
            if (fn.getFirstChild() != container) {
                fn.addChildToFront(container);
            }
        }
        Node statements = new Node(Token.BLOCK);
        while (container.getFirstChild() != end) {
            Node statement = container.getFirstChild();
            container.removeChild(statement);
            statements.addChildToBack(statement);
        }
        if (!statements.hasChildren()) {
            return;
        }
        Node scope = ClassLowering.newClassNode(ClassOp.PARAM_SCOPE);
        ClassOp.of(scope).name = String.join(",", names);
        int lineno = fn.getBody().getLineno(), column = fn.getBody().getColumn();
        Node with = new Node(Token.BLOCK, lineno, column);
        with.addChildToBack(new Node(Token.ENTERWITH, scope));
        with.addChildToBack(new Node(Token.WITH, statements, lineno, column));
        with.addChildToBack(new Node(Token.LEAVE_SCOPE));
        container.addChildToFront(with);
        // The names inside the with scope are looked up in scope objects.
        fn.setRequiresActivation();
    }

    private static Set<String> bodyNamesUsedByParameters(FunctionNode fn) {
        Set<String> names = new HashSet<>();
        if (fn.getSymbolTable() == null) {
            return names;
        }
        Set<String> parameters = new HashSet<>();
        for (AstNode param : fn.getParams()) {
            boundNames(param, parameters);
        }
        for (String name : fn.getSymbolTable().keySet()) {
            if (!parameters.contains(name)
                    && !"arguments".equals(name)
                    && !TEMP_NAME.matcher(name).matches()) {
                names.add(name);
            }
        }
        if (names.isEmpty()) {
            return names;
        }
        Set<String> referenced = new HashSet<>();
        NodeVisitor visitor =
                node -> {
                    if (node instanceof Name) {
                        referenced.add(((Name) node).getIdentifier());
                    }
                    return true;
                };
        for (AstNode param : fn.getParams()) {
            param.visit(visitor);
        }
        List<Object> defaults = fn.getDefaultParams();
        if (defaults != null) {
            for (Object value : defaults) {
                if (value instanceof AstNode) {
                    ((AstNode) value).visit(visitor);
                }
            }
        }
        List<Node[]> rvalues = fn.getDestructuringRvalues();
        if (rvalues != null) {
            for (Node[] pair : rvalues) {
                if (pair[1] instanceof AstNode) {
                    ((AstNode) pair[1]).visit(visitor);
                }
            }
        }
        names.retainAll(referenced);
        return names;
    }

    /** {@code arguments} in a parameter list: the function needs the activation that holds it. */
    void requireArguments() {
        FunctionNode fn = (FunctionNode) parser.currentScriptOrFn;
        if (fn.getFunctionType() != FunctionNode.ARROW_FUNCTION) {
            fn.setRequiresActivation();
        }
    }
}
