/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.mozilla.javascript.ast.AstNode;
import org.mozilla.javascript.ast.Block;
import org.mozilla.javascript.ast.ClassElement;
import org.mozilla.javascript.ast.ClassNode;
import org.mozilla.javascript.ast.ComputedPropertyKey;
import org.mozilla.javascript.ast.FunctionCall;
import org.mozilla.javascript.ast.FunctionNode;
import org.mozilla.javascript.ast.Name;
import org.mozilla.javascript.ast.ScriptNode;
import org.mozilla.javascript.ast.Spread;

/**
 * AutoJs6 class syntax: lowering of {@link ClassNode} into IR (ROADMAP P2.1).
 *
 * <p>A class definition becomes one {@link Token#CLASS} expression node whose {@link
 * Node#CLASS_PROP} is an {@link ClassOp} of kind {@link ClassOp#DEFINE}. Its children are the
 * heritage expression (when there is one), the constructor ({@link Token#FUNCTION}) and one {@link
 * Token#CLASS_ELEMENT} node per method, accessor or generator method in source order, holding the
 * key expression and the method function; the element's int {@link Node#CLASS_PROP} carries the
 * {@code ELEMENT_*} flags. The back ends evaluate the heritage, create the prototype and the
 * constructor ({@code ClassRuntime.defineClass}), then evaluate each key once and define the
 * element ({@code ClassRuntime.defineElement}); the value of the node is the constructor.
 *
 * <p>A named class gets its own scope holding the immutable inner binding: the {@link ClassNode}
 * itself becomes a {@link Token#LETEXPR} that initializes the binding with the definition, which
 * {@link NodeTransformer} turns into a scope expression like any other let expression. A class
 * declaration then initializes the outer {@code let} binding the parser declared.
 *
 * <p>The same node type stands for the other class primitives, told apart by {@link
 * ClassOp#getKind}: {@link ClassOp#SUPER_CALL}, {@link ClassOp#THIS} (this inside a derived
 * constructor), {@link ClassOp#TDZ} (a definition time reference to the class binding) and {@link
 * ClassOp#CTOR_CHECK} (the [[Call]] check at the top of every class constructor).
 *
 * <p>Fields, static blocks and private names are left to ROADMAP P3: they are reported as not
 * supported yet. P3 adds element kinds to {@code ELEMENT_*} and initializer functions to {@link
 * ClassOp} (see NOTES.md, P2).
 */
final class ClassLowering {

    /** The name of the rest parameter of a default derived constructor. */
    private static final String DEFAULT_ARGS = "args";

    private ClassLowering() {}

    static ClassOp getOp(Node node) {
        return ClassOp.of(node);
    }

    static Node newClassNode(int kind) {
        Node node = new Node(Token.CLASS);
        node.putProp(Node.CLASS_PROP, new ClassOp(kind));
        return node;
    }

    /** Transforms a {@link ClassNode} declaration or expression. */
    static Node transform(IRFactory factory, Parser parser, AstNode node) {
        ClassNode cls = (ClassNode) node;
        IRFactory.AstNodePosition position = (IRFactory.AstNodePosition) parser.currentPos;
        position.push(cls);
        boolean savedStrict = parser.inUseStrictDirective;
        boolean savedOuterStrict = factory.outerScopeIsStrict;
        // All parts of a class are strict mode code, functions in the heritage and keys included.
        parser.inUseStrictDirective = true;
        factory.outerScopeIsStrict = true;
        // The parser made the class a scope even without a name, and nested scopes point to it.
        parser.pushScope(cls);
        parser.classSyntax.lowering++;
        try {
            Node definition = define(factory, parser, cls);
            Node result = definition;
            if (!cls.isAnonymous()) {
                result = classScope(cls, definition);
            }
            if (cls.isStatement()) {
                Node binding = parser.createName(cls.getClassName().getIdentifier());
                binding.addChildToBack(result);
                Node declaration = new Node(Token.LET, binding);
                declaration.setLineColumnNumber(cls.getLineno(), cls.getColumn());
                return declaration;
            }
            return result;
        } finally {
            parser.classSyntax.lowering--;
            parser.popScope();
            parser.inUseStrictDirective = savedStrict;
            factory.outerScopeIsStrict = savedOuterStrict;
            position.pop();
        }
    }

