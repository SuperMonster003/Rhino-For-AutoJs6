/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests.classsyntax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mozilla.javascript.CompilerEnvirons;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Parser;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Token;
import org.mozilla.javascript.ast.AstNode;
import org.mozilla.javascript.ast.AstRoot;
import org.mozilla.javascript.ast.ClassElement;
import org.mozilla.javascript.ast.ClassNode;
import org.mozilla.javascript.ast.ComputedPropertyKey;
import org.mozilla.javascript.ast.ExpressionStatement;
import org.mozilla.javascript.ast.FunctionNode;
import org.mozilla.javascript.ast.InfixExpression;
import org.mozilla.javascript.ast.Name;
import org.mozilla.javascript.ast.PrivateName;
import org.mozilla.javascript.ast.PropertyGet;
import org.mozilla.javascript.ast.VariableDeclaration;

/**
 * AutoJs6 class syntax (ROADMAP P1): lexing, AST shape, toSource round trips and early errors of
 * class declarations and expressions. Execution of classes is covered from P2 on.
 */
class ClassSyntaxParserTest {

    private static AstRoot parse(String source) {
        return parse(source, Context.VERSION_ECMASCRIPT, true);
    }

    private static AstRoot parse(String source, int version, boolean classSyntax) {
        try (Context cx = Context.enter()) {
            cx.setLanguageVersion(version);
            CompilerEnvirons env = new CompilerEnvirons();
            env.initFromContext(cx);
            env.setClassSyntaxEnabled(classSyntax);
            env.setErrorReporter(cx.getErrorReporter());
            return new Parser(env).parse(source, "test.js", 1);
        }
    }

    /** Returns the message of the syntax error that parsing reports. */
    private static String parseError(String source) {
        EvaluatorException e = assertThrows(EvaluatorException.class, () -> parse(source));
        return e.details();
    }

    private static String message(String key, Object... args) {
        try (Context cx = Context.enter()) {
            return ScriptRuntime.getMessageById(key, args);
        }
    }

    private static ClassNode firstClass(AstRoot root) {
        List<ClassNode> found = new ArrayList<>();
        root.visit(
                node -> {
                    if (node instanceof ClassNode && found.isEmpty()) {
                        found.add((ClassNode) node);
                    }
                    return true;
                });
        assertFalse(found.isEmpty(), "no class in " + root.toSource());
        return found.get(0);
    }

    @Test
    void declarationShape() {
        AstRoot root =
                parse(
                        "class A extends B {\n"
                                + "  constructor(x) { super(x); }\n"
                                + "  static s() {}\n"
                                + "  get g() { return 1; }\n"
                                + "  set g(v) {}\n"
                                + "  *gen() { yield 1; }\n"
                                + "  async am() {}\n"
                                + "  x = 1;\n"
                                + "  static y;\n"
                                + "  #p = 2;\n"
                                + "  #m() { return this.#p; }\n"
                                + "  static { this.y = 3; }\n"
                                + "  ['c' + 1]() {}\n"
                                + "}");
        ClassNode cls = (ClassNode) root.getFirstChild();
        assertEquals(Token.CLASS, cls.getType());
        assertTrue(cls.isStatement());
        assertFalse(cls.isAnonymous());
        assertEquals("A", cls.getClassName().getIdentifier());
        assertTrue(cls.isDerived());
        assertEquals("B", ((Name) cls.getSuperClass()).getIdentifier());

        List<ClassElement> elements = cls.getElements();
        assertEquals(12, elements.size());
        assertKind(elements.get(0), ClassElement.Kind.CONSTRUCTOR, false, "constructor");
        assertSame(elements.get(0), cls.getConstructor());
        assertKind(elements.get(1), ClassElement.Kind.METHOD, true, "s");
        assertKind(elements.get(2), ClassElement.Kind.GETTER, false, "g");
        assertKind(elements.get(3), ClassElement.Kind.SETTER, false, "g");
        assertKind(elements.get(4), ClassElement.Kind.METHOD, false, "gen");
        assertTrue(elements.get(4).getValue().isES6Generator());
        assertKind(elements.get(5), ClassElement.Kind.METHOD, false, "am");
        assertTrue(elements.get(5).getValue().isAsync());
        assertKind(elements.get(6), ClassElement.Kind.FIELD, false, "x");
        assertEquals("1", elements.get(6).getInitializer().toSource());
        assertKind(elements.get(7), ClassElement.Kind.FIELD, true, "y");
        assertNull(elements.get(7).getValue());
        assertNull(elements.get(7).getInitializer());
        assertKind(elements.get(8), ClassElement.Kind.FIELD, false, "#p");
        assertTrue(elements.get(8).isPrivate());
        assertInstanceOf(PrivateName.class, elements.get(8).getKey());
        assertKind(elements.get(9), ClassElement.Kind.METHOD, false, "#m");
        assertKind(elements.get(10), ClassElement.Kind.STATIC_BLOCK, true, null);
        assertNull(elements.get(10).getKey());
        assertNotNull(elements.get(10).getValue());
        assertKind(elements.get(11), ClassElement.Kind.METHOD, false, null);
        assertTrue(elements.get(11).isComputed());
        assertInstanceOf(ComputedPropertyKey.class, elements.get(11).getKey());

        for (ClassElement element : elements) {
            assertEquals(Token.CLASS_ELEMENT, element.getType());
            assertSame(cls, element.getParent());
            if (element.getValue() != null) {
                assertTrue(element.getValue().isInStrictMode());
                assertTrue(element.getValue().isMethod());
            }
        }
    }

