# class / async 钩子清单

本文是 fork 的 class / async 定制在上游文件里留下的钩子的唯一事实来源 (`AGENTS.md` 第七节 7.1). 路线图见同目录 `ROADMAP.md`, 决策 D1-D16 与 T1-T11 不在此重复.

- 上游文件里每一行新增或改动的代码都带 `// AutoJs6: class syntax` 或 `// AutoJs6: async` 标记 (钩子块前后的空行除外); 逻辑放在第 4 节的新文件里. 核对: `git diff -U0 <基线> -- rhino/src/main/java` 里新增的非空行都应带标记.
- 第 3 节的表按 "文件 + 标记" 记行数, `HooksRegistryTest` 会把它与 `rg -n "AutoJs6: (class syntax|async)" rhino/src/main/java tests/src` 的实际结果逐文件逐标记对照 (P6 起含上游测试源码 `tests/src`); 第 4 节的新文件 (`rhino/` 与 `tests/` 下) 必须存在.
- 钩子增减或搬家时, 同一提交内更新本文. 行号会随上游变化, 表里只写方法名.

## 1. 阶段进度

| 阶段 | 状态 | 内容 |
|---|---|---|
| P1 | 完成 (2026-10-24) | 词法, AST, 解析器, 早期错误, async 预解析, 编译期 "暂不支持" 报错 |
| P2 | 完成 (2026-10-25) | class 降糖与运行时原语, 解释 / 编译后端, V2 指令类; 通用绑定修复 (参数作用域, 参数解构的迭代器协议, for-of 关闭, 函数名推断); 生成器 `yield` 解析 (附录 C Q10) 与编译模式 yield 点的栈类型保存 |
| P3 | 完成 (2026-10-25) | 公共字段, static block, 私有名 (T5: 每次类求值新建 `PrivateNameKey`, 对象上独立的私有元素表), 直接 eval 里的外层私有名与类元素上下文 (附录 C Q13), V2 指令类 |
| P4 | 完成 (2026-10-25) | 内建构造器按 new.target 创建 (T9): 统一入口 `ScriptRuntime.createFromConstructor` 与新文件 `BuiltinConstruction` (GetPrototypeFromConstructor 含 GetFunctionRealm, 跨 realm 按构造器名取对方的内建原型); 逐个构造器改为先取原型 (附录 D 36 行); Map / Set 装入用实例的 `set` / `add`, RegExp 的 IsRegExp 与 RegExpExec, DataView 取原型后重查, ArrayBuffer 的 `Symbol.species`, TypedArray 非对象实参的 ToIndex, 生成器函数的 prototype 不带 constructor, Proxy 不读 new.target; 拆掉 P2 的构造后改原型脚手架 (只留 Java 类给 P5) |
| P5 | 完成 (2026-10-25) | 继承 Java 类与接口 (T6, fork-only D3): `class X extends JavaClass` / `extends Interface` / `extends JavaAdapter(Base, I1, I2)` (附录 C Q3 = A), 实例是包装适配器对象的脚本对象 (附录 C Q4 = A), 适配器类经 `JavaAdapter.createAdapterCode` 按签名缓存; 钩子层在 `autojs6` 包 |
| P6 | 完成 (2026-10-25) | test262 解锁: `Test262SuiteTest` 运行 class 特性 (T10 的 `decorators` / `nonextensible-applies-to-private` 改为 unsupported), `test262.properties` 由工具重生成 (class 两目录不再整目录排除, 剩余登记只有 P9 与 T10, 附录 E); 可选的 Interpreter V2 模式 (`-Drhino.test262.interpreterV2=true`, 目前 0 例); `HooksRegistryTest` 起把上游测试源码 `tests/src` 一并对账; `rhino/src/main/java` 不变 |
| P6.6 | 完成 (2026-10-26) | 调用里的实参展开 (附录 C Q16 = B, 与 class 无关的通用修复): `f(...a)` / `o.m(...a)` / `super.m(...a)` / `new F(...a)` / `eval(...a)`, IRFactory 把全部实参收进一个数组字面量, 解释器经 5 个 `SPREAD_*` icode (普通调用复用 CALL 指令的内联帧), 编译器经 `generateCallArgArray` |
| P6.7 | 完成 (2026-10-26) | 逻辑赋值 `||=` / `&&=` / `??=` 的短路 (附录 C Q17 = B, 通用修复): IRFactory 降为 `a || (a = b)` 形式, 属性引用的对象与非常量键存进 let 表达式的临时绑定, 两个后端不改; 显式 `set: undefined` 的访问器在严格模式写入抛 TypeError (附录 C Q18, 已按推荐先行) |
| P6.8 | 完成 (2026-10-26) | 编译模式的两个上游缺陷 (P6.6 / P6.7 时发现, 维护者要求修复, 通用修复): 嵌套对象字面量覆盖外层方法的 home object (内联 `__proto__: {...}` 之后的方法里 `super` 读错对象); 调用的 callee 是数字变量 (及其赋值 / 自增) 时以 double 进入调用 (`VerifyError` 或生成器断言) |
| P7-P9 | 未开始 | 见 `ROADMAP.md` 第 5 节 |

## 2. 开关与门控

- `CompilerEnvirons.isClassSyntaxEnabled()` / `setClassSyntaxEnabled(boolean)`: 默认开, `initFromContext` 取 `Context.FEATURE_CLASS_SYNTAX`.
- `CompilerEnvirons.isAsyncSyntaxEnabled()` / `setAsyncSyntaxEnabled(boolean)`: 默认开, `initFromContext` 取 `Context.FEATURE_ASYNC_SYNTAX`.
- `Context.FEATURE_CLASS_SYNTAX` (23) / `Context.FEATURE_ASYNC_SYNTAX` (24): `ContextFactory.hasFeature` 默认返回 true.
- ES6 门控 (D4): 两个开关都只在语言版本 >= `Context.VERSION_ES6` (200) 时生效. 低于 ES6 或开关关闭时, `class` 仍是保留字 (`msg.reserved.id`), `async` 是普通标识符, `await` 保持原样.
- 上下文关键字 (T3): `extends` / `static` / `get` / `set` / `async` / `of` 不是全局保留字; `await` 只在 async 函数体与参数内是关键字, 脚本顶层与普通函数里仍是标识符 (D15); class static block 内 `await` 不可用作标识符.
- 关闭开关与低版本的行为由 `ClassSyntaxParserTest.lexerGate` / `AsyncSyntaxParserTest.switchOff` 覆盖.

## 3. 上游文件钩子

路径相对仓库根目录. "行数" 是带该标记的行数; 同一文件同一标记可以分多行登记, 测试按和对照.

