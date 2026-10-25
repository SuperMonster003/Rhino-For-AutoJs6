# class / async 钩子清单

本文是 fork 的 class / async 定制在上游文件里留下的钩子的唯一事实来源 (`AGENTS.md` 第七节 7.1). 路线图见同目录 `ROADMAP.md`, 决策 D1-D16 与 T1-T11 不在此重复.

- 上游文件里每一行新增或改动的代码都带 `// AutoJs6: class syntax` 或 `// AutoJs6: async` 标记 (钩子块前后的空行除外); 逻辑放在第 4 节的新文件里. 核对: `git diff -U0 <基线> -- rhino/src/main/java` 里新增的非空行都应带标记.
- 第 3 节的表按 "文件 + 标记" 记行数, `HooksRegistryTest` 会把它与 `rg -n "AutoJs6: (class syntax|async)" rhino/src/main/java` 的实际结果逐文件逐标记对照; 第 4 节的新文件必须存在.
- 钩子增减或搬家时, 同一提交内更新本文. 行号会随上游变化, 表里只写方法名.

## 1. 阶段进度

| 阶段 | 状态 | 内容 |
|---|---|---|
| P1 | 完成 (2026-10-24) | 词法, AST, 解析器, 早期错误, async 预解析, 编译期 "暂不支持" 报错 |
| P2 | 完成 (2026-10-25) | class 降糖与运行时原语, 解释 / 编译后端, V2 指令类; 通用绑定修复 (参数作用域, 参数解构的迭代器协议, for-of 关闭, 函数名推断); 生成器 `yield` 解析 (附录 C Q10) 与编译模式 yield 点的栈类型保存 |
| P3 | 完成 (2026-10-25) | 公共字段, static block, 私有名 (T5: 每次类求值新建 `PrivateNameKey`, 对象上独立的私有元素表), 直接 eval 里的外层私有名与类元素上下文 (附录 C Q13), V2 指令类 |
| P4-P9 | 未开始 | 见 `ROADMAP.md` 第 5 节 |

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
| `rhino/src/main/java/org/mozilla/javascript/JSFunction.java` | class syntax | 4 | `construct`: 派生构造器交给 `ClassRuntime.constructDerived`; 有 home object 的类构造器同样创建 this | P2 |
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

合计 368 行 (class syntax 318, async 50); P2 新增 203 行, 另把 `IRFactory.transform` 里 P1 的 `ClassLowering.transform` 调用改为传入 IRFactory; P3 新增 40 行, `transformLiteral` 的 3 行合为 1 行.

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
- `rhino/src/main/java/org/mozilla/javascript/optimizer/ClassBodyCodegen.java`: 编译后端: `Token.CLASS` 的字节码生成, 当前 this 的取值, 直接调用判定用的 `hasYield`, for-of 局部变量清零的判定 (P2).
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

### 4.1 错误消息

基础 `Messages.properties` 与 9 个翻译 bundle (ar, es, fr, ja, ko, ru, zh, zh_HK, zh_TW) 末尾各有一段 `# AutoJs6: class syntax` 与 `# AutoJs6: async` 注释开头的 fork 消息 (T11). key 清单以 `MessageTranslationsTest` 的 `CLASS_SYNTAX_KEYS` 为准 (P1 27 个, P2 新增 11 个, P3 删除 1 个新增 8 个, 共 45 个):

- class P1 (20): `msg.class.name.missing`, `msg.class.no.brace`, `msg.class.no.brace.after`, `msg.class.dup.ctor`, `msg.class.ctor.special`, `msg.class.field.ctor`, `msg.class.static.prototype`, `msg.class.private.ctor`, `msg.class.private.undeclared`, `msg.class.private.dup`, `msg.class.private.delete`, `msg.class.private.super`, `msg.class.private.unexpected`, `msg.class.super.call`, `msg.class.field.arguments`, `msg.class.getter.parms`, `msg.class.setter.parms`, `msg.class.strict.parms`, `msg.class.field.semi`, `msg.class.decl.not.in.block`; `msg.class.not.supported` 已在 P3 删除 (字段 / static block / 私有名可以执行).
- class P2 (11): `msg.class.ctor.call`, `msg.class.extends.not.ctor`, `msg.class.extends.proto`, `msg.class.super.not.ctor`, `msg.class.this.uninit`, `msg.class.super.twice`, `msg.class.derived.return`, `msg.class.tdz`, `msg.class.restricted.prop`, `msg.param.tdz`, `msg.class.new.super`.
- class P3 (8): `msg.class.private.read`, `msg.class.private.write`, `msg.class.private.twice`, `msg.class.private.methods.twice`, `msg.class.private.method.write`, `msg.class.private.no.getter`, `msg.class.private.no.setter`, `msg.class.field.define`.
- async (6): `msg.async.not.supported`, `msg.async.await.context`, `msg.async.await.params`, `msg.async.for.await.of`, `msg.async.decl.not.in.block`, `msg.async.name.missing`.

## 5. 自带测试类

