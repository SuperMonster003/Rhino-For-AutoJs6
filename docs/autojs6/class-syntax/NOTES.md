# class / async 实现笔记

各阶段的实现要点与交接事项. 钩子清单以 `HOOKS.md` 为准, 决策与进度以 `ROADMAP.md` 为准, 这里只记后续阶段需要知道的结构与坑.

## P1 语法与 AST (2026-10-24)

### 解析器结构

- `Parser` 持有 `classSyntax` (`ClassParser`) 与 `asyncSyntax` (`AsyncParser`) 两个包内对象, 上游文件里只有转发调用; 两者需要的 `Parser` 成员已放宽为包内可见 (见 HOOKS.md 第 3 节).
- 函数种类栈 (`ClassParser.FN_*`) 与 await 上下文栈 (`AsyncParser`) 都挂在 `Parser.PerFunctionVariables` 的构造与 `restore` 上: 进入函数前设置 `pendingKind` / `setPending`, 进入时压栈, 离开时出栈. 新的函数形态 (P2 的默认构造器, P9 的 async 驱动) 若不走 `createPerFunctionVariables`, 栈不会变.
- `super()` / `arguments` / `await` 的检查都找 "最近的非箭头函数" 的种类; 对象字面量方法是 `FN_PLAIN`, 不受 class 规则约束 (与上游行为一致).
- 词法: `TokenStream` 记录最后扫描的单词起点与是否含转义 (`wordBeg` / `wordEscaped`), `ClassParser` 用 `ts.wordBeg == ts.tokenBeg` 判断刚前瞻到的是不是单词 (上下文关键字 `static` / `get` / `set` / `async` / `extends` 均不能含转义).

### AST 形状 (P2 降糖的输入)

- `ClassNode` 继承 `Scope`, 类型 `Token.CLASS`. 声明 (`isStatement()`) 在外层作用域以 `let` 绑定类名; 有名字的类在自身作用域里再放一个 `CONST` 绑定 (类体内的类名不可赋值). `getSuperClass()` 是 `extends` 后的 LeftHandSideExpression, 在类作用域内解析 (可以引用类名, 会落入 TDZ).
- `ClassElement.getKind()`: `CONSTRUCTOR` / `METHOD` / `GETTER` / `SETTER` / `FIELD` / `STATIC_BLOCK`. 方法类元素的 `getValue()` 是方法的 `FunctionNode` (`isMethodDefinition()`, 生成器 / async 标志照常); 字段的 `getValue()` 是合成函数 (表达式闭包体, `return <initializer>`), 没有初始化器时为 null; static block 的 `getValue()` 是以块为体的合成函数. 三者都是严格模式.
- 键: `Name` / `StringLiteral` / `NumberLiteral` / `ComputedPropertyKey` / `PrivateName`. `getPropertyName()` 给出非计算键的属性名 (私有名带 `#`).
- 私有名: 声明在类体解析完后统一解析 (`resolvePrivateReferences`), 未声明的引用在最外层类结束时报错; getter + setter 可以配对, 其余重名报错. 引用节点是 `PropertyGet` (属性为 `PrivateName`) 与 `#x in obj` 的 `InfixExpression` (左操作数为 `PrivateName`).
- 匿名类名推断: `ClassNode.isAnonymous()` 与 `getInferredName()` 是 P2 的落点. 上游的函数名推断在 `IRFactory.inferNameIfMissing` (注释写着 "should also handle class names"), P2 在那里给 `ClassNode` 设推断名, 解析器不设.
- `ClassLowering.transform(parser, node)` 是 IRFactory 的唯一入口 (`case Token.CLASS`), P1 只报 `msg.class.not.supported`; P2 在此文件内实现降糖, IRFactory 不必再加钩子.

### P2 / P9 要接手的早期错误