| 文件 | 标记 | 行数 | 用途 | 阶段 |
|---|---|---|---|---|
| `rhino/src/main/java/org/mozilla/javascript/Token.java` | class syntax | 3 | 链尾新增 `CLASS` / `PRIVATE_NAME` / `CLASS_ELEMENT` | P1 |
| `rhino/src/main/java/org/mozilla/javascript/Token.java` | async | 2 | 链尾新增 `AWAIT`, `LAST_TOKEN` 改接到 `AWAIT + 1` | P1 |
| `rhino/src/main/java/org/mozilla/javascript/Token.java` | class syntax | 8 | `typeToName` 与 `keywordToName` 的 `CLASS` / `PRIVATE_NAME` / `CLASS_ELEMENT` 分支 | P1 |
| `rhino/src/main/java/org/mozilla/javascript/Token.java` | async | 4 | `typeToName` 与 `keywordToName` 的 `AWAIT` 分支 | P1 |
| `rhino/src/main/java/org/mozilla/javascript/TokenStream.java` | class syntax | 5 | 记录最后扫描的单词起点与是否含转义 (`wordBeg` / `wordEscaped`, 字段与 `getToken` 里的赋值) | P1 |
| `rhino/src/main/java/org/mozilla/javascript/TokenStream.java` | class syntax | 1 | 关键字识别后交给 `ClassParser.keyword` (ES6 门控下 `class` 由 RESERVED 变为 `Token.CLASS`) | P1 |
| `rhino/src/main/java/org/mozilla/javascript/TokenStream.java` | async | 1 | 关键字识别后交给 `AsyncParser.keyword` (async 函数体内 `await` 变为 `Token.AWAIT`) | P1 |
| `rhino/src/main/java/org/mozilla/javascript/TokenStream.java` | class syntax | 1 | `#` 开头时交给 `ClassParser.privateName` 扫描私有名 | P1 |
| `rhino/src/main/java/org/mozilla/javascript/Context.java` | class syntax | 2 | `FEATURE_CLASS_SYNTAX` 常量与说明 | P1 |
| `rhino/src/main/java/org/mozilla/javascript/Context.java` | async | 2 | `FEATURE_ASYNC_SYNTAX` 常量与说明 | P1 |
| `rhino/src/main/java/org/mozilla/javascript/ContextFactory.java` | class syntax | 2 | `hasFeature` 对 `FEATURE_CLASS_SYNTAX` 默认返回 true (含共用的 `return true`) | P1 |
| `rhino/src/main/java/org/mozilla/javascript/ContextFactory.java` | async | 1 | `hasFeature` 对 `FEATURE_ASYNC_SYNTAX` 默认返回 true | P1 |
| `rhino/src/main/java/org/mozilla/javascript/CompilerEnvirons.java` | class syntax | 10 | class 开关: 字段, 构造器默认值, `initFromContext`, 读写方法 | P1 |
| `rhino/src/main/java/org/mozilla/javascript/CompilerEnvirons.java` | async | 10 | async 开关: 字段, 构造器默认值, `initFromContext`, 读写方法 | P1 |
| `rhino/src/main/java/org/mozilla/javascript/Parser.java` | class syntax | 4 | 字段可见性放宽为包内, 供 `ClassParser` 使用: `sourceURI`, `ts`, `insideMethod`, `inForInit` | P1 |
| `rhino/src/main/java/org/mozilla/javascript/Parser.java` | class syntax | 17 | 方法可见性放宽为包内: `peekToken`, `lineNumber`, `columnNumber`, `peekFlaggedToken`, `consumeToken`, `nextToken`, `peekTokenOrEOL`, `parseFunctionBody` (2 行), `function(int, boolean)` (2 行), `assignExpr`, `shiftExpr`, `memberExpr`, `objliteralProperty`, `createNameNode(boolean, int)`, `makeErrorNode` | P1 |
| `rhino/src/main/java/org/mozilla/javascript/Parser.java` | class syntax | 1 | 字段 `classSyntax` (`ClassParser` 实例) | P1 |
| `rhino/src/main/java/org/mozilla/javascript/Parser.java` | class syntax | 3 | 桥接方法 `inSingleStatementContext()` (if / 循环体等单语句位置不允许 class 声明) | P1 |
| `rhino/src/main/java/org/mozilla/javascript/Parser.java` | class syntax | 2 | `statementHelper`: `case Token.CLASS` 转 `classSyntax.declaration()` | P1 |
| `rhino/src/main/java/org/mozilla/javascript/Parser.java` | class syntax | 2 | `withStatement` 与 `nameOrLabel`: `classSyntax.checkStatementPosition` (with 体与标签后不允许 class 声明) | P1 |
| `rhino/src/main/java/org/mozilla/javascript/Parser.java` | class syntax | 1 | `returnOrYield`: `yield` 与 `*` 之间换行时不是 `yield*` (`peekTokenOrEOL`) | P1 |
| `rhino/src/main/java/org/mozilla/javascript/Parser.java` | class syntax | 1 | `relExpr`: 首个操作数走 `classSyntax.relationalOperand()` (`#x in obj`) | P1 |
| `rhino/src/main/java/org/mozilla/javascript/Parser.java` | class syntax | 1 | `unaryExpr`: `classSyntax.checkDelete` (`delete this.#x` 早期错误) | P1 |
| `rhino/src/main/java/org/mozilla/javascript/Parser.java` | class syntax | 3 | `propertyAccess`: `.` 后的 `Token.PRIVATE_NAME` 转 `classSyntax.privateMember` | P1 |
| `rhino/src/main/java/org/mozilla/javascript/Parser.java` | class syntax | 2 | `primaryExpr`: `case Token.CLASS` 转 `classSyntax.expression()` | P1 |
| `rhino/src/main/java/org/mozilla/javascript/Parser.java` | class syntax | 1 | `primaryExpr` 的 `super`: `classSyntax.checkSuper()` (`super()` 只在派生构造器, `super.x` 只在方法 / 字段 / static block) | P1 |
| `rhino/src/main/java/org/mozilla/javascript/Parser.java` | class syntax | 1 | `name`: 标识符引用交给 `classSyntax.checkArguments` (字段初始化器与 static block 不允许引用 `arguments`; `this.arguments` 之类的属性名不受影响) | P1 |
| `rhino/src/main/java/org/mozilla/javascript/Parser.java` | class syntax | 1 | `plainProperty`: 对象字面量简写 `{ arguments }` 同样交给 `classSyntax.checkArguments` | P1 |
| `rhino/src/main/java/org/mozilla/javascript/Parser.java` | class syntax | 1 | `objliteralProperty`: `*` 后的键不合法 (如私有名 `#m`) 时返回 null 交给调用方报 `msg.bad.prop`, 不再把 null 传进 `GeneratorMethodDefinition` | P1 |
| `rhino/src/main/java/org/mozilla/javascript/Parser.java` | class syntax | 2 | `PerFunctionVariables` 构造与 `restore`: `classSyntax.enterFunction` / `exitFunction` (函数种类栈) | P1 |
| `rhino/src/main/java/org/mozilla/javascript/Parser.java` | async | 1 | 字段 `asyncSyntax` (`AsyncParser` 实例) | P1 |
| `rhino/src/main/java/org/mozilla/javascript/Parser.java` | async | 3 | 方法可见性放宽为包内: `function(int)`, `arrowFunction`, `unaryExpr` | P1 |
| `rhino/src/main/java/org/mozilla/javascript/Parser.java` | async | 2 | `statementHelper`: `async function` 声明转 `asyncSyntax.declaration()` | P1 |
| `rhino/src/main/java/org/mozilla/javascript/Parser.java` | async | 3 | `forLoop`: `for await (... of ...)` 的识别, 非 of 形式报错, 生成 `ForAwaitOfLoop` | P1 |
| `rhino/src/main/java/org/mozilla/javascript/Parser.java` | async | 1 | `assignExpr`: `=>` 前的 async 头转 `asyncSyntax.arrowFunction` | P1 |
| `rhino/src/main/java/org/mozilla/javascript/Parser.java` | async | 2 | `unaryExpr`: `case Token.AWAIT` 转 `asyncSyntax.awaitExpression()` | P1 |
| `rhino/src/main/java/org/mozilla/javascript/Parser.java` | async | 2 | `name`: `async function` 表达式与 async 箭头函数头转 `asyncSyntax.name` | P1 |
| `rhino/src/main/java/org/mozilla/javascript/Parser.java` | async | 2 | `objectLiteral`: `async` 方法键转 `asyncSyntax.methodKey`, 并按方法处理 | P1 |
| `rhino/src/main/java/org/mozilla/javascript/Parser.java` | async | 2 | `PerFunctionVariables` 构造与 `restore`: `asyncSyntax.enterFunction` / `exitFunction` (await 上下文栈) | P1 |
| `rhino/src/main/java/org/mozilla/javascript/IRFactory.java` | class syntax | 2 | `transform`: `case Token.CLASS` 转 `ClassLowering.transform` (P1 报 `msg.class.not.supported`) | P1 |
| `rhino/src/main/java/org/mozilla/javascript/IRFactory.java` | async | 1 | `transformFunction` 开头 `AsyncLowering.check` (P1 报 `msg.async.not.supported`) | P1 |
| `rhino/src/main/java/org/mozilla/javascript/ast/FunctionNode.java` | async | 8 | `isAsync` 字段与读写方法, `toSource` 输出 `async ` | P1 |
| `rhino/src/main/java/org/mozilla/javascript/ast/ObjectProperty.java` | async | 3 | `toSource`: async 方法输出 `async ` | P1 |
| `rhino/src/main/java/org/mozilla/classfile/ClassFileWriter.java` | class syntax | 4 | `stopMethod`: 本方法有 yield 点保存操作数栈时先跑一遍栈图分析 (`StackMapTable.resolveSpills`) 回填装箱与强转, 方法结束清空 | P2 |
| `rhino/src/main/java/org/mozilla/classfile/ClassFileWriter.java` | class syntax | 7 | `getStackSpills` 与字段 `itsStackSpills`: 当前方法的 `StackSpills` | P2 |
| `rhino/src/main/java/org/mozilla/classfile/ClassFileWriter.java` | class syntax | 20 | `StackMapTable`: `resolveSpills`, `generate` 的分析分支 (分析完调用 `StackSpills.patch`), `verify` 在分析时不清理死代码, `executeBlock` 每条指令前的 `stepSpill` (记录保存项类型, 恢复项按记录的类型入栈, 类型变化时重跑恢复所在块) 与字段 `spillAnalysis` | P2 |
| `rhino/src/main/java/org/mozilla/javascript/BaseFunction.java` | class syntax | 6 | `Function.prototype` 描述符: `caller` 受限访问器 (class 代码里的函数访问时抛 TypeError, 其他函数得 undefined) | P2 |
| `rhino/src/main/java/org/mozilla/javascript/BaseFunction.java` | class syntax | 2 | `arguments` 访问器: `ClassRuntime.checkRestricted` | P2 |
| `rhino/src/main/java/org/mozilla/javascript/BoundFunction.java` | class syntax | 4 | `isConstructor`: 绑定函数可否构造取决于目标 (`extends` 的校验用) | P2 |
| `rhino/src/main/java/org/mozilla/javascript/CallFrame.java` | class syntax | 4 | rest 参数收集计入 `boundArgs` (经 bind 内联调用带 rest 参数的函数时绑定的实参会漏掉) | P2 |
| `rhino/src/main/java/org/mozilla/javascript/CodeGenUtils.java` | class syntax | 5 | `setConstructor`: 类构造器可构造 (方法定义不可), 记录是否派生构造器 | P2 |
| `rhino/src/main/java/org/mozilla/javascript/CodeGenUtils.java` | class syntax | 1 | `fillInCommon`: 记录函数是否 class 代码 (`ClassLowering.isClassCode`, 受限访问器用) | P2 |
| `rhino/src/main/java/org/mozilla/javascript/CodeGenerator.java` | class syntax | 4 | `visitExpression` 可见性放宽为包内; `case Token.CLASS` 转 `ClassInterpreter.generate` | P2 |
| `rhino/src/main/java/org/mozilla/javascript/CodeGenerator.java` | class syntax | 5 | 发射辅助方法可见性放宽为包内: `addIcode`, `addUint8`, `addStringOp`, `addIndexOp`, `stackChange` | P2 |
| `rhino/src/main/java/org/mozilla/javascript/IRFactory.java` | class syntax | 2 | 字段 `outerScopeIsStrict` (extends 表达式按类外的严格性求值); `transform` 可见性放宽为包内 | P2 |
| `rhino/src/main/java/org/mozilla/javascript/IRFactory.java` | class syntax | 2 | `transformElementGet` 与 `createPropertyGet`: `ClassLowering.checkSuperThis` (派生构造器里 `super.x` / `super[x]` 先读 this) | P2 |
| `rhino/src/main/java/org/mozilla/javascript/IRFactory.java` | class syntax | 5 | `transformFunction`: 参数作用域 (`BindingLowering.ParamScope` 创建与 `apply`), 参数 TDZ (`paramTdz`), 默认值的函数名推断 (`named`), 生成器参数解构移入参数初始化块 (`genParams`) | P2 |
| `rhino/src/main/java/org/mozilla/javascript/IRFactory.java` | class syntax | 3 | `transformFunctionCall`: 派生构造器 (及其箭头函数) 里的 `super(...)` 转 `ClassLowering.transformSuperCall`, 其他位置照旧由 `transformLiteral` 报错 | P2 |
| `rhino/src/main/java/org/mozilla/javascript/IRFactory.java` | class syntax | 1 | `transformLiteral` 交给 `ClassLowering.transformLiteral`: 派生构造器里的 `this` 转 THIS 原语 (P2), 类元素里直接 eval 代码顶层的 `new.target` 为 undefined (P3) | P2 / P3 |
| `rhino/src/main/java/org/mozilla/javascript/IRFactory.java` | class syntax | 4 | `transformName`: 类定义期间引用类名转 TDZ 原语 | P2 |
| `rhino/src/main/java/org/mozilla/javascript/IRFactory.java` | class syntax | 1 | `createForIn`: for-of 包上关闭迭代器的 try / finally (`BindingLowering.forOf`) | P2 |
| `rhino/src/main/java/org/mozilla/javascript/IRFactory.java` | class syntax | 1 | `createTryCatchFinally` 可见性放宽为包内 | P2 |
| `rhino/src/main/java/org/mozilla/javascript/IRFactory.java` | class syntax | 3 | `inferNameIfMissing`: 匿名类的名字推断交给 `ClassLowering.inferClassName` | P2 |
| `rhino/src/main/java/org/mozilla/javascript/Icode.java` | class syntax | 13 | 链尾新增 `CLASS_DEFINE` ... `CLASS_PARAM_SCOPE` 11 个 icode (含注释行), `MIN_ICODE` 改接到 fork 链尾 (P3 起为 `CLASS_STATIC_INIT`) | P2 |
| `rhino/src/main/java/org/mozilla/javascript/Icode.java` | class syntax | 4 | `bytecodeName`: fork icode 的名字取自 `ClassInterpreter.icodeName` | P2 |
| `rhino/src/main/java/org/mozilla/javascript/Interpreter.java` | class syntax | 4 | `NewState`, `InterpreterState`, `ICodeDumpContext`, `InstructionClass` 可见性放宽为包内 (`ClassInterpreter` 的指令类用) | P2 |
| `rhino/src/main/java/org/mozilla/javascript/Interpreter.java` | class syntax | 1 | 指令表初始化: `ClassInterpreter.register` | P2 |
| `rhino/src/main/java/org/mozilla/javascript/Interpreter.java` | class syntax | 1 | `DoCallByteCode` 的 `CALL_ON_SUPER` (`super.m()`): 当前 this 经 `ClassRuntime.receiver` (派生构造器 super() 之前报错) | P2 |
| `rhino/src/main/java/org/mozilla/javascript/Interpreter.java` | class syntax | 3 | `DoNew`: 派生构造器不走内联帧 (`ClassRuntime.inlinesNew`), 类构造器不按方法拒绝 (`isMethod`), 类构造器也创建 this 对象 | P2 |
| `rhino/src/main/java/org/mozilla/javascript/JSDescriptor.java` | class syntax | 5 | `isDerivedConstructor` 读取已有的 `DERIVED_CONSTRUCTOR_FLAG`; `Builder.derivedConstructor` 字段与传入构造器 | P2 |
| `rhino/src/main/java/org/mozilla/javascript/JSDescriptor.java` | class syntax | 3 | 包内字段 `classCode` (不改构造器签名, `build` 之后赋值) 与 `Builder.classCode` | P2 |
| `rhino/src/main/java/org/mozilla/javascript/JSFunction.java` | class syntax | 4 | `construct`: 派生构造器交给 `ClassRuntime.constructDerived`; 有 home object 的类构造器同样创建 this (P2); this 对象经 `BuiltinConstruction.thisObject`: 脚本函数按 new.target 创建, 内建构造器自己建实例, 给它的 this 不读 new.target (P4) | P2 / P4 |
| `rhino/src/main/java/org/mozilla/javascript/NativeObject.java` | class syntax | 1 | `js_constructor`: new.target 不是 Object 自身时忽略实参, 按 new.target 创建 (T9) | P2 |
| `rhino/src/main/java/org/mozilla/javascript/Node.java` | class syntax | 7 | 属性链尾新增 `CLASS_PROP` / `DEFAULT_NAME_PROP`, `LAST_PROP` 改接; `propToString` 的两个分支 | P2 |
| `rhino/src/main/java/org/mozilla/javascript/NodeTransformer.java` | class syntax | 4 | `transformCompilationUnit_r` 的 OBJECTLIT: 展开项既是子节点又在属性 id 里, 不再变换第二次 (生成器里展开中的 yield 会登记两个恢复点); 辅助方法 `isSpread` | P2 |
| `rhino/src/main/java/org/mozilla/javascript/Parser.java` | class syntax | 1 | 字段 `bindings` (`BindingLowering` 实例) | P2 |
| `rhino/src/main/java/org/mozilla/javascript/Parser.java` | class syntax | 4 | `statementHelper`: ES6 生成器里 `yield` 开头的语句转 `classSyntax.yieldStatement` (`yield a, yield b;`) | P2 |
| `rhino/src/main/java/org/mozilla/javascript/Parser.java` | class syntax | 4 | `returnOrYield`: `yield*` 后可以换行, 逗号与冒号前的 yield 无操作数, 操作数走 `classSyntax.returnOrYieldOperand` (ES6 生成器里是 AssignmentExpression) | P2 |
| `rhino/src/main/java/org/mozilla/javascript/Parser.java` | class syntax | 5 | `expr` 可见性放宽为包内; `expr` 的逗号后与 `argumentList` 里的 yield 只对 JS 1.7 生成器要求括号 (`classSyntax.isLegacyYield`) | P2 |
| `rhino/src/main/java/org/mozilla/javascript/Parser.java` | class syntax | 1 | `memberExpr` 的 new: `classSyntax.checkNewSuper` (`new super()` 早期错误) | P2 |
| `rhino/src/main/java/org/mozilla/javascript/Parser.java` | class syntax | 1 | `methodDefinition`: `classSyntax.generatorMethod` (对象字面量生成器方法在方法体解析前标记) | P2 |
| `rhino/src/main/java/org/mozilla/javascript/Parser.java` | class syntax | 1 | `checkActivationName`: 参数里引用 `arguments` 时 `bindings.requireArguments` (需要活动对象) | P2 |
| `rhino/src/main/java/org/mozilla/javascript/Parser.java` | class syntax | 12 | `destructuringArray`: 函数参数的数组解构按迭代器协议 (`bindings.iterates` / `step` / `rest`, 空位也前进) | P2 |
| `rhino/src/main/java/org/mozilla/javascript/Parser.java` | class syntax | 1 | `processDestructuringDefaults`: 解构默认值的函数名推断 (`bindings.inferName`) | P2 |
| `rhino/src/main/java/org/mozilla/javascript/Parser.java` | class syntax | 6 | `setupDefaultValues` 可见性放宽为包内; `destructuringObject`: 空对象模式与只有 rest 的模式也处理默认值与 RequireObjectCoercible (`bindings.requireObject`) | P2 |
| `rhino/src/main/java/org/mozilla/javascript/ScriptRuntime.java` | class syntax | 6 | `getSuperElem` / `getSuperProp` / `getSuperIndex` / `setSuperElem` / `setSuperProp` / `setSuperIndex`: this 经 `ClassRuntime.receiver` | P2 |
| `rhino/src/main/java/org/mozilla/javascript/ScriptRuntime.java` | class syntax | 4 | `IdEnumeration` 可见性放宽为包内与字段 `done`; `enumNextInOrder` 调用 next 期间置位 (next 抛错或完成时不再 IteratorClose) | P2 |
| `rhino/src/main/java/org/mozilla/javascript/ScriptRuntime.java` | class syntax | 1 | `callSpecial` 的直接 eval: this 经 `ClassRuntime.unwrapThis` | P2 |
| `rhino/src/main/java/org/mozilla/javascript/optimizer/BodyCodegen.java` | class syntax | 4 | `generateExpression` 可见性放宽为包内; `case Token.CLASS` 转 `ClassBodyCodegen.generate` | P2 |
| `rhino/src/main/java/org/mozilla/javascript/optimizer/BodyCodegen.java` | class syntax | 1 | `LOCAL_BLOCK`: for-of 的枚举局部变量在入口清零 (`ClassBodyCodegen.clearsLocal`, 关闭迭代器的处理器可能在 GetIterator 之前进入) | P2 |
| `rhino/src/main/java/org/mozilla/javascript/optimizer/BodyCodegen.java` | class syntax | 1 | CALL / NEW: 参数里有 yield 的调用不走直接调用快速路径 (`ClassBodyCodegen.yieldsIn`, 快速路径把实参生成两遍) | P2 |
| `rhino/src/main/java/org/mozilla/javascript/optimizer/BodyCodegen.java` | class syntax | 3 | `generateLocalYieldPoint`: 保存 / 恢复操作数栈时经 `StackSpills` 留占位, 由栈图分析回填类型 (附录 C Q10) | P2 |
| `rhino/src/main/java/org/mozilla/javascript/optimizer/BodyCodegen.java` | class syntax | 1 | `visitStandardCall` 等的 this: `ClassBodyCodegen.receiver` | P2 |
| `rhino/src/main/java/org/mozilla/javascript/optimizer/BodyCodegen.java` | class syntax | 3 | 包内桥接 `callArgArray` (`ClassBodyCodegen` 生成 `super(...)` 的实参数组) | P2 |
| `rhino/src/main/java/org/mozilla/javascript/optimizer/BodyCodegen.java` | class syntax | 6 | 字段可见性放宽为包内: `variableObjectLocal`, `contextLocal`, `thisObjLocal`, `funObjLocal`, `newTargetLocal`, `isGenerator` | P2 |
| `rhino/src/main/java/org/mozilla/javascript/optimizer/ClassCompiler.java` | class syntax | 1 | 生成 JSDescriptor 时传入 `derivedConstructor` | P2 |
| `rhino/src/main/java/org/mozilla/javascript/CodeGenUtils.java` | class syntax | 1 | `fillInCommon`: 记录函数是哪种类元素 (`ClassLowering.elementKind`: 方法, 字段初始化器, static block; 直接 eval 据此决定 `new.target` / `arguments`) | P3 |
| `rhino/src/main/java/org/mozilla/javascript/CompilerEnvirons.java` | class syntax | 1 | 包内字段 `classEvalContext`: 直接 eval 时 `ClassRuntime.evalContext` 收集的外层私有名与所在类元素的种类, 交给 `ClassParser` (附录 C Q13) | P3 |
| `rhino/src/main/java/org/mozilla/javascript/IRFactory.java` | class syntax | 2 | `transform`: `case Token.PRIVATE_NAME` (`#x in o` 的左边) 转私有名隐藏绑定的 NAME (`ClassLowering.privateReference`) | P3 |
| `rhino/src/main/java/org/mozilla/javascript/IRFactory.java` | class syntax | 3 | `transformPropertyGet`: `o.#x` / `o?.#x` 转 `ClassLowering.privateMember` (以私有名为键的 GETELEM, 赋值, 复合赋值, 自增, 调用都走现有元素访问) | P3 |
| `rhino/src/main/java/org/mozilla/javascript/Icode.java` | class syntax | 3 | 链尾新增 `CLASS_PRIVATE_NAME` / `CLASS_INIT_FIELDS` / `CLASS_STATIC_INIT` | P3 |
| `rhino/src/main/java/org/mozilla/javascript/JSDescriptor.java` | class syntax | 3 | 包内字段 `classElement` 与 `Builder.classElement` (`build` 里传入): 函数是哪种类元素 | P3 |
| `rhino/src/main/java/org/mozilla/javascript/JSFunction.java` | class syntax | 1 | 包内字段 `classData` (`ClassRuntime.ClassData`: 类构造器的实例字段记录, static 元素, 私有方法品牌) | P3 |
| `rhino/src/main/java/org/mozilla/javascript/Parser.java` | class syntax | 1 | `memberExpr` 的 `new.target`: `classSyntax.newTargetAllowed` (函数内, 以及方法 / 字段初始化器 / static block 里直接 eval 代码的顶层, 附录 C Q13) | P3 |
| `rhino/src/main/java/org/mozilla/javascript/Parser.java` | class syntax | 4 | `simpleAssignment` (解构赋值目标): `o.#x` 按 GETELEM 处理, 键为私有名的隐藏绑定 | P3 |
| `rhino/src/main/java/org/mozilla/javascript/Parser.java` | class syntax | 3 | `destructuringObject`: 赋值解构里属性值是成员表达式 (`{a: o.x}`, `{a: this.#f}`) 时直接生成赋值, 先求值目标引用再读属性 (`bindings.assignsMember`; 上游先把属性值存进临时变量, 派生构造器里 getter 调 `super()` 时不报 ReferenceError) | P3 |
| `rhino/src/main/java/org/mozilla/javascript/ScriptRuntime.java` | class syntax | 3 | `getObjectElem(Object, Object, Context, VarScope)`: 私有名键转 `ClassRuntime.privateGet` (PrivateGet 与品牌检查) | P3 |
| `rhino/src/main/java/org/mozilla/javascript/ScriptRuntime.java` | class syntax | 3 | `setObjectElem(Object, Object, Object, Context, VarScope)`: 私有名键转 `ClassRuntime.privateSet` | P3 |
| `rhino/src/main/java/org/mozilla/javascript/ScriptRuntime.java` | class syntax | 6 | `getElemAndThis` / `getElemAndThisOptional`: 私有名键转 `ClassRuntime.privateCall` (`o.#m()` 以 o 为 this, `o?.#m()` 在 o 为 null / undefined 时结束可选链) | P3 |
| `rhino/src/main/java/org/mozilla/javascript/ScriptRuntime.java` | class syntax | 3 | `in(Object, Object, Context)`: 左边是私有名时转 `ClassRuntime.privateIn` (`#x in o`) | P3 |
| `rhino/src/main/java/org/mozilla/javascript/ScriptRuntime.java` | class syntax | 2 | `evalSpecial`: 直接 eval 编译前 `ClassRuntime.evalContext` 收集外层私有名与类元素种类, 放进 `compilerEnvs.classEvalContext` | P3 |
| `rhino/src/main/java/org/mozilla/javascript/ScriptableObject.java` | class syntax | 1 | 包内字段 `privateElements` (私有元素表 `PrivateElements`, 与属性表分开, T5) | P3 |
| `rhino/src/main/java/org/mozilla/javascript/BaseFunction.java` | class syntax | 2 | `construct`: 经 `BuiltinConstruction.create` 创建 this; `createObject` 返回 null 的子类 (构造即调用) 得到的对象按 new.target 补原型 (`withPrototype`) | P4 |
| `rhino/src/main/java/org/mozilla/javascript/BaseFunction.java` | class syntax | 3 | `createObject(cx, scope, nt)`: 改为 `ScriptRuntime.createFromConstructor` (OrdinaryCreateFromConstructor, 默认原型 `Object.prototype`; 原来只认 JSFunction 的 new.target, 绑定函数与 Proxy 退回本函数的类原型) | P4 |
| `rhino/src/main/java/org/mozilla/javascript/BaseFunction.java` | class syntax | 1 | `setupDefaultPrototype`: 生成器函数的 prototype 不定义 `constructor` (`BuiltinConstruction.defineConstructor`; `new GeneratorFunction(...)` 的函数原来带) | P4 |
| `rhino/src/main/java/org/mozilla/javascript/ScriptRuntime.java` | class syntax | 2 | `findPrototype` 两个重载 (Builtins / NativeErrors) 交给 `BuiltinConstruction.prototypeOf`; 默认原型先按构造器名取 (6 个原生错误都传 Error), 类型参数只在 f 不以内建命名时使用 (`Uint8Array.fromBase64` / `fromHex` 以静态方法自身为 f 执行构造代码) | P4 |
| `rhino/src/main/java/org/mozilla/javascript/ScriptRuntime.java` | class syntax | 4 | 统一入口 `createFromConstructor(cx, nt, scope, defaultProto)` (T9), 实现在 `BuiltinConstruction` | P4 |
| `rhino/src/main/java/org/mozilla/javascript/LambdaConstructor.java` | class syntax | 2 | `construct`: Java 构造器建好对象后按 new.target 设原型 (`withPrototype`; 宿主用 LambdaConstructor 定义的构造器可被 class 继承) | P4 |
| `rhino/src/main/java/org/mozilla/javascript/NativeArray.java` | class syntax | 3 | `jsConstructor`: 先取原型 (`prototypeOf`) 再建数组, 末尾设原型与父作用域 (替代 `setBuiltinProtoAndParent`) | P4 |
| `rhino/src/main/java/org/mozilla/javascript/NativeMap.java` | class syntax | 3 | 构造器先设原型与父作用域再装入; `loadFromIterable` 的方法体改为 `BuiltinConstruction.addEntriesFromIterable` (AddEntriesFromIterable: 取实例的 `set`, 子类覆盖的生效; 原方法体删除) | P4 |
| `rhino/src/main/java/org/mozilla/javascript/NativeSet.java` | class syntax | 3 | 同 Map: 先设原型; `loadFromIterable` 的方法体改为 `addValuesFromIterable` (取实例的 `add`) | P4 |
| `rhino/src/main/java/org/mozilla/javascript/NativeWeakMap.java` | class syntax | 2 | 构造器先设原型与父作用域再装入 (装入用实例的 `set`) | P4 |
| `rhino/src/main/java/org/mozilla/javascript/NativeWeakSet.java` | class syntax | 2 | 同 WeakMap (装入用实例的 `add`) | P4 |
| `rhino/src/main/java/org/mozilla/javascript/NativePromise.java` | class syntax | 2 | 构造器在执行器运行之前取原型并设父作用域 | P4 |
| `rhino/src/main/java/org/mozilla/javascript/NativeProxy.java` | class syntax | 1 | 构造器不读 new.target 的 `prototype` (ProxyCreate 不用 new.target; 原型由陷阱决定, 直接原型置 null) | P4 |
| `rhino/src/main/java/org/mozilla/javascript/NativeWeakRef.java` | class syntax | 1 | 原型取 `prototypeOf(f, nt)` (原来固定为 `f.prototype`) | P4 |
| `rhino/src/main/java/org/mozilla/javascript/NativeFinalizationRegistry.java` | class syntax | 1 | 同 WeakRef | P4 |
| `rhino/src/main/java/org/mozilla/javascript/NativeCallSite.java` | class syntax | 1 | 同 WeakRef (Rhino 构造器) | P4 |
| `rhino/src/main/java/org/mozilla/javascript/ImporterTopLevel.java` | class syntax | 1 | `JavaImporter` 构造器: 同 WeakRef | P4 |
| `rhino/src/main/java/org/mozilla/javascript/NativeIterator.java` | class syntax | 3 | `jsConstructor` (JS 1.7 `Iterator`): 结果按 new.target 设原型 | P4 |
| `rhino/src/main/java/org/mozilla/javascript/NativeScript.java` | class syntax | 1 | 构造器按类名设原型后再按 new.target 设原型 | P4 |
| `rhino/src/main/java/org/mozilla/javascript/regexp/NativeRegExp.java` | class syntax | 1 | import `BuiltinConstruction` | P4 |
| `rhino/src/main/java/org/mozilla/javascript/regexp/NativeRegExp.java` | class syntax | 4 | `js_construct`: 实参经 `BuiltinConstruction.regExpArgs` (IsRegExp 读 `Symbol.match`; 作为函数调用且 `pattern.constructor === RegExp` 时返回原对象; 类 RegExp 对象取 `source` / `flags`); `js_constructCall` 原来的提前返回 (5 行) 删除, 调用与构造共用这条路径 | P4 |
| `rhino/src/main/java/org/mozilla/javascript/regexp/NativeRegExp.java` | class syntax | 2 | `js_test`: `exec` 被覆盖 (不是内建 exec) 时按 RegExpExec 调用它 (`BuiltinConstruction.userTest`) | P4 |
| `rhino/src/main/java/org/mozilla/javascript/typedarrays/NativeArrayBuffer.java` | class syntax | 2 | import `BuiltinConstruction`, `ScriptRuntimeES6` | P4 |
| `rhino/src/main/java/org/mozilla/javascript/typedarrays/NativeArrayBuffer.java` | class syntax | 4 | 构造器的 `Symbol.species` 访问器 (`ScriptRuntimeES6::symbolSpecies`; `slice` 原来就按 species 创建) | P4 |
| `rhino/src/main/java/org/mozilla/javascript/typedarrays/NativeArrayBuffer.java` | class syntax | 3 | 构造器先取原型再分配数据块, 末尾设原型与父作用域 | P4 |
| `rhino/src/main/java/org/mozilla/javascript/typedarrays/NativeDataView.java` | class syntax | 1 | import `BuiltinConstruction` (未再使用的 `TopLevel` import 随之删除) | P4 |
| `rhino/src/main/java/org/mozilla/javascript/typedarrays/NativeDataView.java` | class syntax | 4 | 构造器先取原型, 之后重查缓冲区 (`BuiltinConstruction.checkView`: detached, 偏移, 长度), 末尾设原型与父作用域 | P4 |
| `rhino/src/main/java/org/mozilla/javascript/typedarrays/NativeTypedArrayView.java` | class syntax | 3 | `js_constructor` 的最后分支: 非对象实参按 ToIndex 分配 (Symbol 抛 TypeError; 原来抛 `Error: invalid argument`) | P4 |

