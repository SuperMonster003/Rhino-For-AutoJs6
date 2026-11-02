/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.mozilla.javascript.ast.AbstractObjectProperty;
import org.mozilla.javascript.ast.ArrayLiteral;
import org.mozilla.javascript.ast.Assignment;
import org.mozilla.javascript.ast.AstNode;
import org.mozilla.javascript.ast.AwaitExpression;
import org.mozilla.javascript.ast.EmptyExpression;
import org.mozilla.javascript.ast.ExpressionStatement;
import org.mozilla.javascript.ast.ForAwaitOfLoop;
import org.mozilla.javascript.ast.ForInLoop;
import org.mozilla.javascript.ast.FunctionCall;
import org.mozilla.javascript.ast.FunctionNode;
import org.mozilla.javascript.ast.InfixExpression;
import org.mozilla.javascript.ast.Name;
import org.mozilla.javascript.ast.ObjectLiteral;
import org.mozilla.javascript.ast.ObjectProperty;
import org.mozilla.javascript.ast.ParenthesizedExpression;
import org.mozilla.javascript.ast.PropertyGet;
import org.mozilla.javascript.ast.Scope;
import org.mozilla.javascript.ast.Spread;
import org.mozilla.javascript.ast.Symbol;

/**
 * AutoJs6 async: parsing of async functions, async methods, async arrow functions, {@code await}
 * and {@code for await} (ES2018). The early errors that the parser only reports for some functions
 * are reported for every async function here; {@link AsyncLowering} takes over from the AST.
 *
 * <p>{@code await} is a keyword only inside async functions (and reserved inside class static
 * blocks); everywhere else in a script it stays an identifier. The await context of every function
 * being parsed is kept on a stack pushed and popped by {@code Parser.PerFunctionVariables}, and the
 * lexer consults it through {@link #keyword(Parser, int)}.
 */
final class AsyncParser {

    /** {@code await} is an identifier. */
    private static final int AWAIT_NONE = 0;

    /** Inside an async function: {@code await} is the await operator. */
    private static final int AWAIT_ASYNC = 1;

    /** Inside a class static block: {@code await} is reserved and cannot be used at all. */
    private static final int AWAIT_RESERVED = 2;

    private final Parser parser;
    private int[] contexts = new int[16];
    private int[] bodyNesting = new int[16];
    private int depth;
    private boolean pendingAsync;

    // `async x` heads waiting for their `=>`, and positions of `async` callees followed by `(`.
    private AstNode arrowHead;
    private int arrowHeadStart = -1;
    private final Set<Integer> asyncCallees = new HashSet<>();
    private int forAwaitPosition = -1;
    private boolean forAwaitHead;
    private int literalDepth;
    private boolean coverPending;

    AsyncParser(Parser parser) {
        this.parser = parser;
    }

    /** Returns whether async syntax is parsed with the given environment (D4 gate). */
    static boolean isEnabled(CompilerEnvirons env) {
        return env.isAsyncSyntaxEnabled() && env.getLanguageVersion() >= Context.VERSION_ES6;
    }

    // ------------------------------------------------------------------
    // Lexer hook (TokenStream)
    // ------------------------------------------------------------------

    /**
     * Maps the reserved word {@code await}: an identifier outside async functions and static
     * blocks, {@link Token#AWAIT} inside them. An escaped {@code await} inside them stays {@link
     * Token#RESERVED}.
     */
    static int keyword(Parser parser, int token) {
        if (token == Token.YIELD && isEnabled(parser.compilerEnv)) {
            return parser.asyncSyntax.yieldToken();
        }
        if (token != Token.RESERVED
                || !"await".equals(parser.ts.getString())
                || !isEnabled(parser.compilerEnv)) {
            return token;
        }
        if (parser.asyncSyntax.context() == AWAIT_NONE) {
            return Token.NAME;
        }
        return parser.ts.wordEscaped ? Token.RESERVED : Token.AWAIT;
    }

    /**
     * Rhino reads {@code yield} as the legacy generator operator in every function. Inside an async
     * function that is wrong: only a generator has the operator, elsewhere the word is an
     * identifier, or reserved in strict code.
     */
    private int yieldToken() {
        boolean strict = parser.inUseStrictDirective();
        if (!(parser.currentScriptOrFn instanceof FunctionNode)) {
            return strict ? Token.YIELD : Token.NAME;
        }
        if (!insideAsync() || ((FunctionNode) parser.currentScriptOrFn).isES6Generator()) {
            return Token.YIELD;
        }
        return strict ? Token.RESERVED : Token.NAME;
    }