    private static void assertSame(Object expected, Object actual) {
        org.junit.jupiter.api.Assertions.assertSame(expected, actual);
    }

    private static void assertKind(
            ClassElement element, ClassElement.Kind kind, boolean isStatic, String name) {
        assertEquals(kind, element.getKind(), element.toSource());
        assertEquals(isStatic, element.isStatic(), element.toSource());
        assertEquals(name, element.getPropertyName(), element.toSource());
    }

    @Test
    void privateMemberAccessAndBrandCheck() {
        ClassNode cls = firstClass(parse("class C { #x; m(o) { return o.#x + (#x in o); } }"));
        List<AstNode> privateNames = new ArrayList<>();
        cls.visit(
                node -> {
                    if (node instanceof PrivateName) {
                        privateNames.add(node);
                    }
                    return true;
                });
        assertEquals(3, privateNames.size());
        AstNode access = privateNames.get(1).getParent();
        assertInstanceOf(PropertyGet.class, access);
        AstNode brandCheck = privateNames.get(2).getParent();
        assertInstanceOf(InfixExpression.class, brandCheck);
        assertEquals(Token.IN, brandCheck.getType());
        assertEquals("#x", privateNames.get(2).toSource());
    }

    @Test
    void classExpressionIsAnonymousTarget() {
        AstRoot root = parse("var C = class {}; var D = class Named extends Object {};");
        ClassNode anonymous = firstClass(root);
        assertTrue(anonymous.isAnonymous());
        assertFalse(anonymous.isStatement());
        assertNull(anonymous.getSuperClass());
        assertNull(anonymous.getInferredName());
        VariableDeclaration second = (VariableDeclaration) root.getLastChild();
        ClassNode named = (ClassNode) second.getVariables().get(0).getInitializer();
        assertEquals("Named", named.getClassName().getIdentifier());
        assertTrue(named.isDerived());
    }

    @Test
    void contextualKeywordsStayIdentifiers() {
        parse(
                "var extends1 = 1, get = 2, set = 3, async = 4, static1 = 5, await = 6;\n"
                        + "var o = { class: 1, extends: 2, static: 3, get: 4, set: 5, await: 6 };\n"
                        + "o.class + o.await;");
        ClassNode cls =
                firstClass(
                        parse(
                                "class C { static() {} get() {} set() {} async() {} await() {}"
                                        + " static static() {} get\n x() {} static\n y; }"));
        List<ClassElement> elements = cls.getElements();
        assertKind(elements.get(0), ClassElement.Kind.METHOD, false, "static");
        assertKind(elements.get(1), ClassElement.Kind.METHOD, false, "get");
        assertKind(elements.get(2), ClassElement.Kind.METHOD, false, "set");
        assertKind(elements.get(3), ClassElement.Kind.METHOD, false, "async");
        assertKind(elements.get(4), ClassElement.Kind.METHOD, false, "await");
        assertKind(elements.get(5), ClassElement.Kind.METHOD, true, "static");
        assertKind(elements.get(6), ClassElement.Kind.GETTER, false, "x");
        assertKind(elements.get(7), ClassElement.Kind.FIELD, true, "y");
    }

