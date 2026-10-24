/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.mozilla.javascript.ast.AstNode;
import org.mozilla.javascript.ast.Block;
import org.mozilla.javascript.ast.ClassElement;
import org.mozilla.javascript.ast.ClassNode;
import org.mozilla.javascript.ast.ComputedPropertyKey;
import org.mozilla.javascript.ast.ExpressionStatement;
import org.mozilla.javascript.ast.FunctionNode;
import org.mozilla.javascript.ast.Name;
import org.mozilla.javascript.ast.ParenthesizedExpression;
import org.mozilla.javascript.ast.PrivateName;
import org.mozilla.javascript.ast.PropertyGet;
import org.mozilla.javascript.ast.ReturnStatement;
import org.mozilla.javascript.ast.StringLiteral;
import org.mozilla.javascript.ast.Symbol;
import org.mozilla.javascript.ast.UnaryExpression;

/**
 * AutoJs6 class syntax: parsing of class declarations and class expressions, private names, and the
 * early errors that depend on the class context (ES2022, sections 15.7 and 13.1).
 *
 * <p>{@link Parser} and {@link TokenStream} only keep one-line hooks into this class; they are
 * listed in {@code docs/autojs6/class-syntax/HOOKS.md}. Class syntax needs language version {@link
 * Context#VERSION_ES6} or above and {@link CompilerEnvirons#isClassSyntaxEnabled()}; otherwise
 * {@code class} stays a reserved word and {@code #} an illegal character, as before.
 *
 * <p>The parser keeps a stack with the kind of every function being parsed (pushed and popped by
 * {@code Parser.PerFunctionVariables}), so that {@code super()}, {@code arguments} and {@code
 * await} can be checked against the nearest enclosing non-arrow function.
 */
final class ClassParser {

    /** A function that is not a class element (functions, object literal methods). */
    static final int FN_PLAIN = 0;

    /** An arrow function: transparent for {@code super}, {@code arguments} and new.target. */
    static final int FN_ARROW = 1;

    /** A class method, getter or setter (static or not). */
    static final int FN_METHOD = 2;

    /** The constructor of a base class. */
    static final int FN_BASE_CTOR = 3;

    /** The constructor of a derived class: the only place where {@code super()} is allowed. */
    static final int FN_DERIVED_CTOR = 4;

    /** The synthetic function that holds a field initializer. */
    static final int FN_FIELD = 5;

    /** The synthetic function that holds a static block. */
    static final int FN_STATIC_BLOCK = 6;

    // Bits of a declared private name, see declarePrivate().
    private static final int PRIVATE_FIELD = 1;
    private static final int PRIVATE_METHOD = 2;
    private static final int PRIVATE_GETTER = 4;
    private static final int PRIVATE_SETTER = 8;
    private static final int PRIVATE_STATIC = 16;

    private final Parser parser;
    private int[] kinds = new int[16];
    private int depth;
    private int pendingKind = -1;
    private boolean pendingGenerator;
    private PrivateScope privateScope;

    /** The private names declared by one class body, and the references found inside it. */
    private static final class PrivateScope {
        final PrivateScope outer;
        final Map<String, Integer> declared = new HashMap<>();
        final List<PrivateName> references = new ArrayList<>();

        PrivateScope(PrivateScope outer) {
            this.outer = outer;
        }
    }

    ClassParser(Parser parser) {
        this.parser = parser;
    }

    /** Returns whether class syntax is parsed with the given environment (D4 gate). */
    static boolean isEnabled(CompilerEnvirons env) {
        return env.isClassSyntaxEnabled() && env.getLanguageVersion() >= Context.VERSION_ES6;
    }

    // ------------------------------------------------------------------
    // Lexer hooks (TokenStream)
    // ------------------------------------------------------------------

    /**
     * Maps the reserved word {@code class} to {@link Token#CLASS} when class syntax is enabled. An
     * escaped {@code class} stays {@link Token#RESERVED}: it may name a property but never start a
     * class.
     */
    static int keyword(Parser parser, int token) {
        if (token == Token.RESERVED
                && !parser.ts.wordEscaped
                && "class".equals(parser.ts.getString())
                && isEnabled(parser.compilerEnv)) {
            return Token.CLASS;
        }
        return token;
    }

