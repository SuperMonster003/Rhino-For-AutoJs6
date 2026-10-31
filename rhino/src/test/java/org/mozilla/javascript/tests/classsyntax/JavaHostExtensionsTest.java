/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests.classsyntax;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.LambdaFunction;
import org.mozilla.javascript.NativeJavaObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.TopLevel;
import org.mozilla.javascript.VarScope;
import org.mozilla.javascript.WrapFactory;
import org.mozilla.javascript.lc.type.TypeInfo;
import org.mozilla.javascript.testutils.Utils;

/**
 * AutoJs6 class syntax, fork only (ROADMAP P7.2a, appendix C Q15 = A): an instance of a class that
 * extends a Java class has what the WrapFactory of the current Context adds to the Java object. The
 * factory here stands for the one of the host, whose View wrapper has {@code attr} and {@code on};
 * the order is the JavaScript members of the class, the Java members, the names of the wrapper.
 * Each case runs in the interpreted and the compiled mode.
 */
public class JavaHostExtensionsTest {

    private static final String PRELUDE =
            "var Widget = Packages.org.mozilla.javascript.tests.classsyntax"
                    + ".JavaHostExtensionsTest.Widget;\n";

    /** A Java class the host has a wrapper for. */
    public static class Widget {
        public final String title;
        public String log = "";

        public Widget() {
            this("widget");
        }

        public Widget(String title) {
            this.title = title;
            built();
        }

        protected void built() {
            log += "built;";
        }

        public String name() {
            return "java name";
        }

        public String shared() {
            return "java shared";
        }

        public String draw() {
            return "java draw";
        }

        protected String guarded() {
            return "java guarded";
        }

        public static String callDraw(Widget widget) {
            return widget.draw();
        }
    }

    /** The wrapper of the host; like the View wrapper, {@code this} has to be a wrapper. */
    public static final class HostWrapper extends NativeJavaObject {
        private static final long serialVersionUID = 1L;

        private final transient Map<String, Object> extensions = new HashMap<>();
        final transient Scriptable ownPrototype;

        HostWrapper(VarScope scope, Object javaObject, Context cx, Scriptable ownPrototype) {
            super(scope, javaObject, TypeInfo.NONE);
            this.ownPrototype = ownPrototype;
            function(scope, "mark", (thisObj, args) -> "host mark " + kind(thisObj));
            function(scope, "shared", (thisObj, args) -> "host shared");
            function(scope, "name", (thisObj, args) -> "host name");
            function(scope, "field", (thisObj, args) -> "host field");
            function(scope, "chain", (thisObj, args) -> thisObj);
            function(scope, "javaBack", (thisObj, args) -> ((HostWrapper) thisObj).unwrap());
            function(
                    scope,
                    "again",
                    (thisObj, args) -> new HostWrapper(scope, unwrap(), cx, ownPrototype));
            function(scope, "other", (thisObj, args) -> "not the instance");
            extensions.put("count", 7);
            extensions.put("itself", this);
        }

        private interface Body {
            Object call(Object thisObj, Object[] args);
        }

        private void function(VarScope scope, String name, Body body) {
            TopLevel top = ScriptableObject.getTopLevelScope(scope);
            extensions.put(
                    name,
                    new LambdaFunction(
                            top, name, 0, (c, s, thisObj, args) -> body.call(thisObj, args)));
        }

        private static String kind(Object thisObj) {
            return thisObj instanceof HostWrapper ? "on the wrapper" : "on " + thisObj;
        }

        @Override
        public boolean has(String name, Scriptable start) {
            return name.equals("__proto__")
                    || extensions.containsKey(name)
                    || super.has(name, start);
        }

        @Override
        public Object get(String name, Scriptable start) {
            if (name.equals("__proto__")) {
                return ownPrototype;
            }
            Object value = extensions.get(name);
            return value != null ? value : super.get(name, start);
        }
    }

    /** Wraps the Widget objects; counts the wrappers, which are made once per instance. */
    public static final class HostFactory extends WrapFactory {
        final AtomicInteger wrappers = new AtomicInteger();