    @Test
    void fieldsUseAutomaticSemicolonInsertion() {
        ClassNode cls = firstClass(parse("class C {\n a\n b = 1\n get\n *g() {}\n static\n}"));
        List<ClassElement> elements = cls.getElements();
        assertEquals(5, elements.size());
        assertKind(elements.get(0), ClassElement.Kind.FIELD, false, "a");
        assertKind(elements.get(1), ClassElement.Kind.FIELD, false, "b");
        assertKind(elements.get(2), ClassElement.Kind.FIELD, false, "get");
        assertKind(elements.get(3), ClassElement.Kind.METHOD, false, "g");
        assertKind(elements.get(4), ClassElement.Kind.FIELD, false, "static");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "class A {}",
                "class A extends B {}",
                "var C = class {};",
                "class A {\n  constructor(a, b) {\n    super.m(a);\n  }\n}",
                "class A {\n  static #x = 1;\n  static get #y() {\n    return A.#x;\n  }\n}",
                "class A {\n  async *g() {\n    yield await 1;\n  }\n}",
                "class A {\n  static {\n    var x = new.target;\n  }\n}",
                "class A {\n  'quoted'() {\n  }\n  42 = 1;\n  [Symbol.iterator]() {\n  }\n}"
            })
    void toSourceRoundTrip(String source) {
        String once = parse(source).toSource();
        String twice = parse(once).toSource();
        assertEquals(once, twice);
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "class A { constructor() {} constructor() {} }|msg.class.dup.ctor",
                "class A { get constructor() {} }|msg.class.ctor.special",
                "class A { set constructor(v) {} }|msg.class.ctor.special",
                "class A { *constructor() {} }|msg.class.ctor.special",
                "class A { async constructor() {} }|msg.class.ctor.special",
                "class A { constructor = 1 }|msg.class.field.ctor",
                "class A { 'constructor'; }|msg.class.field.ctor",
                "class A { static prototype() {} }|msg.class.static.prototype",
                "class A { static prototype = 1 }|msg.class.static.prototype",
                "class A { #constructor() {} }|msg.class.private.ctor",
                "class A { m() { super(); } }|msg.class.super.call",
                "class A { constructor() { super(); } }|msg.class.super.call",
                "class A extends B { x = super(); }|msg.class.super.call",
                "class A extends B { static { super(); } }|msg.class.super.call",
                "class A { x = arguments; }|msg.class.field.arguments",
                "class A { x = () => arguments; }|msg.class.field.arguments",
                "class A { static { arguments; } }|msg.class.field.arguments",
                "class A { get g(a) {} }|msg.class.getter.parms",
                "class A { set s() {} }|msg.class.setter.parms",
                "class A { set s(...v) {} }|msg.class.setter.parms",
                "class A { m({a}) { 'use strict'; } }|msg.class.strict.parms",
                "class A { x y }|msg.class.field.semi",
                "class A { static { return; } }|msg.bad.return",
                "if (1) class A {}|msg.class.decl.not.in.block",
                "label: class A {}|msg.class.decl.not.in.block",
                "with ({}) class A {}|msg.class.decl.not.in.block",
                "class {}|msg.class.name.missing",
                "class A { x = { arguments }; }|msg.class.field.arguments",
                "var o = { #m() {} };|msg.bad.prop",
                "var o = { *#m() {} };|msg.bad.prop",
                "class A { m() { delete this.#x; } #x; }|msg.class.private.delete",
                "class A extends B { #x; m() { super.#x; } }|msg.class.private.super"
            })
    void earlyErrors(String source, String key) {
        String expected = message(key);
        assertEquals(expected, parseError(source));
    }

    @Test
    void barePrivateName() {
        assertEquals(
                message("msg.class.private.unexpected", "#x"),
                parseError("class A { #x; m() { #x; } }"));
    }

    @Test
    void argumentsAsPropertyNameIsAllowed() {
        parse("class A { x = this.arguments; y = a.arguments; z = { arguments: 1 }; }");
        parse("class A { static { this.arguments; arguments: ; } }");
        parse(
                "class A { x = function () { return arguments; }; static { (function () { arguments; }); } }");
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "class A { m() { return this.#x; } }|#x",
                "this.#y;|#y",
                "class A { #a; m() { class B { n() { this.#b; } } } }|#b",
                "class A extends (o.#x) { #x; }|#x"
            })
    void undeclaredPrivateNames(String source, String name) {
        assertEquals(message("msg.class.private.undeclared", name), parseError(source));
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "class A { #x; #x; }|#x",
                "class A { #x; #x() {} }|#x",
                "class A { get #x() {} get #x() {} }|#x",
                "class A { get #x() {} static set #x(v) {} }|#x"
            })
    void duplicatePrivateNames(String source, String name) {
        assertEquals(message("msg.class.private.dup", name), parseError(source));
    }

    @Test
    void validPrivateNamesAndNesting() {
        parse("class A { get #x() {} set #x(v) {} static get #y() {} static set #y(v) {} }");
        parse("class A { #x; m() { class B { n(o) { return o.#x; } } } }");
        parse("class A { #x; static is(o) { return #x in o; } m() { return this?.#x; } }");
        parse("class A { #\\u0061; m() { return this.#a; } }");
    }

    @Test
    void classBodyIsStrict() {
        assertEquals(message("msg.bad.id.strict", "eval"), parseError("class A { m(eval) {} }"));
        assertEquals(message("msg.reserved.id", "yield"), parseError("class yield {}"));
        assertEquals(message("msg.reserved.id", "let"), parseError("class let {}"));
        assertEquals(message("msg.bad.id.strict", "arguments"), parseError("class arguments {}"));
        // Outside the class the code stays sloppy.
        parse("class A {} var eval1 = 1; with ({}) {}");
    }

    @Test
    void superAndNewTargetInClassCode() {
        parse("class A extends B { constructor() { super(); () => super(); } }");
        parse("class A extends B { constructor(x = super()) {} }");
        parse("class A { m() { return super.m(); } x = super.x; static { super.y; } }");
        parse("class A { x = new.target; static { new.target; } m() { new.target; } }");
        // Outside class code the old behavior is kept.
        parse("({ m() { return super.m(); } })");
    }

    @Test
    void lexerGate() {
        // Below ES6, or with the switch off, `class` stays a reserved word and `#` illegal.
        EvaluatorException oldVersion =
                assertThrows(
                        EvaluatorException.class,
                        () -> parse("class A {}", Context.VERSION_1_8, true));
        assertNotNull(oldVersion.details());
        EvaluatorException off =
                assertThrows(
                        EvaluatorException.class,
                        () -> parse("class A {}", Context.VERSION_ECMASCRIPT, false));
        assertEquals(message("msg.reserved.id", "class"), off.details());
        // An escaped `class` never starts a class but may name a property.
        parse("var o = { cl\\u0061ss: 1 }; o.cl\\u0061ss;");
        assertEquals(message("msg.reserved.id", "class"), parseError("cl\\u0061ss A {}"));
        // A private name needs a class and an adjacent identifier.
        assertEquals(message("msg.illegal.character", "#"), parseError("class A { # x; }"));
    }

    @Test
    void classesAreNotExecutableYet() {
        try (Context cx = Context.enter()) {
            cx.setLanguageVersion(Context.VERSION_ECMASCRIPT);
            EvaluatorException e =
                    assertThrows(
                            EvaluatorException.class,
                            () -> cx.compileString("class A {}", "test.js", 1, null));
            assertEquals(ScriptRuntime.getMessageById("msg.class.not.supported"), e.details());
        }
    }

    @Test
    void statementsAroundClassesStillParse() {
        AstRoot root = parse("var a = 1;\nclass A {}\na++;");
        assertEquals(3, countStatements(root));
        assertInstanceOf(ExpressionStatement.class, root.getLastChild());
        FunctionNode fn = firstClass(parse("class A { m() {} }")).getElements().get(0).getValue();
        assertEquals(FunctionNode.FUNCTION_EXPRESSION, fn.getFunctionType());
    }

    private static int countStatements(AstRoot root) {
        int count = 0;
        for (org.mozilla.javascript.Node ignored : root) {
            count++;
        }
        return count;
    }
}