| `rhino/src/main/java/org/mozilla/javascript/ClassRuntime.java` | class syntax | 1 | import `autojs6.JavaClassInheritance` | P5, fork-only (D3) |
| `rhino/src/main/java/org/mozilla/javascript/ClassRuntime.java` | class syntax | 1 | `defineClass`: heritage 先经 `JavaClassInheritance.heritage` (Java 类 / 接口换成它的 `JavaSuperclass`, final / 非 public 的类报 TypeError); P4 留下的 `superCall` 里 Java 类的构造后改原型脚手架已删除 | P5, fork-only (D3) |
| `rhino/src/main/java/org/mozilla/javascript/ClassRuntime.java` | class syntax | 2 | `uninitializedThis` (`thisValue` / `derivedResult` 共用): 派生自 Java 类时 super() 之前读 this 的消息改为 `msg.java.class.this.uninit` (`JavaClassInheritance.superclassName`) | P5, fork-only (D3) |
| `rhino/src/main/java/org/mozilla/javascript/JavaAdapter.java` | class syntax | 1 | import `autojs6.JavaClassInheritance` | P5, fork-only (D3) |
| `rhino/src/main/java/org/mozilla/javascript/JavaAdapter.java` | class syntax | 2 | `createAdapterWrapper`: 类适配器的对象以类实例为 self (`JavaClassInheritance.attach`) | P5, fork-only (D3) |
| `rhino/src/main/java/org/mozilla/javascript/JavaAdapter.java` | class syntax | 2 | `js_createAdapter`: 实参全是 Java 类与接口时返回超类工厂的结果 `JavaAdapter(Base, I1, I2)` (`JavaClassInheritance.superclassOf`, 附录 C Q3); 带实现对象的旧写法不变 | P5, fork-only (D3) |
| `rhino/src/main/java/org/mozilla/javascript/JavaAdapter.java` | class syntax | 2 | `getFunction`: 类实例的方法查找 (`JavaClassInheritance.method`: 数据属性直接返回, 其余在调用时查找, 未实现的抽象方法报 TypeError) | P5, fork-only (D3) |
| `rhino/src/main/java/org/mozilla/javascript/JavaAdapter.java` | class syntax | 2 | `generateCtor` 两个分支: 类适配器 (`classadapter` 前缀) 在调用 Java 构造器之前先存 delegee / self / factory (`JavaInheritanceSupport.storeEarly`) | P5, fork-only (D3) |
| `rhino/src/main/java/org/mozilla/javascript/JavaAdapter.java` | class syntax | 1 | `generateMethod`: 类适配器的方法先把 Java 对象交给实例 (`JavaInheritanceSupport.attachEarly`; Java 构造器调用被覆盖的方法时实例已能读 Java 成员) | P5, fork-only (D3) |
| `rhino/src/main/java/org/mozilla/javascript/JavaMembers.java` | class syntax | 4 | 包内方法 `member(name)` (实例成员, 再静态成员), 供 `JavaInheritanceSupport.isWritableJavaProperty` 判断赋值写 Java 对象还是建自有属性 | P5, fork-only (D3) |
| `rhino/src/main/java/org/mozilla/javascript/WrapFactory.java` | class syntax | 1 | import `autojs6.JavaClassInheritance` | P5, fork-only (D3) |
| `rhino/src/main/java/org/mozilla/javascript/WrapFactory.java` | class syntax | 4 | `wrap` 与 `wrapNewObject`: 类实例的 Java 对象包装回实例本身 (`JavaClassInheritance.selfOf`; 在 `wrapAsJavaObject` 之前) | P5, fork-only (D3) |
| `tests/src/test/java/org/mozilla/javascript/tests/Test262SuiteTest.java` | class syntax | 3 | `UNSUPPORTED_FEATURES`: 去掉 `class` / `class-fields-private` / `class-fields-public` (说明行), 加 T10 排除的 `decorators` 与 `nonextensible-applies-to-private` (附录 C Q14), 属性文件里记为 `{unsupported: [...]}` | P6.1 |
| `tests/src/test/java/org/mozilla/javascript/tests/Test262SuiteTest.java` | class syntax | 2 | `TestMode.V2`: 可选的 Interpreter V2 模式 (`-Drhino.test262.interpreterV2=true`, 不在默认矩阵, 可用性见 `InterpreterV2Mode`) | P6.2 |
| `tests/src/test/java/org/mozilla/javascript/tests/Test262SuiteTest.java` | class syntax | 2 | `TestResultTracker.getResult`: "全部模式都失败" 的模式数按 `InterpreterV2Mode.modeCount()` 计 (V2 不运行时不计, 结果与上游的 `values().length * 2 - 2` 相同) | P6.2 |
| `rhino/src/main/java/org/mozilla/javascript/IRFactory.java` | class syntax | 2 | `transformFunctionCall` 与 `transformNewExpr`: 带展开实参的调用 / new 交给 `SpreadCall.lower` (全部实参, 含 JavaAdapter 写法的初始化对象, 收进一个数组字面量, 作为唯一的 `DOTDOTDOT` 实参) | P6.6 |
| `rhino/src/main/java/org/mozilla/javascript/Icode.java` | class syntax | 6 | 链尾新增 `SPREAD_CALL` / `SPREAD_CALL_ON_SUPER` / `SPREAD_NEW` / `SPREAD_CALLSPECIAL` / `SPREAD_REF_CALL` (含注释行); `MIN_ICODE` 改接到 `SPREAD_REF_CALL` (该行已在 P2 登记) | P6.6 |
| `rhino/src/main/java/org/mozilla/javascript/CodeGenerator.java` | class syntax | 5 | `visitExpression` 的 CALL / NEW / REF_CALL: 实参经 `SpreadCall.argument` (展开调用求值它的数组字面量), 展开调用由 `SpreadCall.emit` 发射 `SPREAD_*` icode; 原来的 `if (type != Token.REF_CALL && callType != ...)` 改为 `else if` 并拆成两行 | P6.6 |
| `rhino/src/main/java/org/mozilla/javascript/Interpreter.java` | class syntax | 5 | `DoCallByteCode`: `SPREAD_CALL` / `SPREAD_CALL_ON_SUPER` 把数组元素作为 bound 实参 (`SpreadCall.callArguments`), 改按 `CALL` / `CALL_ON_SUPER` 继续 (解释函数仍走内联帧, 栈深与 continuation 同普通调用) | P6.6 |
| `rhino/src/main/java/org/mozilla/javascript/optimizer/BodyCodegen.java` | class syntax | 1 | `generateCallArgArray`: 展开调用的实参数组由 `SpreadCallCodegen.generateArgs` 生成 (普通调用, super 调用, 可选调用, new, eval, REF_CALL 都经这里) | P6.6 |
| `rhino/src/main/java/org/mozilla/javascript/optimizer/OptTransformer.java` | class syntax | 1 | `detectDirectCall`: 展开调用不做直接调用 (实参个数运行时才知道) | P6.6 |
| `rhino/src/main/java/org/mozilla/javascript/IRFactory.java` | class syntax | 2 | `createAssignment` 开头: 逻辑赋值交给 `LogicalAssignment.lower` (返回 null 时照旧, 即特殊属性与 E4X 引用) | P6.7 |
| `rhino/src/main/java/org/mozilla/javascript/AccessorSlot.java` | class syntax | 5 | `setValue`: setter 为 `undefined` 的 `FunctionSetter` 按没有 setter 处理 (`throwNoSetterException`, 严格模式抛 TypeError, 非严格忽略); 上游原来静默成功 (附录 C Q18) | P6.7 |
| `rhino/src/main/java/org/mozilla/javascript/optimizer/BodyCodegen.java` | class syntax | 1 | `case Token.SUPER`: home object 经 `ClassBodyCodegen.homeObject` (`ClassRuntime.homeObject` 沿作用域链找到函数的活动对象); 上游把当前作用域直接强转为 `NativeCall`, 方法里 let 块 (作用域对象) 或 with 语句中的 `super` 在编译模式抛 `ClassCastException`, 逻辑赋值的临时绑定同样会碰到; 删除的上游行: `CHECKCAST NativeCall` 与 `getHomeObject` 调用 (共 6 行) | P6.7 |
| `rhino/src/main/java/org/mozilla/javascript/optimizer/BodyCodegen.java` | class syntax | 2 | `visitObjectLiteral`: 生成属性前后保存 / 恢复 `savedHomeObjectLocal` (上游只有一个字段, 嵌套字面量覆盖后不恢复, 其后的方法 / 访问器以内层对象为 home object) | P6.8 |
| `rhino/src/main/java/org/mozilla/javascript/optimizer/Optimizer.java` | class syntax | 4 | `rewriteForNumberVariables` 的 CALL: callee 本身按对象位置改写 (`rewriteForNumberVariables` 结果为数字时经 `ClassBodyCodegen.toObject` 包 `TO_OBJECT`); 删除的上游行: `rewriteAsObjectChildren(child, child.getFirstChild())` (只改写 callee 的子节点) | P6.8 |
合计 494 行 (class syntax 444, async 50; 上游测试源码 `tests/src` 7 行, P6 起登记); P2 新增 203 行, 另把 `IRFactory.transform` 里 P1 的 `ClassLowering.transform` 调用改为传入 IRFactory; P3 新增 40 行, `transformLiteral` 的 3 行合为 1 行; P4 新增 62 行, 另把 `JSFunction.construct` 里 P2 的 `createObject` 调用改为 `BuiltinConstruction.thisObject`. P4 在上游文件里删除的行: `findPrototype` 两个方法体, `NativeMap` / `NativeSet` 的 `loadFromIterable` 方法体, 各构造器的 `setBuiltinProtoAndParent` 调用, `BaseFunction.createObject` 的方法体, `NativeRegExp.js_constructCall` 的提前返回, `NativeDataView` 的 `TopLevel` import; 合并时上游改了这些位置, 按第三节第 7 条以上游版本为基础重新插入钩子. P5 新增 23 行, 全部是 Java 类继承 (fork-only, D3): 上游文件 19 行, fork 文件 `ClassRuntime` 4 行 (它是第 4 节的新文件, 这 4 行仍带标记, 退役 class 族时按 `AGENTS.md` 7.4 改挂到上游实现, 见 NOTES.md P5). P6 新增 7 行, 都在上游测试源码 `Test262SuiteTest` (test262 解锁与 V2 模式开关), `rhino/src/main/java` 不变; 删除的行: `UNSUPPORTED_FEATURES` 里的 `class` / `class-fields-private` / `class-fields-public`, `getResult` 里原来的 `allCovered` 一行. P8 上游 PR 的 "test262 解锁" 提交只带去掉 class 特性那部分, V2 开关与 T10 的两个特性是否随带由 P8 定 (NOTES.md P6). P6.6 (实参展开, 与 class 无关, P8 单独一个上游 PR) 新增 20 行: IRFactory 2, Icode 6, CodeGenerator 5 (其中改动的上游行 2 行), Interpreter 5, BodyCodegen 1, OptTransformer 1; 标记沿用 `class syntax` (同 P2 的 yield 修复). P6.7 (逻辑赋值短路, `set: undefined` 与编译模式块内 `super`, 同样是通用修复, 与 P6.6 合为一个上游 PR 还是分开由 P8 定) 新增 8 行: IRFactory 2, AccessorSlot 5 (新的钩子文件), BodyCodegen 1 (另删除上游 6 行). P6.8 (编译模式的两个上游缺陷, 通用修复, 各是几行的独立上游 PR) 新增 6 行: BodyCodegen 2, Optimizer 4 (新的钩子文件, 另删除上游 1 行).