    private boolean insideAsync() {
        for (int i = 0; i < depth; i++) {
            if (contexts[i] == AWAIT_ASYNC) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Function context stack (Parser.PerFunctionVariables)
    // ------------------------------------------------------------------

    void enterFunction(FunctionNode fn) {
        if (pendingAsync) {
            pendingAsync = false;
            fn.setIsAsync(true);
        }
        int context;
        if (fn.isAsync()) {
            context = AWAIT_ASYNC;
        } else if (parser.classSyntax.currentKind() == ClassParser.FN_STATIC_BLOCK) {
            context = AWAIT_RESERVED;
        } else {
            context = AWAIT_NONE;
        }
        if (depth == contexts.length) {
            int[] grownContexts = new int[depth * 2];
            int[] grownNesting = new int[depth * 2];
            System.arraycopy(contexts, 0, grownContexts, 0, depth);
            System.arraycopy(bodyNesting, 0, grownNesting, 0, depth);
            contexts = grownContexts;
            bodyNesting = grownNesting;
        }
        contexts[depth] = context;
        bodyNesting[depth] = parser.nestingOfFunction;
        depth++;
    }

    void exitFunction() {
        if (depth > 0) {
            depth--;
        }
    }

    private int context() {
        return depth == 0 ? AWAIT_NONE : contexts[depth - 1];
    }

    /** Returns whether the parameters (not the body) of the innermost function are parsed. */
    private boolean inParameters() {
        return depth > 0 && parser.nestingOfFunction == bodyNesting[depth - 1];
    }

    /** Marks the next function that the parser creates as async. */
    void setPending() {
        pendingAsync = true;
    }

    void clearPending() {
        pendingAsync = false;
    }

    /** Returns whether an async method key was just parsed and its function is still pending. */
    boolean isPending() {
        return pendingAsync;
    }

    // ------------------------------------------------------------------
    // Early errors
    // ------------------------------------------------------------------

    /** A generator expression may not be named {@code yield}. */
    void checkGeneratorName(Name name, int functionType, boolean isGenerator) {
        if (isGenerator
                && functionType == FunctionNode.FUNCTION_EXPRESSION
                && "yield".equals(name.getIdentifier())) {
            parser.reportError("msg.syntax");
        }
    }

    /** A {@code "use strict"} directive needs simple parameters; the parser checks the others. */
    void checkUseStrict(FunctionNode fn) {
        if (fn.isAsync() && fn.getProp(Node.DESTRUCTURING_PARAMS) != null) {
            parser.reportError("msg.default.args.use.strict");
        }
    }

    /** A rest parameter has no initializer; the {@code =} is the next token. */
    void checkRestParameter(FunctionNode fn) throws IOException {
        if (fn.isAsync() && parser.peekToken() == Token.ASSIGN) {
            parser.reportError("msg.parm.after.rest");
        }
    }

    /** Parameters of a method, or that are not simple, have no duplicates in sloppy code. */
    void checkDuplicateParameters(FunctionNode fn) {
        if (!fn.isAsync()
                || parser.inUseStrictDirective()
                || (!parser.insideMethod
                        && fn.getDefaultParams() == null
                        && !fn.hasRestParameter()
                        && fn.getProp(Node.DESTRUCTURING_PARAMS) == null)) {
            return;
        }
        Set<String> names = new HashSet<>();
        for (AstNode param : fn.getParams()) {
            visitTargets(
                    param,
                    name -> {
                        if (!names.add(name.getIdentifier())) {
                            parser.addError("msg.dup.param.strict", name.getIdentifier());
                        }
                    });
        }
    }

    /** The lexical declarations of a function body may not name a parameter. */
    void checkRedeclared(
            Symbol.Type declType, Symbol.Type oldType, Scope definingScope, String name) {
        if (oldType == Symbol.Type.LP
                && Symbol.isDeclTypeLexical(declType)
                && definingScope == parser.currentScope
                && definingScope instanceof FunctionNode
                && ((FunctionNode) definingScope).isAsync()) {
            parser.addError("msg.parm.redecl", name);
        }
    }

    /** A parenthesized pattern is no assignment target; a pattern settles its cover grammar. */
    void checkAssignmentTarget(AstNode target) {
        coverPending = false;
        if (context() == AWAIT_ASYNC
                && target instanceof ParenthesizedExpression
                && isPattern(((ParenthesizedExpression) target).getExpression())) {
            parser.reportError("msg.syntax.invalid.assignment.lhs");
        }
    }

    /** In strict code {@code eval} and {@code arguments} are no targets of the pattern. */
    void checkForAwaitTarget(AstNode head) {
        if (parser.inUseStrictDirective() && isPattern(head)) {
            visitTargets(
                    head,
                    name -> {
                        String id = name.getIdentifier();
                        if ("eval".equals(id) || "arguments".equals(id)) {
                            parser.reportError("msg.bad.id.strict", id);
                        }
                    });
        }
    }

    private static boolean isPattern(AstNode node) {
        return node instanceof ArrayLiteral || node instanceof ObjectLiteral;
    }

    private static void visitTargets(AstNode target, Consumer<Name> visitor) {
        if (target instanceof Name) {
            visitor.accept((Name) target);
        } else if (target instanceof ArrayLiteral) {
            for (AstNode element : ((ArrayLiteral) target).getElements()) {
                visitTargets(element, visitor);
            }
        } else if (target instanceof ObjectLiteral) {
            for (AbstractObjectProperty property : ((ObjectLiteral) target).getElements()) {
                if (property instanceof ObjectProperty) {
                    visitTargets(((ObjectProperty) property).getValue(), visitor);
                }
            }
        } else if (target instanceof Spread) {
            visitTargets(((Spread) target).getExpression(), visitor);
        } else if (target instanceof Assignment) {
            visitTargets(((Assignment) target).getLeft(), visitor);
        }
    }

    // ------------------------------------------------------------------
    // Statements
    // ------------------------------------------------------------------

    /**
     * Parses an async function declaration if the peeked {@link Token#NAME} is {@code async}
     * followed by {@code function} on the same line; returns {@code null} otherwise.
     */
    AstNode declaration() throws IOException {
        TokenStream ts = parser.ts;
        if (!isEnabled(parser.compilerEnv)
                || ts.wordEscaped
                || !"async".equals(ts.getString())
                || !followedByFunction()) {
            return null;
        }
        if (parser.inSingleStatementContext()) {
            parser.reportError("msg.async.decl.not.in.block");
        }
        parser.consumeToken();
        int pos = ts.tokenBeg;
        int lineno = parser.lineNumber();
        int column = parser.columnNumber();
        if (parser.nextToken() != Token.FUNCTION) {
            parser.reportError("msg.syntax");
        }
        int type =
                parser.currentScope == parser.currentScriptOrFn
                        ? FunctionNode.FUNCTION_STATEMENT
                        : FunctionNode.FUNCTION_BLOCK_SCOPED;
        FunctionNode fn = asyncFunction(type);
        if (fn.getFunctionName() == null) {
            parser.reportError("msg.async.name.missing");
        }
        moveStart(fn, pos);
        fn.setLineColumnNumber(lineno, column);
        return fn;
    }

    private FunctionNode asyncFunction(int type) throws IOException {
        pendingAsync = true;
        try {
            return parser.function(type);
        } finally {
            pendingAsync = false;
        }
    }

    /**
     * Checks the raw source after the peeked {@code async} token: whitespace and single-line block
     * comments, then the keyword {@code function}. A line terminator in between breaks the match.
     */
    private boolean followedByFunction() {
        TokenStream ts = parser.ts;
        String src = ts.getSourceString();
        if (src == null) {
            return false;
        }
        int i = ts.tokenEnd;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int close = src.indexOf("*/", i + 2);
                if (close < 0) {
                    return false;
                }
                for (int j = i + 2; j < close; j++) {
                    if (ScriptRuntime.isJSLineTerminator(src.charAt(j))) {
                        return false;
                    }
                }
                i = close + 2;
            } else if (ScriptRuntime.isJSWhitespaceOrLineTerminator(c)
                    && !ScriptRuntime.isJSLineTerminator(c)) {
                i++;
            } else {
                break;
            }
        }
        int end = i + "function".length();
        return src.startsWith("function", i)
                && (end >= n || !Character.isJavaIdentifierPart(src.charAt(end)));
    }