    /**
     * Scans a private name after {@code #}: an identifier name (keywords included) that directly
     * follows the {@code #}. Returns {@link Token#PRIVATE_NAME} with the identifier as the token
     * string and the token bounds covering the {@code #}.
     */
    static int privateName(Parser parser) throws IOException {
        TokenStream ts = parser.ts;
        int hashPos = ts.tokenBeg;
        if (!isEnabled(parser.compilerEnv)) {
            parser.addError("msg.illegal.character", '#');
            return Token.ERROR;
        }
        ts.getToken();
        if (ts.tokenBeg != hashPos + 1 || ts.wordBeg != ts.tokenBeg) {
            parser.addError("msg.illegal.character", '#');
            return Token.ERROR;
        }
        ts.tokenBeg = hashPos;
        return Token.PRIVATE_NAME;
    }

    // ------------------------------------------------------------------
    // Function kind stack (Parser.PerFunctionVariables)
    // ------------------------------------------------------------------

    void enterFunction(FunctionNode fn) {
        int kind = pendingKind;
        pendingKind = -1;
        if (pendingGenerator) {
            // Known before the body is parsed, so that `return value` is allowed with yield.
            pendingGenerator = false;
            fn.setIsES6Generator();
        }
        if (kind < 0) {
            kind = fn.getFunctionType() == FunctionNode.ARROW_FUNCTION ? FN_ARROW : FN_PLAIN;
        }
        if (depth == kinds.length) {
            int[] grown = new int[depth * 2];
            System.arraycopy(kinds, 0, grown, 0, depth);
            kinds = grown;
        }
        kinds[depth++] = kind;
    }

    void exitFunction() {
        if (depth > 0) {
            depth--;
        }
    }

    /** Returns the kind of the innermost function being parsed, or -1 at script level. */
    int currentKind() {
        return depth == 0 ? -1 : kinds[depth - 1];
    }

