/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests.classsyntax;

import static org.mozilla.javascript.testutils.Utils.assertEcmaErrorES6;
import static org.mozilla.javascript.testutils.Utils.assertWithAllModes_ES6;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * AutoJs6 class syntax, fork only (ROADMAP P5, D3 / T6): classes that extend a Java class or
 * interface, or {@code JavaAdapter(Base, I1, I2)} (appendix C Q3 = A); an instance is a wrapper of
 * the Java object whose prototype is the class prototype (appendix C Q4 = A). Each case runs in the
 * interpreted and the compiled mode.
 */
public class JavaClassInheritanceTest {

    private static final String SHAPE =
            "var Shape = Packages.org.mozilla.javascript.tests.classsyntax"
                    + ".JavaClassInheritanceTest.Shape;\n";

    /** A Java superclass with constructors, fields and methods of every kind. */
    public abstract static class Shape {
        public String label = "shape";
        protected int sides;
        public final List<String> calls = new ArrayList<>();

        protected Shape() {
            this(0);
        }

        protected Shape(int sides) {
            this.sides = sides;
            init();
        }

        public Shape(String label, int sides) {
            this(sides);
            this.label = label;
        }

        public Shape(Class<?> type) {
            this(type.getSimpleName(), 0);
        }

        protected void init() {
            calls.add("Shape.init");
        }

        public abstract double area();

        public String describe() {
            return label + ":" + sides + ":" + area();
        }

        public final String kind() {
            return "shape";
        }

        protected String secret() {
            return "protected";
        }

        public static String describeAll(List<? extends Shape> shapes) {
            StringBuilder sb = new StringBuilder();
            for (Shape s : shapes) {
                sb.append(sb.length() == 0 ? "" : "|").append(s.describe());
            }
            return sb.toString();
        }

        public static double totalArea(Shape... shapes) {
            double total = 0;
            for (Shape s : shapes) {
                total += s.area();
            }
            return total;
        }
    }

    /** Not public, so it cannot be extended. */
    abstract static class Hidden {}

    @Test
    void extendsThread() {
        assertWithAllModes_ES6(
                "1,w1:x,true,true,false,true",
                """
                class Worker extends java.lang.Thread {
                  count = 0;
                  #secret = 'x';
                  constructor(name) { super(name); }
                  run() { this.count++; this.log = this.getName() + ':' + this.#secret; }
                  static create(n) { return new Worker(n); }
                  static has(o) { return #secret in o; }
                }
                var w = Worker.create('w1');
                w.start();
                w.join();
                [w.count, w.log, w instanceof Worker, w instanceof java.lang.Thread,
                 w.isAlive(), Worker.has(w)].join();
                """);
    }

    @Test
    void superCallsTheJavaImplementation() {
        assertWithAllModes_ES6(
                "true,named,r,done",
                """
                var log = [];
                class T extends java.lang.Thread {
                  constructor(r, n) { super(r, n); }
                  toString() { return 'T(' + super.toString() + ')'; }
                  run() { super.run(); log.push('done'); }
                }
                var t = new T(() => { log.push('r'); }, 'named');
                t.start();
                t.join();
                [String(t).startsWith('T(Thread['), t.getName(), log].join();
                """);
    }

    @Test
    void extendsAbstractList() {
        assertWithAllModes_ES6(
                "3;[v0, v1, v2];false;[v0, v1, v2];v0|v1|v2;v2;v1",
                """
                class Range extends java.util.AbstractList {
                  #n;
                  constructor(n) { super(); this.#n = n; }
                  get(i) { return 'v' + i; }
                  size() { return this.#n; }
                }
                var r = new Range(3);
                var copy = new java.util.ArrayList(r);
                var values = [];
                for (var v of r) values.push(v);
                [r.size(), copy.toString(), r.isEmpty(), String(r), values.join('|'),
                 java.util.Collections.max(r), r.get(1)].join(';');
                """);
    }