    /** Checks {@code for await}: consumes {@code await} and returns true when present. */
    boolean forAwait() throws IOException {
        forAwaitHead = parser.peekToken() == Token.AWAIT;
        if (!forAwaitHead) {
            return false;
        }
        parser.consumeToken();
        forAwaitPosition = parser.ts.tokenBeg;
        if (context() != AWAIT_ASYNC || inParameters()) {
            parser.reportError("msg.async.await.context");
        }
        return true;
    }

    /**
     * A {@code let} that is the whole body of a {@code for await} loop: in sloppy code it is an
     * identifier when a line terminator ends the statement, and an error otherwise. Returns {@code
     * null} when the loop has a block or the statement is not that body.
     */
    AstNode letAsForAwaitBody() throws IOException {
        if (!(parser.currentScope instanceof ForAwaitOfLoop) || parser.inUseStrictDirective()) {
            return null;
        }
        TokenStream ts = parser.ts;
        parser.consumeToken();
        int lineno = parser.lineNumber();
        int column = parser.columnNumber();
        Name name = new Name(ts.tokenBeg, "let");
        name.setLineColumnNumber(lineno, column);
        if (parser.peekTokenOrEOL() != Token.EOL || parser.peekToken() == Token.LB) {
            parser.reportError("msg.let.decl.not.in.block");
        }
        ExpressionStatement statement = new ExpressionStatement(name, !parser.insideFunctionBody());
        statement.setLineColumnNumber(lineno, column);
        return statement;
    }

