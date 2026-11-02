# 上游 PR 文案 (P8.2)

本文是 P8.2 准备的 mozilla/rhino PR 候选文案, 由维护者确认后由维护者推送与开 draft PR; 子会话不推送, 不开 PR. 分支在克隆 `E:/.cache/aj6-rhino-upstream-p8`, 备份 (bundle 与 patch) 在 `build/upstream-sync/class-p8-20261101/`. 分支, 提交, 测试结果与依赖的总表在 `NOTES.md` 的 P8.2 小节.

开 PR 之前:

- 先看附录 C Q49: 上游已有 draft #2397 (class) 与 #2340 (async), 作者 aardvark179. 建议先在 #835 问清楚想要怎样的拆分, 下面的主线文案第一段就是这个说明.
- 克隆的 `gradle.properties` 带本机的 `-Daj6.session`, 已用 `assume-unchanged` 排除, 分支提交里没有它.
- 提交里的身份是 noreply 邮箱; 分支基于上游 5f98a38f2, 推送前 rebase 到当时的 upstream/master, 主线的 "test262" 提交要重新生成 properties (见该段).
- 文案是英文, 直接贴到 PR 描述.

---

## 主线: ES2015+ classes (draft)

分支 `p8/class-mainline`, 五个提交: "Parse ES2015+ classes", "Run ES2015 classes in the interpreter and the compiler", "Run class fields, static blocks and private names", "Create built-in instances from new.target", "Unlock the class directories of test262".

```markdown
Implements ES2015+ classes, the work tracked in #835: declarations and expressions, `extends`, constructors and `super()`, instance and static methods, accessors, computed keys, generator methods, `super.x`, public and private fields, static blocks, private methods and accessors, `#x in obj`, and subclassing of the built-in constructors through `new.target`.

**Relation to #2397 and #2340.** I saw the draft PoCs from @aardvark179 after this work was done. This branch is an independent implementation, and I am opening it as a draft so we can compare the two and decide what to do. It takes a different approach: `IRFactory` lowers a class to the existing IR plus a single new node (`Token.CLASS` with a `ClassOp` property), so each backend gains one dispatch instead of many specialised bytecodes. Happy to split it differently, or to feed the tests and the fixes into #2397 if that is the better path.

Commits, in review order:

1. **Parse ES2015+ classes.** Tokens, AST nodes (`ClassNode`, `ClassElement`, `PrivateName`), `ClassParser` and the early errors. On at language version ES6 and above, behind `Context.FEATURE_CLASS_SYNTAX`; `class` stays a reserved word below that.
2. **Run ES2015 classes in the interpreter and the compiler.** `ClassLowering` in `IRFactory`, `ClassRuntime`, `ClassInterpreter`, `ClassBodyCodegen`. Includes the binding fixes the class directories of test262 need (`BindingLowering`) and `StackSpills` for `yield` with a non-empty operand stack in compiled generators.
3. **Run class fields, static blocks and private names.** `PrivateNameKey`, a separate private element table on `ScriptableObject`, invisible to reflection, Proxy, JSON and for-in.
4. **Create built-in instances from new.target.** `BuiltinConstruction` (GetPrototypeFromConstructor with GetFunctionRealm), used by every built-in constructor, so `class A extends Array` and friends get exotic instances.
5. **Unlock the class directories of test262.** Removes `class`, `class-fields-private` and `class-fields-public` from `UNSUPPORTED_FEATURES` and regenerates `test262.properties`.

Spec: ES2024 15.7 (Class Definitions), 15.4 (Method Definitions), 13.3.7 (The super Keyword), 6.2.12 (Private Names), 7.3.33 (PrivateFieldAdd and friends), 10.1.14 (GetPrototypeFromConstructor).

Behavior changes: none below language version ES6. At ES6 and above `class` is no longer a reserved word; the feature flag turns it off. `async` methods are not part of this PR: a class body rejects them, and the async test262 cases stay excluded.

Testing: `ClassSyntaxParserTest`, `ClassSyntaxTest`, `ClassFieldsTest`, `PrivateNamesTest` and `BuiltinSubclassingTest`, each case in interpreted and compiled mode. test262: 6909 more files pass against the current master and none newly fail. 5679 of them are in the class directories. Those directories keep 2747 of their 8426 files listed as failing: `language/expressions/class` 1367 of 4059, `language/statements/class` 1380 of 4367. 1913 of the 2747 need async functions or async iteration, which are not part of this PR.