    @Test
    void extendsCustomAbstractClass() {
        assertWithAllModes_ES6(
                "Square square:4:9.0;9;square;4;shape;protected;init;"
                        + "Square square:4:9.0|Square square:4:4.0;13",
                SHAPE
                        + """
                        class Square extends Shape {
                          static log = [];
                          side;
                          constructor(side) { super('square', 4); this.side = side; }
                          area() { return this.side * this.side; }
                          init() { Square.log.push('init'); }
                          describe() { return 'Square ' + super.describe(); }
                        }
                        var s = new Square(3), t = new Square(2);
                        [s.describe(), s.area(), s.label, s.sides, s.kind(), s.secret(),
                         Square.log[0], Shape.describeAll(java.util.List.of(s, t)),
                         Shape.totalArea(s, t)].join(';');
                        """);
    }

    @Test
    void javaConstructorCallsOverriddenMethod() {
        // The Java constructor runs init() before super() returns: the method of the class runs
        // on the instance, which has its Java members but not its fields yet.
        assertWithAllModes_ES6(
                "true,,0,Shape.init,1",
                SHAPE
                        + """
                        var seen = [];
                        class A extends Shape {
                          field = 1;
                          init() {
                            seen.push(this instanceof A, this.field, this.sides);
                            super.init();
                          }
                          area() { return 0; }
                        }
                        var a = new A();
                        seen.concat(a.calls.get(0), a.field).join();
                        """);
    }

    @Test
    void assignmentsAndLookups() {
        // Writable Java fields are assigned on the Java object; other names become own properties.
        assertWithAllModes_ES6(
                "renamed:5:1.0;false;true;{\"side\":1,\"extra\":1};[object Object];shape",
                SHAPE
                        + """
                        class Sq extends Shape {
                          side = 1;
                          constructor() { super('sq', 4); }
                          area() { return this.side; }
                        }
                        var s = new Sq();
                        s.label = 'renamed';
                        s.sides = 5;
                        s.extra = 1;
                        var jp = Object.getPrototypeOf(Sq.prototype);
                        [s.describe(), s.hasOwnProperty('label'), s.hasOwnProperty('extra'),
                         JSON.stringify(s), String(jp),
                         jp.constructor === Object.getPrototypeOf(Sq) && s.kind()].join(';');
                        """);
        assertEcmaErrorES6(
                "TypeError: Cannot find function area",
                SHAPE
                        + "class Sq extends Shape { area() { return super.area(); } };"
                        + " new Sq().area();");
    }

    @Test
    void implementsSingleInterface() {
        assertWithAllModes_ES6(
                "2,true,true,true",
                """
                class Task extends java.lang.Runnable {
                  ran = 0;
                  run() { this.ran++; }
                }
                var t = new Task();
                var th = new java.lang.Thread(t);
                th.start();
                th.join();
                t.run();
                [t.ran, t instanceof java.lang.Runnable, t instanceof Task,
                 Object.getPrototypeOf(Task).name === 'java.lang.Runnable'].join();
                """);
    }

    @Test
    void implementsSeveralInterfacesWithJavaAdapter() {
        assertWithAllModes_ES6(
                "1,2,3;true;true;true;true",
                """
                var Base = JavaAdapter(java.lang.Object, java.lang.Runnable, java.lang.Comparable);
                class Job extends Base {
                  static ran = [];
                  constructor(p) { super(); this.p = p; }
                  run() { Job.ran.push(this.p); }
                  compareTo(o) { return this.p - o.p; }
                }
                var jobs = new java.util.ArrayList();
                jobs.add(new Job(3));
                jobs.add(new Job(1));
                jobs.add(new Job(2));
                java.util.Collections.sort(jobs);
                for (var j of jobs) j.run();
                var first = jobs.get(0);
                [Job.ran.join(), first instanceof Job, first === jobs.get(0),
                 first instanceof java.lang.Comparable,
                 first instanceof java.lang.Runnable].join(';');
                """);
        assertWithAllModes_ES6(
                "t,-1,true",
                """
                class T extends JavaAdapter(java.lang.Thread, java.lang.Comparable) {
                  constructor(n) { super(n); }
                  compareTo(o) { return this.getName() < o.getName() ? -1 : 1; }
                }
                var a = new T('a'), b = new T('t');
                [b.getName(), a.compareTo(b), JavaAdapter(java.lang.Thread, java.lang.Comparable)
                    === Object.getPrototypeOf(T)].join();
                """);
    }