    /** Whether the expression before {@code tt} is the head of a for-in or for-of loop. */
    private boolean inForHead(int tt) {
        return parser.inForInit
                && (tt == Token.IN || (tt == Token.NAME && "of".equals(parser.ts.getString())));
    }

    /**
     * An object literal with a shorthand initializer ends before {@code tt} and no assignment
     * follows. It is still a pattern in the head of a for-in or for-of loop; inside another literal
     * the outermost one decides. Returns whether that holds, otherwise the literal is an error.
     */
    boolean coverInitializer(int tt) {
        if (literalDepth > 0) {
            coverPending = true;
            return true;
        }
        return inForHead(tt);
    }

    /** The assignment expression that ends before {@code tt} may end a nested cover grammar. */
    void endAssignment(int tt) {
        if (coverPending && literalDepth == 0) {
            coverPending = false;
            if (!inForHead(tt)) {
                parser.reportError("msg.syntax");
            }
        }
    }

    /**
     * Parses a literal: its elements have the {@code in} operator in the head of a for loop, and
     * they may be patterns with shorthand initializers.
     */
    AstNode literal(Parser.LiteralParser literal) throws IOException {
        boolean wasInForInit = parser.inForInit;
        parser.inForInit = false;
        literalDepth++;
        try {
            return literal.parse();
        } finally {
            literalDepth--;
            parser.inForInit = wasInForInit;
        }
    }

    /** Creates the loop node of a for-in or for-of loop. */
    ForInLoop forInLoop(int forPos, boolean isForAwait) {
        if (!isForAwait) {
            return new ForInLoop(forPos);
        }
        ForAwaitOfLoop loop = new ForAwaitOfLoop(forPos);
        loop.setAwaitPosition(forAwaitPosition - forPos);
        return loop;
    }

    // ------------------------------------------------------------------
    // Expressions
    // ------------------------------------------------------------------

