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
import org.mozilla.javascript.ast.AstNode;
import org.mozilla.javascript.ast.AwaitExpression;
import org.mozilla.javascript.ast.EmptyExpression;
import org.mozilla.javascript.ast.ForAwaitOfLoop;
import org.mozilla.javascript.ast.ForInLoop;
import org.mozilla.javascript.ast.FunctionCall;
import org.mozilla.javascript.ast.FunctionNode;
import org.mozilla.javascript.ast.InfixExpression;
import org.mozilla.javascript.ast.Name;
import org.mozilla.javascript.ast.ObjectProperty;
import org.mozilla.javascript.ast.ParenthesizedExpression;
import org.mozilla.javascript.ast.PropertyGet;

/**
 * AutoJs6 async: parsing of async functions, async methods, async arrow functions, {@code await}
 * and {@code for await} (ES2018). The syntax is parsed ahead of its implementation: until P9 of
 * {@code docs/autojs6/class-syntax/ROADMAP.md} lands, {@link AsyncLowering} rejects async functions
 * with a readable "not supported yet" error instead of a syntax error.
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
        if (parser.peekToken() != Token.AWAIT) {
            return false;
        }
        parser.consumeToken();
        forAwaitPosition = parser.ts.tokenBeg;
        if (context() != AWAIT_ASYNC || inParameters()) {
            parser.reportError("msg.async.await.context");
        }
        return true;
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
        if (call.getIntProp(Node.TRAILING_COMMA, 0) == 1) {
            params.putIntProp(Node.TRAILING_COMMA, 1);
        }
        return params;
    }

    /**
     * Rejects {@code await} expressions in arrow parameters, and for async arrow functions also
     * {@code await} used as a parameter or reference.
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