        @Override
        public Scriptable wrapAsJavaObject(
                Context cx, VarScope scope, Object javaObject, TypeInfo staticType) {
            if (javaObject instanceof Widget) {
                wrappers.incrementAndGet();
                return new HostWrapper(scope, javaObject, cx, cx.newObject(scope));
            }
            return super.wrapAsJavaObject(cx, scope, javaObject, staticType);
        }
    }

    private static void assertResult(String expected, String script) {
        assertResult(new HostFactory(), expected, script);
    }

    private static void assertResult(HostFactory factory, String expected, String script) {
        Utils.runWithAllModes(
                new ContextFactory(),
                cx -> {
                    cx.setLanguageVersion(Context.VERSION_ES6);
                    cx.setWrapFactory(factory);
                    var scope = cx.initStandardObjects();
                    Object result = cx.evaluateString(scope, PRELUDE + script, "test.js", 1, null);
                    assertEquals(expected, Context.toString(result));
                    return null;
                });
    }

    @Test
    void lookupOrder() {
        assertResult(
                "js shared,js field,java name,java draw,host mark on the wrapper",
                """
                class W extends Widget {
                  field = 'js field';
                  shared() { return 'js shared'; }
                }
                var w = new W();
                [w.shared(), w.field, w.name(), w.draw(), w.mark()].join();
                """);
    }

    @Test
    void functionsRunWithTheWrapperAsThis() {
        assertResult(
                "host mark on the wrapper,host mark on the wrapper,host mark on the wrapper,function",
                """
                class W extends Widget {
                  viaThis() { return this.mark(); }
                }
                var w = new W(), detached = w.mark;
                [w.viaThis(), detached(), w.mark.call({}), typeof w.mark].join();
                """);
    }

    @Test
    void theInstanceComesBackFromAChain() {
        assertResult(
                "true,true,true,true,true,not the instance,7",
                """
                class A extends Widget {}
                class B extends A {}
                var w = new B();
                [w.chain() === w, w.chain().chain() === w, w.javaBack() === w, w.again() === w,
                 w.itself === w, w.other(), w.count].join();
                """);
    }

    @Test
    void functionsAreTheSameFunctionEachTime() {
        assertResult(
                "true,false",
                """
                class W extends Widget {}
                var a = new W(), b = new W();
                [a.mark === a.mark, a.mark === b.mark].join();
                """);
    }

    @Test
    void protectedMembersAndSuperMethodsStayOnTheAdapter() {
        assertResult(
                "java guarded,java draw,js draw,java guarded,undefined,undefined,undefined,undefined",
                """
                class W extends Widget {
                  draw() { return 'js draw'; }
                  viaSuper() { return super.draw(); }
                  guardedSuper() { return super.guarded(); }
                }
                var w = new W();
                [w.guarded(), w.viaSuper(), Widget.callDraw(w), w.guardedSuper(),
                 typeof w['super$draw'] === 'undefined' ? 'undefined' : 'shown',
                 String(w.delegee), String(w.factory), String(w.self)].join();
                """);
    }

    @Test
    void readingFromSuperFindsTheExtension() {
        assertResult(
                "js host mark on the wrapper",
                """
                class W extends Widget {
                  mark() { return 'js ' + super.mark.call(this); }
                }
                new W().mark();
                """);
    }

    @Test
    void theInOperator() {
        assertResult(
                "true,true,true,false,false,false",
                """
                class W extends Widget {}
                var w = new W();
                ['mark' in w, 'name' in w, 'guarded' in w, 'nothing' in w, 'delegee' in w,
                 Object.keys(w).includes('mark')].join();
                """);
    }

    @Test
    void assigningAnExtensionNameMakesAnOwnProperty() {
        assertResult(
                "1,true",
                """
                class W extends Widget {}
                var w = new W();
                w.mark = 1;
                [w.mark, Object.hasOwn(w, 'mark')].join();
                """);
    }

    @Test
    void theProtoOfTheWrapperStaysHidden() {
        assertResult(
                "true,true",
                """
                class W extends Widget {}
                var w = new W();
                [w.__proto__ === W.prototype, w['__proto__'] === W.prototype].join();
                """);
    }

