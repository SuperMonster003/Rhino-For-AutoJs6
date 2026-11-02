/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests.classsyntax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mozilla.javascript.CompilerEnvirons;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Parser;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Token;
import org.mozilla.javascript.ast.AstNode;
import org.mozilla.javascript.ast.AstRoot;
import org.mozilla.javascript.ast.AwaitExpression;
import org.mozilla.javascript.ast.ForAwaitOfLoop;
import org.mozilla.javascript.ast.FunctionNode;
import org.mozilla.javascript.ast.ObjectProperty;

/**
 * AutoJs6 async (ROADMAP P1.5): async functions, methods, arrow functions, {@code await} and {@code
 * for await} are parsed ahead of their implementation; compiling them reports a readable "not
 * supported yet" error until P9.
 */
class AsyncSyntaxParserTest {

    private static AstRoot parse(String source) {
        return parse(source, true);
    }

    private static AstRoot parse(String source, boolean asyncSyntax) {
        try (Context cx = Context.enter()) {
            cx.setLanguageVersion(Context.VERSION_ECMASCRIPT);
            CompilerEnvirons env = new CompilerEnvirons();
            env.initFromContext(cx);
            env.setAsyncSyntaxEnabled(asyncSyntax);
            env.setErrorReporter(cx.getErrorReporter());
            return new Parser(env).parse(source, "test.js", 1);
        }
    }

    private static String parseError(String source) {
        EvaluatorException e = assertThrows(EvaluatorException.class, () -> parse(source));
        return e.details();
    }

    private static String message(String key, Object... args) {
        try (Context cx = Context.enter()) {
            return ScriptRuntime.getMessageById(key, args);
        }
    }

    private static <T extends AstNode> List<T> collect(AstNode root, Class<T> type) {
        List<T> found = new ArrayList<>();
        root.visit(
                node -> {
                    if (type.isInstance(node)) {
                        found.add(type.cast(node));
                    }
                    return true;
                });
        return found;
    }

    @Test
    void asyncFunctionsAreFlagged() {
        AstRoot root =
                parse(
                        "async function a() { await 1; }\n"
                                + "var b = async function () {};\n"
                                + "var c = async x => x;\n"
                                + "var d = async (x, y) => { await x; };\n"
                                + "var e = async () => 1;\n"
                                + "async function* f() { yield await 1; }\n"
                                + "var o = { async m() {}, async *g() {}, async: 1, async() {} };\n"
                                + "function plain() {}\n");
        List<FunctionNode> functions = collect(root, FunctionNode.class);
        List<Boolean> asyncFlags = new ArrayList<>();
        for (FunctionNode fn : functions) {
            asyncFlags.add(fn.isAsync());
        }
        assertEquals(
                List.of(true, true, true, true, true, true, true, true, false, false), asyncFlags);
        FunctionNode declaration = functions.get(0);
        assertEquals(FunctionNode.FUNCTION_STATEMENT, declaration.getFunctionType());
        assertEquals(0, declaration.getPosition());
        assertTrue(declaration.toSource().startsWith("async function a()"));
        assertEquals(FunctionNode.ARROW_FUNCTION, functions.get(2).getFunctionType());
        assertTrue(functions.get(5).isES6Generator());

        List<ObjectProperty> props = collect(root, ObjectProperty.class);
        assertTrue(props.get(0).toSource().contains("async m()"));
        assertTrue(props.get(1).toSource().contains("async *g()"));
    }

    @Test
    void awaitExpressionAndForAwait() {
        AstRoot root = parse("async function f(xs) { for await (const x of xs) { await x; } }");
        List<AwaitExpression> awaits = collect(root, AwaitExpression.class);
        assertEquals(1, awaits.size());
        assertEquals(Token.AWAIT, awaits.get(0).getType());
        assertEquals("await x", awaits.get(0).toSource());
        List<ForAwaitOfLoop> loops = collect(root, ForAwaitOfLoop.class);
        assertEquals(1, loops.size());
        assertTrue(loops.get(0).isForOf());
        assertTrue(loops.get(0).toSource().startsWith("for await (const x of xs)"));
    }

    @Test
    void awaitIsAnIdentifierOutsideAsyncFunctions() {
        parse("var await = 1; await += 1; function await2(await) { return await; }");
        parse("async function f() { function g() { var await; } }");
        parse("class C { static { (() => { var await; }); } }");
        AstRoot root = parse("var x = await;");
        assertTrue(collect(root, AwaitExpression.class).isEmpty());
    }

    @Test
    void asyncIsAContextualKeyword() {
        parse("var async = 1; async = async + 1; async(1); async\nfunction f() {}");
        AstRoot root = parse("async\nfunction f() {}");
        for (FunctionNode fn : collect(root, FunctionNode.class)) {
            assertFalse(fn.isAsync());
        }
        // An escaped async never makes a function async.
        assertEquals(message("msg.no.semi.stmt"), parseError("\\u0061sync function f() {}"));
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "async function f() { function g() { await 1; } }|msg.no.semi.stmt",
                "async function f(x = await 1) {}|msg.async.await.params",
                "async (await) => 1|msg.async.await.params",
                "async await => 1|msg.async.await.params",
                "var f = async function await() {};|msg.async.await.params",
                "async function f() { (x = await 1) => x; }|msg.async.await.params",
                "class C { static { await 1; } }|msg.async.await.context",
                "async function f() { for await (x in y) {} }|msg.async.for.await.of",
                "async function f() { for await (;;) {} }|msg.async.for.await.of",
                "if (1) async function f() {}|msg.async.decl.not.in.block",
                "label: async function f() {}|msg.async.decl.not.in.block",
                "async function () {}|msg.async.name.missing",
                "async function () {} = 1;|msg.async.name.missing"
            })
    void earlyErrors(String source, String key) {
        assertEquals(message(key), parseError(source));
    }

    @Test
    void switchOff() {
        // With the switch off, async is an ordinary identifier and await stays reserved.
        assertThrows(EvaluatorException.class, () -> parse("async function f() {}", false));
        parse("var async = 1; async(2);", false);
    }

    @Test
    void asyncFunctionsCompile() {
        try (Context cx = Context.enter()) {
            cx.setLanguageVersion(Context.VERSION_ECMASCRIPT);
            for (String source :
                    new String[] {
                        "async function f() {}",
                        "var f = async () => 1;",
                        "var o = { async m() {} };"
                    }) {
                cx.compileString(source, "test.js", 1, null);
            }
        }
    }

    @Test
    void asyncArrowPositions() {
        AstRoot root = parse("var f = async (a, b) => a + b;");
        FunctionNode fn = collect(root, FunctionNode.class).get(0);
        assertTrue(fn.isAsync());
        assertEquals(2, fn.getParams().size());
        assertEquals("async (a, b) => a + b", fn.toSource());
        assertInstanceOf(FunctionNode.class, fn);
        assertEquals(8, fn.getAbsolutePosition());
    }
}