    private static Node define(IRFactory factory, Parser parser, ClassNode cls) {
        Node definition = newClassNode(ClassOp.DEFINE);
        definition.setLineColumnNumber(cls.getLineno(), cls.getColumn());
        ClassOp op = getOp(definition);
        if (cls.getSuperClass() != null) {
            op.hasHeritage = true;
            definition.addChildToBack(factory.transform(cls.getSuperClass()));
        }

        ClassElement explicit = cls.getConstructor();
        FunctionNode constructor =
                explicit != null ? explicit.getValue() : defaultConstructor(parser, cls);
        int start = cls.getAbsolutePosition();
        // Function.prototype.toString of a class is the source text of the class (ROADMAP Q1).
        constructor.setRawSourceBounds(start, start + cls.getLength());
        Node constructorNode = factory.transform(constructor);
        Node body = constructor.getLastChild();
        if (explicit == null && cls.isDerived()) {
            Node superCall = newClassNode(ClassOp.SUPER_CALL);
            getOp(superCall).arrayArguments = true;
            superCall.addChildToBack(parser.createName(DEFAULT_ARGS));
            Node newChild = new Node(Token.EXPR_VOID, superCall);
            // Before the return that initFunction added.
            body.addChildBefore(newChild, body.getLastChild());
        }
        body.addChildToFront(new Node(Token.EXPR_VOID, newClassNode(ClassOp.CTOR_CHECK)));
        Name className = cls.isAnonymous() ? cls.getInferredName() : cls.getClassName();
        if (className != null) {
            constructor.setFunctionName(className);
        }
        definition.addChildToBack(constructorNode);

        for (ClassElement element : cls.getElements()) {
            ClassElement.Kind kind = element.getKind();
            if (kind == ClassElement.Kind.CONSTRUCTOR) {
                continue;
            }
            if (kind == ClassElement.Kind.FIELD
                    || kind == ClassElement.Kind.STATIC_BLOCK
                    || element.isPrivate()) {
                reportNotSupported(parser, element, "msg.class.not.supported");
            }
            int flags =
                    kind == ClassElement.Kind.GETTER
                            ? ClassOp.ELEMENT_GETTER
                            : kind == ClassElement.Kind.SETTER
                                    ? ClassOp.ELEMENT_SETTER
                                    : ClassOp.ELEMENT_METHOD;
            if (element.isStatic()) {
                flags |= ClassOp.ELEMENT_STATIC;
            }
            Node key;
            String name = null;
            if (element.isComputed()) {
                flags |= ClassOp.ELEMENT_COMPUTED;
                key = factory.transform(((ComputedPropertyKey) element.getKey()).getExpression());
            } else {
                name = element.getPropertyName();
                key = Node.newString(name);
            }
            FunctionNode method = element.getValue();
            if (kind == ClassElement.Kind.GETTER || kind == ClassElement.Kind.SETTER) {
                // Accessors are not constructors and have no prototype property.
                method.setIsShorthand();
            }
            Node methodNode = factory.transform(method);
            if (name != null) {
                String prefix =
                        kind == ClassElement.Kind.GETTER
                                ? "get "
                                : kind == ClassElement.Kind.SETTER ? "set " : "";
                Name functionName = new Name(0, prefix + name);
                functionName.setLineColumnNumber(
                        element.getKey().getLineno(), element.getKey().getColumn());
                method.setFunctionName(functionName);
            }
            Node elementNode = new Node(Token.CLASS_ELEMENT, key, methodNode);
            elementNode.putIntProp(Node.CLASS_PROP, flags);
            definition.addChildToBack(elementNode);
        }
        return definition;
    }

    /**
     * Turns the class node into the let expression of its scope: the inner binding of the class
     * name starts uninitialized and the definition initializes it.
     */
    private static Node classScope(ClassNode cls, Node definition) {
        Node vars = new Node(Token.LET);
        List<Boolean> consts = new ArrayList<>();
        String className = cls.getClassName().getIdentifier();
        Map<String, org.mozilla.javascript.ast.Symbol> symbols = cls.getSymbolTable();
        if (symbols != null) {
            for (org.mozilla.javascript.ast.Symbol symbol : symbols.values()) {
                vars.addChildToBack(Node.newString(Token.NAME, symbol.getName()));
                consts.add(symbol.getDeclType() == org.mozilla.javascript.ast.Symbol.Type.CONST);
            }
        }
        NodeTransformer.putConstFlags(vars, consts);
        Node init = new Node(Token.SETCONST, Node.newString(Token.BINDNAME, className), definition);
        cls.setType(Token.LETEXPR);
        cls.addChildToBack(vars);
        cls.addChildToBack(init);
        return cls;
    }