    @Test
    void staticMembers() {
        assertWithAllModes_ES6(
                "10,true,made,java.lang.Thread,true",
                """
                class Worker extends java.lang.Thread {
                  static made = 'made';
                  static current() { return super.currentThread(); }
                }
                [Worker.MAX_PRIORITY, Worker.currentThread() === Worker.current(), Worker.made,
                 Object.getPrototypeOf(Worker).name,
                 Object.getPrototypeOf(Worker)
                     === Object.getPrototypeOf(class extends java.lang.Thread {})
                ].join();
                """);
    }

    @Test
    void fieldsAndPrivateNamesAreOnTheInstance() {
        assertWithAllModes_ES6(
                "a,b;true;false;1;true;JavaObject",
                """
                class P extends java.lang.Object {
                  a = 1;
                  #b = 2;
                  constructor() { super(); this.b = this.#b - 1; }
                  static brand(o) { return #b in o; }
                }
                var p = new P();
                [Object.keys(p).join(), p.hasOwnProperty('a'), p.hasOwnProperty('toString'),
                 p.b, P.brand(p), Object.prototype.toString.call(p).slice(8, -1)].join(';');
                """);
    }

    @Test
    void javaApisGetTheJavaObject() {
        assertWithAllModes_ES6(
                "true,true,true,classadapter",
                """
                class K extends java.lang.Object {}
                var k = new K();
                var list = new java.util.ArrayList();
                list.add(k);
                [list.get(0) === k, list.contains(k), java.util.Objects.equals(k, list.get(0)),
                 String(k.getClass().getName()).replace(/[0-9]+$/, '')].join();
                """);
    }

    @Test
    void toStringEqualsAndHashCode() {
        assertWithAllModes_ES6(
                "2;Key(z);Key(q);true;Key(v);1",
                """
                class Key extends java.lang.Object {
                  constructor(k) { super(); this.k = k; }
                  equals(o) { return o instanceof Key && o.k === this.k; }
                  hashCode() { return this.k.length; }
                  toString() { return 'Key(' + this.k + ')'; }
                }
                var set = new java.util.HashSet();
                set.add(new Key('a'));
                set.add(new Key('a'));
                set.add(new Key('bb'));
                [set.size(), String(new Key('z')), new Key('q').toString(),
                 set.contains(new Key('bb')), java.lang.String.valueOf(new Key('v')),
                 new Key('a').hashCode()].join(';');
                """);
    }

    @Test
    void subclassOfSubclass() {
        assertWithAllModes_ES6(
                "A,B,true,true,b",
                """
                var log = [];
                class A extends java.lang.Thread {
                  run() { super.run(); log.push('A'); }
                  name2() { return 'a'; }
                }
                class B extends A {
                  run() { super.run(); log.push('B'); }
                  name2() { return 'b'; }
                }
                var b = new B();
                b.start();
                b.join();
                [log, b instanceof A, b instanceof java.lang.Thread, b.name2()].join();
                """);
    }

    @Test
    void callbacksFromJavaThreads() {
        assertWithAllModes_ES6(
                "true,true,3",
                """
                class Counter extends java.util.concurrent.Callable {
                  #calls = 0;
                  call() { this.#calls++; return java.lang.Thread.currentThread().getName(); }
                  get calls() { return this.#calls; }
                }
                var c = new Counter(), result;
                var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
                try {
                  var names = [pool.submit(c).get(), pool.submit(c).get()];
                  var main = java.lang.Thread.currentThread().getName();
                  c.call();
                  result = [String(names[0]) !== String(main), String(names[1]).startsWith('pool-'),
                            c.calls].join();
                } finally {
                  pool.shutdown();
                }
                result;
                """);
    }

    @Test
    void adapterClassIsCached() {
        assertWithAllModes_ES6(
                "true,false",
                """
                function make() {
                  return class extends java.lang.Object { toString() { return 'x'; } };
                }
                var A = make(), B = make();
                class C extends java.lang.Object { hashCode() { return 1; } }
                var name = o => String(o.getClass().getName());
                [name(new A()) === name(new B()), name(new A()) === name(new C())].join();
                """);
    }