## 4. 新文件

- `rhino/src/main/java/org/mozilla/javascript/ClassParser.java`: class 的词法钩子 (`keyword`, `privateName`), 声明 / 表达式 / 类体 / 元素 / 字段初始化器 / static block 的解析, 私有名作用域与引用解析, 函数种类栈与早期错误 (P1).
- `rhino/src/main/java/org/mozilla/javascript/AsyncParser.java`: `async` / `await` 的词法钩子, async 函数声明 / 表达式 / 箭头函数 / 方法与 `for await` 的预解析, await 上下文栈与早期错误 (P1).
- `rhino/src/main/java/org/mozilla/javascript/ClassLowering.java`: IRFactory 的 class 降糖 (P2): 类作用域 + 构造器 + 元素定义, 默认构造器, `super(...)`, 派生构造器的 this, 类名 TDZ, 匿名类名推断, 构造器调用检查; P3 加字段 / static block 元素, 基类构造器开头的 INIT_FIELDS, 类定义后的 STATIC_INIT, 私有名的隐藏绑定与 `o.#x` 的元素访问, 字段值的匿名函数命名, eval 顶层的 `new.target`.
- `rhino/src/main/java/org/mozilla/javascript/AsyncLowering.java`: IRFactory 的 async 入口; P1 只报 `msg.async.not.supported` (P9 起实现).
- `rhino/src/main/java/org/mozilla/javascript/ast/ClassNode.java`: class 声明 / 表达式节点 (继承 `Scope`, 类型 `Token.CLASS`; 名字, 超类, 元素, 声明或表达式, 匿名类的推断名).
- `rhino/src/main/java/org/mozilla/javascript/ast/ClassElement.java`: 类元素 (constructor / 方法 / getter / setter / 字段 / static block, static 标志, 计算键, 私有名; 类型 `Token.CLASS_ELEMENT`).
- `rhino/src/main/java/org/mozilla/javascript/ast/PrivateName.java`: 私有名 `#x` (继承 `Name`, 类型 `Token.PRIVATE_NAME`).
- `rhino/src/main/java/org/mozilla/javascript/ast/AwaitExpression.java`: `await` 表达式 (类型 `Token.AWAIT`).
- `rhino/src/main/java/org/mozilla/javascript/ast/ForAwaitOfLoop.java`: `for await (... of ...)` 循环 (继承 `ForInLoop`).
- `rhino/src/main/java/org/mozilla/javascript/ClassOp.java`: `Token.CLASS` IR 节点的操作描述 (`Node.CLASS_PROP`): DEFINE, SUPER_CALL, THIS, TDZ, CTOR_CHECK, ITERATOR_REST, REQUIRE_OBJECT, ITERATOR_CLOSE, PARAM_SCOPE (P2), PRIVATE_NAME, INIT_FIELDS, STATIC_INIT 与元素标志 FIELD / PRIVATE / NAMED / NO_VALUE / BLOCK (P3).
- `rhino/src/main/java/org/mozilla/javascript/ClassRuntime.java`: 三个后端共用的运行时: 类定义 (ClassDefinitionEvaluation), 元素定义, `super()` 构造, 派生构造器的 this (`DerivedThis`) 与返回值, TDZ 错误, 迭代器 rest / 关闭, RequireObjectCoercible, 参数作用域对象, 受限属性 (P2); 字段记录 (`ClassData` / `FieldRecord`), InitializeInstanceElements, static 元素, DefineField, 私有名的创建 / 读 / 写 / 调用 / in 与品牌检查, 直接 eval 的上下文 (`EvalContext`) (P3).
- `rhino/src/main/java/org/mozilla/javascript/PrivateNameKey.java`: Private Name (T5): 每次类求值为每个私有名新建一个; 私有方法与访问器的函数与品牌也挂在这里 (P3).
- `rhino/src/main/java/org/mozilla/javascript/PrivateElements.java`: 对象的 [[PrivateElements]] (T5): `ScriptableObject` 用字段 `privateElements` 持有, 写时复制的两个数组, 添加在宿主对象监视器下进行 (不用 VarHandle); 其他 `Scriptable` (Java 对象包装按被包装的对象) 用弱引用的标识表 (P3).
- `rhino/src/main/java/org/mozilla/javascript/ClassInterpreter.java`: 解释器后端: `Token.CLASS` 的 icode 生成 (`generate`), 指令类注册 (`register`) 与 icode 名字 (P2).
- `rhino/src/main/java/org/mozilla/javascript/BindingLowering.java`: 与 class 无关的通用绑定修复, test262 class 目录的方法用例依赖它们: 参数作用域, 参数 TDZ, 参数解构的迭代器协议, 生成器参数解构, for-of 关闭迭代器, 函数名推断, 需要 arguments 的活动对象 (P2); 对象解构赋值里成员表达式目标的求值顺序 (P3).
- `rhino/src/main/java/org/mozilla/javascript/optimizer/ClassBodyCodegen.java`: 编译后端: `Token.CLASS` 的字节码生成, 当前 this 的取值, 直接调用判定用的 `hasYield`, for-of 局部变量清零的判定 (P2); P6.7 加 `homeObject` (编译模式 `super` 的 home object, 运行时在 `ClassRuntime.homeObject`, 通用修复); P6.8 加 `toObject` (Optimizer 把数字值的 callee 包成 `TO_OBJECT`).
- `rhino/src/main/java/org/mozilla/javascript/BuiltinConstruction.java`: 内建构造器按 new.target 创建 (P4, T9): `prototypeOf` (GetPrototypeFromConstructor; new.target 就是构造器自身或没有时直接取自身的 prototype, 不查找), `createFromConstructor` (普通对象的 OrdinaryCreateFromConstructor, 经 `ScriptRuntime.createFromConstructor` 调用), `functionRealm` (GetFunctionRealm: 绑定函数取目标, Proxy 取目标, 已撤销抛 TypeError), 默认原型 (按构造器名查 `TopLevel.Builtins` / `NativeErrors`, 再取 `findPrototype` 传入的类型, 再取同名类原型; 跨 realm 时在 new.target 的 realm 里取), `create` / `withPrototype` / `thisObject` (BaseFunction, LambdaConstructor, JSFunction 的构造路径), `defineConstructor`, Map / Set 的 AddEntriesFromIterable, DataView 的 `checkView`, RegExp 的 `regExpArgs` 与 `userTest`.
- `rhino/src/main/java/org/mozilla/classfile/StackSpills.java`: 编译模式生成器在 yield 点保存 / 恢复操作数栈的类型: 先留空指令占位, 方法结束时按栈图分析的类型改写为装箱 / 拆箱 / 强转 (P2, 附录 C Q10).
- `rhino/src/main/java/org/mozilla/javascript/interpreterv2/instruction/ClassCtorCheck.java`: V2 指令, 对应 icode `CLASS_CTOR_CHECK` (P2, 附录 C Q11: 尚无 IR 到 V2 的编译器).
- `rhino/src/main/java/org/mozilla/javascript/interpreterv2/instruction/ClassDefine.java`: V2 指令, 对应 icode `CLASS_DEFINE` (P2, 附录 C Q11: 尚无 IR 到 V2 的编译器).
- `rhino/src/main/java/org/mozilla/javascript/interpreterv2/instruction/ClassElement.java`: V2 指令, 对应 icode `CLASS_ELEMENT` (P2, 附录 C Q11: 尚无 IR 到 V2 的编译器; P3 起也定义字段与 static block, 无初始化器的字段 `fnIndex` 为 -1).
- `rhino/src/main/java/org/mozilla/javascript/interpreterv2/instruction/ClassInitFields.java`: V2 指令, 对应 icode `CLASS_INIT_FIELDS` (P3, 附录 C Q11).
- `rhino/src/main/java/org/mozilla/javascript/interpreterv2/instruction/ClassIterClose.java`: V2 指令, 对应 icode `CLASS_ITER_CLOSE` (P2, 附录 C Q11: 尚无 IR 到 V2 的编译器).
- `rhino/src/main/java/org/mozilla/javascript/interpreterv2/instruction/ClassIterRest.java`: V2 指令, 对应 icode `CLASS_ITER_REST` (P2, 附录 C Q11: 尚无 IR 到 V2 的编译器).
- `rhino/src/main/java/org/mozilla/javascript/interpreterv2/instruction/ClassParamScope.java`: V2 指令, 对应 icode `CLASS_PARAM_SCOPE` (P2, 附录 C Q11: 尚无 IR 到 V2 的编译器).
- `rhino/src/main/java/org/mozilla/javascript/interpreterv2/instruction/ClassPrivateName.java`: V2 指令, 对应 icode `CLASS_PRIVATE_NAME` (P3, 附录 C Q11).
- `rhino/src/main/java/org/mozilla/javascript/interpreterv2/instruction/ClassRequireObject.java`: V2 指令, 对应 icode `CLASS_REQUIRE_OBJECT` (P2, 附录 C Q11: 尚无 IR 到 V2 的编译器).
- `rhino/src/main/java/org/mozilla/javascript/interpreterv2/instruction/ClassStaticInit.java`: V2 指令, 对应 icode `CLASS_STATIC_INIT` (P3, 附录 C Q11).
- `rhino/src/main/java/org/mozilla/javascript/interpreterv2/instruction/ClassSuperCall.java`: V2 指令, 对应 icode `CLASS_SUPER_CALL` (P2, 附录 C Q11: 尚无 IR 到 V2 的编译器).
- `rhino/src/main/java/org/mozilla/javascript/interpreterv2/instruction/ClassSuperFunc.java`: V2 指令, 对应 icode `CLASS_SUPER_FUNC` (P2, 附录 C Q11: 尚无 IR 到 V2 的编译器).
- `rhino/src/main/java/org/mozilla/javascript/interpreterv2/instruction/ClassTdz.java`: V2 指令, 对应 icode `CLASS_TDZ` (P2, 附录 C Q11: 尚无 IR 到 V2 的编译器).
- `rhino/src/main/java/org/mozilla/javascript/interpreterv2/instruction/ClassThis.java`: V2 指令, 对应 icode `CLASS_THIS` (P2, 附录 C Q11: 尚无 IR 到 V2 的编译器).
- `rhino/src/main/java/org/mozilla/javascript/interpreterv2/instruction/ClassValues.java`: V2 指令类共用的出栈辅助 (P2).

