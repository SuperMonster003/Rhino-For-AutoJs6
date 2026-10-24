# class / async 钩子清单

本文是 fork 的 class / async 定制在上游文件里留下的钩子的唯一事实来源 (`AGENTS.md` 第七节 7.1). 路线图见同目录 `ROADMAP.md`, 决策 D1-D16 与 T1-T11 不在此重复.

- 上游文件里每一行新增或改动的代码都带 `// AutoJs6: class syntax` 或 `// AutoJs6: async` 标记 (钩子块前后的空行除外); 逻辑放在第 4 节的新文件里. 核对: `git diff -U0 <基线> -- rhino/src/main/java` 里新增的非空行都应带标记.
- 第 3 节的表按 "文件 + 标记" 记行数, `HooksRegistryTest` 会把它与 `rg -n "AutoJs6: (class syntax|async)" rhino/src/main/java` 的实际结果逐文件逐标记对照; 第 4 节的新文件必须存在.
- 钩子增减或搬家时, 同一提交内更新本文. 行号会随上游变化, 表里只写方法名.

## 1. 阶段进度

| 阶段 | 状态 | 内容 |
|---|---|---|
| P1 | 完成 (2026-10-24) | 词法, AST, 解析器, 早期错误, async 预解析, 编译期 "暂不支持" 报错 |
| P2-P9 | 未开始 | 见 `ROADMAP.md` 第 5 节 |

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

合计 127 行 (class syntax 77, async 50).

## 4. 新文件

- `rhino/src/main/java/org/mozilla/javascript/ClassParser.java`: class 的词法钩子 (`keyword`, `privateName`), 声明 / 表达式 / 类体 / 元素 / 字段初始化器 / static block 的解析, 私有名作用域与引用解析, 函数种类栈与早期错误 (P1).
- `rhino/src/main/java/org/mozilla/javascript/AsyncParser.java`: `async` / `await` 的词法钩子, async 函数声明 / 表达式 / 箭头函数 / 方法与 `for await` 的预解析, await 上下文栈与早期错误 (P1).
- `rhino/src/main/java/org/mozilla/javascript/ClassLowering.java`: IRFactory 的 class 降糖入口; P1 只报 `msg.class.not.supported` (P2 起实现降糖).
- `rhino/src/main/java/org/mozilla/javascript/AsyncLowering.java`: IRFactory 的 async 入口; P1 只报 `msg.async.not.supported` (P9 起实现).
- `rhino/src/main/java/org/mozilla/javascript/ast/ClassNode.java`: class 声明 / 表达式节点 (继承 `Scope`, 类型 `Token.CLASS`; 名字, 超类, 元素, 声明或表达式, 匿名类的推断名).
- `rhino/src/main/java/org/mozilla/javascript/ast/ClassElement.java`: 类元素 (constructor / 方法 / getter / setter / 字段 / static block, static 标志, 计算键, 私有名; 类型 `Token.CLASS_ELEMENT`).
- `rhino/src/main/java/org/mozilla/javascript/ast/PrivateName.java`: 私有名 `#x` (继承 `Name`, 类型 `Token.PRIVATE_NAME`).
- `rhino/src/main/java/org/mozilla/javascript/ast/AwaitExpression.java`: `await` 表达式 (类型 `Token.AWAIT`).
- `rhino/src/main/java/org/mozilla/javascript/ast/ForAwaitOfLoop.java`: `for await (... of ...)` 循环 (继承 `ForInLoop`).

### 4.1 错误消息

基础 `Messages.properties` 与 9 个翻译 bundle (ar, es, fr, ja, ko, ru, zh, zh_HK, zh_TW) 末尾各有一段 `# AutoJs6: class syntax` 与 `# AutoJs6: async` 注释开头的 fork 消息 (T11). key 清单以 `MessageTranslationsTest` 的 `CLASS_SYNTAX_KEYS` 为准 (P1 共 27 个):

- class (21): `msg.class.name.missing`, `msg.class.no.brace`, `msg.class.no.brace.after`, `msg.class.dup.ctor`, `msg.class.ctor.special`, `msg.class.field.ctor`, `msg.class.static.prototype`, `msg.class.private.ctor`, `msg.class.private.undeclared`, `msg.class.private.dup`, `msg.class.private.delete`, `msg.class.private.super`, `msg.class.private.unexpected`, `msg.class.super.call`, `msg.class.field.arguments`, `msg.class.getter.parms`, `msg.class.setter.parms`, `msg.class.strict.parms`, `msg.class.field.semi`, `msg.class.decl.not.in.block`, `msg.class.not.supported`.
- async (6): `msg.async.not.supported`, `msg.async.await.context`, `msg.async.await.params`, `msg.async.for.await.of`, `msg.async.decl.not.in.block`, `msg.async.name.missing`.

## 5. 自带测试类