    @Test
    void legacyJavaAdapterStillWorks() {
        assertWithAllModes_ES6(
                "legacy,1,2,named,shape:0:2.0,Thread:0:3.0",
                SHAPE
                        + """
                var log = [];
                var o = new JavaAdapter(java.lang.Object, { toString() { return 'legacy'; } });
                var r = new java.lang.Runnable({ run() { log.push(1); } });
                r.run();
                var t = new JavaAdapter(java.lang.Thread, { run() { log.push(2); } }, 'named');
                t.start();
                t.join();
                var shape = new JavaAdapter(Shape, { area() { return 2; } });
                class Impl { area() { return 3; } }
                var typed = new JavaAdapter(Shape, new Impl(), java.lang.Thread);
                [String(o), log.join(), t.getName(), shape.describe(), typed.describe()].join();
                """);
    }

    @Test
    void finalClassesAndMethods() {
        assertEcmaErrorES6(
                "TypeError: Java class java.lang.String is final and cannot be extended",
                "class S extends java.lang.String {}");
        assertEcmaErrorES6(
                "TypeError: Java class org.mozilla.javascript.tests.classsyntax"
                        + ".JavaClassInheritanceTest$Hidden is not public and cannot be extended",
                "class H extends Packages.org.mozilla.javascript.tests.classsyntax"
                        + ".JavaClassInheritanceTest.Hidden {}");
        assertEcmaErrorES6(
                "TypeError: The method getName of Java class java.lang.Thread is final and cannot"
                        + " be overridden",
                "class W extends java.lang.Thread { getName() { return 'x'; } }; new W();");
    }

    @Test
    void javaAdapterFactoryErrors() {
        assertEcmaErrorES6(
                "TypeError: JavaAdapter: the class java.lang.Thread must be the first argument,"
                        + " before the interfaces",
                "JavaAdapter(java.lang.Runnable, java.lang.Thread)");
        assertEcmaErrorES6(
                "TypeError: Only one class may be extended by a JavaAdapter. Had java.lang.Thread"
                        + " and java.util.AbstractList.",
                "JavaAdapter(java.lang.Thread, java.util.AbstractList)");
        assertEcmaErrorES6(
                "TypeError: Argument 1 is not Java class: 42.",
                "JavaAdapter(java.lang.Runnable, 42, java.lang.Comparable)");
        assertEcmaErrorES6(
                "TypeError: JavaAdapter: java.lang.Runnable is listed more than once",
                "JavaAdapter(java.lang.Runnable, java.lang.Runnable)");
        assertEcmaErrorES6(
                "TypeError: Class constructor java.lang.Object cannot be invoked without \"new\"",
                "Object.getPrototypeOf(class extends java.lang.Object {})()");
    }

    @Test
    void constructorErrors() {
        assertEcmaErrorES6(
                "TypeError: No constructor of Java class java.lang.Object accepts the arguments"
                        + " (number)",
                "class O extends java.lang.Object { constructor() { super(1); } }; new O();");
        assertEcmaErrorES6(
                "ReferenceError: Must call the super constructor before accessing \"this\":"
                        + " super() creates the object of Java class java.lang.Thread",
                "class T extends java.lang.Thread { constructor() { this.x = 1; super(); } };"
                        + " new T();");
    }

    @Test
    void abstractMethodsAndOverridesThatAreNotFunctions() {
        assertEcmaErrorES6(
                "TypeError: The abstract method java.lang.Runnable.run is not implemented",
                "class R extends java.lang.Runnable {};"
                        + " java.util.concurrent.Executors.callable(new R()).call();");
        assertEcmaErrorES6(
                "TypeError: run overrides a method of Java class java.lang.Runnable but is not a"
                        + " function",
                "class R extends java.lang.Runnable { get run() { return 5; } };"
                        + " java.util.concurrent.Executors.callable(new R()).call();");
        assertWithAllModes_ES6(
                "undefined,ok",
                """
                class R extends java.lang.Runnable {}
                var r = new R();
                var result = [typeof r.run];
                r.run = function () { result.push('ok'); };
                java.util.concurrent.Executors.callable(r).call();
                result.join();
                """);
    }
}