- `rhino/src/main/java/org/mozilla/javascript/JavaInheritanceSupport.java`: fork-only (D3), P5: `autojs6` 包用到的包内互操作入口, 没有自己的逻辑: 适配器类的生成与缓存 (与私有的 `JavaAdapter.getAdapterClass` 相同, 键 `JavaAdapterSignature`, 类名前缀 `classadapter`), 可覆盖方法, 构造器重载选择 (`NativeJavaMethod.findFunction`), 不调用访问器的自有属性读取, 生成代码里的 `storeEarly` / `attachEarly`, Java 字段 / bean 属性是否可写.
- `rhino/src/main/java/org/mozilla/javascript/autojs6/JavaClassInheritance.java`: fork-only (D3), P5 (T6): 钩子入口 (`heritage`, `superclassOf`, `attach`, `method`, `selfOf`, `superclassName`), 构造 (new.target 的原型, 原型链上覆盖的方法名, 适配器类, 实例), 每个顶层作用域按 "Java 类 + 接口" 一份的超类注册表.
- `rhino/src/main/java/org/mozilla/javascript/autojs6/JavaSuperclass.java`: fork-only (D3), P5: class 继承的超类, 代替 Java 类 / 接口或 `JavaAdapter(Base, I1, I2)` 的结果: prototype 是 `JavaPrototype`, [[Construct]] 以公有与受保护构造器的重载选择建适配器对象, 不可调用, 显示 Java 类的静态成员, 可覆盖 / final / 实例方法名表, 每个适配器类的 `Adapter` (派发的方法, `super$` 方法, 构造器, Java 成员视图).
- `rhino/src/main/java/org/mozilla/javascript/autojs6/JavaPrototype.java`: fork-only (D3), P5: 超类的 prototype, 原型链上 JS 部分的末端: 从实例查找时给出 Java 对象的成员 (被覆盖的方法给 `super$` 实现, 抽象的不给), 可写 Java 字段 / bean 属性的赋值, `super.m()` 的延迟查找函数.
- `rhino/src/main/java/org/mozilla/javascript/autojs6/JavaSubclassObject.java`: fork-only (D3), P5: 类实例 (附录 C Q4 = A): 包装适配器对象 (`Wrapper`), 是适配器的 delegee 与 self, 字段与私有元素是它自己的; Java 成员经适配器对象的 `NativeJavaObject` 视图 (含受保护成员), `Iterable` 的 `Symbol.iterator`.
- `rhino/src/main/java/org/mozilla/javascript/autojs6/JavaSubclass.java`: fork-only (D3), P5: 类适配器实现的标记接口, `WrapFactory` 钩子据此认出类实例的 Java 对象.

