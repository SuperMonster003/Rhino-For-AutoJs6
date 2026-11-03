package org.mozilla.javascript.benchmarks;

import java.io.FileReader;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Context.EvaluationMethod;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.TopLevel;
import org.openjdk.jmh.annotations.*;

/**
 * AutoJs6 class syntax (ROADMAP P2.5, appendix A): instantiation and method calls of ES2015 classes
 * against constructor functions with prototypes. A class method call that is more than 10% slower
 * than the prototype method call fails the phase (appendix C Q8).
 */
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class ClassBenchmark {
    @State(Scope.Thread)
    public static class ClassState {
        Context cx;
        TopLevel scope;

        Function classNew;
        Function functionNew;
        Function functionExprNew;
        Function strictFunctionExprNew;
        Function classDerivedNew;
        Function functionDerivedNew;
        Function classCall;
        Function functionCall;
        Function classSuperCall;
        Function functionSuperCall;
        Function awaitSettled;
        Function awaitPending;
        Function thenSettled;

        @Param({"Interpreter", "Compiler"})
        public EvaluationMethod evalMethod;

        @Setup(Level.Trial)
        public void setup() throws IOException {
            cx = Context.enter();
            cx.setEvaluationMethod(evalMethod);
            cx.setLanguageVersion(Context.VERSION_ES6);
            scope = cx.initStandardObjects();

            try (FileReader rdr = new FileReader("testsrc/benchmarks/micro/class-benchmarks.js")) {
                cx.evaluateReader(scope, rdr, "class-benchmarks.js", 1, null);
            }
            classNew = function("classNew");
            functionNew = function("functionNew");
            functionExprNew = function("functionExprNew");
            strictFunctionExprNew = function("strictFunctionExprNew");
            classDerivedNew = function("classDerivedNew");
            functionDerivedNew = function("functionDerivedNew");
            classCall = function("classCall");
            functionCall = function("functionCall");
            classSuperCall = function("classSuperCall");
            functionSuperCall = function("functionSuperCall");
            awaitSettled = function("awaitSettled");
            awaitPending = function("awaitPending");
            thenSettled = function("thenSettled");
        }

        private Function function(String name) {
            return (Function) ScriptableObject.getProperty(scope, name);
        }

        Object run(Function f) {
            return f.call(cx, scope, null, ScriptRuntime.emptyArgs);
        }

        Object runMicrotasks(Function f) {
            Object result = run(f);
            cx.processMicrotasks();
            return result;
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            cx.close();
        }
    }

    @Benchmark
    public Object classNew(ClassState state) {
        return state.run(state.classNew);
    }

    @Benchmark
    public Object functionNew(ClassState state) {
        return state.run(state.functionNew);
    }

    @Benchmark
    public Object functionExprNew(ClassState state) {
        return state.run(state.functionExprNew);
    }

    @Benchmark
    public Object strictFunctionExprNew(ClassState state) {
        return state.run(state.strictFunctionExprNew);
    }

    @Benchmark
    public Object classDerivedNew(ClassState state) {
        return state.run(state.classDerivedNew);
    }

    @Benchmark
    public Object functionDerivedNew(ClassState state) {
        return state.run(state.functionDerivedNew);
    }

    @Benchmark
    public Object classCall(ClassState state) {
        return state.run(state.classCall);
    }

    @Benchmark
    public Object functionCall(ClassState state) {
        return state.run(state.functionCall);
    }

    @Benchmark
    public Object classSuperCall(ClassState state) {
        return state.run(state.classSuperCall);
    }

    @Benchmark
    public Object functionSuperCall(ClassState state) {
        return state.run(state.functionSuperCall);
    }

    /**
     * ROADMAP P9.4: the round trip of an await of a settled promise, against the same with then.
     */
    @Benchmark
    public Object awaitSettled(ClassState state) {
        return state.runMicrotasks(state.awaitSettled);
    }

    /** Appendix C Q64: an await of what an async function returns, which settles afterwards. */
    @Benchmark
    public Object awaitPending(ClassState state) {
        return state.runMicrotasks(state.awaitPending);
    }

    @Benchmark
    public Object thenSettled(ClassState state) {
        return state.runMicrotasks(state.thenSettled);
    }
}