- 以下 parse 阶段反例目前由编译期 "暂不支持" 拦下, 实现执行后必须在解析或降糖阶段补报: 把 class 表达式用作赋值目标 (`(class {}) = 1`, P2); async 函数 / 箭头函数的参数与函数体重名, 严格函数体配非简单参数, 把 async 箭头函数 / async 函数表达式 / `await` 表达式用作赋值目标, 对象 async 方法参数里的 `super()` (P9). test262 用例清单见 P1 会话的统计: 共 25 例, 位于 `language/expressions/assignmenttargettype`, `async-arrow-function`, `async-function`, `await`, `object/method-definition` 与 `language/statements/async-function`.
- `class A extends new B {}` 被 fork 的 JavaAdapter 扩展语法 `new X {}` 吃掉, test262 范围内没有用例; P5 (Java 继承) 时一并决定是否在 `extends` 后关闭该扩展.
- eval 代码里引用外层类的私有名目前在解析期报未声明; 直接 eval 的私有名可见性留给 P3.
- `new super()` 与 `new super.x` 的区分未做, P2 降糖时确认.
- 解析器只前瞻 1 个 token: 表达式体的 async 箭头函数结束时, 紧随其后的那个 token 已在箭头函数的 await 上下文里扫描过 (`await` 会被当成关键字). 只在换行省略分号且下一行以 `await` 开头时可见, P9 处理.
- 顺带修好的上游崩溃: 对象字面量 `{ * ; }` 之类 `*` 后键不合法时, 原来把 null 传进 `GeneratorMethodDefinition` 抛 `IllegalArgumentException`, 现在报 `msg.bad.prop` (私有名 token 让 `{ *#m() {} }` 也走到这里, 见 HOOKS.md).

### test262 现状

- `test262.properties` 未改 (附录 C Q9), class 两目录只解析的结果由 `ClassSyntaxTest262ParseTest` 守住: 反例 1262 / 1262 拒绝, 正例 7101 / 7127 可解析, 26 例是上游生成器 `yield` 解析缺口 (附录 C Q10), 跳过 module 13 例与 decorators 24 例.
- 开关打开对关闭, 全部 test262 (language, built-ins, annexB, intl402, harness) 只解析对照: 正例无一例退步, 13690 个正例由不可解析变为可解析; 1 个反例 (`for-of/head-lhs-async-invalid.js`) 由放过变为拒绝; 101 个 parse 反例由拒绝变为放过, 其中 76 个带 async / class 特性标签 (测试套件跳过), 其余 25 个靠编译期 "暂不支持" 仍判为 SyntaxError (见上一节).
- `Test262SuiteTest` 全量 (`test262.properties` 未改): 失败集与基线相同; 有 9 个登记为失败的用例现在通过 (`await` 作标识符 / 标签, `yield` 与 `*` 之间换行, `for (async of ...)`), P6.1 重生成时会从 properties 里去掉.

## P2 ES2015 核心 (2026-10-25 完成, 子会话 rhino-p2)

### 降糖结构 (P3 的输入)

- IR: class 定义降为一个 `Token.CLASS` 表达式节点, `Node.CLASS_PROP` 挂 `ClassOp` (新文件). 定义节点 (`ClassOp.DEFINE`) 的子节点依次为: 超类表达式 (`hasHeritage` 时才有), 构造器 FUNCTION 节点, 每个方法元素一个 `Token.CLASS_ELEMENT` 节点 (键表达式 + 方法 FUNCTION 节点; 元素节点的 `CLASS_PROP` 是 int 标志: `ELEMENT_METHOD` / `GETTER` / `SETTER`, `ELEMENT_STATIC`, `ELEMENT_COMPUTED`). 有名字的类外面包一层 LETEXPR (ClassNode 自身作作用域, 类名为 const 绑定, 定义期间对类名的引用转 TDZ 原语), NodeTransformer 照常转为 SCOPEEXPR; 声明再包一层外层 `let` 绑定.
- 同一个 `Token.CLASS` 节点类型也承载其他原语, 由 `ClassOp.kind` 区分 (0-8): DEFINE, SUPER_CALL (`super(...)`, `arrayArguments` 时实参是数组, 用于默认派生构造器与展开), THIS (派生构造器里的 this), TDZ (类名或参数在初始化前引用, 恒抛 ReferenceError), CTOR_CHECK (类构造器体开头的 [[Call]] 检查), ITERATOR_REST, REQUIRE_OBJECT, ITERATOR_CLOSE, PARAM_SCOPE (后四个属于通用绑定修复, 见下). 每个后端只在表达式分派处加一个 `case Token.CLASS`: 解释器 `ClassInterpreter` (icode `CLASS_*`), 编译器 `ClassBodyCodegen`, V2 `interpreterv2/instruction/Class*`.
- 运行时全部在 `ClassRuntime`: `defineClass` (校验 heritage, 建 prototype 与构造器 F 并互连, 设 home object), `defineElement` (不可枚举方法 / 访问器, 静态元素定义在 F 上, 计算键按源码顺序求值一次), `superCall` / `superCallArray` (以 F.[[Prototype]] 与当前 new.target 构造并绑定 this, 重复调用抛 ReferenceError), `derivedResult` (对象 / undefined -> 绑定值 / 其他 TypeError), `checkConstructorCall`.
- 派生构造器的 this: `JSFunction.construct` 对派生构造器走 `ClassRuntime.constructDerived`, 传入 `ClassRuntime.DerivedThis` 作为 this (持有构造器 F 与绑定值); 构造器内与其中箭头函数 (经 lexicalThis 捕获同一对象) 的 this 读取走 THIS 原语, `super.x` / `super.m()` 经 `ClassRuntime.receiver`, 直接 eval 与调试器经 `unwrapThis`. 只有派生构造器 (及其箭头函数) 里的 `super(...)` 降为 SUPER_CALL, 其他位置 (对象方法, eval 代码) 照旧由 IRFactory 报 `msg.super.shorthand.function`. 解释器的 `DoNew` 不内联派生构造器 (`inlinesNew`), 基类构造器照常内联.
- 名称与源码: 匿名类按绑定名推断 (`ClassLowering.inferClassName`, 由 IRFactory 的 `inferNameIfMissing` 调用); 静态 `name` 方法覆盖推断名; 构造器函数的源码范围设为整个 class, `Function.prototype.toString` 返回类定义源码 (附录 C Q1 = A). `Function.prototype` 的 `caller` / `arguments` 访问器对 class 代码里的函数抛 TypeError (`JSDescriptor.classCode`, 由 `CodeGenUtils` 按 AST 是否在 ClassNode 内设置); 其他函数, 包括普通严格函数, 保持 Rhino 原行为 (上游 `ArgumentsTest` 的约定).
- T9: 脚本函数本来就按 new.target 的 prototype 创建 (`BaseFunction.createObject`); Object 在 new.target 不是自身时忽略实参 (`NativeObject.js_constructor` 一行钩子), Function 本来就按 new.target 创建. 其余内建 (WeakRef, ArrayBuffer, GeneratorFunction 等) 留给 P4.