- `tests/src/test/java/org/mozilla/javascript/tests/InterpreterV2Mode.java`: P6.2 (T8, 附录 C Q11): `Test262SuiteTest` 的可选 Interpreter V2 模式. 系统属性 `rhino.test262.interpreterV2=true` 请求 (Gradle 把 `rhino.*` 属性都传给测试), 只有 Context 能用 V2 编译脚本时才运行, 否则打印原因并运行 0 例 (现在: `EvaluationMethod` 没有 -2 级别, `setOptimizationLevel(-2)` 抛 `IllegalStateException`; 上游补上级别后还会因 `InterpreterV2.compileScript` 没有编译器抛 `UnsupportedOperationException`); 上游编译器合入后同一开关直接跑全部 test262 (含 class 两目录) 的 V2 模式.

- `rhino/src/main/java/org/mozilla/javascript/SpreadCall.java`: P6.6 (附录 C Q16 = B, 通用修复): 实参展开的 IR 收拢 (`lower`: 全部实参移进一个数组字面量, 它按迭代器协议展开并从左到右求值), 解释器的 5 个 icode (`emit` 发射, `register` 注册: `SPREAD_CALL` / `SPREAD_CALL_ON_SUPER` 由 CALL 的指令执行, `SPREAD_NEW` / `SPREAD_CALLSPECIAL` / `SPREAD_REF_CALL` 是自有指令类), `callArguments` (CALL 指令的钩子用) 与运行时 `args` (数组字面量 -> 实参数组, 编译代码调用, 故为 public); 不生成对 `Function.prototype.apply` / `Reflect.apply` 的 JS 级调用.
- `rhino/src/main/java/org/mozilla/javascript/optimizer/SpreadCallCodegen.java`: P6.6: 编译后端 `generateCallArgArray` 的钩子, 展开调用的实参数组 = 数组字面量 + `SpreadCall.args`.
- `rhino/src/main/java/org/mozilla/javascript/LogicalAssignment.java`: P6.7 (附录 C Q17 = B, 通用修复): 逻辑赋值的 IR 降糖 (`lower`): 标识符为 `a || (a = b)` (const 只在真正写入时报错, `with` / 全局 / let 照常解析); 属性引用 `o.k` / `o[k]` / `super.k` / `this.#x` 的对象与非常量键各求值一次 (存进 LETEXPR 的临时绑定, 名字以 `%` 开头, 标识符引用不到), `this` / `super` / 常量键 / 私有名重新求值; 非常量键先对对象做 RequireObjectCoercible (`ClassOp.REQUIRE_OBJECT`); 调用作为目标报 SyntaxError (可选链目标由 Parser 照旧报错); 写入走普通赋值, 保留函数名推断与私有名 / 严格模式的检查.

### 4.1 错误消息

基础 `Messages.properties` 与 9 个翻译 bundle (ar, es, fr, ja, ko, ru, zh, zh_HK, zh_TW) 末尾各有一段 `# AutoJs6: class syntax` 与 `# AutoJs6: async` 注释开头的 fork 消息 (T11). key 清单以 `MessageTranslationsTest` 的 `CLASS_SYNTAX_KEYS` 为准 (P1 27 个, P2 新增 11 个, P3 删除 1 个新增 8 个, P5 新增 9 个, 共 54 个):

- class P1 (20): `msg.class.name.missing`, `msg.class.no.brace`, `msg.class.no.brace.after`, `msg.class.dup.ctor`, `msg.class.ctor.special`, `msg.class.field.ctor`, `msg.class.static.prototype`, `msg.class.private.ctor`, `msg.class.private.undeclared`, `msg.class.private.dup`, `msg.class.private.delete`, `msg.class.private.super`, `msg.class.private.unexpected`, `msg.class.super.call`, `msg.class.field.arguments`, `msg.class.getter.parms`, `msg.class.setter.parms`, `msg.class.strict.parms`, `msg.class.field.semi`, `msg.class.decl.not.in.block`; `msg.class.not.supported` 已在 P3 删除 (字段 / static block / 私有名可以执行).
- class P2 (11): `msg.class.ctor.call`, `msg.class.extends.not.ctor`, `msg.class.extends.proto`, `msg.class.super.not.ctor`, `msg.class.this.uninit`, `msg.class.super.twice`, `msg.class.derived.return`, `msg.class.tdz`, `msg.class.restricted.prop`, `msg.param.tdz`, `msg.class.new.super`.
- class P3 (8): `msg.class.private.read`, `msg.class.private.write`, `msg.class.private.twice`, `msg.class.private.methods.twice`, `msg.class.private.method.write`, `msg.class.private.no.getter`, `msg.class.private.no.setter`, `msg.class.field.define`.
- class P5 (9, Java 类继承, fork-only D3, 在 `msg.class.field.define` 之后的 `# AutoJs6: class syntax, Java classes` 一段): `msg.java.class.final`, `msg.java.class.not.public`, `msg.java.class.final.method`, `msg.java.class.no.ctor`, `msg.java.class.abstract`, `msg.java.class.override.not.function`, `msg.java.class.this.uninit`, `msg.java.adapter.class.first`, `msg.java.adapter.dup`; 另沿用上游已有的 `msg.not.java.class.arg`, `msg.only.one.super` 与 P2 的 `msg.class.ctor.call`.
- async (6): `msg.async.not.supported`, `msg.async.await.context`, `msg.async.await.params`, `msg.async.for.await.of`, `msg.async.decl.not.in.block`, `msg.async.name.missing`.

## 5. 自带测试类

| 测试类 | 模块 | 内容 | 阶段 |
|---|---|---|---|
| `org.mozilla.javascript.tests.classsyntax.ClassSyntaxParserTest` | rhino | class 的 AST 形状, 位置, `toSource`, 早期错误, 开关与 ES6 门控; P2 加 ES6 生成器的 `yield` 解析与 JS 1.7 生成器的括号要求; P3 起字段 / static block / 私有名可以编译 | P1 / P2 / P3 |
| `org.mozilla.javascript.tests.classsyntax.AsyncSyntaxParserTest` | rhino | async / await / for await 的预解析, 早期错误, 开关, 编译期 "暂不支持" | P1 |
| `org.mozilla.javascript.tests.classsyntax.TokenUniquenessTest` | rhino | 编号链唯一性 (第 7 节) | P1 |
| `org.mozilla.javascript.tests.classsyntax.HooksRegistryTest` | rhino | 本文第 3 / 4 节与源码对照; P6 起含上游测试源码 `tests/src` 的标记行与 `tests/` 下的新文件 | P1 / P6 |
| `org.mozilla.javascript.tests.MessageTranslationsTest` (fork 已有, 新增 `classSyntaxMessagesAreTranslated`) | rhino | fork 消息在 9 个翻译 bundle 齐全 | P1 |
| `org.mozilla.javascript.tests.classsyntax.ClassSyntaxTest262ParseTest` | tests | test262 class 两目录只解析: parse 阶段反例全部拒绝, 其余用例全部可解析 (P2 起 `KNOWN_PARSE_GAPS` 为空) | P1 |
| `org.mozilla.javascript.tests.classsyntax.ClassSyntaxTest` | rhino | ES2015 class 语义, 每条规则一组, 解释 / 编译两模式 | P2 |
| `org.mozilla.javascript.interpreterv2.ClassInstructionsTest` | rhino | V2 class 指令的栈变化与调试串 (附录 C Q11); P3 加 `ClassPrivateName` / `ClassInitFields` / `ClassStaticInit` 与无函数的 `ClassElement` | P2 / P3 |
| `org.mozilla.javascript.ClassChainUniquenessTest` | rhino | `Icode` 链与 `Node` 属性 id 的唯一性 (第 7 节) | P2 / P3 |
| `org.mozilla.javascript.tests.ClassSyntaxTest262CoreTest` | tests | test262 class 两目录 (P3 起只排除 async 与 T10 特性) 解释 / 编译两模式执行, 按核心 / 字段 / 私有名分组计数 (ROADMAP 1.4), 已知失败按阶段登记 (附录 E) | P2 / P3 |
| `org.mozilla.javascript.tests.classsyntax.ClassFieldsTest` | rhino | 公共字段与 static block 的语义 (初始化时机, 作用域, 计算键顺序, [[Define]], 命名, static block 的顺序与限制, eval), 解释 / 编译两模式 | P3 |
| `org.mozilla.javascript.tests.classsyntax.PrivateNamesTest` | rhino | 私有字段 / 方法 / 访问器 / 静态私有成员, 每次类求值的唯一性, 品牌检查与重复安装的 TypeError, 反射与 Proxy 不可见, freeze 之后仍可写, 嵌套作用域, 运算符, 直接 eval, Java 对象, 解释 / 编译两模式 | P3 |
| `org.mozilla.javascript.tests.classsyntax.BuiltinSubclassingTest` | rhino | 附录 D 的每个构造器至少一个子类用例 (instanceof, 原型方法, species, 自定义成员, 覆盖的 `set` / `add` / `exec`), Proxy / 绑定函数 / 另一 realm 的函数作 new.target, 宿主 LambdaConstructor 被继承, 解释 / 编译两模式 | P4 |
| `org.mozilla.javascript.tests.BuiltinSubclassingTest262Test` | tests | test262 `subclass-builtins` (两目录 72 个), `subclass/builtin-objects` (70 个) 与 `built-ins` 下含 `newTarget` 的文件 (219 个) 解释 / 编译两模式执行, 跳过规则同 `ClassSyntaxTest262CoreTest`, 已知失败登记附录 E (P9 1 例); 失败明细写到 `tests/build/class-syntax/builtins-<模式>.txt` | P4 |
| `org.mozilla.javascript.tests.classsyntax.JavaClassInheritanceTest` | rhino | 继承 Java 类 (Thread, AbstractList, 带受保护构造器 / 字段 / 方法的抽象类), 单个接口, `JavaAdapter(Base, I1, I2)`, super() 的重载选择, `super.m()`, Java 构造器调用被覆盖的方法, 静态成员, 字段 / 私有名在实例上, 赋值写 Java 字段, Java API 拿到 Java 对象且回来仍是实例, toString / equals / hashCode, 类的子类, Java 线程池回调, 适配器类缓存, 旧 `JavaAdapter` 写法回归, 各类 TypeError / ReferenceError 消息, 解释 / 编译两模式 | P5 |
| `org.mozilla.javascript.tests.classsyntax.SpreadCallTest` | rhino | 实参展开的四种形式, 混合与多个展开, 可选调用, 各种可迭代对象, 数组迭代可观察 (覆写 `Symbol.iterator` 与 `next`), 不可迭代的 TypeError, 求值顺序与中途异常, new.target 与 new 的结果, `super.m` 的 this 与 home object, 不经 `apply` / `Reflect`, 直接 eval, 绑定函数与 call / apply, REF_CALL, 生成器里的 yield, 解释模式的深递归与 continuation (内联帧), 解释 / 编译两模式 | P6.6 |
| `org.mozilla.javascript.tests.classsyntax.LogicalAssignmentTest` | rhino | 三个运算符的值, 标识符 (const / let / `with` / 全局 / 未声明), 脚本顶层与闭包里的临时绑定, 短路时不调 setter 与冻结对象不报错, 对象与键只求值一次, null 基对象先于键转换报错, 数组元素, 私有名 (只有 getter 的访问器, 静态, 品牌检查), super 属性 (含派生构造器与非常量键), 函数名推断, 生成器, 非法目标的 SyntaxError, `set: undefined` 的严格模式写入 (Q18), 解释 / 编译两模式 | P6.7 |
| `org.mozilla.javascript.tests.classsyntax.CompiledModeFixesTest` | rhino | 嵌套对象字面量 (内联 `__proto__`, 方法前的嵌套字面量, 多层, 访问器, 箭头函数, 超过 10 个属性的字面量) 里方法的 `super`; 数字变量及其赋值 / 自增作 callee (普通 / 可选 / 展开调用, new) 抛 TypeError, 数字下标的元素调用, 直接调用的数字实参, 解释 / 编译两模式 | P6.8 |