    /**
     * Creates the default constructor: {@code constructor() {}} for a base class and {@code
     * constructor(...args) { super(...args); }} for a derived class, the latter forwarding the
     * arguments without iterating them (the super call is added to the body after transformation).
     */
    private static FunctionNode defaultConstructor(Parser parser, ClassNode cls) {
        FunctionNode fn = new FunctionNode(cls.getAbsolutePosition());
        fn.setLineColumnNumber(cls.getLineno(), cls.getColumn());
        fn.setFunctionType(FunctionNode.FUNCTION_EXPRESSION);
        fn.setMethodDefinition(true);
        fn.setFunctionIsNormalMethod();
        fn.setInStrictMode(true);
        fn.setSourceName(parser.currentScriptOrFn.getSourceName());
        if (cls.isDerived()) {
            Name args = new Name(0, DEFAULT_ARGS);
            fn.addParam(args);
            fn.setHasRestParameter(true);
            fn.putSymbol(
                    new org.mozilla.javascript.ast.Symbol(
                            org.mozilla.javascript.ast.Symbol.Type.LP, DEFAULT_ARGS));
        }
        Block body = new Block(0, 0);
        body.setLineColumnNumber(cls.getLineno(), cls.getColumn());
        fn.setBody(body);
        ClassElement element = new ClassElement();
        element.setKind(ClassElement.Kind.CONSTRUCTOR);
        element.setValue(fn);
        element.setParent(cls);
        return fn;
    }

    /** Returns whether the function is the constructor of a class. */
    static boolean isClassConstructor(ScriptNode node) {
        if (!(node instanceof FunctionNode)) {
            return false;
        }
        AstNode parent = node.getParent();
        return parent instanceof ClassElement
                && ((ClassElement) parent).getKind() == ClassElement.Kind.CONSTRUCTOR;
    }

    /** Returns whether the function is class code: it is in a class definition. */
    static boolean isClassCode(ScriptNode node) {
        for (AstNode n = node.getParent(); n != null; n = n.getParent()) {
            if (n instanceof ClassNode) {
                return true;
            }
        }
        return false;
    }

    /** Returns whether the function is the constructor of a class with an extends clause. */
    static boolean isDerived(ScriptNode node) {
        return isClassConstructor(node) && ((ClassNode) node.getParent().getParent()).isDerived();
    }

    /** Whether the node is {@code this} inside a derived constructor or its arrow functions. */
    static boolean isDerivedThis(Parser parser, AstNode node) {
        return node.getType() == Token.THIS && isDerivedThis(parser);
    }

    /**
     * Returns whether the this of the function being transformed is the this of a derived
     * constructor, that is whether the closest enclosing function other than an arrow function is a
     * derived constructor.
     */
    static boolean isDerivedThis(Parser parser) {
        AstNode n = parser.currentScriptOrFn;
        while (n instanceof FunctionNode
                && ((FunctionNode) n).getFunctionType() == FunctionNode.ARROW_FUNCTION) {
            do {
                n = n.getParent();
            } while (n != null && !(n instanceof ScriptNode));
        }
        return n instanceof FunctionNode && isDerived((FunctionNode) n);
    }

    /** Transforms {@code this} inside a derived constructor or its arrow functions. */
    static Node derivedThis(AstNode node) {
        Node result = newClassNode(ClassOp.THIS);
        result.setLineColumnNumber(node.getLineno(), node.getColumn());
        return result;
    }

    /**
     * In a derived constructor, {@code super.x} and {@code super[x]} read the this binding before
     * anything else is evaluated (MakeSuperPropertyReference), so that they fail before {@code
     * super()}: the super object is evaluated after a {@link ClassOp#THIS} read.
     */
    static void checkSuperThis(Parser parser, Node access) {
        if (!isDerivedThis(parser)) {
            return;
        }
        Node superNode = access.getFirstChild();
        access.removeChild(superNode);
        access.addChildToFront(new Node(Token.COMMA, newClassNode(ClassOp.THIS), superNode));
    }