| 测试类 | 模块 | 内容 | 阶段 |
|---|---|---|---|
| `org.mozilla.javascript.tests.classsyntax.ClassSyntaxParserTest` | rhino | class 的 AST 形状, 位置, `toSource`, 早期错误, 开关与 ES6 门控; P2 加 ES6 生成器的 `yield` 解析与 JS 1.7 生成器的括号要求; P3 起字段 / static block / 私有名可以编译 | P1 / P2 / P3 |
| `org.mozilla.javascript.tests.classsyntax.AsyncSyntaxParserTest` | rhino | async / await / for await 的预解析, 早期错误, 开关, 编译期 "暂不支持" | P1 |
| `org.mozilla.javascript.tests.classsyntax.TokenUniquenessTest` | rhino | 编号链唯一性 (第 7 节) | P1 |
| `org.mozilla.javascript.tests.classsyntax.HooksRegistryTest` | rhino | 本文第 3 / 4 节与源码对照 | P1 |
| `org.mozilla.javascript.tests.MessageTranslationsTest` (fork 已有, 新增 `classSyntaxMessagesAreTranslated`) | rhino | fork 消息在 9 个翻译 bundle 齐全 | P1 |
| `org.mozilla.javascript.tests.classsyntax.ClassSyntaxTest262ParseTest` | tests | test262 class 两目录只解析: parse 阶段反例全部拒绝, 其余用例全部可解析 (P2 起 `KNOWN_PARSE_GAPS` 为空) | P1 |
| `org.mozilla.javascript.tests.classsyntax.ClassSyntaxTest` | rhino | ES2015 class 语义, 每条规则一组, 解释 / 编译两模式 | P2 |
| `org.mozilla.javascript.interpreterv2.ClassInstructionsTest` | rhino | V2 class 指令的栈变化与调试串 (附录 C Q11); P3 加 `ClassPrivateName` / `ClassInitFields` / `ClassStaticInit` 与无函数的 `ClassElement` | P2 / P3 |
| `org.mozilla.javascript.ClassChainUniquenessTest` | rhino | `Icode` 链与 `Node` 属性 id 的唯一性 (第 7 节) | P2 / P3 |
| `org.mozilla.javascript.tests.ClassSyntaxTest262CoreTest` | tests | test262 class 两目录 (P3 起只排除 async 与 T10 特性) 解释 / 编译两模式执行, 按核心 / 字段 / 私有名分组计数 (ROADMAP 1.4), 已知失败按阶段登记 (附录 E) | P2 / P3 |
| `org.mozilla.javascript.tests.classsyntax.ClassFieldsTest` | rhino | 公共字段与 static block 的语义 (初始化时机, 作用域, 计算键顺序, [[Define]], 命名, static block 的顺序与限制, eval), 解释 / 编译两模式 | P3 |
| `org.mozilla.javascript.tests.classsyntax.PrivateNamesTest` | rhino | 私有字段 / 方法 / 访问器 / 静态私有成员, 每次类求值的唯一性, 品牌检查与重复安装的 TypeError, 反射与 Proxy 不可见, freeze 之后仍可写, 嵌套作用域, 运算符, 直接 eval, Java 对象, 解释 / 编译两模式 | P3 |

后续阶段规划的测试类 (BuiltinSubclassingTest, JavaClassInheritanceTest, AsyncFunctionTest, AsyncGeneratorTest, ForAwaitTest) 落地时登记到本表.

运行: `./gradlew.bat :rhino:test --tests "org.mozilla.javascript.tests.classsyntax.*" --tests "*MessageTranslationsTest*" --tests "*ClassInstructionsTest*" --tests "*ClassChainUniquenessTest*"` 与 `./gradlew.bat :tests:test --tests "*ClassSyntaxTest262ParseTest*" --tests "*ClassSyntaxTest262CoreTest*"` (核心集的失败明细写到 `tests/build/class-syntax/core-<模式>.txt`).

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

后续阶段将依赖 (登记时补充): `GeneratorState` 与 async 驱动, `NativePromise` 与 `Context.enqueueMicrotask`, `UnhandledRejectionTracker`.

## 7. 编号链

| 链 | fork 成员 (按顺序) | 唯一性测试 |
|---|---|---|
| `Token` 常量 | `CLASS`, `PRIVATE_NAME`, `CLASS_ELEMENT`, `AWAIT` (接在上游链尾 `SCOPE_REPLACE` 之后, `LAST_TOKEN = AWAIT + 1`) | `TokenUniquenessTest.tokenValuesAreUnique`, `forkTokensAreAtTheTail` |
| `Context.FEATURE_*` | `FEATURE_CLASS_SYNTAX` (23), `FEATURE_ASYNC_SYNTAX` (24) | `TokenUniquenessTest.featureValuesAreUniqueAndForkFeaturesLast` |
| `Icode` 常量 | `CLASS_DEFINE`, `CLASS_ELEMENT`, `CLASS_CTOR_CHECK`, `CLASS_THIS`, `CLASS_TDZ`, `CLASS_SUPER_FUNC`, `CLASS_SUPER_CALL`, `CLASS_ITER_REST`, `CLASS_REQUIRE_OBJECT`, `CLASS_ITER_CLOSE`, `CLASS_PARAM_SCOPE`, `CLASS_PRIVATE_NAME`, `CLASS_INIT_FIELDS`, `CLASS_STATIC_INIT` (接在上游链尾 `SCOPE_REPLACE` 之后依次减一, `MIN_ICODE = CLASS_STATIC_INIT`) | `ClassChainUniquenessTest.icodesAreUniqueWithForkIcodesLast` |
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
- 除 Object / Function 外的内建构造器被继承时还不按 new.target 创建 (P4); `ClassSyntaxTest262CoreTest` 里 4 例登记为 P4 已知失败, 另 5 例 async 箭头函数用例登记为 P9.
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

## 9. 退役记录

(空)