    @Test
    void theJavaConstructorHasNoWrapperYet() {
        HostFactory factory = new HostFactory();
        assertResult(
                factory,
                "undefined,function,built",
                """
                var seen = [];
                class W extends Widget {
                  built() { seen.push(typeof this.mark); }
                }
                var w = new W();
                seen.push(typeof w.mark, 'built');
                seen.join();
                """);
        assertEquals(2, factory.wrappers.get(), "a wrapper per instance and mode");
    }

    @Test
    void aWrapperIsMadeOncePerFactory() {
        HostFactory factory = new HostFactory();
        assertResult(
                factory,
                "5",
                """
                class W extends Widget {}
                var w = new W(), n = 0;
                for (var i = 0; i < 5; i++) { w.mark(); n++; }
                n;
                """);
        assertEquals(2, factory.wrappers.get(), "a wrapper per instance and mode");
    }

    @Test
    void aContextWithAnotherFactoryHasNoExtensions() {
        assertResult(
                "function,undefined",
                """
                var seen = [];
                class W extends Packages.org.mozilla.javascript.tests.classsyntax
                    .JavaHostExtensionsTest.RunnableWidget {
                  run() { seen.push(typeof this.mark); }
                }
                var w = new W();
                seen.push(typeof w.mark);
                var t = new java.lang.Thread(w);
                t.start();
                t.join();
                seen.join();
                """);
    }

    /** A Widget that runs on a Java thread, whose Context has the default factory. */
    public abstract static class RunnableWidget extends Widget implements Runnable {}

    @Test
    void wrappersOfRhinoAddNothing() {
        Utils.runWithAllModes(
                new ContextFactory(),
                cx -> {
                    cx.setLanguageVersion(Context.VERSION_ES6);
                    var scope = cx.initStandardObjects();
                    Object result =
                            cx.evaluateString(
                                    scope,
                                    """
                                    var asked = 0;
                                    class L extends java.util.AbstractList {
                                      size() { return 2; }
                                      get(i) { return i; }
                                    }
                                    class M extends java.util.AbstractMap {
                                      entrySet() { asked++; return new java.util.HashSet(); }
                                    }
                                    var l = new L(), m = new M();
                                    [String(l.length), l.size(), String(m.missing), asked,
                                     'length' in l].join();
                                    """,
                                    "test.js",
                                    1,
                                    null);
                    assertEquals("undefined,2,undefined,0,false", Context.toString(result));
                    return null;
                });
    }

    @Test
    void javaApisStillGetTheJavaObject() {
        Utils.runWithAllModes(
                new ContextFactory(),
                cx -> {
                    cx.setLanguageVersion(Context.VERSION_ES6);
                    HostFactory factory = new HostFactory();
                    cx.setWrapFactory(factory);
                    var scope = cx.initStandardObjects();
                    Object result =
                            cx.evaluateString(
                                    scope,
                                    PRELUDE
                                            + """
                                            class W extends Widget {}
                                            var w = new W();
                                            var list = new java.util.ArrayList();
                                            list.add(w);
                                            w.mark();
                                            [list.get(0) === w, w instanceof W,
                                             w instanceof Widget].join();
                                            """,
                                    "test.js",
                                    1,
                                    null);
                    assertEquals("true,true,true", Context.toString(result));
                    return null;
                });
    }

    @Test
    void superThroughAJavaScriptClass() {
        Utils.assertWithAllModes_ES6(
                "true,java draw,java name,js",
                PRELUDE
                        + """
                        class A extends java.lang.Thread {
                          m() { return 'js'; }
                        }
                        class B extends A {
                          threadName() { return super.getName(); }
                          m() { return super.m(); }
                        }
                        class C extends Widget {}
                        class D extends C {
                          draw() { return super.draw(); }
                          n() { return super.name(); }
                        }
                        var b = new B(), d = new D();
                        [b.threadName() === b.getName(), d.draw(), d.n(), b.m()].join();
                        """);
    }
}