### 通用修复 (与 class 无关, test262 class 目录的方法用例依赖)

- `BindingLowering` (新文件): 参数作用域 (参数默认值里的闭包看不到函数体的 var, `ParamScope` + PARAM_SCOPE 原语), 参数 TDZ (默认值引用自身或后面的参数), 参数数组解构按迭代器协议 (IteratorStep, 空位前进, rest 用 ITERATOR_REST, 结束时关闭), 只有 rest 或为空的对象模式也做 RequireObjectCoercible 与默认值, 生成器参数解构移入参数初始化块, for-of 语句在 break / return / throw 时 IteratorClose (`next` 自己抛错或完成时不关, `IdEnumeration.done`; GetIterator 留在循环作用域里, 也在 try 内, 编译模式在局部块入口把枚举局部变量清零; JS 1.7 的数组推导式 / 生成器表达式保持原样, 推导式是表达式, 放不下 try), 解构默认值与参数默认值里匿名函数 / 类的名字推断 (`DEFAULT_NAME_PROP`), 参数里引用 `arguments` 时要求活动对象.
- 其他上游缺陷: rest 参数在 bind 内联调用时漏掉绑定实参 (`CallFrame`), 绑定函数的 `isConstructor` (`BoundFunction`), NodeTransformer 把对象展开项变换两次 (生成器里展开中的 yield 登记两个恢复点, 编译模式跳到未初始化的 switch 项).
- 生成器 `yield` (附录 C Q10): ES6 生成器里 yield 是 AssignmentExpression, 逗号后 / 实参 / 条件分支 / 对象展开 / 计算键里不再要求括号 (JS 1.7 生成器保持原要求); `yield a, yield b;` 语句; `yield*` 后可换行; 逗号与冒号前的 yield 无操作数.
- 编译模式生成器的 yield 点 (Q10 放开解析后暴露的上游后端缺陷: 栈上的 int / `LookupResult` 等存进 `Object[]` 后类型丢失, VerifyError): 新文件 `classfile/StackSpills` 在保存 / 恢复处留空指令占位, `ClassFileWriter.stopMethod` 先跑一遍栈图分析得到每个栈项的类型, 再改写为装箱 / 拆箱 / CHECKCAST, 然后照常生成 StackMapTable. 另外参数里有 yield 的调用不走直接调用快速路径 (它把实参生成两遍, 标签重复标记).

### 给 P3 的接口