后续阶段规划的测试类 (AsyncFunctionTest, AsyncGeneratorTest, ForAwaitTest) 落地时登记到本表.

运行: `./gradlew.bat :rhino:test --tests "org.mozilla.javascript.tests.classsyntax.*" --tests "*MessageTranslationsTest*" --tests "*ClassInstructionsTest*" --tests "*ClassChainUniquenessTest*"` 与 `./gradlew.bat :tests:test --tests "*ClassSyntaxTest262ParseTest*" --tests "*ClassSyntaxTest262CoreTest*" --tests "*BuiltinSubclassingTest262Test*"` (核心集的失败明细写到 `tests/build/class-syntax/core-<模式>.txt`).

## 6. 依赖的上游机制

P1 只依赖解析器内部机制:

- `Parser` 的 1 token 前瞻 (`peekToken` / `consumeToken` / `peekFlaggedToken` 的 `TI_AFTER_EOL` 标志), `TokenStream.tokenBeg` / `tokenEnd` 与最后扫描单词的对应关系 (`wordBeg == tokenBeg` 即刚前瞻的是单词).
- `Parser.PerFunctionVariables` 在进入与离开每个函数时保存 / 恢复状态 (钩子挂在这里维护函数种类栈与 await 上下文栈).
- `Parser.inUseStrictDirective`, `insideMethod`, `inForInit`, `nestingOfFunction`, `defineSymbol` / `pushScope` / `popScope` 的作用域模型, `FunctionNode` 的 `isES6Generator` / 方法标志.
- `IRFactory.transform` 按节点类型分派, `IRFactory.AstNodePosition` 提供报错位置.
- 对象字面量方法 (`ObjectProperty` 的 `METHOD_ENTRY`) 的解析路径, async 方法复用它.

P2 起依赖 (上游改动这些机制时按 `AGENTS.md` 7.6 跟着改 fork 实现, 并跑第 5 节全部测试):

- home object: `JSFunction.getHomeObject` / `setHomeObject`, super 属性访问 (`ScriptRuntime.getSuperProp` 等) 以 home object 与当前 this 工作; 有 home object 且可构造即类构造器 (`ClassRuntime.isClassConstructor`).
- 构造: `JSFunction.construct(cx, nt, ...)` 与 `BaseFunction.createObject(cx, scope, nt)` 按 new.target 的 prototype 创建 (T9 入口), `JSDescriptor` 的 `DERIVED_CONSTRUCTOR_FLAG` 与构造参数顺序 (`ClassCompiler` 生成), `ScriptRuntime.setBuiltinProtoAndParent` 对 new.target 的处理 (Object / Function).
- 解释器: `DoNew` 的内联构造帧, `CallFrame` 的 `boundArgs`, 按 icode 编号的 `instructionObjs` 指令表, `CodeGenerator.visitExpression` 分派与栈深度记账.
- 编译器: `BodyCodegen.generateExpression` 分派, 生成器 yield 点 (`generateLocalYieldPoint` 把操作数栈存进 `Object[]`), `ClassFileWriter.StackMapTable` 的超级块与类型推导, `OptTransformer` 的直接调用 (`DIRECTCALL_PROP`).
- IR: 参数解构在解析期构建 (`destructuringArray` / `destructuringObject` / `setupDefaultValues`), 生成器参数初始化块 (`FunctionNode.getGeneratorParamInitBlock`), NodeTransformer 的 LETEXPR -> SCOPEEXPR 与对象字面量属性 id 的单独变换, for-of 的 `ENUM_INIT_VALUES` / `IdEnumeration`.
- V2: `Instruction.stackChange` / `toDebugString` 与 `CallFrameV2` 的栈接口 (只写了指令类, 上游尚无编译器).

P3 起依赖:

- 元素访问: `ScriptRuntime.getObjectElem` / `setObjectElem` / `getElemAndThis` / `getElemAndThisOptional` / `in` 是 GETELEM / SETELEM / 元素调用 / `in` 在两个后端的共同出口 (解释器 icode 与编译模式的直接调用和动态链接都落到这里); 私有名键在入口处分流. 上游给元素访问加不经过这些方法的快速路径时, 要确认 `PrivateNameKey` 键仍走到钩子.
- 作用域: 类作用域的 LETEXPR 经 NodeTransformer 成为运行时的 `LocalScope` (含嵌套函数的函数总需要活动对象), 直接 eval 的 `ClassRuntime.evalContext` 沿作用域链从 `LocalScope` 收集 `#..@n` 绑定, 从函数活动对象的 `JSDescriptor.classElement` 读元素种类.
- `ScriptableObject` 的对象监视器 (`SlotMapOwner.ThreadedAccess` 同样用 synchronized 代替 VarHandle), `Wrapper.unwrap` (Java 对象包装的标识).
- 直接 eval: `ScriptRuntime.evalSpecial` 里 `CompilerEnvirons` 的创建与 `Context.compileString` 路径.

P4 起依赖:

- 内建构造器的形态: `ClassDescriptor.Builder` 定义的构造器是 `JSFunction` (描述符 `isFunction()` 为 false, 以此区分脚本函数), 构造代码签名 `(cx, f, nt, s, thisObj, args)` 拿到 new.target, `JSFunction.construct` 先建 this 再执行构造代码; 内建实例的原型经 `ScriptRuntime.setBuiltinProtoAndParent` -> `findPrototype` 或各构造器的钩子取得. 上游新增内建构造器时, 按附录 D 的做法让它在建实例之前取 `BuiltinConstruction.prototypeOf(f, nt)`, 并在 `BuiltinSubclassingTest` 加子类用例.
- realm: `ScriptableObject.getTopLevelScope` / `TopLevel.getBuiltinPrototype` / `getClassPrototype`, `BoundFunction.getTargetFunction`, `NativeProxy.getTargetThrowIfRevoked`; 构造器的 `getFunctionName` 与 `TopLevel.Builtins` / `NativeErrors` 的枚举名一致 (跨 realm 按名取原型).
- `LambdaConstructor.construct` 与 `BaseFunction.construct` / `createObject` 的调用顺序, `NativeRegExp.EXEC_DESCRIPTOR` (判断 exec 是否被覆盖), `ScriptRuntimeES6::symbolSpecies`.

P5 起依赖 (Java 类继承, fork-only D3; 上游改动这些机制时 `autojs6` 包跟着改):

- JavaAdapter 生成类的布局: 字段 `factory` / `delegee` / `self`, 每个非私有基类构造器对应 `(Scriptable, ContextFactory, <基类构造器参数>)`, 方法经 `getFunction(delegee, name)` 与 `callMethod(factory, self, ...)` 派发, 非抽象方法的 `super$name`, 构造器末尾的 `createAdapterWrapper(delegee, this)`; `JavaAdapter.createAdapterCode` / `loadAdapterClass` / `getOverridableMethods` / `JavaAdapterSignature`, `ClassCache.getInterfaceAdapterCacheMap` (每个顶层作用域一份的适配器类缓存).
- `NativeJavaMethod.findFunction` (重载选择) 与 `ExecutableBox.wrapArgsInternal`, `NativeJavaObject` 的 isAdapter 视图 (含受保护成员) 与 `JavaMembers` 的成员表 (字段, `BeanProperty`, `ExecutableOverload.WithField`).
- `WrapFactory.wrap` / `wrapNewObject` 是 Java 值进入脚本的出口 (宿主覆盖 `wrapAsJavaObject` 不影响钩子, 钩子在它之前); `NativeJavaClass.hasInstance` 解包 `Wrapper` (instanceof Java 类).
- super 访问: 读取 (`ScriptableObject.getSuperProperty`) 以当前 this 为 start; 方法调用 (`getPropAndThis` + `CALL_ON_SUPER`) 以 super 对象为 start 查找, 调用时换成 this, `JavaPrototype` 为此返回延迟查找的函数. 上游把 super 调用改为以 this 为 start 时, 这层可以去掉.
- `ContextFactory.call`: 没有 Context 的 Java 线程回调 (`JavaAdapter.callMethod`) 以构造时的 `cx.getFactory()` 进入.

P6 起依赖 (上游测试框架; 上游改动时 `Test262SuiteTest` 的钩子跟着搬, P9 / 同步时按 NOTES.md P6 的步骤重生成):

- `Test262SuiteTest` 的 `UNSUPPORTED_FEATURES` (按 features 跳过, 写进 properties 的 `{unsupported: [...]}`), properties 的读法 (`~` 整目录排除, 子目录汇总只含直接文件) 与重生成 (`-DupdateTest262properties=all`, `tests/build.gradle` 要求 `RHINO_TEST_JAVA_VERSION=17`).
- `TestMode` 的矩阵与 `TestResultTracker.getResult` 的标签规则 ("全部模式都失败" 时不写标签, 模式数按 `InterpreterV2Mode.modeCount()`), `Context.EvaluationMethod` / `setOptimizationLevel(-2)` 选择 V2 的方式 (V2 模式的可用性按 "能否用 V2 编译脚本" 判断).

P6.6 / P6.7 起依赖 (通用修复; 上游实现实参展开或逻辑赋值短路时按 `AGENTS.md` 7.4 判定退役这两项):

- 数组字面量的展开: `ARRAYLIT` 的 `NUMBER_OF_SPREAD` / `DESTRUCTURING_ARRAY_LENGTH` 属性, 解释器 `NewLiteralStorage.spreadArray` 与编译器同名路径按迭代器协议 (`ScriptRuntime.callIterator`) 展开; 结果是 `NativeArray`.
- 解释器 `DoCallByteCode` 的 bound 实参 (`boundArgs` / `blen`, 内联帧的 `initFrame`), `CALL_ON_SUPER` 的 this 替换; 编译器 `BodyCodegen.generateCallArgArray` 是全部调用形式的实参数组出口.
- LETEXPR 的临时绑定: `Parser.createScopeNode` / `pushScope` / `defineSymbol(Token.LET, ...)` / `popScope` 与 `currentScriptOrFn.getNextTempName` (同 `Parser.destructuringAssignmentHelper`), NodeTransformer 把它变为局部变量或作用域对象; `Parser.simpleAssignment` 的目标检查 (const, 严格模式, 私有名) 与函数名推断.
- `AccessorSlot.FunctionSetter` (显式 `set: undefined` 时 target 为 `Undefined`) 与 `throwNoSetterException` 的严格模式判断 (`Context.isStrictMode`).
- P6.8: `BodyCodegen` 以 `savedHomeObjectLocal` 把 home object 交给 `visitFunction` 的方法定义分支; `Optimizer.rewriteForNumberVariables` 的返回值约定 (`NumberType` 的节点生成 double, 父节点需要对象时包 `TO_OBJECT`) 与 `visitGetVar` 对数字变量总是 `DLOAD`.

后续阶段将依赖 (登记时补充): `GeneratorState` 与 async 驱动, `NativePromise` 与 `Context.enqueueMicrotask`, `UnhandledRejectionTracker`.

## 7. 编号链

| 链 | fork 成员 (按顺序) | 唯一性测试 |
|---|---|---|
| `Token` 常量 | `CLASS`, `PRIVATE_NAME`, `CLASS_ELEMENT`, `AWAIT` (接在上游链尾 `SCOPE_REPLACE` 之后, `LAST_TOKEN = AWAIT + 1`) | `TokenUniquenessTest.tokenValuesAreUnique`, `forkTokensAreAtTheTail` |
| `Context.FEATURE_*` | `FEATURE_CLASS_SYNTAX` (23), `FEATURE_ASYNC_SYNTAX` (24) | `TokenUniquenessTest.featureValuesAreUniqueAndForkFeaturesLast` |
| `Icode` 常量 | `CLASS_DEFINE`, `CLASS_ELEMENT`, `CLASS_CTOR_CHECK`, `CLASS_THIS`, `CLASS_TDZ`, `CLASS_SUPER_FUNC`, `CLASS_SUPER_CALL`, `CLASS_ITER_REST`, `CLASS_REQUIRE_OBJECT`, `CLASS_ITER_CLOSE`, `CLASS_PARAM_SCOPE`, `CLASS_PRIVATE_NAME`, `CLASS_INIT_FIELDS`, `CLASS_STATIC_INIT`, P6.6 的 `SPREAD_CALL`, `SPREAD_CALL_ON_SUPER`, `SPREAD_NEW`, `SPREAD_CALLSPECIAL`, `SPREAD_REF_CALL` (接在上游链尾 `SCOPE_REPLACE` 之后依次减一, `MIN_ICODE = SPREAD_REF_CALL`; 名字由 `ClassInterpreter.icodeName` 给出, 实参展开的转给 `SpreadCall.icodeName`) | `ClassChainUniquenessTest.icodesAreUniqueWithForkIcodesLast` |
| `Node` 属性 id | `CLASS_PROP` (35), `DEFAULT_NAME_PROP` (36), `LAST_PROP = DEFAULT_NAME_PROP` | `ClassChainUniquenessTest.nodePropertiesAreUniqueWithForkPropertiesLast` |