Performance, `ClassBenchmark` on this branch, JDK 21, class against function plus prototype, interpreted / compiled: method call +0.5% / -10.4%, `super.m()` against `Base.prototype.m.call(this)` -14.2% / -23.2%, `new` +20.2% / +9.4%, `new` of a derived class -2.7% / -1.0%. The interpreted `new` is the one to watch: 342 ± 72 against 285 ± 17 us/op, so the error bars overlap, but I have not looked for the cost yet.

Interpreter V2 has no class instructions in this PR. It cannot compile from the IR yet, so they could not be exercised.
```

---

## 候选修复

每个候选一个 PR, 都不依赖 class, 分支基于 5f98a38f2, 除非写明依赖. 这些缺陷都是跑 test262 的 class 目录时发现的, 所以文案里提到 #835. `test262` 行是该分支相对基线重新生成 properties 的结果. 分支的最后一个提交 "Update test262.properties" 就是这份结果 (P6.8 没有变化, 没有这个提交); rebase 后要用 `-DupdateTest262properties` 重新生成再替换它.

### 1. OrdinaryToPrimitive 与 ToBigInt (P6.19, Q46)

分支 `p8/to-bigint-to-primitive`, 两个提交, 可以拆成两个 PR.

```markdown
**Return null from OrdinaryToPrimitive and follow ToBigInt**