    /**
     * Hook at the start of {@code Parser.name()} for an identifier that was just consumed. Parses
     * {@code async function} expressions and the {@code async x} head of an async arrow function,
     * and remembers {@code async} callees that may turn out to be {@code async (...) =>} heads.
     * Returns {@code null} when the identifier is an ordinary name.
     */
    AstNode name(int ttFlagged, String nameString, int namePos) throws IOException {
        TokenStream ts = parser.ts;
        if (!"async".equals(nameString) || ts.wordEscaped || !isEnabled(parser.compilerEnv)) {
            return null;
        }
        int lineno = parser.lineNumber();
        int column = parser.columnNumber();
        int next = parser.peekFlaggedToken();
        if ((next & Parser.TI_AFTER_EOL) != 0) {
            return null;
        }
        switch (next & Parser.CLEAR_TI_MASK) {
            case Token.FUNCTION:
                {
                    if ((ttFlagged & Parser.TI_CHECK_LABEL) != 0) {
                        // A declaration where only a statement may appear (after a label).
                        parser.reportError("msg.async.decl.not.in.block");
                    }
                    parser.consumeToken();
                    FunctionNode fn = asyncFunction(FunctionNode.FUNCTION_EXPRESSION);
                    Name fnName = fn.getFunctionName();
                    if (fnName != null && "await".equals(fnName.getIdentifier())) {
                        parser.reportError("msg.async.await.params");
                    }
                    moveStart(fn, namePos);
                    fn.setLineColumnNumber(lineno, column);
                    return fn;
                }
            case Token.NAME:
                {
                    if (forAwaitHead
                            && parser.inForInit
                            && "of".equals(ts.getString())
                            && !followedByArrow()) {
                        return null; // for await (async of ...)
                    }
                    parser.consumeToken();
                    Name param = parser.createNameNode(true, Token.NAME);
                    if ("await".equals(param.getIdentifier())) {
                        parser.reportError("msg.async.await.params");
                    }
                    if (parser.peekTokenOrEOL() != Token.ARROW) {
                        parser.reportError("msg.syntax");
                    }
                    arrowHead = param;
                    arrowHeadStart = namePos;
                    return param;
                }
            case Token.LP:
                asyncCallees.add(namePos);
                return null;
            default:
                return null;
        }
    }

    private boolean followedByArrow() {
        String src = parser.ts.getSourceString();
        int i = parser.ts.tokenEnd;
        while (src != null && i < src.length() && Character.isWhitespace(src.charAt(i))) {
            i++;
        }
        return src != null && src.startsWith("=>", i);
    }

    /**
     * Hook for {@code =>} in {@code Parser.assignExpr()}: builds an async arrow function when the
     * head is {@code async x} or {@code async (...)}, an ordinary arrow function otherwise. Arrow
     * parameters may not contain {@code await} expressions.
     */
    AstNode arrowFunction(AstNode head, int startLine, int startColumn) throws IOException {
        int asyncStart = -1;
        AstNode params = head;
        if (head != null && head == arrowHead) {
            asyncStart = arrowHeadStart;
            arrowHead = null;
        } else if (head instanceof FunctionCall
                && asyncCallees.remove(head.getPosition())
                && ((FunctionCall) head).getTarget() instanceof Name) {
            asyncStart = head.getPosition();
            params = toParameters((FunctionCall) head);
        }
        checkParameters(params, asyncStart >= 0);
        if (asyncStart < 0) {
            return parser.arrowFunction(params, startLine, startColumn);
        }
        AstNode result;
        pendingAsync = true;
        try {
            result = parser.arrowFunction(params, startLine, startColumn);
        } finally {
            pendingAsync = false;
        }
        if (result instanceof FunctionNode) {
            moveStart((FunctionNode) result, asyncStart);
        }
        return result;
    }

    /** Turns the arguments of {@code async(...)} into parenthesized arrow parameters. */
    private static AstNode toParameters(FunctionCall call) {
        int callPos = call.getPosition();
        int lp = callPos + call.getLp();
        int rp = callPos + call.getRp();
        List<AstNode> args = call.getArguments();
        AstNode expr = null;
        for (AstNode arg : args) {
            arg.setParent(null);
            expr =
                    expr == null
                            ? arg
                            : new InfixExpression(
                                    Token.COMMA, expr, arg, Math.max(arg.getPosition() - 1, 0));
        }
        if (expr == null) {
            expr = new EmptyExpression(lp + 1, 0);
        }
        ParenthesizedExpression params = new ParenthesizedExpression(lp, rp + 1 - lp, expr);
        params.setLineColumnNumber(call.getLineno(), call.getColumn());
        if (!args.isEmpty() && args.get(args.size() - 1).getIntProp(Node.TRAILING_COMMA, 0) == 1) {
            params.putIntProp(Node.TRAILING_COMMA, 1);
        }
        return params;
    }

    /** The trailing comma of arguments that may turn out to be async arrow parameters. */
    void markTrailingComma(List<AstNode> arguments) {
        arguments.get(arguments.size() - 1).putIntProp(Node.TRAILING_COMMA, 1);
    }