- 元素循环: `ClassLowering.define` 里字段 / static block / 私有成员现在报 `msg.class.not.supported`, P3 从这里接. 建议: 实例字段初始化器合成一个以 home object 为 prototype 的方法, 基类在构造器体开头 (CTOR_CHECK 之后) 调用, 派生类在 `ClassRuntime.superCall` 绑定 this 之后调用; static 字段与 static block 在 `defineClass` 之后按源码顺序以 F 为 this 调用; 计算字段名与方法键一起按源码顺序求值一次.
- 新原语照现有模式加: `ClassOp` 新 kind + `ClassInterpreter` 新 icode (接在 `CLASS_PARAM_SCOPE` 之后, 更新 `MIN_ICODE` 与 `ClassChainUniquenessTest`) + `ClassBodyCodegen` 分支 + V2 指令类 (Q11) + `ClassRuntime` 方法; 新 Node 属性接在 `DEFAULT_NAME_PROP` 之后.
- 派生构造器的 this 绑定点只有 `ClassRuntime.superCall` / `superCallArray` 一处 (字段初始化挂这里); 箭头函数里的 `super()` 绑定同一个 `DerivedThis`.
- `msg.class.not.supported` 的文案在 P2 改为 "暂不支持类字段, static 块与私有成员" (10 个 bundle); P3 完成后删除该 key 或改为只报剩余特性.

### 留给 P3 / P4 / P6 / P8 / P9 的事项

- P3: 字段, static block, 私有名 (含 eval 里引用外层私有名), `ClassSyntaxTest262ParseTest` / `ClassSyntaxTest262CoreTest` 的 `LATER_PHASES` 逐步去掉 P3 特性.
- P4: 内建构造器按 new.target 创建; `ClassSyntaxTest262CoreTest.KNOWN_FAILURES` 里 4 例 P4 (subclass-builtins/subclass-WeakRef ×2, subclass/builtin-objects/ArrayBuffer/regular-subclassing, GeneratorFunction/instance-prototype) 在 P4 修好后删除.
- P6.1: 重生成 `test262.properties` 时, 下面 "新通过的 test262 用例" 一节的用例从失败登记里去掉; eval 里的 new.target 与 super() (Q12) 相关用例保持失败.
- P8 上游 PR 候选 (与 class 无关, 可单独提): yield 解析 (Q10), `StackSpills` (编译模式生成器 yield 点), NodeTransformer 展开项二次变换, for-of 的 IteratorClose, 参数作用域与参数 TDZ, 参数数组解构的迭代器协议, rest 参数与 bind, Object 按 new.target 忽略实参.
- P9: `ClassSyntaxTest262CoreTest.KNOWN_FAILURES` 里 5 例 async 箭头函数 (cpn-...-from-async-arrow-function-expression ×4, heritage-async-arrow-function) 在 P9 修好后删除.

### 已知限制

见 `HOOKS.md` 第 8 节 "P2" (类声明外层绑定无 TDZ, super() 之前 eval 读 this 得 undefined, eval 里不能用 new.target 与 super() (Q12), 参数初始化顺序, 普通严格函数的 `caller` / `arguments` 保持 Rhino 行为, V2 无编译器, yield 点不保存 long / double 栈项).

### 验证

- 自带测试 (最终全量里的计数): rhino 模块 ClassSyntaxTest 22, ClassSyntaxParserTest 76, ClassInstructionsTest 14, ClassChainUniquenessTest 2, HooksRegistryTest 2, TokenUniquenessTest 4, AsyncSyntaxParserTest 20, MessageTranslationsTest 36 全部通过 (解释 / 编译两模式; V2 见附录 C Q11); tests 模块 `ClassSyntaxTest262CoreTest` (解释 / 编译各运行 2428 例, 各通过 2419, 其余 9 例是登记的 P4 / P9 已知失败) 与 `ClassSyntaxTest262ParseTest` (反例 1262 / 1262 拒绝, 正例 7127 / 7127 可解析) 通过.
- 微基准: class 方法调用对 function + prototype 写法 解释 -1.5%, 编译 +1.4%, 10% 硬门槛通过; 数值见 ROADMAP 附录 A.
- 全量 (JDK 21, 五个模块): rhino 4147 (0 失败, 4 跳过), rhino-xml 6, rhino-engine 43, rhino-tools 21 (1 失败, 同基线), tests 121078 (54 失败, 16 跳过); 失败集与附录 A 基线 55 项完全相同. 记录在 `build/upstream-sync/class-p2-20261025/` (git 忽略目录: `current-failures.txt`, `full-tests.log`, `unexpectedly-passing*.txt`, `bench-class.txt`).
- 门禁: `spotlessCheck`, `git diff --check`, `.python/generate_markdown.py --check` 通过; 钩子审计 (上游文件新增行全部带标记且不超过 100 字符, 共 330 行) 与 HOOKS.md 一致.
- 第一轮全量发现的回归 (已在提交前修复, 对应上游测试现在通过): 对象方法与 eval 里的 `super()` 不再报 SyntaxError (SuperTest 2 例, test262 `super-call` 相关 5 个文件) -> 只有派生构造器里的 `super()` 降糖; for-of 的迭代表达式离开了循环作用域 (ConstLetScopingTest) -> GetIterator 放回循环, 编译模式清零局部变量; JS 1.7 数组推导式里的 for-of 也包了 try (harmony ForOfTest 两模式) -> 只处理 for-of 语句; `Function.prototype` 受限访问器波及普通函数 (ArgumentsTest 2 例, `arguments-object/10.6-13-a-2/3`) -> 只限 class 代码.