| 测试类 | 模块 | 内容 | 阶段 |
|---|---|---|---|
| `org.mozilla.javascript.tests.classsyntax.ClassSyntaxParserTest` | rhino | class 的 AST 形状, 位置, `toSource`, 早期错误, 开关与 ES6 门控, 编译期 "暂不支持" | P1 |
| `org.mozilla.javascript.tests.classsyntax.AsyncSyntaxParserTest` | rhino | async / await / for await 的预解析, 早期错误, 开关, 编译期 "暂不支持" | P1 |
| `org.mozilla.javascript.tests.classsyntax.TokenUniquenessTest` | rhino | 编号链唯一性 (第 7 节) | P1 |
| `org.mozilla.javascript.tests.classsyntax.HooksRegistryTest` | rhino | 本文第 3 / 4 节与源码对照 | P1 |
| `org.mozilla.javascript.tests.MessageTranslationsTest` (fork 已有, 新增 `classSyntaxMessagesAreTranslated`) | rhino | fork 消息在 9 个翻译 bundle 齐全 | P1 |
| `org.mozilla.javascript.tests.classsyntax.ClassSyntaxTest262ParseTest` | tests | test262 class 两目录只解析: parse 阶段反例全部拒绝, 其余用例除登记的生成器缺口外全部可解析 | P1 |

后续阶段规划的测试类 (ClassSyntaxTest, ClassFieldsTest, PrivateNamesTest, BuiltinSubclassingTest, JavaClassInheritanceTest, AsyncFunctionTest, AsyncGeneratorTest, ForAwaitTest) 落地时登记到本表.

运行: `./gradlew.bat :rhino:test --tests "org.mozilla.javascript.tests.classsyntax.*" --tests "*MessageTranslationsTest*"` 与 `./gradlew.bat :tests:test --tests "*ClassSyntaxTest262ParseTest*"`.

## 6. 依赖的上游机制

P1 只依赖解析器内部机制:

- `Parser` 的 1 token 前瞻 (`peekToken` / `consumeToken` / `peekFlaggedToken` 的 `TI_AFTER_EOL` 标志), `TokenStream.tokenBeg` / `tokenEnd` 与最后扫描单词的对应关系 (`wordBeg == tokenBeg` 即刚前瞻的是单词).
- `Parser.PerFunctionVariables` 在进入与离开每个函数时保存 / 恢复状态 (钩子挂在这里维护函数种类栈与 await 上下文栈).
- `Parser.inUseStrictDirective`, `insideMethod`, `inForInit`, `nestingOfFunction`, `defineSymbol` / `pushScope` / `popScope` 的作用域模型, `FunctionNode` 的 `isES6Generator` / 方法标志.
- `IRFactory.transform` 按节点类型分派, `IRFactory.AstNodePosition` 提供报错位置.
- 对象字面量方法 (`ObjectProperty` 的 `METHOD_ENTRY`) 的解析路径, async 方法复用它.

后续阶段将依赖 (P2 起登记): 对象字面量方法的 home object, `Constructable.construct(cx, nt, ...)`, `GeneratorState` 与生成器 Icode, `NativePromise` 与 `Context.enqueueMicrotask`, `UnhandledRejectionTracker`, V2 指令注册.

## 7. 编号链

| 链 | fork 成员 (按顺序) | 唯一性测试 |
|---|---|---|
| `Token` 常量 | `CLASS`, `PRIVATE_NAME`, `CLASS_ELEMENT`, `AWAIT` (接在上游链尾 `SCOPE_REPLACE` 之后, `LAST_TOKEN = AWAIT + 1`) | `TokenUniquenessTest.tokenValuesAreUnique`, `forkTokensAreAtTheTail` |
| `Context.FEATURE_*` | `FEATURE_CLASS_SYNTAX` (23), `FEATURE_ASYNC_SYNTAX` (24) | `TokenUniquenessTest.featureValuesAreUniqueAndForkFeaturesLast` |

上游在同一链尾新增常量时, 上游的接在前, fork 的接在后并重算 (`AGENTS.md` 第三节第 8 条). `Icode` 常量, `Node` 属性 id, V2 指令注册在 P1 未改动, 后续阶段新增时补测试并登记.

## 8. 已知限制 (P1)

- class 与 async 代码能解析, 但编译 (`IRFactory`) 时报 "暂不支持" (`msg.class.not.supported` / `msg.async.not.supported`), 分别由 P2 / P9 实现.
- `test262.properties` 未改动, class 两目录仍整体排除; P1 的覆盖由 `ClassSyntaxTest262ParseTest` 只解析地验证 (反例 1262 / 1262 拒绝), P6.1 重生成时解锁 (附录 C Q9).
- 26 个正例因上游生成器解析的缺口 (逗号表达式或参数列表里未加括号的 `yield`, 对象展开里的 `yield`) 不能解析, 同样的写法在普通生成器函数里也失败; 登记在 `ClassSyntaxTest262ParseTest.KNOWN_PARSE_GAPS`, 按附录 C Q10 处理.
- 部分早期错误在解析阶段尚未报错, 由编译期 "暂不支持" 拦下 (test262 class / async 特性以外目录里 25 例 parse 反例): async 函数 / 箭头函数的参数与函数体重名, 严格函数体配非简单参数, 把 async 箭头函数 / async 函数表达式 / class 表达式 / `await` 表达式用作赋值目标, 对象 async 方法参数里的 `super()`. class 表达式那一类由 P2 补齐, 其余由 P9 补齐.
- `class A extends new B {}` 与 fork 的 JavaAdapter 写法 `new X {}` 冲突, 按 JavaAdapter 处理; eval 代码里引用外层私有名在解析期报错 (P3 处理); 块内 async 函数声明按 Annex B 与普通函数同样处理; `new super()` 未报错.

## 9. 退役记录

(空)