    /**
     * Rejects {@code await} expressions in arrow parameters, and for async arrow functions also
     * {@code await} used as a parameter or reference, in nested arrow parameters as well.
     */
    private void checkParameters(AstNode params, boolean isAsync) {
        if (params == null) {
            return;
        }
        params.visit(
                node -> {
                    if (node instanceof AwaitExpression) {
                        parser.reportError("msg.async.await.params");
                    } else if (isAsync
                            && node instanceof Name
                            && "await".equals(((Name) node).getIdentifier())
                            && !isPropertyName(node)) {
                        parser.reportError("msg.async.await.params");
                    } else if (isAsync
                            && node instanceof FunctionNode
                            && ((FunctionNode) node).getFunctionType()
                                    == FunctionNode.ARROW_FUNCTION) {
                        for (AstNode param : ((FunctionNode) node).getParams()) {
                            checkParameters(param, true);
                        }
                    }
                    return !(node instanceof FunctionNode);
                });
    }

    private static boolean isPropertyName(AstNode node) {
        AstNode parent = node.getParent();
        if (parent instanceof PropertyGet) {
            return ((PropertyGet) parent).getProperty() == node;
        }
        if (parent instanceof ObjectProperty) {
            ObjectProperty prop = (ObjectProperty) parent;
            return prop.getKey() == node && prop.getValue() != node;
        }
        return false;
    }

    /** Parses an {@code await} expression; the peeked token is {@link Token#AWAIT}. */
    AstNode awaitExpression() throws IOException {
        TokenStream ts = parser.ts;
        parser.consumeToken();
        int pos = ts.tokenBeg;
        int lineno = parser.lineNumber();
        int column = parser.columnNumber();
        if (context() != AWAIT_ASYNC || inParameters()) {
            parser.reportError(
                    context() == AWAIT_ASYNC
                            ? "msg.async.await.params"
                            : "msg.async.await.context");
        }
        AstNode operand = parser.unaryExpr();
        AwaitExpression node =
                new AwaitExpression(
                        pos, operand.getPosition() + operand.getLength() - pos, operand);
        node.setLineColumnNumber(lineno, column);
        return node;
    }

    /**
     * Hook in {@code Parser.objectLiteral()}: when the key just parsed is {@code async} and another
     * key follows on the same line, parses that key and marks the method as async.
     */
    AstNode methodKey(AstNode key) throws IOException {
        if (!(key instanceof Name)
                || !"async".equals(((Name) key).getIdentifier())
                || !isEnabled(parser.compilerEnv)
                || !isRawWord(key, "async")) {
            return key;
        }
        int flagged = parser.peekFlaggedToken();
        int tt = flagged & Parser.CLEAR_TI_MASK;
        if ((flagged & Parser.TI_AFTER_EOL) != 0
                || tt == Token.LP
                || tt == Token.COMMA
                || tt == Token.COLON
                || tt == Token.RC
                || tt == Token.ASSIGN) {
            return key;
        }
        AstNode methodKey = parser.objliteralProperty();
        if (methodKey == null) {
            parser.reportError("msg.bad.prop");
            return key;
        }
        parser.consumeToken();
        pendingAsync = true;
        return methodKey;
    }

    /** Returns whether the source text at the node is the word itself, without escapes. */
    private boolean isRawWord(AstNode node, String word) {
        String src = parser.ts.getSourceString();
        return src == null || src.startsWith(word, node.getPosition());
    }

    /**
     * Moves the start of a function that was parsed after its {@code async} keyword back to that
     * keyword, keeping the children at their absolute positions.
     */
    static void moveStart(FunctionNode fn, int newStart) {
        int delta = fn.getPosition() - newStart;
        if (delta <= 0) {
            return;
        }
        for (AstNode param : fn.getParams()) {
            if (param.getParent() == fn) {
                param.setPosition(param.getPosition() + delta);
            }
        }
        if (fn.getFunctionName() != null && fn.getFunctionName().getParent() == fn) {
            fn.getFunctionName().setPosition(fn.getFunctionName().getPosition() + delta);
        }
        AstNode body = fn.getBody();
        if (body != null) {
            body.setPosition(body.getPosition() + delta);
        }
        if (fn.getLp() != -1) {
            fn.setLp(fn.getLp() + delta);
        }
        if (fn.getRp() != -1) {
            fn.setRp(fn.getRp() + delta);
        }
        int end = fn.getPosition() + fn.getLength();
        fn.setPosition(newStart);
        fn.setLength(end - newStart);
        fn.setRawSourceBounds(newStart, end);
    }
}