### 新通过的 test262 用例 (P6.1 重生成时去掉失败登记)

`Test262SuiteTest` 报 "Test is marked as failing but it does not" 的文件共 974 个, P1 已有 47 个, P2 新增 927 个 (完整清单 `build/upstream-sync/class-p2-20261025/unexpectedly-passing-new.txt`). P6.1 用 `-DupdateTest262properties` 重生成时由工具去掉, 不必手工编辑. 来源: 用例或 harness 里用到 class (现在能执行), 参数 / 解构 / for-of 的通用修复, yield 解析. 按目录 (10 个以上的):

| 目录 | 个数 |
| --- | --- |
| language/expressions/object | 127 |
| built-ins/TypedArray/prototype | 93 |
| language/expressions/generators | 86 |
| language/statements/generators | 85 |
| language/statements/for-of | 64 |
| built-ins/Array/prototype | 62 |
| language/expressions/super | 47 |
| language/expressions/function | 39 |
| language/statements/function | 39 |
| language/statements/for | 36 |
| language/expressions/arrow-function | 33 |
| language/arguments-object | 32 |
| language/computed-property-names/class | 29 |
| built-ins/Set/prototype | 21 |
| built-ins/Function/prototype | 18 |
| language/expressions/assignment | 16 |
| language/statements/const, let, variable | 各 12 |
| built-ins/Iterator/prototype | 11 |
| language/statements/try | 10 |
| 其余 27 个目录 | 43 |

普通生成器 (附录 C Q10 要求列出) 182 个: `language/{expressions,statements}/generators` 171, `language/expressions/object/method-definition` 9, `language/expressions/yield` 2. 其中 160 个是 `generators/dstr` 下的参数解构用例 (两个目录各 80: `ary-*` / `dflt-ary-*` 各 22, `obj-*` / `dflt-obj-*` 各 18), 来自参数数组解构的迭代器协议与 RequireObjectCoercible 修复. 其余 22 个:

- yield 解析 (Q10): `expressions/generators/named-yield-spread-obj.js`, `expressions/generators/yield-spread-obj.js`, `expressions/generators/yield-star-before-newline.js`, `statements/generators/yield-spread-obj.js`, `statements/generators/yield-star-before-newline.js`, `expressions/object/method-definition/gen-yield-spread-obj.js`, `expressions/object/method-definition/yield-as-expression-with-rhs.js`, `expressions/object/method-definition/yield-as-expression-without-rhs.js`, `expressions/object/method-definition/yield-star-before-newline.js`, `expressions/yield/rhs-omitted.js`, `expressions/yield/rhs-primitive.js` (路径省略 `language/`).
- 参数 TDZ, 参数作用域与参数里的 `arguments`:`expressions/generators/dflt-params-ref-later.js`, `expressions/generators/dflt-params-ref-self.js`, `expressions/generators/scope-paramsbody-var-open.js`, `statements/generators/dflt-params-ref-later.js`, `statements/generators/dflt-params-ref-self.js`, `statements/generators/scope-paramsbody-var-open.js`, `expressions/object/method-definition/gen-meth-dflt-params-ref-later.js`, `expressions/object/method-definition/gen-meth-dflt-params-ref-self.js`, `expressions/object/method-definition/meth-dflt-params-ref-later.js`, `expressions/object/method-definition/meth-dflt-params-ref-self.js`, `expressions/object/method-definition/params-dflt-meth-ref-arguments.js`.

`ClassSyntaxTest262CoreTest` 不受 `test262.properties` 影响, 上面的清单只关系到 `Test262SuiteTest`.