    /**
     * Returns whether the call is {@code super(...)} in a derived constructor or its arrow
     * functions. Any other one is left to IRFactory, which reports it (the parser already did in
     * class code); so is one in eval code, which does not know the function it runs in.
     */
    static boolean isSuperCall(Parser parser, FunctionCall call) {
        return call.getTarget().getType() == Token.SUPER && isDerivedThis(parser);
    }

    /**
     * Transforms {@code super(...)}. Spread arguments are collected by an array literal, which
     * iterates them like any argument list.
     */
    static Node transformSuperCall(IRFactory factory, FunctionCall call) {
        Node result = newClassNode(ClassOp.SUPER_CALL);
        result.setLineColumnNumber(call.getLineno(), call.getColumn());
        List<AstNode> args = call.getArguments();
        boolean spread = false;
        for (AstNode arg : args) {
            spread |= arg instanceof Spread;
        }
        if (spread) {
            org.mozilla.javascript.ast.ArrayLiteral array =
                    new org.mozilla.javascript.ast.ArrayLiteral(call.getPosition());
            array.setLineColumnNumber(call.getLineno(), call.getColumn());
            for (AstNode arg : args) {
                array.addElement(arg);
            }
            getOp(result).arrayArguments = true;
            result.addChildToBack(factory.transform(array));
        } else {
            for (AstNode arg : args) {
                result.addChildToBack(factory.transform(arg));
            }
        }
        return result;
    }

    /**
     * Returns the IR for a reference to the inner binding of a class that is evaluated while the
     * class is being defined (in its heritage or a computed key, outside any function), or {@code
     * null} for any other reference. Such a reference is always in the temporal dead zone of the
     * binding, so it is compiled into a ReferenceError.
     */
    static Node transformName(Parser parser, Name name) {
        if (parser.classSyntax.lowering == 0) {
            return null;
        }
        String id = name.getIdentifier();
        for (AstNode n = name.getParent(); n != null; n = n.getParent()) {
            if (n instanceof FunctionNode) {
                return null;
            }
            if (n instanceof ClassNode) {
                ClassNode cls = (ClassNode) n;
                if (!cls.isAnonymous() && id.equals(cls.getClassName().getIdentifier())) {
                    Node result = newClassNode(ClassOp.TDZ);
                    getOp(result).name = id;
                    result.setLineColumnNumber(name.getLineno(), name.getColumn());
                    return result;
                }
            }
        }
        return null;
    }

    /**
     * Gives an anonymous class the name inferred from its context ({@code var C = class {}}), as
     * {@code IRFactory.inferNameIfMissing} does for functions.
     */
    /** IRFactory.inferNameIfMissing for a class: returns whether the value is a class. */
    static boolean inferClassName(Parser parser, Object left, Node right, String prefix) {
        if (!(left instanceof Name) || right == null || right.getType() != Token.CLASS) {
            return false;
        }
        inferName(parser, right, (Name) left, prefix);
        return true;
    }

    static void inferName(Parser parser, Node definition, Name name, String prefix) {
        Node node = definition;
        if (node.getType() != Token.CLASS) {
            return;
        }
        ClassOp op = getOp(node);
        if (op == null || op.kind != ClassOp.DEFINE) {
            return;
        }
        Node constructorNode =
                op.hasHeritage ? node.getFirstChild().getNext() : node.getFirstChild();
        int index = constructorNode.getExistingIntProp(Node.FUNCTION_PROP);
        FunctionNode constructor = parser.currentScriptOrFn.getFunctionNode(index);
        if (constructor.getFunctionName() == null) {
            constructor.setFunctionName(prefix != null ? name.withPrefix(prefix) : name);
        }
    }

    /** Reports an error at the position of the given node during IR transformation. */
    static void reportNotSupported(Parser parser, AstNode node, String key) {
        IRFactory.AstNodePosition position = (IRFactory.AstNodePosition) parser.currentPos;
        position.push(node);
        try {
            parser.reportError(key, node.getAbsolutePosition(), node.getLength());
        } finally {
            position.pop();
        }
    }
}
