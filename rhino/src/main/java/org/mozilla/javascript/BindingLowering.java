/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.mozilla.javascript.ast.ArrayLiteral;
import org.mozilla.javascript.ast.Assignment;
import org.mozilla.javascript.ast.AstNode;
import org.mozilla.javascript.ast.ClassNode;
import org.mozilla.javascript.ast.DoLoop;
import org.mozilla.javascript.ast.ExpressionStatement;
import org.mozilla.javascript.ast.ForAwaitOfLoop;
import org.mozilla.javascript.ast.ForInLoop;
import org.mozilla.javascript.ast.ForLoop;
import org.mozilla.javascript.ast.FunctionNode;
import org.mozilla.javascript.ast.IfStatement;
import org.mozilla.javascript.ast.Jump;
import org.mozilla.javascript.ast.LabeledStatement;
import org.mozilla.javascript.ast.Name;
import org.mozilla.javascript.ast.NodeVisitor;
import org.mozilla.javascript.ast.ObjectLiteral;
import org.mozilla.javascript.ast.ObjectProperty;
import org.mozilla.javascript.ast.ParenthesizedExpression;
import org.mozilla.javascript.ast.ReturnStatement;
import org.mozilla.javascript.ast.Scope;
import org.mozilla.javascript.ast.ScriptNode;
import org.mozilla.javascript.ast.Spread;
import org.mozilla.javascript.ast.SpreadObjectProperty;
import org.mozilla.javascript.ast.SwitchStatement;
import org.mozilla.javascript.ast.ThrowStatement;
import org.mozilla.javascript.ast.VariableDeclaration;
import org.mozilla.javascript.ast.WhileLoop;
import org.mozilla.javascript.ast.WithStatement;

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
    private final Map<Scope, Set<String>> catchPatterns = new HashMap<>();

    BindingLowering(Parser parser) {
        this.parser = parser;
    }

    /**
     * Whether a destructuring takes its values from an iterator (Parser.destructuringArray): one
     * with a record ({@link #open}), or a parameter list.
     */
    boolean iterates(boolean isFunctionParameter, Node record) {
        return record != null
                || (isFunctionParameter
                        && parser.compilerEnv.getLanguageVersion() >= Context.VERSION_ES6);
    }

    /**
     * An elision of an iterator destructuring of a parameter list: IteratorStep, without reading
     * the value. A pattern with a record skips with {@link #skip}.
     */
    void step(Node parent, String iterator, String lastResult) {
        if (iterator == null) {
            return;
        }
        Node next = new Node(Token.GETPROP, parser.createName(iterator), Node.newString("next"));
        Node target = parser.createName(Token.BINDNAME, lastResult, null);
        parent.addChildToBack(new Node(Token.SETNAME, target, new Node(Token.CALL, next)));
    }

    /** The rest element of an iterator destructuring: the values the iterator has left. */
    Node rest(Node record, String iterator) {
        if (record != null) {
            return destructure(record, ArrayDestructuring.REST);
        }
        Node rest = ClassLowering.newClassNode(ClassOp.ITERATOR_REST);
        rest.addChildToBack(parser.createName(iterator));
        return rest;
    }

    /**
     * The array patterns outside parameter lists iterate their values as well (ROADMAP P6.12,
     * appendix C Q25), with an iterator record ({@link ArrayDestructuring}) in the local of a
     * LOCAL_BLOCK per pattern. The steps are expressions; the IteratorClose of a pattern that ends
     * with an exception needs a try, so the innermost statement that holds the pattern is wrapped
     * ({@link #wrap}). The parameter lists keep the iteration of the upstream lowering.
     */
    static final class Patterns {
        private final ScriptNode function;
        private final List<Node> records = new ArrayList<>();

        private Patterns(ScriptNode function) {
            this.function = function;
        }
    }

    private final Deque<Patterns> statements = new ArrayDeque<>();
    private AstNode entered;

    /**
     * The hook at the start of IRFactory.transform: a statement that can hold an array pattern is
     * transformed as the holder of its patterns and wrapped; null for other nodes.
     */
    Node statement(IRFactory factory, AstNode node) {
        if (node == entered) {
            entered = null;
            return null;
        }
        if (!holdsPatterns(node) || parser.compilerEnv.getLanguageVersion() < Context.VERSION_ES6) {
            return null;
        }
        Patterns patterns = new Patterns(parser.currentScriptOrFn);
        statements.push(patterns);
        Node result;
        try {
            entered = node;
            result = factory.transform(node);
        } finally {
            statements.pop();
        }
        return wrap(patterns, result);
    }

    private static boolean holdsPatterns(AstNode node) {
        if (node instanceof VariableDeclaration) {
            // The head of a for statement belongs to the loop.
            AstNode parent = node.getParent();
            return !(parent instanceof ForLoop && ((ForLoop) parent).getInitializer() == node)
                    && !(parent instanceof ForInLoop && ((ForInLoop) parent).getIterator() == node);
        }
        return node instanceof ExpressionStatement
                || node instanceof ReturnStatement
                || node instanceof ThrowStatement
                || node instanceof IfStatement
                || node instanceof SwitchStatement
                || node instanceof WithStatement
                || node instanceof WhileLoop
                || node instanceof DoLoop
                || node instanceof ForLoop
                || isForInOf(node);
    }

    /** A for-in, for-of or for-await-of statement, not a loop of a comprehension. */
    private static boolean isForInOf(AstNode node) {
        return node.getClass() == ForInLoop.class || node instanceof ForAwaitOfLoop;
    }

    /**
     * The head of a for-in or for-of statement (IRFactory.createForIn): the destructuring of each
     * value holds its patterns, which close their iterators before the loop closes its own. Null
     * for the loops of the comprehensions, whose patterns belong to the enclosing statement.
     */
    Patterns head(AstNode ast) {
        if (!isForInOf(ast) || parser.compilerEnv.getLanguageVersion() < Context.VERSION_ES6) {
            return null;
        }
        Patterns patterns = new Patterns(parser.currentScriptOrFn);
        statements.push(patterns);
        return patterns;
    }

    /** The destructuring of each value of a for-in or for-of statement, see {@link #head}. */
    Node head(Patterns patterns, Node node) {
        if (patterns == null) {
            return node;
        }
        statements.pop();
        return wrap(patterns, node);
    }

    /**
     * The start of an array pattern (Parser.destructuringArray): GetIterator of the value in {@code
     * tempName}, into the record of a new LOCAL_BLOCK of the innermost statement, which is
     * returned. Null when the pattern reads its value by index as before: in a parameter list, in a
     * version before ES6, or outside such a statement of its function.
     */
    Node open(boolean isFunctionParameter, Node parent, String tempName) {
        Patterns patterns = statements.peek();
        if (isFunctionParameter
                || patterns == null
                || patterns.function != parser.currentScriptOrFn) {
            return null;
        }
        Node localBlock = new Node(Token.LOCAL_BLOCK);
        // The handlers read the local, which the statement may not have reached
        // (ClassBodyCodegen.clearsLocal).
        localBlock.putIntProp(Node.CLASS_PROP, 1);
        patterns.records.add(localBlock);
        Node open = destructure(localBlock, ArrayDestructuring.OPEN);
        open.addChildToBack(parser.createName(tempName));
        parent.addChildToBack(open);
        return localBlock;
    }

    /** An elision of a pattern with a record: IteratorStep, without reading the value. */
    void skip(Node parent, Node record) {
        if (record != null) {
            parent.addChildToBack(destructure(record, ArrayDestructuring.SKIP));
        }
    }

    /**
     * The value of an element of a pattern with a record. An element with a default reads it twice,
     * so the step is an expression of its own.
     */
    Node element(Node parent, Node record, AstNode element) {
        if (element.getType() != Token.ASSIGN) {
            return destructure(record, ArrayDestructuring.STEP);
        }
        parent.addChildToBack(destructure(record, ArrayDestructuring.STEP));
        return destructure(record, ArrayDestructuring.VALUE);
    }

    /**
     * A member expression target of an array assignment pattern with a record (appendix C Q32):
     * {@code [o.x] = it}, {@code [o[k] = d] = it}, {@code [...o[k]] = it}. The reference, the
     * object and the key, is evaluated before the iterator steps (the DestructuringAssignmentTarget
     * before IteratorStepValue), into let bindings like those of a logical assignment, so an error
     * or a yield there leaves the open iterator to the closing of the pattern. False for the other
     * elements, which Parser.destructuringArray handles (a rest element that is not the last is
     * reported there).
     */
    boolean member(Node parent, Node record, AstNode element, Parser.Transformer transformer) {
        if (record == null || transformer == null) {
            return false;
        }
        AstNode target = element;
        AstNode init = null;
        boolean rest = element instanceof Spread;
        if (rest) {
            List<AstNode> elements = ((ArrayLiteral) element.getParent()).getElements();
            if (elements.get(elements.size() - 1) != element) {
                return false;
            }
            target = ((Spread) element).getExpression();
        } else if (element.getType() == Token.ASSIGN) {
            target = ((Assignment) element).getLeft();
            init = ((Assignment) element).getRight();
        }
        if (target.getType() != Token.GETPROP && target.getType() != Token.GETELEM) {
            return false;
        }
        Node ref = transformer.transform(target);
        int type = ref.getType();
        LogicalAssignment.Temps temps = new LogicalAssignment.Temps(parser);
        Node body = new Node(Token.COMMA);
        if (type == Token.GETPROP || type == Token.GETELEM) {
            Node obj = ref.getFirstChild();
            Node id = ref.getLastChild();
            boolean superAccess = ref.getIntProp(Node.SUPER_PROPERTY_ACCESS, 0) == 1;
            if (!superAccess) {
                obj = temps.add(obj);
            }
            if (!isConstantKey(id)) {
                id = temps.add(id);
            }
            ref = new Node(type, obj, id);
            if (superAccess) {
                ref.putIntProp(Node.SUPER_PROPERTY_ACCESS, 1);
            }
        }
        Node value;
        if (rest) {
            value = rest(record, null);
        } else if (init == null) {
            value = destructure(record, ArrayDestructuring.STEP);
        } else {
            body.addChildToBack(destructure(record, ArrayDestructuring.STEP));
            Node undefined =
                    new Node(
                            Token.SHEQ,
                            new Node(Token.UNDEFINED),
                            destructure(record, ArrayDestructuring.VALUE));
            value =
                    new Node(
                            Token.HOOK,
                            undefined,
                            transformer.transform(init),
                            destructure(record, ArrayDestructuring.VALUE));
        }
        Node write = parser.simpleAssignment(ref, value);
        if (ref.getIntProp(Node.SUPER_PROPERTY_ACCESS, 0) == 1) {
            write.putIntProp(Node.SUPER_PROPERTY_ACCESS, 1);
        }
        body.addChildToBack(write);
        parent.addChildToBack(temps.wrap(ref, body));
        return true;
    }

    /** A key that evaluates to the same value again: a string, a number or a private name. */
    private static boolean isConstantKey(Node id) {
        switch (id.getType()) {
            case Token.STRING:
            case Token.NUMBER:
                return true;
            case Token.NAME:
                return ClassParser.isPrivateBinding(id.getString());
            default:
                return false;
        }
    }

    /** The end of a pattern with a record: IteratorClose, unless the iterator is done. */
    void finish(Node parent, Node record) {
        if (record != null) {
            parent.addChildToBack(destructure(record, ArrayDestructuring.FINISH));
        }
    }

    /**
     * Wraps a statement with records in a {@link #closing} try per record, the innermost pattern
     * innermost, so that an exception closes the iterators of the patterns it leaves. The steps do
     * nothing once the pattern finished or its iterator is done. In a generator whose statement
     * yields, a finally block closes them as well when the generator returns during a yield inside
     * a pattern.
     */
    private static Node wrap(Patterns patterns, Node node) {
        List<Node> records = patterns.records;
        if (records.isEmpty()) {
            return node;
        }
        boolean yields =
                patterns.function instanceof FunctionNode
                        && ((FunctionNode) patterns.function).isGenerator()
                        && hasYield(node);
        int lineno = node.getLineno(), column = node.getColumn();
        for (int i = records.size() - 1; i >= 0; i--) {
            Node localBlock = records.get(i);
            Node onReturn =
                    yields ? destructure(localBlock, ArrayDestructuring.CLOSE_RETURN) : null;
            localBlock.addChildToBack(
                    closing(
                            node,
                            destructure(localBlock, ArrayDestructuring.CLOSE_QUIET),
                            onReturn,
                            lineno,
                            column));
            node = localBlock;
        }
        return node;
    }

    /**
     * {@code try { statement } catch { onThrow; rethrow } finally { onReturn }}, without the
     * finally when {@code onReturn} is null. The handler does not bind the exception, so it needs
     * no catch scope, which would put the variables of the whole function into an activation
     * (IRFactory.createTryCatchFinally).
     */
    static Node closing(Node statement, Node onThrow, Node onReturn, int lineno, int column) {
        Node handlerBlock = new Node(Token.LOCAL_BLOCK);
        Jump tryNode = new Jump(Token.TRY, new Node(Token.BLOCK, statement));
        tryNode.setLineColumnNumber(lineno, column);
        tryNode.putProp(Node.LOCAL_BLOCK_PROP, handlerBlock);
        Node endCatch = Node.newTarget();
        tryNode.addChildToBack(jump(Token.GOTO, endCatch));
        Node catchTarget = Node.newTarget();
        tryNode.target = catchTarget;
        tryNode.addChildToBack(catchTarget);
        tryNode.addChildToBack(new Node(Token.EXPR_VOID, onThrow));
        Node rethrow = new Node(Token.RETHROW);
        rethrow.putProp(Node.LOCAL_BLOCK_PROP, handlerBlock);
        tryNode.addChildToBack(rethrow);
        tryNode.addChildToBack(endCatch);
        if (onReturn != null) {
            Node finallyTarget = Node.newTarget();
            tryNode.setFinally(finallyTarget);
            tryNode.addChildToBack(jump(Token.JSR, finallyTarget));
            Node finallyEnd = Node.newTarget();
            tryNode.addChildToBack(jump(Token.GOTO, finallyEnd));
            tryNode.addChildToBack(finallyTarget);
            Node finallyBlock =
                    new Node(
                            Token.FINALLY,
                            new Node(Token.BLOCK, new Node(Token.EXPR_VOID, onReturn)));
            finallyBlock.putProp(Node.LOCAL_BLOCK_PROP, handlerBlock);
            tryNode.addChildToBack(finallyBlock);
            tryNode.addChildToBack(finallyEnd);
        }
        handlerBlock.addChildToBack(tryNode);
        return handlerBlock;
    }

    private static Jump jump(int type, Node target) {
        Jump jump = new Jump(type);
        jump.target = target;
        return jump;
    }

    /**
     * A statement of a block or of a case of a switch (appendix C Q59, Q61). The block initializes
     * its function declarations on entry, also the labelled ones and those of the cases that do not
     * run, so a declaration moves to {@code functions} and its {@link #evaluation} stays.
     */
    static Node inBlock(Node statement, Node node, List<Node> functions) {
        boolean labelled = statement instanceof LabeledStatement;
        Node declaration = labelled ? ((LabeledStatement) statement).getStatement() : statement;
        if (!(declaration instanceof FunctionNode)
                || ((FunctionNode) declaration).getFunctionType()
                        != FunctionNode.FUNCTION_BLOCK_SCOPED) {
            return node;
        }
        if (!labelled) {
            functions.add(node);
            return evaluation(declaration);
        }
        Node function = node.getFirstChild().getNext();
        functions.add(function);
        node.replaceChild(function, evaluation(declaration));
        return node;
    }

    /**
     * What stays where a function declaration of a block is written. A function that Annex B.3.3
     * also gives a var binding copies the block binding to it here, which is not done on entry.
     */
    static Node evaluation(Node declaration) {
        FunctionNode fn = (FunctionNode) declaration;
        if (!fn.isAnnexBHoisted()) {
            return new Node(Token.EMPTY);
        }
        fn.setAnnexBHoisted(false);
        Node copy = ClassLowering.newClassNode(ClassOp.BLOCK_FUNCTION);
        ClassOp.of(copy).name = fn.getName();
        return new Node(Token.EXPR_VOID, copy);
    }

    /** Puts the functions that {@link #inBlock} took out in front of the switch in its block. */
    static void enterCases(Node block, List<Node> functions) {
        for (int i = functions.size() - 1; i >= 0; i--) {
            block.addChildToFront(functions.get(i));
        }
    }

    /**
     * Whether the statement after a label is a function declaration at the top level of a script or
     * function: it belongs to the top level declarations, not to a block (appendix C Q63). Strict
     * code stays on the path of a block function, where a labelled function in the position of a
     * single statement is an error.
     */
    boolean labelsTopLevelFunction() throws IOException {
        return parser.peekToken() == Token.FUNCTION
                && parser.currentScope == parser.currentScriptOrFn
                && !parser.inUseStrictDirective
                && parser.compilerEnv.getLanguageVersion() >= Context.VERSION_ES6;
    }

    /**
     * Remembers the names of a destructuring catch parameter. They only become bindings when the
     * body of the catch is transformed, too late for the Annex B.3.3 check of its functions.
     */
    void catchParameter(Scope block, AstNode parameter) {
        if (parameter instanceof ArrayLiteral || parameter instanceof ObjectLiteral) {
            Set<String> names = new HashSet<>();
            boundNames(parameter, names);
            catchPatterns.put(block, names);
        }
    }

    /**
     * Whether a var binding for a function of the current block would be an early error, because a
     * destructuring catch parameter around it binds the name (appendix C Q62). B.3.5 only excuses a
     * catch parameter that is a plain name.
     */
    boolean boundByCatchPattern(Name name) {
        if (name == null || catchPatterns.isEmpty()) {
            return false;
        }
        Scope scope = parser.currentScope;
        while (scope != null && scope != parser.currentScriptOrFn) {
            Set<String> names = catchPatterns.get(scope);
            if (names != null && names.contains(name.getIdentifier())) {
                return true;
            }
            scope = scope.getParentScope();
        }
        return false;
    }

    /** Nested functions are FUNCTION leaves in the tree, so their yields are not found. */
    private static boolean hasYield(Node node) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (child.getType() == Token.YIELD
                    || child.getType() == Token.YIELD_STAR
                    || hasYield(child)) {
                return true;
            }
        }
        return false;
    }

    private static Node destructure(Node localBlock, int step) {
        Node node = ClassLowering.newClassNode(ClassOp.DESTRUCTURE);
        ClassOp.of(node).step = step;
        node.putProp(Node.LOCAL_BLOCK_PROP, localBlock);
        return node;
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
     * (ForIn/OfBodyEvaluation): {@code try { loop } catch { CLOSE_QUIET; rethrow } finally { CLOSE
     * }} ({@link #closing}). The handler closes the iterator, ignoring the errors of its {@code
     * return} method, and rethrows the exception as it is; the finally block closes the iterator on
     * break, return and continue to an outer label. Neither does anything once the iterator is
     * done.
     *
     * <p>Only a for-of statement: the loops of the JavaScript 1.7 comprehensions keep the old
     * behavior (an array comprehension is an expression, which cannot hold a try).
     */
    Node forOf(IRFactory factory, AstNode ast, Node localBlock, Node loop) {
        if (ast.getClass() != ForInLoop.class || !((ForInLoop) ast).isForOf()) {
            return loop;
        }
        // GetIterator stays in the loop, whose scope shadows the head bindings while the iterated
        // expression is evaluated, so it is inside the try as well: the handlers find no
        // enumeration when it throws, and the compiled code clears the local up front
        // (ClassBodyCodegen.clearsLocal).
        localBlock.putIntProp(Node.CLASS_PROP, 1);
        Node tryNode =
                closing(
                        loop,
                        iteratorClose(localBlock, true),
                        iteratorClose(localBlock, false),
                        loop.getLineno(),
                        loop.getColumn());
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
        private final boolean evalInParameters;

        ParamScope(FunctionNode fn, Node body) {
            this.fn = fn;
            this.body = body;
            bodyStart = body.getFirstChild();
            names = bodyNamesUsedByParameters(fn);
            evalInParameters = AsyncLowering.evalInParameters(fn);
        }

        void apply() {
            if (!names.isEmpty() || evalInParameters) {
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