    /** Returns the kind of the nearest enclosing non-arrow function, or -1 at script level. */
    private int nearestNonArrowKind() {
        for (int i = depth - 1; i >= 0; i--) {
            if (kinds[i] != FN_ARROW) {
                return kinds[i];
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // Expression hooks (Parser)
    // ------------------------------------------------------------------

    /**
     * Checks a {@code super} keyword that was just consumed: a following {@code (} makes it a
     * {@code super()} call, which is only valid in the constructor of a derived class (arrow
     * functions are transparent). Outside class code the old behavior is kept.
     */
    void checkSuper() throws IOException {
        if (parser.peekToken() != Token.LP) {
            return;
        }
        switch (nearestNonArrowKind()) {
            case FN_METHOD:
            case FN_BASE_CTOR:
            case FN_FIELD:
            case FN_STATIC_BLOCK:
                parser.reportError("msg.class.super.call");
                break;
            default:
                break;
        }
    }

    /** Rejects an {@code arguments} reference in field initializers and static blocks. */
    void checkArguments(String name) {
        if (!"arguments".equals(name)) {
            return;
        }
        int kind = nearestNonArrowKind();
        if (kind == FN_FIELD || kind == FN_STATIC_BLOCK) {
            parser.reportError("msg.class.field.arguments");
        }
    }

    /** Rejects {@code delete} of a private member, also when it is parenthesized. */
    void checkDelete(AstNode node) {
        AstNode operand = ((UnaryExpression) node).getOperand();
        while (operand instanceof ParenthesizedExpression) {
            operand = ((ParenthesizedExpression) operand).getExpression();
        }
        if (operand instanceof PropertyGet
                && ((PropertyGet) operand).getProperty() instanceof PrivateName) {
            parser.reportError("msg.class.private.delete");
        }
    }

    /**
     * Parses the right side of {@code .} or {@code ?.} when it is a private name: the current token
     * is {@link Token#PRIVATE_NAME}, already consumed.
     */
    AstNode privateMember(AstNode target) {
        PrivateName ref = currentPrivateName();
        if (target.getType() == Token.SUPER) {
            parser.reportError("msg.class.private.super");
        }
        referencePrivate(ref);
        return ref;
    }

    /**
     * Parses the left operand of a relational expression: either a private name, which must be
     * followed by {@code in} ({@code #x in o}), or a shift expression.
     */
    AstNode relationalOperand() throws IOException {
        if (parser.peekToken() != Token.PRIVATE_NAME) {
            return parser.shiftExpr();
        }
        parser.consumeToken();
        PrivateName ref = currentPrivateName();
        referencePrivate(ref);
        if (parser.peekToken() != Token.IN || parser.inForInit) {
            parser.reportError("msg.class.private.unexpected", "#" + ref.getIdentifier());
        }
        return ref;
    }

    private PrivateName currentPrivateName() {
        TokenStream ts = parser.ts;
        PrivateName name =
                new PrivateName(
                        ts.tokenBeg, ts.tokenEnd - ts.tokenBeg, privateIdentifier(ts.getString()));
        name.setLineColumnNumber(parser.lineNumber(), parser.columnNumber());
        return name;
    }

    /**
     * Returns the identifier of a private name with ZWNJ and ZWJ removed: the lexer drops these
     * format characters when they are written literally but keeps them when they are escaped, so
     * both spellings must name the same private member.
     */
    private static String privateIdentifier(String id) {
        if (id.indexOf('\u200C') < 0 && id.indexOf('\u200D') < 0) {
            return id;
        }
        return id.replace("\u200C", "").replace("\u200D", "");
    }

    private void referencePrivate(PrivateName ref) {
        if (privateScope == null) {
            reportUndeclared(ref);
        } else {
            privateScope.references.add(ref);
        }
    }

    private void reportUndeclared(PrivateName ref) {
        parser.reportError(
                "msg.class.private.undeclared",
                "#" + ref.getIdentifier(),
                ref.getAbsolutePosition(),
                ref.getLength());
    }

    // ------------------------------------------------------------------
    // Class declarations and expressions
    // ------------------------------------------------------------------

    /** Parses a class declaration; the current token is {@link Token#CLASS}, not consumed. */
    AstNode declaration() throws IOException {
        if (parser.inSingleStatementContext()) {
            parser.reportError("msg.class.decl.not.in.block");
        }
        return parseClass(true);
    }

    /**
     * Rejects a class or async function declaration as the body of {@code with} or of a labeled
     * statement, where only statements are allowed (if, loops and else are caught by {@link
     * #declaration()} and {@link AsyncParser#declaration()} themselves).
     */
    void checkStatementPosition(AstNode statement) {
        if (statement instanceof ClassNode && ((ClassNode) statement).isStatement()) {
            parser.reportError(
                    "msg.class.decl.not.in.block", statement.getPosition(), statement.getLength());
        } else if (statement instanceof FunctionNode && ((FunctionNode) statement).isAsync()) {
            parser.reportError(
                    "msg.async.decl.not.in.block", statement.getPosition(), statement.getLength());
        }
    }

    /** Parses a class expression; the current token is {@link Token#CLASS}, not consumed. */
    AstNode expression() throws IOException {
        return parseClass(false);
    }

    private ClassNode parseClass(boolean isStatement) throws IOException {
        TokenStream ts = parser.ts;
        parser.consumeToken();
        int pos = ts.tokenBeg;
        ClassNode cls = new ClassNode(pos);
        cls.setLineColumnNumber(parser.lineNumber(), parser.columnNumber());
        cls.setIsStatement(isStatement);

        // All parts of a class are strict mode code, the name included.
        boolean savedStrict = parser.inUseStrictDirective;
        boolean savedInForInit = parser.inForInit;
        parser.inUseStrictDirective = true;
        parser.inForInit = false;
        try {
            int tt = parser.peekToken();
            boolean isWord = ts.wordBeg == ts.tokenBeg;
            if (tt == Token.NAME || tt == Token.UNDEFINED) {
                parser.consumeToken();
                Name name = parser.createNameNode(false, Token.NAME);
                String id = name.getIdentifier();
                if ("eval".equals(id) || "arguments".equals(id)) {
                    parser.reportError("msg.bad.id.strict", id);
                }
                cls.setClassName(name);
                if (isStatement) {
                    parser.defineSymbol(Token.LET, id);
                }
            } else if (isWord && !isExtends()) {
                parser.reportError("msg.reserved.id", ts.getString());
            } else if (isStatement) {
                parser.reportError("msg.class.name.missing");
            }

            parser.pushScope(cls);
            try {
                if (cls.getClassName() != null) {
                    String id = cls.getClassName().getIdentifier();
                    cls.putSymbol(new Symbol(Symbol.Type.CONST, id));
                }
                if (isExtends()) {
                    parser.consumeToken();
                    cls.setExtendsPosition(ts.tokenBeg - pos);
                    cls.setSuperClass(parser.memberExpr(true));
                }
                if (mustMatch(Token.LC, "msg.class.no.brace")) {
                    cls.setLc(ts.tokenBeg - pos);
                }

                PrivateScope scope = new PrivateScope(privateScope);
                privateScope = scope;
                try {
                    classBody(cls);
                } finally {
                    privateScope = scope.outer;
                }
                resolvePrivateReferences(scope);
            } finally {
                parser.popScope();
            }

            int end = ts.tokenEnd;
            if (mustMatch(Token.RC, "msg.class.no.brace.after")) {
                end = ts.tokenEnd;
            }
            cls.setLength(end - pos);
            checkLegacyGenerators(cls);
        } finally {
            parser.inUseStrictDirective = savedStrict;
            parser.inForInit = savedInForInit;
        }
        return cls;
    }

    /** Consumes the next token (skipping comments) if it is the given one. */
    private boolean match(int token) throws IOException {
        int tt = parser.peekToken();
        while (tt == Token.COMMENT) {
            parser.consumeToken();
            tt = parser.peekToken();
        }
        if (tt != token) {
            return false;
        }
        parser.consumeToken();
        return true;
    }

    private boolean mustMatch(int token, String messageId) throws IOException {
        if (match(token)) {
            return true;
        }
        TokenStream ts = parser.ts;
        parser.reportError(messageId, ts.tokenBeg, ts.tokenEnd - ts.tokenBeg);
        return false;
    }

    /** Returns whether the peeked token is the unescaped keyword {@code extends}. */
    private boolean isExtends() throws IOException {
        int tt = parser.peekToken();
        return tt == Token.RESERVED && isPeekedWord("extends");
    }

    /**
     * Returns whether the peeked token is the given word, spelled without escapes. Only valid right
     * after {@link Parser#peekToken()}, while the peeked token is the last one scanned.
     */
    private boolean isPeekedWord(String word) {
        TokenStream ts = parser.ts;
        return ts.wordBeg == ts.tokenBeg && !ts.wordEscaped && word.equals(ts.getString());
    }

    private boolean peekWord(String word) throws IOException {
        int tt = parser.peekToken();
        return (tt == Token.NAME || tt == Token.RESERVED) && isPeekedWord(word);
    }

    private void classBody(ClassNode cls) throws IOException {
        boolean hasConstructor = false;
        for (; ; ) {
            int tt = parser.peekToken();
            if (tt == Token.SEMI || tt == Token.COMMENT) {
                parser.consumeToken();
                continue;
            }
            if (tt == Token.RC || tt == Token.EOF || tt == Token.ERROR) {
                break;
            }
            ClassElement element = element(cls);
            if (element.getKind() == ClassElement.Kind.CONSTRUCTOR) {
                if (hasConstructor) {
                    parser.reportError(
                            "msg.class.dup.ctor",
                            element.getAbsolutePosition(),
                            element.getLength());
                }
                hasConstructor = true;
            }
            cls.addElement(element);
        }
    }

    // Tokens after a modifier word that make the word itself the element name.
    private static boolean endsElementName(int tt) {
        return tt == Token.LP
                || tt == Token.ASSIGN
                || tt == Token.SEMI
                || tt == Token.RC
                || tt == Token.EOF;
    }

    private ClassElement element(ClassNode cls) throws IOException {
        TokenStream ts = parser.ts;
        parser.peekToken();
        int start = ts.tokenBeg;
        ClassElement element = new ClassElement(start);
        element.setLineColumnNumber(ts.getTokenStartLineno(), ts.getTokenColumn());
        AstNode key = null;
        boolean isStatic = false;

        if (peekWord("static")) {
            parser.consumeToken();
            Name word = wordName();
            int tt = parser.peekToken();
            if (tt == Token.LC) {
                return staticBlock(element, start);
            }
            if (endsElementName(tt)) {
                key = word;
            } else {
                isStatic = true;
            }
        }

        int definitionStart = start;
        if (isStatic) {
            parser.peekToken();
            definitionStart = ts.tokenBeg;
        }
        boolean isAsync = false;
        boolean isGenerator = false;
        ClassElement.Kind kind = ClassElement.Kind.METHOD;

        if (key == null && peekWord("async")) {
            parser.consumeToken();
            Name word = wordName();
            int flagged = parser.peekFlaggedToken();
            if ((flagged & Parser.TI_AFTER_EOL) == 0
                    && !endsElementName(flagged & Parser.CLEAR_TI_MASK)
                    && AsyncParser.isEnabled(parser.compilerEnv)) {
                isAsync = true;
            } else {
                key = word;
            }
        }
        if (key == null && parser.peekToken() == Token.MUL) {
            parser.consumeToken();
            isGenerator = true;
        }
        if (key == null && !isAsync && !isGenerator && (peekWord("get") || peekWord("set"))) {
            boolean isGetter = "get".equals(ts.getString());
            parser.consumeToken();
            Name word = wordName();
            int tt = parser.peekToken();
            if (endsElementName(tt) || tt == Token.MUL) {
                key = word;
            } else {
                kind = isGetter ? ClassElement.Kind.GETTER : ClassElement.Kind.SETTER;
            }
        }
        if (key == null) {
            key = elementName();
        }
        int end = key.getPosition() + key.getLength();
        element.setKey(key);
        element.setIsStatic(isStatic);

        String propertyName = propertyName(key);
        if (key instanceof PrivateName) {
            declarePrivate((PrivateName) key, kind, isStatic, parser.peekToken() != Token.LP);
        }

        if (parser.peekToken() == Token.LP) {
            if (!isStatic && "constructor".equals(propertyName)) {
                if (kind != ClassElement.Kind.METHOD || isAsync || isGenerator) {
                    parser.reportError(
                            "msg.class.ctor.special", key.getAbsolutePosition(), key.getLength());
                }
                kind = ClassElement.Kind.CONSTRUCTOR;
            }
            if (isStatic && "prototype".equals(propertyName)) {
                parser.reportError(
                        "msg.class.static.prototype", key.getAbsolutePosition(), key.getLength());
            }
            element.setKind(kind);
            int fnKind = FN_METHOD;
            if (kind == ClassElement.Kind.CONSTRUCTOR) {
                fnKind = cls.isDerived() ? FN_DERIVED_CTOR : FN_BASE_CTOR;
            }
            FunctionNode fn = method(kind, fnKind, isAsync, isGenerator);
            fn.setRawSourceStart(definitionStart);
            element.setValue(fn);
            end = fn.getPosition() + fn.getLength();
        } else {
            if (isAsync || isGenerator || kind != ClassElement.Kind.METHOD) {
                parser.reportError("msg.no.paren.parms");
            }
            element.setKind(ClassElement.Kind.FIELD);
            if ("constructor".equals(propertyName)) {
                parser.reportError(
                        "msg.class.field.ctor", key.getAbsolutePosition(), key.getLength());
            }
            if (isStatic && "prototype".equals(propertyName)) {
                parser.reportError(
                        "msg.class.static.prototype", key.getAbsolutePosition(), key.getLength());
            }
            if (match(Token.ASSIGN)) {
                FunctionNode init = fieldInitializer();
                element.setValue(init);
                end = init.getPosition() + init.getLength();
            }
            int flagged = parser.peekFlaggedToken();
            int tt = flagged & Parser.CLEAR_TI_MASK;
            if (tt == Token.SEMI) {
                parser.consumeToken();
                end = ts.tokenEnd;
            } else if (tt != Token.RC && (flagged & Parser.TI_AFTER_EOL) == 0) {
                parser.reportError("msg.class.field.semi");
            }
        }
        element.setLength(end - start);
        return element;
    }

    /** Creates a name node for the word token that was just consumed. */
    private Name wordName() {
        TokenStream ts = parser.ts;
        Name name = new Name(ts.tokenBeg, ts.tokenEnd - ts.tokenBeg, ts.getString());
        name.setLineColumnNumber(parser.lineNumber(), parser.columnNumber());
        return name;
    }

    /**
     * Parses a class element name: a private name, an identifier name (keywords included), a string
     * or numeric literal, or a computed key.
     */
    private AstNode elementName() throws IOException {
        TokenStream ts = parser.ts;
        int tt = parser.peekToken();
        if (tt == Token.PRIVATE_NAME) {
            parser.consumeToken();
            return currentPrivateName();
        }
        boolean isWord = ts.wordBeg == ts.tokenBeg;
        if (tt != Token.NAME
                && tt != Token.STRING
                && tt != Token.NUMBER
                && tt != Token.BIGINT
                && tt != Token.LB
                && !isWord) {
            parser.reportError("msg.bad.prop");
        }
        AstNode key = parser.objliteralProperty();
        if (key == null) {
            parser.reportError("msg.bad.prop");
            return parser.makeErrorNode();
        }
        parser.consumeToken();
        if (key instanceof Name) {
            key.setLineColumnNumber(parser.lineNumber(), parser.columnNumber());
        }
        return key;
    }

    /** Returns the property name of a non-computed, non-private key, or {@code null}. */
    private static String propertyName(AstNode key) {
        if (key instanceof PrivateName || key instanceof ComputedPropertyKey) {
            return null;
        } else if (key instanceof Name) {
            return ((Name) key).getIdentifier();
        } else if (key instanceof StringLiteral) {
            return ((StringLiteral) key).getValue();
        }
        return null;
    }

    private FunctionNode method(
            ClassElement.Kind kind, int fnKind, boolean isAsync, boolean isGenerator)
            throws IOException {
        FunctionNode fn;
        pendingKind = fnKind;
        pendingGenerator = isGenerator;
        if (isAsync) {
            parser.asyncSyntax.setPending();
        }
        try {
            fn = parser.function(FunctionNode.FUNCTION_EXPRESSION, true);
        } finally {
            pendingKind = -1;
            pendingGenerator = false;
            parser.asyncSyntax.clearPending();
        }
        checkStrictParameters(fn);
        Name name = fn.getFunctionName();
        if (name != null && name.length() != 0) {
            parser.reportError("msg.bad.prop");
        }
        fn.setInStrictMode(true);
        switch (kind) {
            case GETTER:
                fn.setFunctionIsGetterMethod();
                if (!fn.getParams().isEmpty()) {
                    parser.reportError("msg.class.getter.parms", fn.getPosition(), fn.getLength());
                }
                break;
            case SETTER:
                fn.setFunctionIsSetterMethod();
                if (fn.getParams().size() != 1 || fn.hasRestParameter()) {
                    parser.reportError("msg.class.setter.parms", fn.getPosition(), fn.getLength());
                }
                break;
            default:
                fn.setFunctionIsNormalMethod();
                if (isGenerator) {
                    fn.setIsES6Generator();
                }
                if (kind == ClassElement.Kind.METHOD) {
                    fn.setIsShorthand();
                }
                break;
        }
        return fn;
    }

    /**
     * Parses a field initializer after {@code =} into a synthetic method whose expression-closure
     * body returns the initializer. The initializer sees {@code this} and {@code super} like a
     * method, but neither {@code arguments} nor {@code await}.
     */
    private FunctionNode fieldInitializer() throws IOException {
        TokenStream ts = parser.ts;
        FunctionNode fn = new FunctionNode(ts.tokenEnd);
        fn.setFunctionType(FunctionNode.FUNCTION_EXPRESSION);
        fn.setMethodDefinition(true);
        fn.setFunctionIsNormalMethod();
        fn.setInStrictMode(true);
        fn.setSourceName(parser.sourceURI);

        Parser.PerFunctionVariables savedVars;
        pendingKind = FN_FIELD;
        try {
            savedVars = parser.createPerFunctionVariables(fn);
        } finally {
            pendingKind = -1;
        }
        boolean wasInsideMethod = parser.insideMethod;
        parser.insideMethod = true;
        ++parser.nestingOfFunction;
        try {
            AstNode value = parser.assignExpr();
            ReturnStatement ret =
                    new ReturnStatement(value.getPosition(), value.getLength(), value);
            ret.putProp(Node.EXPRESSION_CLOSURE_PROP, Boolean.TRUE);
            ret.setLineColumnNumber(value.getLineno(), value.getColumn());
            Block body = new Block(value.getPosition(), value.getLength());
            body.putProp(Node.EXPRESSION_CLOSURE_PROP, Boolean.TRUE);
            body.setLineColumnNumber(value.getLineno(), value.getColumn());
            body.addStatement(ret);
            fn.setBody(body);
            fn.setLineColumnNumber(value.getLineno(), value.getColumn());
            fn.setEndLineno(parser.lineNumber());
        } finally {
            --parser.nestingOfFunction;
            parser.insideMethod = wasInsideMethod;
            savedVars.restore();
        }
        checkGeneratorUse(fn);
        return fn;
    }

    /** Parses a static block; {@code static} is consumed and the opening brace is peeked. */
    private ClassElement staticBlock(ClassElement element, int start) throws IOException {
        TokenStream ts = parser.ts;
        FunctionNode fn = new FunctionNode(ts.tokenBeg);
        fn.setFunctionType(FunctionNode.FUNCTION_EXPRESSION);
        fn.setMethodDefinition(true);
        fn.setFunctionIsNormalMethod();
        fn.setInStrictMode(true);
        fn.setSourceName(parser.sourceURI);
        fn.setLineColumnNumber(ts.getLineno(), ts.getTokenColumn());

        Parser.PerFunctionVariables savedVars;
        pendingKind = FN_STATIC_BLOCK;
        try {
            savedVars = parser.createPerFunctionVariables(fn);
        } finally {
            pendingKind = -1;
        }
        boolean wasInsideMethod = parser.insideMethod;
        parser.insideMethod = true;
        try {
            AstNode body = parser.parseFunctionBody(FunctionNode.FUNCTION_EXPRESSION, fn);
            fn.setBody(body);
            fn.setEndLineno(parser.lineNumber());
        } finally {
            parser.insideMethod = wasInsideMethod;
            savedVars.restore();
        }
        checkGeneratorUse(fn);
        fn.getBody()
                .visit(
                        node -> {
                            if (node instanceof FunctionNode) {
                                return false;
                            }
                            if (node instanceof ReturnStatement) {
                                parser.reportError(
                                        "msg.bad.return",
                                        node.getAbsolutePosition(),
                                        node.getLength());
                            }
                            return true;
                        });

        element.setKind(ClassElement.Kind.STATIC_BLOCK);
        element.setIsStatic(true);
        element.setValue(fn);
        element.setLength(ts.tokenEnd - start);
        return element;
    }

    /**
     * Rejects a "use strict" directive in a method with non-simple parameters (the parser checks
     * defaults and rest parameters but not destructuring), and a default value on a rest parameter.
     */
    private void checkStrictParameters(FunctionNode fn) {
        List<AstNode> params = fn.getParams();
        boolean simple = fn.getDefaultParams() == null && !fn.hasRestParameter();
        for (AstNode param : params) {
            if (!(param instanceof Name)) {
                simple = false;
            }
        }
        if (fn.hasRestParameter() && fn.getDefaultParams() != null && !params.isEmpty()) {
            AstNode rest = params.get(params.size() - 1);
            List<Object> defaults = fn.getDefaultParams();
            for (int i = 0; i + 1 < defaults.size(); i += 2) {
                if (rest instanceof Name && ((Name) rest).getIdentifier().equals(defaults.get(i))) {
                    parser.reportError("msg.parm.after.rest", rest.getPosition(), rest.getLength());
                }
            }
        }
        if (simple || !(fn.getBody() instanceof Block)) {
            return;
        }
        for (Node statement : fn.getBody()) {
            if (!(statement instanceof ExpressionStatement)
                    || !(((ExpressionStatement) statement).getExpression()
                            instanceof StringLiteral)) {
                break;
            }
            StringLiteral directive =
                    (StringLiteral) ((ExpressionStatement) statement).getExpression();
            if ("use strict".equals(directive.getValue())) {
                parser.reportError("msg.class.strict.parms", fn.getPosition(), fn.getLength());
            }
        }
    }

    /** Rejects {@code yield} in a synthetic function: it is a reserved word in class code. */
    private void checkGeneratorUse(FunctionNode fn) {
        if (fn.isGenerator()) {
            parser.reportError("msg.reserved.id", "yield", fn.getPosition(), fn.getLength());
        }
    }

    /**
     * Rejects legacy (JavaScript 1.7) generators inside a class: class code is strict, so {@code
     * yield} is only valid inside {@code function*} and generator methods.
     */
    private void checkLegacyGenerators(ClassNode cls) {
        cls.visit(
                node -> {
                    if (node instanceof FunctionNode) {
                        FunctionNode fn = (FunctionNode) node;
                        if (fn.isGenerator()
                                && !fn.isES6Generator()
                                && fn.getFunctionType() != FunctionNode.ARROW_FUNCTION) {
                            parser.reportError(
                                    "msg.reserved.id",
                                    "yield",
                                    fn.getAbsolutePosition(),
                                    fn.getLength());
                        }
                    }
                    return true;
                });
    }

    // ------------------------------------------------------------------
    // Private names
    // ------------------------------------------------------------------

    private void declarePrivate(
            PrivateName name, ClassElement.Kind kind, boolean isStatic, boolean isField) {
        String id = name.getIdentifier();
        if ("constructor".equals(id)) {
            parser.reportError("msg.class.private.ctor", name.getAbsolutePosition(), name.length());
            return;
        }
        int bits;
        if (isField) {
            bits = PRIVATE_FIELD;
        } else if (kind == ClassElement.Kind.GETTER) {
            bits = PRIVATE_GETTER;
        } else if (kind == ClassElement.Kind.SETTER) {
            bits = PRIVATE_SETTER;
        } else {
            bits = PRIVATE_METHOD;
        }
        if (isStatic) {
            bits |= PRIVATE_STATIC;
        }
        Integer previous = privateScope.declared.get(id);
        if (previous == null) {
            privateScope.declared.put(id, bits);
            return;
        }
        int prev = previous;
        boolean sameStatic = (prev & PRIVATE_STATIC) == (bits & PRIVATE_STATIC);
        int prevKind = prev & ~PRIVATE_STATIC;
        int kindBits = bits & ~PRIVATE_STATIC;
        if (sameStatic
                && ((prevKind == PRIVATE_GETTER && kindBits == PRIVATE_SETTER)
                        || (prevKind == PRIVATE_SETTER && kindBits == PRIVATE_GETTER))) {
            privateScope.declared.put(id, prev | bits);
            return;
        }
        parser.reportError(
                "msg.class.private.dup", "#" + id, name.getAbsolutePosition(), name.getLength());
    }

    /** Checks the references of a finished class body, passing unknown names outwards. */
    private void resolvePrivateReferences(PrivateScope scope) {
        for (PrivateName ref : scope.references) {
            if (scope.declared.containsKey(ref.getIdentifier())) {
                continue;
            }
            if (scope.outer != null) {
                scope.outer.references.add(ref);
            } else {
                reportUndeclared(ref);
            }
        }
    }
}