上游在同一链尾新增常量时, 上游的接在前, fork 的接在后并重算 (`AGENTS.md` 第三节第 8 条). V2 没有按编号的指令注册表 (每条指令是一个类, 由尚不存在的 IR 到 V2 编译器创建), fork 的 V2 指令类不占编号.

## 8. 已知限制

P1 (解析) 留下的限制, P2 之后的状态:

- class 能执行 (P2), 字段 / static block / 私有名能执行 (P3); async 报 `msg.async.not.supported` (P9).
- `test262.properties` 未改动, class 两目录仍整体排除; 覆盖由 `ClassSyntaxTest262ParseTest` (只解析) 与 `ClassSyntaxTest262CoreTest` (执行核心部分) 验证, P6.1 重生成时解锁 (附录 C Q9).
- 26 个生成器 `yield` 解析缺口已在 P2 修复 (附录 C Q10), `KNOWN_PARSE_GAPS` 为空.
- 解析阶段尚未报的早期错误: async 函数 / 箭头函数的参数与函数体重名, 严格函数体配非简单参数, async 箭头函数 / async 函数表达式 / `await` 表达式用作赋值目标, 对象 async 方法参数里的 `super()` (P9). class 表达式用作赋值目标与 `new super()` 已在 P2 报错.
- `class A extends new B {}` 与 fork 的 JavaAdapter 写法 `new X {}` 冲突, 按 JavaAdapter 处理; eval 代码里引用外层私有名已在 P3 支持 (附录 C Q13); 块内 async 函数声明按 Annex B 与普通函数同样处理.

P2:

- class 声明的外层绑定与 `let` 一样没有 TDZ (上游 `let` 的限制): 声明之前访问得到 undefined, 不抛 ReferenceError. 类定义期间 (extends 与计算键) 对类名的引用有 TDZ.
- 派生构造器里 `super()` 之前的直接 eval 读 `this` 得到 undefined, 规范要求 ReferenceError.
- eval 代码里不能用 `new.target` (Parser 只在函数内接受, 普通函数同样; 上游 test262 `eval-code/direct/new.target-fn.js` 失败), 也不能调 `super()` (派生构造器里的直接 eval `super()` 报 SyntaxError, 规范允许; eval 代码不知道所在的函数), 附录 C Q12. P3 起例外: 方法, 字段初始化器与 static block 里的直接 eval 知道所在的类元素 (附录 C Q13), 顶层 `new.target` 为 undefined, 字段初始化器与 static block 里的 `arguments` 报 SyntaxError, 外层私有名可见.
- 内建构造器被继承时按 new.target 创建已在 P4 完成, `ClassSyntaxTest262CoreTest` 里 4 例 P4 已知失败已删除; 5 例 async 箭头函数用例仍登记为 P9.
- 参数初始化顺序 (上游缺陷, 普通函数同样): 带解构的参数先于简单默认值初始化, 例如 `function f(a = 1, [x] = [a + 1])` 里 x 为 NaN.
- `Function.prototype` 的 `caller` / `arguments` 受限访问器只对 class 代码里的函数 (类构造器, 方法, heritage 与计算键里的函数, 以及它们内部的函数) 抛 TypeError; 其他严格函数 (含严格对象字面量方法) 保持 Rhino 原行为 (未执行时 `arguments` 为 null, 没有 `caller`), 上游 `ArgumentsTest` 依赖它, 规范要求一律抛错.
- Interpreter V2 只有指令类, 没有 IR 到 V2 的编译器 (附录 C Q11), V2 端到端 0 例.
- 编译模式 yield 点的栈类型保存只处理引用, int 与 float 栈项; long / double 栈项保持原来的无类型保存 (生成器不做数值优化, 目前不会出现).

P3:

- 私有元素表的线程语义: 读与写已有的私有字段不加锁, 添加 (字段初始化, 品牌) 在对象监视器下写时复制. 两个线程同时给同一对象添加私有元素与写它的私有字段时, 写入可能丢失; 与 Rhino 默认对象 (未开 `FEATURE_THREAD_SAFE_OBJECTS`) 的属性表一样不保证跨线程一致.
- Java 对象 (`NativeJavaObject` 等非 `ScriptableObject` 的 `Scriptable`) 作为 this 时: 私有元素记在被包装的 Java 对象上 (弱引用标识表), 同一 Java 对象的不同包装共享私有元素, 与 `===` 把它们视为同一对象一致. 只有基类构造器返回该对象 (`return o` 覆盖) 时才会被安装私有元素; 普通 Java 对象的品牌检查得 false / TypeError.
- `optimizer/ClassCompiler` (AOT 编译到 class 文件) 不写 `classElement`, 预编译代码里方法 / 字段初始化器内的直接 eval 退回 P2 的限制 (Q12); `Context.compileString` 与解释模式不受影响.
- `o?.[k]()` 在 o 为 null 时抛 TypeError 是上游缺陷 (`getElemAndThisOptional` 不结束可选链), 私有名的 `o?.#m()` 已单独处理.
- Interpreter V2 只有 3 个新指令类与单测, V2 端到端仍为 0 例 (附录 C Q11).

P4:

- 求值顺序与规范不同, 只有 new.target 的 `prototype` 是 getter (或实参有副作用) 时可观察, test262 未覆盖: TypedArray 构造器先处理实参 (读对象实参的迭代器 / length) 后取原型, 规范先取原型; ArrayBuffer 的 `maxByteLength` 超过可用内存时在取原型之前抛 RangeError (规范在取原型之后分配失败); `RegExp.prototype.test` 先读 `exec` 再对实参 ToString (规范相反).
- 宿主用 `LambdaConstructor` 定义的构造器: Java 构造代码先建好对象 (此时原型是类原型), 再按 new.target 改原型; 构造代码里看不到子类原型.
- Java 类被 class 继承时的 P2 脚手架 (`ClassRuntime.superCall` 里构造后改原型) 已由 P5 (T6) 接管并删除; 其他非 JSFunction 构造器 (绑定函数, Proxy, LambdaConstructor 等) 都由自身的 [[Construct]] 按 new.target 创建.
- WeakMap / WeakSet 有规范没有的 `Symbol.species` (上游现状, 不影响子类化, 未改); E4X 构造器 (XML, XMLList, Namespace, QName) 不看 new.target (范围外, 附录 D).
- `new f(...args)` (new 的实参里有展开) 原来两种模式都报内部错误 (`RuntimeException: 190`, 上游缺陷, 与 class 无关), 已由 P6.6 修复.

P5 (Java 类继承, 决定与理由见 NOTES.md P5):

- 未实现的抽象方法在被调用时报 TypeError (`msg.java.class.abstract`), 不在类定义或构造时检查; 覆盖 final 方法在首次构造时报 TypeError; 继承 final 或非 public 的类在类定义时报 TypeError.
- Java 只看到构造时已存在的覆盖: 适配器类按 new.target 原型链上 (到 Java 超类为止) 的函数名生成, 之后加到原型或实例上的新方法名只对 JS 可见; 已覆盖的方法名每次调用都重新查找, 替换方法体 (包括实例自有属性) 对 Java 生效.
- 赋值: 可写的 Java 字段与有 setter 的 bean 属性写到 Java 对象; 其他名字 (final 字段, 只读 bean 属性, 方法名) 成为实例的自有属性, 只对 JS 可见. 字段声明 (P3, [[Define]]) 总是建自有属性, 与同名 Java 字段并存.
- 基类构造器调用被覆盖的方法时, 方法以实例为 this 运行, 能读 Java 成员, 但实例字段尚未初始化 (与 JS 派生类在 super() 期间调用被覆盖方法一致).
- 额外接口与基类实现的接口的 default 方法: 类里的同名方法只对 JS 调用生效, Java 调用仍走 default 实现 (与 `new JavaAdapter` 相同, `getOverridableMethods` 不含它们).
- 桌面 JVM 上 JDK 类的受保护成员 (例如 `AbstractList.modCount`) 因模块不开放不可见, 该适配器类退回只有公有成员的视图; Android 没有模块, 不受影响. 旧写法 `new JavaAdapter(java.util.AbstractList, {...})` 在桌面 JDK 17+ 上因同一原因抛 `InaccessibleObjectException` (上游现状, 未改).
- 以 Symbol 为键的 `super[sym]()` 不查 Java 成员; 实例上 `Symbol.iterator` 对 `Iterable` 可用.
- 实例的 `Object.prototype.toString` 标签为 `JavaObject`, `typeof` 为 `object`; JSON / for-in / `Object.keys` 只列实例自有属性 (字段), 不含 Java 成员.
- 把类实例当作旧写法 `new JavaAdapter(Base, instance)` 的实现对象时, 方法经 `JavaClassInheritance.method` 查找, 缺失的抽象方法报 TypeError (普通实现对象得到 undefined).
- 适配器类按顶层作用域缓存 (`ClassCache`), 新的顶层作用域 (宿主每次运行脚本) 重新生成; Android 上每个签名首次构造要经宿主 D8 转 dex (P5.2 结论, P7 测量).

P6 (test262 解锁, 记录与理由见 NOTES.md P6):

- `test262.properties` 按 JDK 17 生成 (`tests/build.gradle` 的要求, 与上游一致): JDK 17 的 Unicode 版本较旧, 32 个 RegExp property-escapes / identifiers 用例在 JDK 21 通过但保持登记 (JDK 21 全量只打印 "marked as failing but it does not"); 本机 test262 检出为 CRLF (`core.autocrlf=true`), `Function/prototype/toString/line-terminator-normalisation-LF.js` 因此登记为失败, LF 检出的机器上同样只打印提示.
- 附录 A 的 7 个既有 test262 基线差异 (`__proto__` 定制 6 个, CRLF 1 个) 随重生成写进 properties, 全量对照里不再出现; 它们仍是 fork 与上游的行为差异, 同步时按 `AGENTS.md` 第五节最后一条照旧核对.
- 实参展开 `f(...args)` / `new F(...args)` / `super.m(...args)` 原来在上游各后端未实现 (内部错误 `190`), 已由 P6.6 补上 (附录 C Q16 = B), 限制见下面 "P6.6"; 逻辑赋值 `&&=` / `||=` / `??=` 原来不短路 (上游 IR 总是写回, 私有名同样), 已由 P6.7 修复 (附录 C Q17 = B), 限制见下面 "P6.7".

P6.6 (实参展开, 记录与理由见 NOTES.md "P6.6 / P6.7"):

- 解释模式的 `new F(...a)`, `eval(...a)` 与 `f(...a) = v` (REF_CALL) 走自有指令, 不内联被调用的解释函数 (与解释器调用编译函数相同, 构造器里捕获 continuation 会失败); 普通调用与 `super.m(...a)` 经 CALL 的指令, 与普通调用一样内联.
- 编译模式的直接调用优化 (同一脚本里按名调用实参个数相同的函数) 不用于展开调用.
- 数组的展开总按迭代器协议 (`Array.prototype[Symbol.iterator]` 与数组迭代器的 `next` 都可被覆写), 没有未覆写时的快速路径, 比 `f.apply(null, a)` 慢 (计时见 NOTES.md, 耗时主要在上游数组字面量 `[...a]` 的迭代, 附录 C Q19).
- V2 模式 0 例: `Context.EvaluationMethod` 没有 -2 级别, 上游也没有 IR 到 V2 的编译器 (附录 C Q11).

P6.7 (逻辑赋值短路, 记录与理由见 NOTES.md "P6.6 / P6.7"):

- 特殊属性 (`__proto__` / `__parent__` 的 `GET_REF`) 与 E4X 引用 (`x.@a`, `x..y`) 上的逻辑赋值不归 `LogicalAssignment` 处理, 照旧走上游的 "读, 运算, 总是写回".
- 脚本顶层与含嵌套函数的函数里, 临时绑定随 LETEXPR 成为作用域对象 (同块级 let), 比局部变量慢; 只有对象不是 `this` / `super` 或键不是常量时才有临时绑定.
- 可选链目标 (`o?.x ||= 1`) 仍由 Parser 报 SyntaxError, 消息沿用上游的 "Invalid left-hand side in assignment"; 调用目标 (`f() ||= 1`) 报 `msg.bad.assign.left`, 不再有 `=` / 复合赋值的 web 兼容运行时 ReferenceError.
- `set: undefined` 的修复 (Q18) 对 `=` / 复合赋值同样生效: 严格模式代码 (含 class 体) 写这种访问器从静默忽略改为 TypeError, 与没有 setter 的访问器一致.

## 9. 退役记录

(空)