Two fixes found while running test262 for classes (#835). The commits are independent if you would rather take them apart.

`ScriptableObject.getDefaultValue` treated a JS `null` returned by `valueOf` or `toString` as a non primitive (`null` is Java `null` in Rhino) and went on to the other method, so `{ valueOf() { return null; } } + ''` gave `[object Object]`. Spec: 7.1.1.1 OrdinaryToPrimitive.

`ScriptRuntime.toBigInt` also serves the `BigInt()` function, which takes a Number, while ToBigInt throws a TypeError for one. `BigInt.asIntN`, `BigInt.asUintN`, `DataView.prototype.setBigInt64`, `setBigUint64` and writes to BigInt typed arrays now use a new `ToBigInt.of`. `ScriptRuntime.toBigInt` is public, so I left it alone; it could become the real ToBigInt, with the Number case moved to `BigInt()` and `NativeJavaObject`, if you prefer that. Spec: 7.1.13 ToBigInt.

Behavior changes: `BigInt.asIntN(8, 1)`, `new DataView(buffer).setBigInt64(0, 1)` and `new BigInt64Array(1)[0] = 1` now throw a TypeError. `TypedArrayJavaTest.allTypes` built BigInt arrays from Numbers, so it now maps them through `BigInt`.

Testing: `ToBigIntAndToPrimitiveTest` in all modes. test262: 11 more files pass (BigInt.asIntN / asUintN 4, TypedArray 3, DataView 2, String 2) and none newly fail.
```

### 2. Reflect 的键 (P6.13 Q27)

分支 `p8/reflect-property-keys`, 一个提交.

```markdown
**Follow ToPropertyKey in the Reflect methods**

Found while running test262 for classes (#835). The Reflect methods took a string key as a name, so a Proxy trap forwarding its key through `Reflect.get` read no array element, and a missing value came back as the scriptable undefined, whose `typeof` is "object".

The key is now converted once with ToPropertyKey, before anything else the method converts, and dispatched to the index or the name the way property access does. `Reflect.get` returns plain undefined, `Reflect.deleteProperty` deletes a property of the target only, `Reflect.has` keeps the indexes of a typed array own, and `Reflect.defineProperty` converts the key before its argument check, so an error from the key is no longer turned into `false`.

Spec: 28.1 The Reflect Object, 7.1.19 ToPropertyKey.

Testing: `ReflectKeyTest` in all modes. test262: 5 more files pass (Reflect 2, TypedArray internals 2, Proxy 1) and none newly fail.
```

### 3. Proxy 的 [[Set]] / [[Delete]] (P6.14, Q33)

分支 `p8/proxy-set-delete`, 基于 `p8/reflect-property-keys`, 排在它之后. `AbstractEcmaObjectOperations.ordinarySet` 在这个分支上已经没有调用者, 但它是 public, 留给评审决定是否删除.

```markdown
**Follow [[Set]] and [[Delete]] with their results for Reflect and Proxy**

Depends on #NNN (Reflect keys). Found while running test262 for classes (#835).

`put` and `delete` return nothing in Rhino, so the results of [[Set]] and [[Delete]] were lost:

- `Reflect.set` always returned true, and a set trap forwarding with `Reflect.set(t, k, v, r)` recursed until StackOverflowError.
- A Proxy without a set trap replaced the receiver with its target, so the getOwnPropertyDescriptor and defineProperty traps of a Proxy receiver were not called and a setter saw the target as `this`.
- An assignment through a prototype chain asked the has trap of a Proxy and never called its set trap.
- `Reflect.deleteProperty` and `delete p.k` took their result from the has trap that followed.

This adds `OrdinarySet` (10.1.9 OrdinarySet, 10.5.9 Proxy [[Set]]) and `OrdinaryDelete` (10.5.10 Proxy [[Delete]]) and uses them for `Reflect.set`, `Reflect.deleteProperty`, the `delete` operator on a Proxy, the `put` methods of `NativeProxy` and assignment through a prototype chain. Plain objects are still written the way they were, so dense arrays and Java slots keep their fast paths. Assignment to a primitive base follows PutValue, `ToObject(base).[[Set]](k, v, base)`, `copyWithin` asks HasProperty before Get, and an assignment in `with (proxy)` asks the has trap first.

Behavior changes, all of them what the spec says: strict code throws a TypeError when a Proxy assignment or `delete` returns false; `Reflect.set` returns the real result and calls setters on the prototype; a setter behind a Proxy without a set trap sees the Proxy as `this`; strict assignment to a property of a primitive calls a setter or Proxy on the prototype instead of throwing first; `copyWithin` copies an element whose value is undefined instead of deleting the target.

Cost: assignment walks the prototype chain with one more `instanceof NativeProxy` per level. Across eight forms of assignment, interpreted is 0.94 to 1.04 times the old cost; compiled is 0.87 to 1.07, except an assignment to an existing own property at 1.09 to 1.14, which is about the size of the noise on that machine.

Testing: `ProxySetAndDeleteTest` in all modes, and the upstream `NativeProxyTest`. test262: 43 more files pass on top of that PR, 48 against master (Proxy 24, Reflect 9, TypedArray internals 9, `language/types` 2, JSON 2, Object 1, Array 1), and none newly fail.
```

### 4. Set 集合方法与 `Object.fromEntries` (P6.11, Q23 / Q24)

分支 `p8/set-likes-from-entries`, 一个提交. 这个分支的测试去掉了依赖 P6.10 的一处断言.

```markdown
**Take the keys of set-likes as iterators and read fromEntries entries with Get**

Found while running test262 for classes (#835). The Set methods treated the result of `other.keys()` as an iterable and called its `Symbol.iterator`, so an iterator with only `next` threw; GetSetRecord's users take the result as the iterator itself, and it has to be an object. `Object.fromEntries` read the `0` and `1` of an entry without walking the prototype chain and with the wrong `this` for accessors.

Spec: 24.2.1.2 GetSetRecord, 7.3.34 AddEntriesFromIterable.

Testing: `SetMethodsAndFromEntriesTest` in all modes. test262: 2 more files pass (`Set.prototype.difference` and `intersection`, `set-like-class-mutation.js`) and none newly fail.
```

### 5. 编译模式的两个缺陷 (P6.8, Q19 / Q20)

分支 `p8/compiled-home-object-number-callee`, 一个提交.

```markdown
**Keep the home object of nested object literals, box number callees**

Two bugs of the compiled mode, found while running test262 for classes (#835).

An object literal nested in another one, such as an inline `__proto__: {...}`, replaced the saved home object of the outer literal, so `super` in a later method of the outer literal read the wrong object.

A call whose callee is a number variable, or an assignment or increment of one, reached the call as a double, which ended in a VerifyError or a failed assertion in the generator code. The expected TypeError for calling a number comes back.

Testing: `CompiledModeFixesTest`, both cases in all modes. test262: no change.
```

### 6. 解构默认值 (P6.13 Q30)

分支 `p8/destructuring-default-target`, 一个提交.

```markdown
**Apply destructuring defaults to targets that already hold a value**

Found while running test262 for classes (#835). A default was only assigned when the target was still undefined, so `[x = 1] = [undefined]` left a previous `x` alone, and a declaration running again in a loop kept the value of the last round. The value of the element decides, not the target.

Spec: 8.6.2 DestructuringAssignmentEvaluation, 14.3.3 BindingInitialization.

Testing: `DestructuringDefaultsTest` in all modes, parameter defaults included. test262: 5 more files pass (`assignment/dstr` 3, `for-of/dstr` 2) and none newly fail.
```

### 7. `set: undefined` (P6.7 Q18)

分支 `p8/undefined-setter`, 一个提交. **先不要单独开这个 PR**: 上游的 `a &&= b` 不短路, 总是写回, 所以这个修复让 3 个 `logical-assignment/*-no-set.js` 在严格模式下新失败 (它们原来是因为写入被悄悄忽略才碰巧通过, 已登记进该分支的 properties). fork 里这 3 个由 P6.7 逻辑赋值短路一起解决. 先做续做清单第一项 P6.7, 把本分支 rebase 到它上面重新生成 properties, 然后删掉文案里 "Interaction" 这一段.

```markdown
**Reject assignment to an accessor with an undefined setter**

Found while running test262 for classes (#835). `Object.defineProperty(o, 'x', { get() {}, set: undefined })` stored a setter that is not callable, so a strict assignment was silently ignored. OrdinarySetWithOwnDescriptor returns false when the setter is undefined, whichever way it came to be undefined. The accessor without a `set` already behaved that way.

Spec: 10.1.9.2 OrdinarySetWithOwnDescriptor, step 4.

Interaction: `a &&= b`, `a ||= b` and `a ??= b` do not short circuit yet, they always write back. Three strict cases (`language/expressions/logical-assignment/lgcl-{and,or,nullish}-assignment-operator-no-set.js`) passed only because that write was silently ignored, and they now fail with a TypeError. They are listed in `test262.properties` here and come back with the short circuit fix (#NNN).

Testing: `UndefinedSetterTest` in all modes. test262: 15 more files pass (`compound-assignment` 11, `logical-assignment` 3, `assignment` 1), and the 3 files above newly fail.
```

### 8. 编译模式块内 `super` (P6.7)

分支 `p8/compiled-super-in-block`, 一个提交.

```markdown
**Find the home object of super in a block of a compiled method**

Found while running test262 for classes (#835). The compiled code cast the current scope to `NativeCall` to read the home object, but inside a let block that needs a scope of its own, or inside a `with` statement, the current scope is a child of the activation, and `super` threw a ClassCastException. It now walks up to the activation.

Testing: `SuperInBlockTest` in all modes. test262: no change.
```

---

## 续做的候选 (P8.2 续做, 没有分支)

这些候选依赖 fork 其他部分, 要先替换依赖, 一张卡做不完, 清单与依赖见 roadmap P8.2 的续做子项. 下面只留下必须写进文案的计时取舍 (Q39 / Q40 / Q45), 原文可以直接并入对应 PR.

### P6.15 Proxy 的 HasProperty (Q39) 与读取 (Q40)

```markdown
Cost of `in`: HasProperty now checks for a Proxy at every level of the prototype chain. That alone made `in` along a prototype chain up to 2.2 times slower in compiled mode (a four level chain held in a local variable went from 12.5 ns to 28 ns, the C2 result for this tight loop, whatever the form of the check). So until the first Proxy is created in the process, `hasProperty` keeps the old `getBase` loop behind a volatile flag set by the `NativeProxy` constructor, and the old numbers hold (1.01 to 1.09 times). The flag never resets: once any script in the process has created a Proxy, compiled `in` along a prototype chain costs 1.15 to 2.12 times the old cost, Proxy on that chain or not. Interpreted stays at 0.90 to 1.24, which is within the noise.

Cost of reads in `with`: in compiled mode a name in a `with` block that the with object does not have (`with (env) { s += outer }`) costs 1.03 to 1.41 times the old cost, mostly 1.2 to 1.3; interpreted is 1.05 to 1.06. The read loop `getPropWalkingPrototypeChain` with the Proxy check is 43 bytes, over HotSpot's MaxInlineSize of 35, so cold call sites stop inlining it. A smaller form moved the cost to compiled `in` on globals (1.09 to 1.15), and a guard at the entry of `getProperty` was worse for plain reads (1.09 to 1.15) and `in` (1.12 to 1.18), so I kept the check in the loop.
```

### P6.17 `with` 里名字的逻辑赋值 (Q42, Q45)

```markdown
Cost: in compiled mode a logical assignment to a name in a `with` block that short circuits (`with (env) { a ||= k; b &&= k }`) costs 1.16 times the old cost over 15 rounds; interpreted is 1.05, and the writing form is 0.90 because the name now resolves only once. The number of lookups is the same as before. The difference is that the read is split into two runtime calls, one that finds the binding and one that reads from it. A name whose innermost scope is the `with` scope goes straight to HasBinding, which took it from 1.22 to 1.16.
```
