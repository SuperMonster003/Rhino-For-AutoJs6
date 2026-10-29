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
- [x] eval 代码里引用外层类的私有名目前在解析期报未声明; 直接 eval 的私有名可见性留给 P3. (P3 已完成: 直接 eval 沿作用域链收集外层私有名, 附录 C Q13; 间接 eval 与 `Function` 构造器仍报未声明, 符合规范)
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
- `msg.class.not.supported` 的文案在 P2 改为 "暂不支持类字段, static 块与私有成员" (10 个 bundle); P3 完成后删除该 key 或改为只报剩余特性. (P3 已从 10 个 bundle 与 `CLASS_SYNTAX_KEYS` 删除; `ClassLowering.reportNotSupported` 保留给 `AsyncLowering`)

### 留给 P3 / P4 / P6 / P8 / P9 的事项

- [x] P3: 字段, static block, 私有名 (含 eval 里引用外层私有名), `ClassSyntaxTest262ParseTest` / `ClassSyntaxTest262CoreTest` 的 `LATER_PHASES` 逐步去掉 P3 特性. (P3 已完成, `LATER_PHASES` 只剩 async-functions / async-iteration; `ClassSyntaxTest262ParseTest` 本来就不跳过 P3 特性)
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

## P3 字段, static block, 私有名 (2026-10-25 完成, 子会话 rhino-p3)

### 运行时结构

- T5 盘点结论: 可行, 不登记推翻. 私有元素表 (新文件 `PrivateElements`) 是 `ScriptableObject` 上一个独立的 volatile 字段 `privateElements`, 与 `SlotMapOwner` 的属性表无关: 按 `PrivateNameKey` 身份查找的两个小数组, 读不加锁, 添加时在 `synchronized (owner)` 里整表复制后发布 (与 fork 在 `SlotMapOwner.ThreadedAccess` 的做法一致, 不用 VarHandle, Android 24 可用). 不是 `ScriptableObject` 的 `Scriptable` (`NativeJavaObject` 等) 用 `PrivateElements` 里的弱引用标识表, Java 对象包装按被包装的 Java 对象记 (同一 Java 对象的不同包装共享私有元素, 与 `===` 一致). 反射, Proxy 陷阱, JSON, for-in, `Object.assign`, 展开都只看属性表, 所以看不到私有元素; freeze / preventExtensions 只改属性表, 安装与写入私有元素不受影响 (现行规范; stage 3 提案见附录 C Q14).
- 私有名的词法绑定: `ClassParser.declarePrivate` 给每个声明的私有名分配隐藏绑定 `#<名字>@<全局序号>` (源码标识符不可能以 `#` 开头, `@` 不能出现在标识符里; getter / setter 对共用一个), 作为 LET 符号放进 ClassNode 的作用域; 引用在类体结束时解析到本类, 外层类或 eval 上下文的绑定 (`PrivateName.getBinding`). 降糖时类作用域 (LETEXPR) 给每个绑定一个 PRIVATE_NAME 初值, 每次类定义求值新建 `PrivateNameKey`; 匿名类有私有名时同样建类作用域. 闭包, 嵌套类, 直接 eval 都走普通作用域链.
- 私有成员访问没有新的读写原语: `o.#x` 降为 `GETELEM(o, NAME(#x@n))`, `#x in o` 的左边降为 `NAME(#x@n)`; 赋值, 复合赋值, `++`, 解构目标, for-in / for-of 目标, 可选链, 带 this 的调用, 标签模板都沿用元素访问的 IR. `ScriptRuntime` 的 `getObjectElem` / `setObjectElem` / `getElemAndThis` / `getElemAndThisOptional` / `in` 在键是 `PrivateNameKey` 时转 `ClassRuntime` 的 PrivateGet / PrivateSet / 调用 / 私有 in. 键对象只出现在这些位置, 脚本拿不到.
- 私有方法与访问器: 函数放在 `PrivateNameKey` 上 (kind METHOD / ACCESSOR), 对象只记录类的品牌 (kind BRAND 的键, 实例品牌与静态品牌各一个, 每次类求值一个); `#m in o`, `o.#m`, `o.#g` 查品牌. 同一个类的实例共用同一个方法函数 (`a.#m === b.#m`).
- 类的内部槽放在构造器 F 的 `JSFunction.classData` (`ClassRuntime.ClassData`): 实例字段 (`FieldRecord`: 键为属性键或 `PrivateNameKey`, 初始化器方法, 是否按键命名), 待执行的静态元素 (字段与 static block 按源码顺序), 实例 / 静态品牌, 类名 (品牌报错用).
- 元素: 字段与 static block 沿用 CLASS_ELEMENT, 新标志 `ELEMENT_FIELD` (种类 3), `ELEMENT_PRIVATE` (键是私有名), `ELEMENT_NAMED` (计算键的字段值是匿名函数 / 类, 运行时按键命名; 非计算键在降糖时命名), `ELEMENT_NO_VALUE` (没有初始化器), `ELEMENT_BLOCK` (static block). 初始化器与 static block 各是一个方法 (home object 为 prototype 或 F, 所以 `super.x` 可用), 键与方法一起按源码顺序求值一次.
- 新原语 (ClassOp 9-11, icode `CLASS_PRIVATE_NAME` / `CLASS_INIT_FIELDS` / `CLASS_STATIC_INIT` 接在链尾): PRIVATE_NAME 新建私有名; INIT_FIELDS 在基类构造器体开头 (CTOR_CHECK 之后, 类有实例字段或私有方法时) 执行 InitializeInstanceElements (先品牌, 再按顺序定义字段); STATIC_INIT 包在定义与类名绑定的 SETCONST 外面, 类名绑定初始化之后加静态品牌并执行静态字段与 static block. 派生类在 `ClassRuntime.superCall` 绑定 this 之后 (重复 `super()` 的 ReferenceError 在此之前) 执行 InitializeInstanceElements. 字段用 [[Define]] (`defineOwnProperty`, 不触发原型上的 setter), 定义失败抛 TypeError.
- 报错的 realm: 私有元素操作失败 (品牌检查, 重复安装, 写私有方法, 缺 getter / setter) 抛执行该操作的代码所在 realm 的 TypeError 对象 (`ScriptRuntime.throwCustomError`, Java 侧是 `JavaScriptException`); EcmaError 会在 catch 处变成捕获方 realm 的错误, test262 的 `*-multiple-evaluations-of-class-realm*` 用例据此失败过. 基值是 null / undefined 与 `#x in` 非对象时与属性访问一样是 EcmaError.
- 直接 eval (附录 C Q13): `CodeGenUtils` 把 `ClassLowering.elementKind` (方法, 字段初始化器, static block; 构造器与普通函数为 -1) 写进 `JSDescriptor.classElement`. `ScriptRuntime.evalSpecial` 编译前调 `ClassRuntime.evalContext(scope, source)`: 源码含 `#` 时沿作用域链从 `LocalScope` 收集 `#..@n` 绑定 (内层优先), 并跳过箭头函数的活动对象取第一个非箭头函数的 classElement; 结果经 `CompilerEnvirons.classEvalContext` 交给 `ClassParser`: 私有名引用解析到收集的绑定, 顶层 `new.target` 放行并降为 undefined (这三种函数从不被构造), 字段初始化器与 static block 里引用 `arguments` 报 SyntaxError. 普通函数与构造器里的 eval 仍按 Q12 = A.
- 顺带修复 (test262 class 目录依赖, 与私有名无关的也一并修): 对象解构赋值里属性值是成员表达式时先求值目标引用再读属性 (`BindingLowering.assignsMember`, 上游先把值存进临时变量); 不在任何非箭头函数里的箭头函数用 `new.target` 报 SyntaxError (上游只看函数嵌套层数, `(0, eval)('() => new.target')` 被放过); `o?.#m()` 在 o 为 null / undefined 时结束可选链 (同样写法的 `o?.[k]()` 是上游缺陷, 未修, 见 HOOKS.md 第 8 节).

### 给 P4 / P5 / P6 / P9 的接口

- P4 (内建按 new.target 创建): 派生类的字段在 `superCall` 拿到父构造器返回的对象后安装 (`initializeInstanceElements(result, holder.constructor, cx)`), 内建子类的实例只要经 `superCall` 返回就得到字段与私有元素; 内建对象都是 `ScriptableObject`, 私有元素走字段表. P4 拆掉 `superCall` 里 "非 JSFunction 构造器按 new.target 改原型" 的 P2 脚手架时, 字段安装的位置不变.
- P5 (Java 继承, T6: 字段与私有名在 JS self 对象上): Java 父类构造完成后, 钩子层以 JS self 对象 (`ScriptableObject`, 原型为 X.prototype) 调 `ClassRuntime.initializeInstanceElements(self, F, cx)`, 不要传 Java 对象的包装 (公共字段会经 `putProperty` 写到 Java 对象上, 私有元素会进弱引用标识表). Java 回调 JS 方法时 this 要是 self 对象, 否则 `this.#x` 的品牌检查失败.
- P6.1: 重生成 `test262.properties` 时 `nonextensible-applies-to-private` 按 T10 排除 (附录 C Q14); 字段 / 私有名用例里依赖 eval 的已能通过 (Q13).
- P9 (async): `ClassSyntaxTest262CoreTest.KNOWN_FAILURES` 新增 14 例 P9 (私有 async 方法 / async 生成器方法 8 例, 字段计算键里的 async 箭头函数 4 例, static block 里的 async 函数 / 方法 2 例), 加上 P2 的 5 例共 19 例; async 方法作为类元素沿用 `defineElement` / `definePrivateMethod` (`newMethod` 已为生成器方法设 prototype, async 生成器方法同理); async 方法的 `elementKind` 返回 FN_METHOD, eval 规则即生效; static block 里的 `await` 已是早期错误. P9 解锁后 class 两目录还有 2078 例 (async 标志) 与 607 例 (async 特性) 要跑, 其中字段 56 例, 私有名 1354 例.

### 已知限制

见 `HOOKS.md` 第 8 节 "P3" (私有元素表的线程语义, Java 对象的私有元素, AOT 编译的 `classElement`, 上游的 `o?.[k]()`, V2 只有指令类).

### 验证

- test262 class 两目录 (`ClassSyntaxTest262CoreTest`, P3 起只跳过 async 与 T10): 解释与编译模式各运行 5700 例, 各通过 5677, 两种模式相同. 按 ROADMAP 1.4 分组: 核心 2428 / 2419 (同 P2), 字段 612 / 606, 私有名 2660 / 2652; 未通过的 23 例全部是 `KNOWN_FAILURES` (P4 4 例, P9 19 例, 其中 P3 新登记 14 例都要 async). 1.4 节的字段 673 例 = 运行 612 + async 标志 56 (P9) + module 5 (T10); 私有名 4016 例 = 运行 2660 + async 标志 1148 与 async 特性 206 (P9) + `nonextensible-applies-to-private` 2 (T10, 附录 C Q14). 首轮 31 例新失败已全部修复 (realm 10 例, eval 里的 new.target 4 例, 解构求值顺序 1 例, nonextensible 2 例按 T10 排除, 余 14 例登记 P9).
- `ClassSyntaxTest262ParseTest`: 反例 1262 / 1262 拒绝, 正例 7127 / 7127 可解析 (同 P2).
- 自带测试 (rhino 模块, 最终全量里的计数): ClassFieldsTest 12, PrivateNamesTest 15 (新), ClassSyntaxTest 22, ClassSyntaxParserTest 76, ClassInstructionsTest 18, ClassChainUniquenessTest 2, HooksRegistryTest 2, TokenUniquenessTest 4, AsyncSyntaxParserTest 20, MessageTranslationsTest 36 全部通过 (解释 / 编译两模式; V2 只有指令类单测, 端到端 0 例, 附录 C Q11).
- 全量 (JDK 21, 五个模块): rhino 4178 (0 失败, 4 跳过), rhino-xml 6, rhino-engine 43, rhino-tools 21 (1 失败, 同基线), tests 121078 (54 失败, 16 跳过); 失败集与 P2 基线 55 项完全相同, 无新增也无修复 (`.python/junit_failures.py` 对照, 记录在 `build/upstream-sync/class-p3-20261025/`: `current-failures.txt`, `full-tests.log`, `unexpectedly-passing*.txt`).
- 门禁: `spotlessCheck`, `git diff --check`, `.python/generate_markdown.py --check` 通过; 钩子审计 (上游文件新增行全部带标记且不超过 100 字符, 共 368 行) 与 HOOKS.md 一致.

### 新通过的 test262 用例 (P6.1 重生成时去掉失败登记)

`Test262SuiteTest` 报 "Test is marked as failing but it does not" 的文件共 1000 个: P1 47 个, P2 927 个, P3 新增 26 个 (清单 `build/upstream-sync/class-p3-20261025/unexpectedly-passing-new.txt`), P2 的 974 个仍全部通过. P6.1 用 `-DupdateTest262properties` 重生成时由工具去掉. P3 新增的 26 个 (路径省略 `language/` 与 `built-ins/`):

- static block 里 `await` 作绑定 / 引用的早期错误 (`static-init-await-*`, 原来因 "暂不支持" 报错而失败) 17 个: `expressions/{function,generators}/static-init-await-reference.js`, `expressions/object/ident-name-prop-name-literal-await-static-init.js`, `expressions/object/identifier-shorthand-static-init-await-valid.js`, `expressions/object/method-definition/static-init-await-{binding,reference}-{accessor,generator,normal}.js` (6), `statements/{const,function,let,try,variable}/static-init-await-binding-valid.js` (5), `statements/variable/dstr/{ary,obj}-ptrn-elem-id-static-init-await-valid.js`.
- `#x in obj` 4 个: `expressions/in/private-field-presence-{accessor,method}{,-shadowed}.js`.
- 私有方法的 `Function.prototype.toString` 4 个: `Function/prototype/toString/private-{,static-}method-class-{expression,statement}.js`.
- 顶层箭头函数里的 `new.target` 报 SyntaxError 1 个: `global-code/new.target-arrow.js`.

## P4 内建构造器按 new.target 创建 (2026-10-25 完成, 子会话 rhino-p4)

### 统一入口与快速路径

- T9 盘点结论: 可行, 不登记推翻 (附录 D "T9 落地方式"). 上游已把内建迁到 `ClassDescriptor` (构造器是 `JSFunction`, 构造代码拿到 new.target), 并有近似入口 `ScriptRuntime.findPrototype` / `setBuiltinProtoAndParent`; P4 把它们与脚本函数的 `BaseFunction.createObject` 统一到新文件 `BuiltinConstruction`, 上游文件只留钩子 (HOOKS.md 第 3 节 P4 行, 62 行).
- 两个入口: `BuiltinConstruction.prototypeOf(f, nt)` (内建构造器: GetPrototypeFromConstructor, 默认原型是 f 自己的 `prototype`) 与 `ScriptRuntime.createFromConstructor(cx, nt, scope, defaultProto)` (普通对象的 OrdinaryCreateFromConstructor, 脚本函数 / class 基类构造器的 this 经 `BaseFunction.createObject` 用它). `findPrototype` 两个重载转调 `prototypeOf`, 已经调用 `setBuiltinProtoAndParent` 的构造器由此一并套用; 其余构造器在建实例之前调 `prototypeOf` (样板 NativeMap 与 NativeError, 然后批量套用, 附录 D 每行的状态).
- 快速路径: new.target 就是构造器自身 (普通 `new X()`) 或没有 new.target (作为函数调用) 时直接取 X 的 `prototype` 字段, 不做属性查找也不算 realm; new.target 是 JSFunction 时同样读字段, 只有绑定函数 / Proxy / 宿主函数作 new.target 才 Get `prototype`.
- GetFunctionRealm (`functionRealm`): 绑定函数取目标, Proxy 取 target (已撤销抛 TypeError), 其他函数取声明作用域的顶层. new.target 的 `prototype` 不是对象时取默认原型: 同 realm 时先取 f 自己的 `prototype`; 否则 (或它不是对象) 在 new.target 所在 realm 里按 f 的名字取内建原型 (`TopLevel.Builtins` / `NativeErrors` 枚举名; 6 个原生错误共用的 `js_constructor` 都传 Error, 按名才分得开, 不必改 `NativeGlobal`), 名字不是内建名时取 `findPrototype` 传入的类型, 再退到同名类原型. 类型参数不能省: `Uint8Array.fromBase64` / `fromHex` 以静态方法自身为 f 执行构造代码 (首轮全量的 7 个 test262 文件与 `AdditionalUint8ArrayMethodsTest` 因此失败, 已修并在 `BuiltinSubclassingTest.typedArrays` 加用例).
- 内建构造器的 this: `JSFunction.construct` 在执行构造代码前总会建一个 this 对象, 内建构造器不用它. P4 让它经 `BuiltinConstruction.thisObject`: 脚本函数 (描述符 `isFunction()`) 走 OrdinaryCreateFromConstructor, 内建构造器得到与 P4 之前相同的对象, 不读 new.target. 首轮 test262 的 3 例新失败 (TypedArray 的 Symbol 实参, DataView 偏移, ArrayBuffer `maxByteLength`) 都是这个 this 提前调用了 new.target 的 `prototype` getter.
- 宿主构造器: `LambdaConstructor.construct` 与 `BaseFunction.construct` (非 JSFunction) 在 Java 侧建好对象后按 new.target 改原型 (`withPrototype`); Java 代码不知道 new.target, 对象此时还没有暴露给脚本.

### 特殊项

逐行状态见 ROADMAP 附录 D; 这里只记实现上需要知道的:

- Map / Set / WeakMap / WeakSet: 先设原型再装入; Map / Set 的装入改为 `addEntriesFromIterable` / `addValuesFromIterable`, adder 从新对象上 Get (子类覆盖的 `set` / `add` 生效), 上游原来取 realm 全局原型上的方法. 迭代器为 undefined 时忽略 (上游原有的宽松处理, 保留).
- RegExp: `regExpArgs` 实现构造器第 1-6 步 (IsRegExp 读 `Symbol.match`; 作为函数调用, 实参是正则 (或 `Symbol.match` 为真), 没有 flags 且 `pattern.constructor === RegExp` 时返回原对象; 类 RegExp 对象取 `source` / `flags`). `test` 在 exec 被覆盖时走 RegExpExec (`userTest`), 内建 exec 保留原来的快速路径. species 与 lastIndex 原来就对.
- GeneratorFunction: 生成器函数的 `prototype` 不再带 `constructor` (`BaseFunction.setupDefaultPrototype`); 上游缺陷, 普通生成器声明同样受益.
- ArrayBuffer 补 `Symbol.species`; DataView 取原型后重查 (`checkView`); TypedArray 非对象实参按 ToIndex (Symbol 抛 TypeError, 原来抛 `Error: invalid argument`).
- Proxy: ProxyCreate 不读 new.target (原来经 findPrototype 多一次可观察的 Get).
- Promise: 执行器运行之前取原型.
- 不可构造与只核对的 (Symbol, BigInt, %TypedArray%, Continuation, 绑定函数与 Proxy 的 [[Construct]]) 行为不变, 只加用例.

### P4.3 脚手架

`ClassRuntime.superCall` 里 P2 的 "父构造器不是 JSFunction 时构造后按 new.target 改原型" 收窄为 `superConstructor instanceof NativeJavaClass`, 注释写明 P5 (T6) 接管; P2 留在 `ClassRuntime` 里未用的 `createFromConstructor` / `prototypeFromConstructor` 删除 (由 `BuiltinConstruction` 取代). 其他非 JSFunction 父构造器 (绑定函数, Proxy, LambdaConstructor, FunctionObject) 都由自身的 [[Construct]] 按 new.target 创建, 不再需要构造后改原型.

### 给 P5 / P6 / P9 的接口

- P5 (Java 继承): `superCall` 里只剩 `NativeJavaClass` 的构造后改原型, P5 的 JavaClassInheritance 在钩子层接手时删掉这一处 (连同注释). 宿主用 `LambdaConstructor` 定义的构造器已能被 class 继承, P5 不必处理. 跨 realm 的原型规则 (`BuiltinConstruction.prototypeOf` / `functionRealm`) 可直接复用.
- P6.1: `Test262SuiteTest` 新通过的文件见下节, 重生成 `test262.properties` 时由工具去掉; `BuiltinSubclassingTest262Test` 跳过的 SharedArrayBuffer 等按 T10 (附录 E).
- P9 (async): `built-ins/AsyncFunction/instance-construct-throws.js` 是 `BuiltinSubclassingTest262Test` 唯一的已知失败 (附录 E). AsyncFunction / AsyncGeneratorFunction 构造器落地时: 用 `BaseFunction` 的 Function 构造路径 (经 `findPrototype` 自动按 new.target 取原型), async 函数没有 `prototype` 也不可构造; `class X extends Promise` 已按 new.target 创建 (执行器之前取原型), async 函数返回的 Promise 用 realm 的 %Promise%, 不受子类影响. Promise 子类的 `then` / `resolve` / `all` 等 species 行为原来就对.

### 已知限制

见 `HOOKS.md` 第 8 节 "P4" (TypedArray 对象实参 / ArrayBuffer 超大 `maxByteLength` / `RegExp.prototype.test` 的求值顺序, LambdaConstructor 构造代码看不到子类原型, Java 类留给 P5, WeakMap / WeakSet 的额外 species, E4X, 上游的 `new f(...args)` 内部错误).

### 验证

- test262 内建子类化 (`BuiltinSubclassingTest262Test`, 新, 解释 / 编译相同): `subclass-builtins` 72 个文件运行 70 通过 70 (`subclass-SharedArrayBuffer.js` 两例按 T10 跳过), `subclass/builtin-objects` 70 / 70 / 70, `built-ins` 下含 `newTarget` 的 219 个文件运行 171 通过 170 (跳过 SharedArrayBuffer 20, explicit-resource-management 13, Temporal 9, async-iteration 4, async-functions 1, iterator-helpers 1; 未通过的 1 例是 P9 已知失败 `AsyncFunction/instance-construct-throws.js`, 附录 E). 盘点基线两模式各 33 例失败, 全部修好 (P9 那 1 例除外).
- test262 class 两目录 (`ClassSyntaxTest262CoreTest`): 两模式各运行 5700 例, 各通过 5681 (P3 为 5677); 核心 2428 / 2423 (P2 / P3 为 2419), 字段 612 / 606, 私有名 2660 / 2652; 未通过的 19 例都是 P9 已知失败, P4 的 4 例已删除. 只解析 (`ClassSyntaxTest262ParseTest`): 反例 1262 / 1262, 正例 7127 / 7127 (同 P3).
- 自带测试 (rhino 模块, 最终全量里的计数): BuiltinSubclassingTest 18 (新), ClassFieldsTest 12, PrivateNamesTest 15, ClassSyntaxTest 22, ClassSyntaxParserTest 76, ClassInstructionsTest 18, ClassChainUniquenessTest 2, HooksRegistryTest 2, TokenUniquenessTest 4, AsyncSyntaxParserTest 20, MessageTranslationsTest 36 全部通过 (解释 / 编译两模式). P4 没有新消息 key (只用已有的 `msg.arraybuf.detached`, `msg.dataview.offset.range`, `msg.dataview.length.range`, `msg.arg.not.object`).
- 全量 (JDK 21, 五个模块): rhino 4196 (0 失败, 4 跳过), rhino-xml 6, rhino-engine 43, rhino-tools 21 (1 失败, 同基线), tests 121080 (54 失败, 16 跳过); 失败集与 P3 基线 55 项完全相同, 无新增也无修复 (`.python/junit_failures.py` 对照, 记录在 `build/upstream-sync/class-p4-20261025/`: `current-failures.txt`, `full-tests.log`, `unexpectedly-passing*.txt`). 首轮全量有 30 个新失败 (`Uint8Array.fromBase64` / `fromHex` 的 7 个 test262 文件 x 4 种组合与 `AdditionalUint8ArrayMethodsTest` 2 例), 原因是静态方法以自身为 f 执行构造代码, 默认原型漏掉了 `findPrototype` 的类型参数; 修好后重跑即上面的结果.
- 门禁: `spotlessCheck`, `git diff --check`, `.python/generate_markdown.py --check` 通过; 钩子审计 (上游文件新增行全部带标记且不超过 100 字符, 共 430 行; 唯一未带标记的新增行是 `ScriptRuntime.createFromConstructor` 方法前格式化要求的空行) 与 HOOKS.md 一致.

### 新通过的 test262 用例 (P6.1 重生成时去掉失败登记)

`Test262SuiteTest` 报 "Test is marked as failing but it does not" 的文件共 1047 个: P1 47 个, P2 927 个, P3 26 个, P4 新增 47 个 (清单 `build/upstream-sync/class-p4-20261025/unexpectedly-passing-new.txt`), 之前的 1000 个仍全部通过. P6.1 用 `-DupdateTest262properties` 重生成时由工具去掉. P4 新增的 47 个 (路径省略 `built-ins/`, 另注明 `language/` 与 `annexB/`):

- RegExp 12 个 (built-ins 11, annexB 1): `RegExp/call_with_non_regexp_same_constructor.js`, `call_with_regexp_match_falsy.js`, `call_with_regexp_not_same_constructor.js`, `from-regexp-like{,-flag-override,-get-ctor-err,-get-flags-err,-get-source-err,-short-circuit}.js` (6), `prototype/Symbol.matchAll/isregexp-{called-once,this-throws}.js`, 以及 `annexB/built-ins/RegExp/prototype/Symbol.split/Symbol.match-getter-recompiles-source.js`.
- 跨 realm 与 GetFunctionRealm 12 个: `NativeErrors/{Eval,Range,Reference,Syntax,Type,URI}Error/proto-from-ctor-realm.js` (6), `Function/internals/Construct/base-ctor-revoked-proxy{,-realm}.js`, `Function/prototype/bind/{get-fn-realm,get-fn-realm-recursive,proto-from-ctor-realm}.js`, `Proxy/construct/trap-is-undefined-proto-from-newtarget-realm.js`.
- WeakRef / FinalizationRegistry 6 个: `{WeakRef,FinalizationRegistry}/{proto-from-ctor-realm,prototype-from-newtarget-abrupt,prototype-from-newtarget-custom}.js`.
- ArrayBuffer 5 个: `ArrayBuffer/Symbol.species/{length,return-value,symbol-species-name,symbol-species}.js`, `ArrayBuffer/data-allocation-after-object-creation.js`.
- TypedArray 5 个: `TypedArrayConstructors/{ctors,ctors-bigint}/length-arg/{is-symbol-throws,toindex-length}.js` (4), `TypedArrayConstructors/ctors/typedarray-arg/throw-type-error-before-custom-proto-access.js`.
- DataView 3 个: `DataView/custom-proto-access-{detaches-buffer,resizes-buffer-invalid-by-length,resizes-buffer-invalid-by-offset}.js`.
- 生成器函数的 prototype 不带 constructor 2 个: `language/{expressions,statements}/generators/prototype-own-properties.js`.
- 其他 2 个: `Iterator/proto-from-ctor-realm.js` (`BuiltinSubclassingTest262Test` 按 iterator-helpers 跳过, `Test262SuiteTest` 照跑), `language/expressions/super/realm.js`.

`BuiltinSubclassingTest262Test` 与 `ClassSyntaxTest262CoreTest` 不受 `test262.properties` 影响, 上面的清单只关系到 `Test262SuiteTest`.

## P5 继承 Java 类与接口 (2026-10-25 完成, 子会话 rhino-p5)

### 钩子层结构

- 写法 (附录 C Q3 = A): `class X extends JavaClass {}`, `class X extends Interface {}`, `class X extends JavaAdapter(Base, I1, I2) {}` (第一个实参是类时它是超类, 其余是接口; 只有接口时超类是 Object). `JavaAdapter(...)` 只在实参全是 Java 类 / 接口时返回超类; 带实现对象的旧写法 `new JavaAdapter(Base, {...}, args)` 与 `new Interface({...})` 不变 (`legacyJavaAdapterStillWorks`).
- 上游文件只有钩子 (HOOKS.md 第 3 节 P5 行, 上游文件 19 行, 全部 fork-only D3): `JavaAdapter` 5 处 (超类工厂, 实例作 self, 方法查找, 生成代码里的两处早期存储), `WrapFactory` 2 处 (Java 对象回到实例), `JavaMembers` 1 个包内方法. fork 文件 `ClassRuntime` 另有 4 行带标记 (heritage 分派, this 未初始化的消息), 退役 class 族时它们要改挂到上游实现 (`AGENTS.md` 7.4: Java 继承永久留在 fork). P4.3 留在 `ClassRuntime.superCall` 的 Java 类脚手架已删除.
- 逻辑在 `org.mozilla.javascript.autojs6` 包: `JavaClassInheritance` (钩子入口与构造), `JavaSuperclass` (超类), `JavaPrototype` (超类的 prototype), `JavaSubclassObject` (实例), `JavaSubclass` (标记接口). 它们要用的包内 API 经 `org.mozilla.javascript.JavaInheritanceSupport` 转调 (适配器类生成与缓存, 可覆盖方法, 构造器重载选择, 不调用访问器的自有属性读取, 生成代码片段, Java 字段是否可写), 桥里没有自己的逻辑.
- 超类 `JavaSuperclass`: 每个顶层作用域按 "Java 类 + 接口" 一份 (所以两个继承 Thread 的类的 `Object.getPrototypeOf` 相同, `JavaAdapter(Thread, Comparable)` 每次求值也相同). 不可调用 (`msg.class.ctor.call`), 显示 Java 类的静态成员 (`X.MAX_PRIORITY`, 静态方法里的 `super.currentThread()`), `name` 是 Java 类名 (或 `JavaAdapter(a, b)`).
- 构造 (派生构造器的 `super(args)` 以 new.target 调超类的 [[Construct]]): 取 new.target 的 `prototype`; 沿原型链收集函数名 (到 `JavaPrototype` 为止, 跳过 `constructor`), 可覆盖的进签名, 与 final 方法同名的报 TypeError; `JavaInheritanceSupport.adapterClass` 按 (超类, 接口 + `JavaSubclass`, 方法名) 取或生成适配器类 (`JavaAdapter.createAdapterCode`, 类名前缀 `classadapter`, 缓存在顶层作用域的 `ClassCache`); 新建实例 (原型 = new.target 的 prototype); 在公有与受保护的基类构造器里做重载选择 (`NativeJavaMethod.findFunction`; `JavaMembers` 只列公有构造器, 所以自己枚举), 实参按 Rhino 互操作规则转换, 调适配器构造器 `(实例, factory, 实参...)`. 适配器构造器在调 Java 构造器之前先存 delegee / self / factory (`storeEarly`), 末尾的 `createAdapterWrapper` 把适配器对象交给实例.
- 方法派发: 适配器方法 `getFunction(delegee, name)` 转 `JavaClassInheritance.method`: 从实例沿原型链找数据属性 (不调用访问器, 不需要 Context), 是函数就直接返回; 否则返回延迟函数, 调用时在 Context 里 Get (访问器, 后来赋给实例的方法), 没有则报 `msg.java.class.abstract`, 不是函数报 `msg.java.class.override.not.function`. 适配器方法开头 `attachEarly` 把 Java 对象交给实例 (Java 构造器调用被覆盖的方法时, 实例已能读 Java 成员). 参数与返回值转换沿用 JavaAdapter (`callMethod`, `convertResult`).

### 返回对象形态 (附录 C Q4 = A)

- `new X()` 得到 `JavaSubclassObject`: 普通 `ScriptableObject`, 实现 `Wrapper` (`unwrap` 是适配器对象, 传给 Java API 即是它). 原型链: 实例 -> X.prototype -> (JS 超类的 prototype ...) -> `JavaPrototype` -> Object.prototype. `Object.prototype.toString` 的标签是 `JavaObject`, `typeof` 是 object.
- 实例自己的: 字段 (P3 的 [[Define]]) 与赋给它的新名字是自有属性; 私有元素在实例的私有元素表 (P3 的 T5, `ScriptableObject` 字段), `#x in o` 成立. JSON / for-in / `Object.keys` 只看到这些.
- Java 成员在 `JavaPrototype`: start 是实例时, 查适配器对象的 `NativeJavaObject` 视图 (isAdapter, 含受保护成员); 被覆盖的方法给 Java 实现 (`super$m`), 抽象的不给 (否则得到回调实例自己的适配器方法, 无限递归); 适配器内部成员 (factory, delegee, self, `super$*`) 不显示. 赋值: 可写字段与有 setter 的 bean 属性写到 Java 对象, 其他名字建实例的自有属性 (`assignmentsAndLookups`).
- `super.m()`: 读 super 属性以 this 为 start, 直接得到 Java 实现; 方法调用时 Rhino 以 super 对象为 start 查找 (上游 `getPropAndThis` + `CALL_ON_SUPER`, 普通 class 的父类 getter 也收到原型对象作 this), 所以 `JavaPrototype` 在 start 是它自己且名字是 Java 实例方法时返回延迟函数 (每个名字缓存一个), 调用时以收到的 this 查找.
- 同一性: Java 返回或回调传入的适配器对象经 `WrapFactory` 钩子 (`selfOf`, 读生成类的 `delegee` 字段) 还原为同一个实例, 所以 `list.get(0) === k`, 回来的对象仍 instanceof X, 字段与私有名都在 (`javaApisGetTheJavaObject`, `implementsSeveralInterfacesWithJavaAdapter`).
- instanceof: `x instanceof X` 走 JS 原型链; `x instanceof java.lang.Thread` / `Runnable` 走 `NativeJavaClass.hasInstance` (解包 Wrapper). 两者都成立.
- toString / equals / hashCode: Object 的非 final 方法都可覆盖, Java 集合 (HashSet) 与 `String.valueOf` 用覆盖; `String(x)` 走原型链上的 toString.

### 决定与理由

- 抽象方法未实现: 被调用时 TypeError (`msg.java.class.abstract`, 带声明它的 Java 类名). 不在类定义时检查: heritage 先于类元素求值, 中间可以有 JS 超类, 方法也可以在定义后加到原型上; 不在构造时检查: Java 的抽象类 / 接口常有用不到的方法, 惯用法是只实现会被调用的监听器方法, 旧写法 `new JavaAdapter` / `new Interface({...})` 也是调用时才发现, 构造时检查会让这类部分实现报错.
- 覆盖 final 方法: 首次构造时 TypeError (适配器类此时按方法名生成; 静默忽略会让人以为覆盖生效). 继承 final / 非 public 类, 基本类型或数组: 类定义时 TypeError.
- 超类工厂只在实参全是 Java 类 / 接口时启用 (最后一个实参不是 Java 类, 或含对象字面量, 都走旧写法); 类必须是第一个实参 (`msg.java.adapter.class.first`), 重复报 `msg.java.adapter.dup`, 两个类沿用上游的 `msg.only.one.super`, 非 Java 类实参沿用 `msg.not.java.class.arg`.
- 构造器没有匹配的重载: TypeError `msg.java.class.no.ctor` (带实参类型). super() 之前读 this: ReferenceError `msg.java.class.this.uninit` (说明 Java 对象由 super() 创建).
- 实现约束: 不放宽 `JavaAdapter.getAdapterClass` 的可见性 (钩子行超过 100 字符会被格式化拆成无标记的行), 在桥里照抄 4 行缓存逻辑; 生成代码里按类名前缀认类适配器, 不给 `createAdapterCode` 加参数, 上游签名不变.
- 桌面 JVM 上 JDK 类的受保护字段不对未命名模块开放 (`InaccessibleObjectException`), 该适配器类退回只有公有成员的视图并记住; 按异常类名判断, 不在 Android 上引用这个类.

### 缓存与线程回调

- 适配器类缓存: 键是超类, 接口 (含 `JavaSubclass`) 与被覆盖的方法名 (元数统一记 0, 只按名字); 同一 class 定义多次求值, 或两个类覆盖同一组名字, 共用一个适配器类 (`adapterClassIsCached`). 缓存在顶层作用域的 `ClassCache` (`isCachingEnabled` 关闭时每次生成); 每个适配器类的派发表与构造器缓存在 `JavaSuperclass`.
- 线程回调: 适配器的 factory 是构造时的 `cx.getFactory()`. Java 线程 (Thread.run, 线程池) 调用被覆盖的方法时, `JavaAdapter.callMethod` 在当前线程已有 Context 时直接调用, 否则经 `factory.call` 新建 Context, 与旧 JavaAdapter / 接口适配器相同 (`callbacksFromJavaThreads`, `extendsThread`). 宿主上的关系 (只读核对): `AndroidContextFactory.makeContext` 给新 Context 的是工厂的 `AndroidContextFactory.WrapFactory`, 不是脚本引擎的 `RhinoJavaScriptEngine.WrapFactory`, 所以这类线程里 Java 返回的 View 没有 ViewExtras 包装 (与现有 `new java.lang.Thread(new java.lang.Runnable({...}))` 相同); 新 Context 是解释模式, `observeInstructionCount` 的中断检查照常. 脚本自己 start 的 Java 线程不登记 `RuntimeResourceScope`, 与旧写法一样由脚本负责结束, 宿主推荐的仍是 threads 模块. P7.2 用例覆盖.
- 实例的私有元素与字段的线程语义同 P3 (添加在对象监视器下, 不保证跨线程一致).

### P5.2 Android 路径 (只读核对宿主)

- 加载: `JavaAdapter.loadAdapterClass` -> `cx.createClassLoader(applicationClassLoader)` -> `AndroidContextFactory.createClassLoader` 新建 `AndroidClassLoader` (共用缓存目录, 不清空, H26) -> `defineClass` 把字节码放进临时 zip, 转 dex (宿主 `ClassFileDexer` / D8) 后用 DexClassLoader 加载, 加载后删除临时文件. 类适配器与旧 JavaAdapter 走同一路径; fork 只多了两段无分支的字节码 (`storeEarly` 的 3 次 PUTFIELD, `attachEarly` 的 1 次 INVOKESTATIC), Android 上 ClassFileWriter 生成 48 版本 (无 StackMapTable) 不受影响.
- 引用: 适配器类引用 `org.mozilla.javascript.JavaAdapter`, `autojs6.JavaSubclass` 与 `autojs6.JavaClassInheritance.attach`, 都由父加载器提供; 宿主 proguard 的 `-keep class org.mozilla.javascript.** { *; }` 覆盖 `autojs6` 包 (宿主目前 release / debug 都 `isMinifyEnabled = false`); `selfOf` 只反射生成类自己的公有字段 `delegee`. 宿主 `AndroidClassLoader.loadClass` 先查已加载的 dex 再查父加载器, 继承 `runtime.loadDex` / `loadJar` 加载的类时适配器能否解析到它, 与旧 JavaAdapter 相同, 列为 P7 用例.
- 缓存: `ClassCache` 跟着顶层作用域, 宿主每次运行脚本是新的顶层作用域, 所以每次运行里每个签名的首次构造要 D8 一次 (同一次运行内不重复), 量级与一次 `new JavaAdapter` 相同, P7 测量.
- 兼容: 不用 API 24 之后的类与方法 (`Method.isDefault` 是 API 24; `InaccessibleObjectException` 只比较类名; 不用 `Map.of` / `ClassValue`).
- 结论: 继承 Java 类在宿主上可用不需要改宿主. 可选的宿主相关项: View 子类实例是否带 ViewExtras 扩展 (附录 C Q15, P7 开始前拍板); 若 P7 测得每次运行的 D8 开销明显, 再议宿主按字节码哈希缓存 dex (涉及 H26 的缓存目录规则); 文档在 P7.4. 打包 APK (inrt) 与混淆下的可用性已作为注记写进 roadmap P7.

### 给 P6 / P7 / P9 的接口

- P6: test262 不涉及 Java 继承 (fork 扩展); P5 不改 class 语义, test262 计数不变 (见验证). 全量回归里 `JavaClassInheritanceTest` 计入 rhino 模块.
- P7: 设备用例 (`RhinoClassSyntaxDeviceTest` 的 Java 部分): `extends java.lang.Thread` (start / join 与 Java 线程回调), `extends android.os.Handler` (handleMessage 在主线程 Looper), `extends java.lang.Runnable` 交给 `View.post` / `Handler.post`, `extends JavaAdapter(java.lang.Object, java.lang.Runnable, java.lang.Comparable)`, ui 模式里继承 View 类 (覆盖 onDraw / onTouchEvent, 加入布局; 是否带 ViewExtras 由 Q15 定), 继承 `loadDex` / `loadJar` 加载的类, 同一组用例在 inrt 打包 APK 里跑; API 24 (legacy dx) 与 API 35; 记录每个签名首次构造的 D8 耗时. Java 线程里没有引擎 WrapFactory 的行为照旧, 只在文档里说明.
- P9: async 方法覆盖 Java 方法时, 适配器得到 Promise 并按 Java 返回类型转换 (void 丢弃, Object 得到 Promise 对象, 其他类型转换失败报错); P9 定是否给专门的错误消息或在文档说明 "Java 拿到的是 Promise". Java 线程经 `factory.call` 进入的回调里产生的微任务何时执行, 随 P9 的微任务驱动 (D13 / D14) 一并定.

### 已知限制

见 `HOOKS.md` 第 8 节 "P5" (构造后新增的方法名对 Java 不可见, 非可写 Java 名字的赋值只对 JS 可见, default 方法, 桌面 JDK 的受保护成员, Symbol 键的 super 调用, 类实例作旧写法的实现对象, 每个顶层作用域的适配器类缓存).

### 验证

- 自带测试 (rhino 模块, 解释 / 编译两模式): `JavaClassInheritanceTest` 20 个测试方法 (新, 见 HOOKS.md 第 5 节), classsyntax 包其余测试与 `MessageTranslationsTest` 36 (含 54 个 class / async key) 全部通过; `HooksRegistryTest` 通过 (钩子 453 行: class syntax 403, async 50, P5 新增 23 行). 全量之后改了超类工厂的判定 (实参里有任何非 Java 类的脚本对象就走旧写法) 并补一条旧写法用例, 重跑 `:rhino:test` 4216 例 0 失败 (4 跳过), `:tests:test` 的 `DoctestsTest` (含 `javaadapter.doctest`) 与 `StringifyJavaObjectsTest` 153 例通过 (日志 `build/upstream-sync/class-p5-20261025/rhino-rerun.log`).
- test262 (P5 不改 class 语义, 与 P4 相同): `ClassSyntaxTest262CoreTest` 两模式各运行 5700 通过 5681 (核心 2423 / 2428, 字段 606 / 612, 私有名 2652 / 2660; 未通过的 19 例都是 P9); `ClassSyntaxTest262ParseTest` 反例 1262 / 1262, 正例 7127 / 7127; `BuiltinSubclassingTest262Test` `subclass-builtins` 70 / 70, `subclass/builtin-objects` 70 / 70, `built-ins` 含 newTarget 的 171 运行通过 170 (P9 1 例).
- 全量 (JDK 21, 五个模块, 2026-10-25 15:55 起): rhino 4216 (0 失败, 4 跳过), rhino-xml 6, rhino-engine 43, rhino-tools 21 (1 失败, 同基线), tests 121080 (54 失败, 16 跳过); 合计 125366 例 55 失败, 与 P4 基线 55 项完全相同, 无新增也无修复 (`.python/junit_failures.py` 对照, 记录在 `build/upstream-sync/class-p5-20261025/`: `current-failures.txt`, `full-tests.log`, `unexpectedly-passing.txt`). `Test262SuiteTest` 登记为失败但通过的文件仍是 1047 个, 与 P4 清单相同 (P5 没有新增).
- 门禁: `spotlessCheck`, `git diff --check`, `.python/generate_markdown.py --check` 通过; 上游文件新增行全部带标记且不超过 100 字符, 未带标记的新增行只在 fork 文件 `ClassRuntime` (`uninitializedThis` 方法体).
- 中途暂停 (2026-10-25 16:16, 维护者流量通知) 不影响结论: 暂停前启动的全量在暂停期间跑完, 恢复后对照并重跑了全量启动后改动的部分.

## P6 test262 解锁与全量回归 (2026-10-25, 子会话 rhino-p6)

### properties 重生成后的状态

- 输入改动只有两处: `Test262SuiteTest.UNSUPPORTED_FEATURES` 去掉 `class` / `class-fields-private` / `class-fields-public` 并加 T10 的 `decorators` / `nonextensible-applies-to-private` (钩子 3 行), properties 里 class 两目录行首的 `~`; 其余全部由 `RHINO_TEST_JAVA_VERSION=17 ./gradlew.bat :tests:test --tests org.mozilla.javascript.tests.Test262SuiteTest -DupdateTest262properties=all` 生成. 注意两点: `tests/build.gradle` 只在 `RHINO_TEST_JAVA_VERSION=17` 时允许重生成 (测试 JVM 换成 JDK 17, Gradle 用 `E:/.gradle/jdks` 里自动下载的 17); 属性值要写 `all` 或不带值, `=true` 会把 rollup / stats / unsupported 都关掉, 生成的文件面目全非.
- 重生成跑三种模式 (解释, 编译, 调试器), 约 19 分钟. class 两目录在三种模式下结果相同, properties 里没有模式标签.
- class 两目录: 通过 5681, 登记 2745 = P9 2704 + T10 41 (ROADMAP P6.1 证据与附录 E). 子目录全是 async 时工具写成一行汇总 (如 `async-gen-method 94/94 (100.0%)`), 不带 `{unsupported: ...}` 标签; 要按原因统计, 用文件的 features / flags 重新归类 (见下面的工具).
- 自有执行测试保留: `ClassSyntaxTest262CoreTest`, `BuiltinSubclassingTest262Test`, `ClassSyntaxTest262ParseTest` 与 properties 的结果逐项对得上 (通过数相同, 已知失败相同), 它们比 properties 更严 (已知失败通过时报错, 按核心 / 字段 / 私有名分组计数), 继续作为 fork 的阶段验收; `Test262SuiteTest` 只在失败时报错.
- 与环境有关的登记: JDK 17 的 Unicode 版本较旧, RegExp property-escapes 25 个与 identifiers 14 个在 JDK 21 通过但保持登记 (JDK 21 全量只打印 "marked as failing but it does not"); 本机 `core.autocrlf=true` 让 test262 检出为 CRLF, `line-terminator-normalisation-LF.js` 登记为失败. 以后在本机重生成结果一致; 在 LF 检出 / JDK 17 的机器上只会多出 "marked as failing" 的提示, 不会失败.
- 附录 A 的 7 个既有 test262 基线差异随重生成写进 properties, 全量对照从 55 项降为 27 项 (rhino-tools 1, tests 26). 它们的行为没有变, 同步时仍按 `AGENTS.md` 第五节最后一条核对 `__proto__` 定制.
- class 两目录之外, 52 个带 class 特性标签的用例运行后失败, 根因都不在 class 实现 (ROADMAP P6.1 证据逐类列出). 其中两类会影响 class 的常见写法, 登记附录 C Q16 (实参展开 `f(...args)` / `super.m(...args)` / `new F(...args)` 在上游各后端未实现) 与 Q17 (逻辑赋值 `&&=` 等不短路, 私有名同样).

### V2 模式开关

- `-Drhino.test262.interpreterV2=true` (Gradle 自动把 `rhino.*` 系统属性传给测试), 实现在 `tests/.../InterpreterV2Mode.java`, `Test262SuiteTest.TestMode.V2` 与 `getResult` 的模式计数各一处钩子. 只有 Context 能用 V2 编译脚本时才加入矩阵; 现在打印 "Interpreter V2 mode (-Drhino.test262.interpreterV2) is not available, 0 cases run: java.lang.IllegalStateException: Unknown optimization level." (`EvaluationMethod` 没有 -2 级别; 补上之后还要 IR 到 V2 的编译器, 附录 C Q11).
- 上游编译器合入后: 先跑 `-Drhino.test262.interpreterV2=true` 看 V2 的失败, 再用 `-DupdateTest262properties=all -Drhino.test262.interpreterV2=true` 重生成 (properties 会出现 `v2` 标签). 如果上游自己在 `TestMode` 加了 V2 模式, 删掉 fork 的 `V2` 与 `InterpreterV2Mode` (按 `AGENTS.md` 7.4 的思路, 以上游为准), `getResult` 的计数改回上游写法.

### 给 P7 / P8 / P9 的接口

- P7: 本阶段没有改 `rhino/src/main/java`, jar 内容与 P5 相同. 附录 C Q15 = A 已写成 ROADMAP P7.2a (查找顺序 JS 类成员 > Java 成员 > 宿主扩展, 函数重绑定到宿主包装, 链式返回映射回实例, 受保护成员与 `super$m` 仍经适配器视图, fork JVM 单测用自定义 WrapFactory). P7.4 文档要写明的限制按 Q16 / Q17 的拍板结果.
- P8 (上游 PR 拆分): "test262 解锁" 提交在干净的上游克隆上自己重生成 properties (同样 `RHINO_TEST_JAVA_VERSION=17` 与 `=all`), 不要拷贝 fork 的 properties: fork 的文件里有 `__proto__` 定制与本机 CRLF 的登记, 上游没有这些差异. 该提交只带 `UNSUPPORTED_FEATURES` 去掉三个 class 特性那部分; `decorators` / `nonextensible-applies-to-private` 是否改为 unsupported 由上游评审定 (不改则这 26 个用例以普通失败登记, class 两目录同样只剩 P9 / T10). fork-only 不进 PR: `autojs6` 包与 P5 的 23 行钩子, V2 开关 (`InterpreterV2Mode` 与 `TestMode.V2` / `getResult` 两处, 共 4 行), fork 自有的三个 test262 执行测试 (阶段管理用, 上游以 properties 为准).
- P9 (async 解锁时的重生成步骤): 1) `Test262SuiteTest.UNSUPPORTED_FEATURES` 去掉 `async-functions` / `async-iteration` (钩子带 `// AutoJs6: async`, 登记 HOOKS.md); 2) `Test262SuiteTest` 现在把 `flags: [async]` 的用例一律当作不支持的环境跳过 (`hasFlag("async")` 两处), class 两目录里有 2078 个这种用例, 要让它们运行必须给 `Test262SuiteTest` 加 async 用例的支持 (加载 `doneprintHandle.js`, 执行后排空微任务, 按 `Test262:AsyncTestComplete` / `Test262:AsyncTestFailure` 判定), 否则只能解锁没有 async 标志的那部分; 3) properties 里 async 的整目录排除去掉 `~` (`language/expressions/{async-function,async-generator,await}`, `language/statements/{async-function,async-generator,for-await-of}`, `built-ins/{AsyncFromSyncIteratorPrototype,AsyncFunction,AsyncGeneratorFunction,AsyncGeneratorPrototype,AsyncIteratorPrototype}`; `AsyncDisposableStack` / `await-using` / `using` 属 explicit-resource-management, T10, 保留); 4) 用 `-DupdateTest262properties=all` (JDK 17) 重生成, 与重生成前逐文件对比 (工具见下), 新增失败必须查明, async 范围内只允许 T10; 5) `ClassSyntaxTest262CoreTest.LATER_PHASES` 清空, 两个 `KNOWN_FAILURES` (19 + 1) 删除, 附录 E 的 P9 部分删除.
- P9 (NativePromise): P6.5 干跑看到上游 #2477 (16e143788, 还没合进 fork) 给 NativePromise 加了 Java 侧接口: `NativePromise.create(VarScope)`, `resolve` / `reject(cx, s, value)`, `onFulfill` / `onReject` / `onResolution(cx, Consumer)` (回调经 `cx.enqueueMicrotask`), `getState()` / `getResult()` / `isHandled()` 与 `enum State`, 并重写了 thenable 解析. async 驱动 (T7) 建 promise 与订阅 await 结果可以直接用这组接口, 不必自写 capability; P9 开工前先做一次真同步把它合进来 (审计归 B 类, 合并后跑全部自带测试). fork 的 NativePromise 钩子 (构造器取原型的 2 行) 不在它改动的行上, 干跑时自动合并.
- 下次真同步: P6.5 干跑 (HEAD 3d454cdd0 对上游 155aaaf47) 只有 `test262.properties` 冲突 (2 处 10 行), 按 AGENTS.md 第三节第 9 条取上游版本后重生成, 再用 `propdiff.py` 对照; 钩子行全部自动保留 (460).
- 工具 (不入库, 放在共享笔记目录 `rhino-p6-tools/`): `propdiff.py` (把两份 properties 按文件展开并对比: 解锁, 新增失败, 标签变化, class 两目录剩余登记), `classify.py` (按 features / flags 把登记的文件归到 P9 / T10 / 仍运行), `reconcile.py` (与 "marked as failing" 清单对照), `run262.py` (用 Rhino shell 单跑 test262 文件, 解释 / 编译 x 严格 / 非严格, 可用 `RUN262_JAVA` 换 JDK).

### 已知限制

见 `HOOKS.md` 第 8 节 "P6" (JDK 17 生成与 Unicode, CRLF 检出, 既有基线差异写进 properties, 实参展开与逻辑赋值 (Q16 / Q17), V2 0 例).

## P6.6 / P6.7 实参展开与逻辑赋值短路 (2026-10-26, 子会话 rhino-p6b)

附录 C Q16 = B / Q17 = B. 两项都与 class 无关, 是上游缺口的通用修复, 标记沿用 `// AutoJs6: class syntax` (同 P2 的 yield 修复), 钩子与新文件见 HOOKS.md 第 3 / 4 节的 P6.6 / P6.7 行.

### 实参展开 (P6.6)

- IR: `IRFactory.transformFunctionCall` / `transformNewExpr` 末尾的 `SpreadCall.lower` 在有展开实参时把全部实参 (含 JavaAdapter 写法的初始化对象) 移进一个数组字面量 (`NUMBER_OF_SPREAD` / `DESTRUCTURING_ARRAY_LENGTH` 照数组字面量的写法), 作为调用唯一的 `DOTDOTDOT` 实参. 数组字面量本来就从左到右求值, 按迭代器协议展开每个展开值, 遇到异常即停, 所以求值顺序与中途异常都不用另写; callee 与 this 仍是调用节点的第一个子节点, 在实参之前求值.
- 解释器: `CodeGenerator` 求值数组字面量 (`SpreadCall.argument`), `SpreadCall.emit` 发射 5 个 icode, 栈记账同一个实参. `SPREAD_CALL` / `SPREAD_CALL_ON_SUPER` 注册到 CALL 的指令类 (`DoCallByteCode`), 钩子在 `frame.stackTop -= indexReg` 之后把数组元素变成 bound 实参 (绑定函数已有的机制) 并把 op 改回 `CALL` / `CALL_ON_SUPER`: 解释函数仍走内联帧, 栈深, continuation, 调试回调都同普通调用 (`SpreadCallTest.interpretedCalleesStayInline`: 3000 层递归, continuation 捕获与恢复). 第一个实参写回数组所在的栈位置, continuation 恢复时在那里放结果. `SPREAD_NEW` (含 `new eval(...)`), `SPREAD_CALLSPECIAL` (可能的直接 eval, 行号在索引寄存器), `SPREAD_REF_CALL` 是自有指令类, 不内联.
- 编译器: `generateCallArgArray` 是普通调用, super 调用, 可选调用, new, eval, REF_CALL 共同的实参数组出口, 展开调用在这里生成数组字面量再调 `SpreadCall.args` (`NativeArray` -> `Object[]`, 空洞为 undefined). `OptTransformer.detectDirectCall` 跳过展开调用 (直接调用要静态的实参个数).
- 不经 `Function.prototype.apply` / `Reflect.apply` / `Reflect.construct` (Java 层直接调用); `super.m(...a)` 的 this 与 home object 同普通 super 调用, `new F(...a)` 的 new.target 是 F, `super(...a)` 仍由 P2 的 `ClassLowering.transformSuperCall` 处理.
- 计时 (附录 C Q19, 证据 `bench-spread.txt`): JDK 21, Rhino shell, `f(...a)` 对 `f.apply(null, a)`, a 为 8 个元素, 20000 次调用一轮, 31 轮交替取中位数, 两次运行. 解释模式 1531 / 1808 ns 对 617 / 678 ns (2.48 / 2.67 倍), 编译模式 960 / 1240 ns 对 64.5 / 102 ns (14.9 / 12.2 倍). 慢 2 倍以上的原因: 耗时几乎都在上游数组字面量 `[...a]` 的迭代协议 (每个元素一次 `next` 调用与一个结果对象, 再经 `NewLiteralStorage` 存进新数组), 编译模式 `[...a]` 单独 679 ns, `f.apply(null, [...a])` 800 ns, `f(...a)` 838 ns, 展开调用本身几乎不加开销. 规范要求数组展开可观察 (`SpreadCallTest.arraysAreIteratedObservably`), 快速路径要先确认 `Array.prototype[Symbol.iterator]` 与数组迭代器的 `next` 都未被覆写, 改动在上游的数组字面量路径, 登记 Q19 (A 保持现状, 已先行); 维护者 2026-10-26 08:25 改选 B, 由 P6.9 实施 (见 "P6.8 / P6.9").

### 逻辑赋值短路 (P6.7)

- `IRFactory.createAssignment` 开头交给 `LogicalAssignment.lower`, 把 `a op= b` 降为 `a op (a = b)` (op 为 `||` / `&&` / `??`): 读与写都是普通的 IR, 两个后端不改; 写入走 `Parser.simpleAssignment`, 所以 const (只在真正写入时报 TypeError), 严格模式, setter, 私有名的检查与匿名函数的名字推断都照旧.
- 属性引用的对象与非常量键只求值一次: 存进 LETEXPR 的临时绑定 (做法同 `Parser.destructuringAssignmentHelper`, 名字 `%` + `getNextTempName`, 标识符引用不到), NodeTransformer 把它变为局部变量 (无活动对象时) 或作用域对象 (脚本顶层, 含嵌套函数的函数). `this` / `super` / 字符串与数字键 / 私有名重新求值, 不用临时绑定. 非常量键时读之前对基对象做 RequireObjectCoercible (`ClassOp.REQUIRE_OBJECT`): GetValue 先 ToObject 再 ToPropertyKey, Rhino 的元素读取是反过来的 (`null[{toString}] ||= 1` 先报 TypeError, 不转换键). 派生构造器里的 `super[k]` 先读 this (未初始化报 ReferenceError) 再求值键, 加一个 THIS 的临时绑定.
- 调用作为目标 (`f() ||= 1`) 报 `msg.bad.assign.left` (SyntaxError): 逻辑赋值没有 `=` / 复合赋值的 web 兼容例外 (test262 `assignmenttargettype` 2 个文件因此解锁); 可选链目标由 Parser 照旧报错. 特殊属性 (`__proto__` 等的 `GET_REF`) 与 E4X 引用返回 null, 照旧走上游 "总是写回" 的路径 (HOOKS.md 第 8 节).
- 顺带修复 1 (附录 C Q18, 已按推荐先行): P6.7 之后 `logical-assignment` 目录仍有 3 个失败 (`lgcl-{and,or,nullish}-assignment-operator-no-set-put.js`, onlyStrict), 根因不在逻辑赋值: 上游 `AccessorSlot.FunctionSetter` 对显式 `set: undefined` 的访问器写入静默成功, 省略 `set` 时 setter 为 null 才走 `throwNoSetterException`. 钩子 5 行让 target 不是函数的 `FunctionSetter` 按没有 setter 处理 (严格模式 TypeError, 非严格忽略), `=` / 复合赋值同样受益 (`assignment/11.13.1-2-s.js`, `compound-assignment/11.13.2-34..37-s.js`).
- 顺带修复 2: 编译模式的 `super` 原来把当前作用域直接强转为 `NativeCall` 取 home object, 方法里 let 块 (函数有活动对象时 let 块是作用域对象) 或 with 语句中的 `super` 抛 Java `ClassCastException` (上游缺陷, 对象字面量方法同样; 解释器用 `frame.fnOrScript.getHomeObject()` 不受影响), P6.7 的临时绑定 (`super[k] ||= v`) 也会碰到. 改为 `ClassRuntime.homeObject` 沿作用域链找到函数的活动对象 (BodyCodegen 1 行钩子, 删上游 6 行).

### 上游 PR 拆分边界 (P8)

- 实参展开一个 PR: `SpreadCall` / `SpreadCallCodegen` 两个新文件与 IRFactory 2, Icode 6, CodeGenerator 5, Interpreter 5, BodyCodegen 1, OptTransformer 1 行钩子. fork 的 icode 注册与名字经 `ClassInterpreter` (上游没有), PR 里改为在 `Interpreter` 的指令表与 `Icode.bytecodeName` 直接登记; icode 编号接在上游链尾. 测试取 `SpreadCallTest` 中不用 class 的用例 (super 用例改用对象字面量方法), properties 在干净的上游克隆上重生成.
- 逻辑赋值短路一个 PR (可与上面合并, 由 P8 定): `LogicalAssignment` 依赖 fork 的 `ClassOp.REQUIRE_OBJECT` / `ClassOp.THIS` / `ClassLowering.newClassNode` 与私有名 (`ClassParser.isPrivateBinding`), 上游版本去掉私有名与派生构造器分支, RequireObjectCoercible 改用上游已有的运行时检查或一个新 icode; IRFactory 2 行钩子.
- `set: undefined` 与编译模式块内 `super` 各是几行的独立修复, 分别单独 PR (后者的 helper 放进 `ScriptRuntime`, 不依赖 `ClassRuntime`).

### 未结问题

- 附录 C Q18 (`set: undefined` 修复, 已先行) 维护者 2026-10-26 08:25 确认保持现状; Q19 (展开的快速路径) 改选 B, 由 P6.9 实施.
- 编译模式: 内联原型的对象字面量 `{ __proto__: { x: 3 }, m() { return super.x; } }` 里 `super.x` 读到 undefined (原型本身正确, 解释模式正常); 2026-10-20 的 `rhino-all` 旧 jar 同样, 与本阶段无关; 已由 P6.8 修复 (见 "P6.8 / P6.9").
- test262 里与本阶段目录相关但仍登记的: `expressions/call` 的 `tco-*.js` 6 个 (T10 特性 tail-call-optimization) 与 `eval-realm-indirect.js` (跨 realm 间接 eval), `expressions/super/prop-expr-getsuperbase-before-topropertykey-putvalue-increment.js` (解释 / 调试器模式, `super[k]++` 的顺序), 都早于 P6.6 登记, 与展开 / 逻辑赋值无关.
- 编译模式下函数里数字类型的寄存器变量作 callee 并直接返回 (`function t() { var x = 1; return x(2); }`) 报 `VerifyError` (展开与否都一样): 已由 P6.8 修复. 上游既有, 未改: 编译模式普通调用 1000 层递归即 `StackOverflowError`.

### 验证

- 提交: 191c7f2be (P6.6 功能), f2072a5b4 (P6.7 功能与两个顺带修复), e97db5209 (`SpreadCallTest` 15 例, `LogicalAssignmentTest` 12 例, 两模式), fb0c04fc6 (properties), 勾选提交. P6.6 提交单独编译并通过 `HooksRegistryTest` / `ClassChainUniquenessTest` / `SpreadCallTest` / `ClassSyntaxTest`.
- properties 重生成: 解锁 122 (实参展开 84, 短路 23, `set: undefined` 15), 新增失败 0, 标签变化 0 (`build/upstream-sync/class-p6b-20261025/unlocked-by-cause.txt`).
- 五模块全量 (JDK 21, `--rerun-tasks`): 149252 例 27 失败, 对照 P6 新增 0; 门禁全部通过. 上游 `LogicalOrAndAssignmentTest` 与 `ClassSyntaxTest262CoreTest` (含 "已知失败通过时报错") 通过.

## P6.8 / P6.9 编译模式的两个缺陷与数组展开的快速路径 (2026-10-26, 子会话 rhino-p6b)

P6.8 是 P6.6 / P6.7 时发现的两个上游缺陷 (维护者要求修复, 附录 C Q20), P6.9 是附录 C Q19 = B. 都与 class 无关, 是通用修复, 标记沿用 `// AutoJs6: class syntax`; 钩子与新文件见 HOOKS.md 第 3 / 4 节的 P6.8 / P6.9 行.

### P6.8 编译模式的两个上游缺陷

- 嵌套对象字面量与 home object: `BodyCodegen.visitObjectLiteral` 把新对象存进 `savedHomeObjectLocal`, `visitFunction` 的方法定义分支以它作 home object; 字段只有一个, 生成属性值时遇到嵌套的对象字面量 (内联 `__proto__: {...}`, 方法前面任何值为对象字面量的属性) 会被覆盖且不恢复, 其后的方法 / 访问器以内层对象为 home object, `super.x` 读的是内层对象的原型 (通常是 `Object.prototype`). 解释器从栈上取 home object, 不受影响. 修法: 生成属性前保存外层的值, 之后恢复 (2 行钩子). 上游另有一处小问题未改: 这个局部变量每个字面量申请一次, 从不释放.
- 数字值的 callee: `Optimizer.rewriteForNumberVariables` 的 CALL 只改写 callee 的子节点 (`rewriteAsObjectChildren(child, child.getFirstChild())`), callee 本身没经过数字类型改写, 而 `visitGetVar` 对数字变量总是 `DLOAD`, 于是 `y(2)` 以 double 进入 `getValueAndThis` (`VerifyError`); 赋值 / 自增作 callee (`(y = 2)(1)`, `(y++)(1)`) 则因 SETVAR / INC 没被标记而在生成器里断言失败或报 "Bad tree". 修法: callee 本身按对象位置改写 (结果为数字时包 `TO_OBJECT`, 同 `rewriteAsObjectChildren` 对其他子节点的做法), 4 行钩子并删上游 1 行; GETPROP / NAME 等 callee 的改写结果与原来相同, GETELEM 的数字下标沿用 `generateLookupResult` 已有的 `ISNUMBER_PROP` 处理.
- 测试 `CompiledModeFixesTest` 3 个用例两模式通过. 上游 PR: 各是几行的独立修复, 不依赖 fork 其他部分 (`ClassBodyCodegen.toObject` 在 PR 里内联进 `Optimizer`).

### P6.9 数组展开的快速路径

- 入口: 三个后端的数组字面量展开都经 `NewLiteralStorage.spreadArray`, 开头 1 行钩子先试 `ArraySpread.spread`, 协议不可观察时把元素直接复制进字面量的存储 (只扩容一次, 不经 `IteratorLikeIterable` 与结果对象), 否则照旧. 展开调用的实参就是这个数组字面量 (P6.6), 所以 `f(...a)` / `o.m(...a)` / `super.m(...a)` / `new F(...a)` / `super(...a)` 跟着变快. 编译模式只有一个展开实参的调用再省掉数组字面量: `SpreadCallCodegen` 只求值展开的值, `SpreadCall.spreadArgs` 用 `ArraySpread.elements` 得到实参数组, 不行再经 `NewLiteralStorage` 按协议展开 (与数组字面量同一路径, 错误与消息相同, `ArraySpreadTest.evaluationOrderAndErrors`). 解释模式不另开路径: 字面量的开销在 icode 分派上, 计时已是 apply 的 1.1-1.2 倍, 为此加 icode 或操作数不值得.
- 条件, 每条对应上游慢路径里一个可被脚本观察的步骤 (`spreadArray` -> `ScriptRuntime.callIterator` -> `IteratorLikeIterable`): 读 `Symbol.iterator` 并调用, 读 `next` 与 `return`, 每次 `next()` 新建结果对象并用 `putProperty` 写 `value` / `done` (会找到 `Object.prototype` 上的 setter), 读 `done` / `value`, 结束时调 `return`; 数组迭代器 `nextValue` 读自有元素 (访问器元素会调 getter). 因此要求: 来源恰为 `NativeArray`, denseOnly (没有访问器元素), 没有空洞, 没有自身的 `Symbol.iterator`; 原型恰为 `NativeArray`, 自身的 `Symbol.iterator` 是普通数据槽 (`StandardSlot`, 不是访问器 / lambda / 延迟加载槽), 值是内建 `values`; `values` 所在 realm 就是调用者的 realm (迭代器的原型取自函数的声明作用域, 结果对象的原型取自调用处的作用域, 要求两者一致); 该 realm 的 `%ArrayIteratorPrototype%` (顶层关联值 `"ArrayIterator"`) 自身的 `next` 是普通数据槽且为内建, 没有 `return`, 原型是缓存的 `Object.prototype`; `Object.prototype` 没有原型, 没有 `return` / `value` / `done`. 检查只读槽 (`getMap().query`), 不调 getter, 每次展开做一次: 运行中途改了任一项, 下一次展开就走协议, 同一调用的两个展开之间改也一样 (`ArraySpreadTest.changesBetweenSpreadsOfOneCallAreObserved`).
- 认内建函数: 上游的 `NativeArray.ITERATOR_DESCRIPTOR` 按 `Symbol.iterator` 找方法描述符, 而 `Symbol.iterator` 是 `values` 的别名 (`withProp(..., alias("values"))`), 不在方法表里, 所以它是 null; `NativeTypedArrayView` 第 518 行拿它比较的快速路径因此从不生效 (上游既有, 未改). fork 在 NativeArray 加 `VALUES_DESCRIPTOR` (`findProtoDesc("values")`), NativeArrayIterator 加 `NEXT_DESCRIPTOR` (`findCtorDesc("next")`), 各 2 行. 把同一个内建赋回去 (`Array.prototype[Symbol.iterator] = Array.prototype.values`) 不改槽的类型与值, 仍走快速路径 (不可观察).
- 空洞的取舍: 上游迭代器只读自有元素, 空洞得 undefined (规范要求经原型链读, 附录 C Q21); 快速路径本可照抄, 但按维护者的要求空洞走协议, 这样上游日后修正这一点时快速路径不会跟着出错. Array 子类的实例 (`A.of(...)`, `new A(...)`) 在 fork 里建出的不是 denseOnly, 改过原型的数组原型不是数组, 都走协议, 速度同 P6.6.
- 计时 (证据 `build/upstream-sync/class-p6c-20261026/bench-spread3.txt`): 同一构建用临时系统属性关掉快速路径作 "改动前" (提交前已删), 每个形式单独一个进程 (同进程交替时三个形式共享 JIT 状态, 改动后其中一个会随顺序落到约 2 倍, 见证据), 两次运行的中位数, ns/次, a 为 8 个元素:

| 形式 | 解释 改动前 | 解释 改动后 | 编译 改动前 | 编译 改动后 |
|---|---|---|---|---|
| `f(...a)` | 1114 / 1103 | 661 / 588 | 728 / 642 | 53.8 / 53.8 |
| `f.apply(null, a)` | 548 / 528 | 542 / 538 | 53.6 / 58.2 | 58.3 / 53.9 |
| `f(...a)` / apply | 2.03 / 2.09 | 1.22 / 1.09 | 13.6 / 11.0 | 0.92 / 1.00 |
| `[...a]` | 868 / 804 | 260 / 262 | 662 / 671 | 61.9 / 61.8 |
| `o.m(...a)` (对 `o.m.apply`) | 1120 / 1174 (582 / 579) | 614 / 608 (600 / 605) | 628 / 658 (68.7 / 76.4) | 58.0 / 57.3 (78.6 / 67.1) |
| `new F(...a)` (对直接 `new F(a[0], ...)`) | 1323 / 1259 (773 / 854) | 744 / 737 (766 / 784) | 749 / 764 (111 / 122) | 96.6 / 113 (107 / 109) |
| `super.m(...a)` (对 `super.m.apply`) | 1390 / 1478 (644 / 647) | 704 / 719 (645 / 655) | 742 / 769 (93.8 / 84.5) | 101 / 90.7 (105 / 94.4) |
| `f(0, ...a, ...a)` | 1912 / 1860 | 658 / 761 | 1324 / 1228 | 233 / 237 |

  目标 "`f(...a)` 不慢于 `f.apply` 的 1.5 倍" 两种模式都达到 (解释 1.09-1.22, 编译 0.92-1.00). 编译模式多个实参或多个展开时仍建数组字面量 (`f(0, ...a, ...a)` 233 ns), 与 `[...a]` 同速.
- 上游与规范的三处偏差 (附录 C Q21, 实测两模式都在): `Array.prototype[1] = 'p'; [...[1, , 3]]` 得 `1,,3` (规范 `1,p,3`); `%ArrayIteratorPrototype%.return` 存在时正常展开完也调用它 1 次 (`IteratorLikeIterable.close`, 规范不调); `Object.prototype.value` 有 setter 时每个结果对象都调用它 (2 个元素 3 次, 规范用 CreateDataProperty 不调). 快速路径只在它们都不可观察时生效, 所以不改变它们; `ArraySpreadTest.returnAndResultPropertiesAsTheProtocolHasThem` 只要求快慢两路一致, 上游修正后测试照样成立.
- 测试 `ArraySpreadTest` (rhino 模块, 包 `org.mozilla.javascript`, 为了直接调包内的 `ArraySpread.elements`) 13 个用例; JS 用例两模式, 每个场景拿可能走快速路径的数组与 `twin(a)` (同样的元素, 自身 `Symbol.iterator` 设为内建 `values`, 协议行为相同但必走慢路径) 比较七种展开形式的结果与日志.

### P6.10 迭代协议的三处偏差与 Q22 (2026-10-26, 附录 C Q21 = B, 维护者 11:19 拍板)

- 数组迭代器读元素 (Q21 (1)): `NativeArrayIterator.nextValue` 改用 `ScriptableObject.getProperty(arrayLike, index)` (规范 CreateArrayIterator 的 Get), 空洞找到原型链上的索引属性, 访问器以数组为 this. 所有经数组迭代器的地方都受益: 展开, 展开调用, for-of, `Array.from`, `values()` / `entries()`, 参数解构, `yield*`; TypedArray 与字符串在长度内的元素都是自有的, 结果不变. 快速路径的 "没有空洞" 条件因此是必要的 (空洞要读原型).
- 正常结束不关闭 (Q21 (2)) 与 Q22: `IteratorLikeIterable` 原来在构造时就查找 `return` (getter 被调用, 不可调用时立刻 TypeError), `close` 只要有 `return` 就调用, 于是正常结束, 以及 `next()` / 结果的 `done` / `value` 自身抛错 (规范 IteratorStep 的 abrupt, 不关闭) 也都调用. 现在字段 `exhausted` 在 `hasNext` 向迭代器要结果前置位, 读到值后清零: 结果 done 或迭代器自身抛错时保持置位, `close` 不关闭; 使用者在两次 `hasNext` 之间停下 (break, 提前返回, 使用者的异常) 时未置位, `close` 经新文件 `IteratorClose` 查找并调用 `return`. Q22 (查找时机与迭代器自身抛错) 是探测时发现的同一处的另两处偏差, 按推荐先行, 单独提交便于撤回. 不变的: `return` 的结果不检查是否为对象 (同上游); 使用者出错时 `close` 的异常由 try-with-resources 记为 suppressed, 使用者的异常胜出 (规范 IteratorClose 对 throw completion 的处理).
- 结果对象 (Q21 (3)): `ES6Iterator.makeIteratorResult` 用新对象自身的 `put` 写 `value` / `done` (CreateDataProperty), 不再经 `putProperty` 找到 `Object.prototype` 上的 setter 或只读属性. 全部 ES6 迭代器 (数组, 字符串, Map / Set, RegExp String) 与生成器 (`ES6Generator` 在结果上再写值时已是自有属性) 都经这里.
- 逐处核对的调用者 (两模式实测, `IteratorProtocolFixesTest`): `NewLiteralStorage.spreadArray` (展开与展开调用), `NativeArray.js_from`, `BuiltinConstruction.addEntriesFromIterable` / `addValuesFromIterable` (Map / Set / WeakMap / WeakSet 装入, fork P4), `ScriptRuntime.loadFromIterable` (`Object.fromEntries`), `NativeError` (AggregateError), `NativeTypedArrayView` (构造与 `from`), `AbstractEcmaObjectOperations` (`Object.groupBy` / `Map.groupBy`), `NativeSet` 的 6 个集合方法: 都是 try-with-resources, 正常结束不再关闭, 提前停下 (Map 条目不是对象, `Array.from` 的映射函数抛错, `isDisjointFrom` 提前返回) 照旧关闭. `NativePromise` 的 all / allSettled / any / race 原来就按 `Itr.isDone` / `setDone` 只在未完成时关闭, 不变. for-of 与参数解构不经 `IteratorLikeIterable` (fork P2 的 `ClassRuntime.iteratorClose`, 正常结束不关闭, 提前停下关闭并检查结果), 不变. 变量 / 赋值的数组解构在上游按下标读, 根本不走迭代器协议 (登记 Q25).
- 快速路径: 条件不变 (`return` / `value` / `done` 的检查对展开已不可观察, 作为保守条件保留, `ArraySpread` 类注释); `ArraySpreadTest.returnAndResultPropertiesAsTheProtocolHasThem` 改为要求快慢两路都等于普通元素 (原来要求慢路径有日志).
- 新发现未修 (登记待决): Set 的集合方法对 `keys()` 的结果再调 `Symbol.iterator` (规范把它直接当迭代器, 只有 `next` 的迭代器对象报 TypeError, Q23); `Object.fromEntries` 读条目的 `0` / `1` 只看自有属性 (`ScriptRuntime.loadFromIterable` 的 `sVal.get(0, sVal)`, Map 装入走 fork P4 的 `getProperty`, 是对的, Q24); 变量 / let / const / 赋值 / catch 参数 / for 头部的数组解构不走迭代器协议 (`Parser.destructuringArray` 只给函数参数生成迭代器, `var [a, b] = new Set([1, 2])` 得 undefined, test262 登记的数组解构失败约 180 个, Q25).
- 结果: 计时同 P6.9 的方法 (每个形式单独进程, 30 次预热, 31 轮中位数, 两次), `f(...a)` 对 `f.apply(null, a)` 解释 1.07 / 1.10 倍, 编译 1.01 / 0.91 倍, `[...a]` 解释 246 / 247 ns (P6.9 后 260 / 262), 编译 61 / 62 ns (P6.9 后 62 / 62), 快速路径未变, 慢路径的 `getProperty` 对自有元素与 `get` 同价. test262 全部目录三模式各跑一次 (只有三处修正 / 加 Q22): 137526 例失败 0, 意外通过只有 JDK 21 的 39 个 Unicode 用例 (同 P6.9), properties 不重生成; test262 现有用例没有覆盖这几处 (Q22 的两处同样), 所以既无新通过也无新失败.

### P6.11 Set 集合方法与 `Object.fromEntries` (2026-10-26, 附录 C Q23 / Q24 = A, 维护者 12:19 前拍板)

- keys 迭代器 (Q23): `NativeSet` 的 `intersection` / `union` / `difference` / `symmetricDifference` / `isSupersetOf` / `isDisjointFrom` 遍历 `other.keys()` 的 6 处原来对结果再调 `ScriptRuntime.callIterator` (读并调用它的 `Symbol.iterator`). 规范的集合方法用 GetIteratorFromMethod: `keys()` 的结果本身就是迭代器 (不是对象报 TypeError), 再读它的 `next`. 新文件 `SetLikeKeys.iterator` 只做对象检查 (Symbol 也算非对象), 参数同 `callIterator`, 每处只换一个方法名. 遍历仍经 `IteratorLikeIterable` (try-with-resources), 所以提前得出结果 (`isSupersetOf` 遇到不在 this 里的值, `isDisjointFrom` 遇到在 this 里的值) 关闭迭代器, 正常结束不关闭 (P6.10).
- 条目 (Q24): `ScriptRuntime.loadFromIterable` (在 `rhino` 里只被 `Object.fromEntries` 用) 读条目的 `0` / `1` 由 `sVal.get(i, sVal)` 改为 `ScriptableObject.getProperty(sVal, i)`: 空洞找到原型的索引属性, 原型上的 getter 以条目为 this. 先读键再读值, 条目不是对象 / 读取出错时照旧关闭迭代器.
- `built-ins/Set` 的 18 个登记失败逐个归因 (`run262.py` 两模式, 修前修后各跑一次): Q23 解锁 4 个 (`difference/set-like-class-mutation.js`, `intersection/set-like-class-mutation.js`, `isDisjointFrom/set-like-iter-return.js`, `isSupersetOf/set-like-iter-return.js`; 后两个同时依赖 P6.10 的正常结束不关闭). 剩下 14 个与 Q23 无关, 都是 `NativeSet` 的其余偏差 (登记 Q26): GetSetRecord 先读 `size` / `has` / `keys` 三项再 ToNumber (规范先 ToNumber 与范围检查, 再读查 `has`, 再读查 `keys`; `difference` / `intersection` / `isDisjointFrom` / `isSubsetOf` / `isSupersetOf` / `union` 的 `set-like-class-order.js` 6 个); `union` 在 GetSetRecord 之前复制 this (`union/set-like-class-mutation.js`); `isSupersetOf` 在 this 较小时仍遍历 keys (`isSupersetOf/set-like-array.js`); `symmetricDifference` 调用参数的 `has` (该目录 6 个).
- 新发现未修 (登记 Q27): `Reflect.get` / `Reflect.has` 的数组下标字符串键不按下标查找 (`NativeReflect.get` 对非 Number 的键用 `getSuperProperty(target, receiver, String)`, 下标属性存在数组的稠密存储或按 int 键的槽里, 名字查不到), Proxy 的 get trap 用 `Reflect.get(t, k, r)` 转发时下标全部是 undefined; `Reflect.get` 读不到时返回 `Undefined.SCRIPTABLE_UNDEFINED`, `typeof` 得 "object". `SetMethodsAndFromEntriesTest` 的 Proxy 用例因此在 trap 里用 `t[k]`.
- 结果: properties 用 JDK 17 重生成 (全部目录三模式), 解锁 4 个 (都是上面 Q23 的 4 个), 新增失败 0, 标签变化 0; `built-ins/Object/fromEntries` 原本就没有登记失败, 修后 25 个文件两模式仍通过 (test262 没有经原型链读条目的用例, Q24 由 `SetMethodsAndFromEntriesTest` 覆盖).

### P6.12 参数之外的数组解构走迭代器协议 (2026-10-26, 附录 C Q25 = A, 维护者 12:19 前拍板)

- 原状: 只有函数参数的数组解构 (ES6 起) 走迭代器协议; var / let / const / 赋值 / catch 参数 (`IRFactory.transformTry` 把它改写成 catch 体开头的 let 语句) / for 头部都由 `Parser.destructuringArray` 降为 `tmp[i]` 与 `tmp.slice(i)`, Set / Map / 生成器等非数组的可迭代对象得 undefined, 不可迭代的值也不报错.
- 记录与步骤: 每个参数之外的数组模式在 `BindingLowering.open` 里得到一个 LOCAL_BLOCK, 它的局部变量存迭代器记录 (`ArrayDestructuring.IteratorRecord`); 模式的各步是 `ClassOp.DESTRUCTURE` 节点 (一个 icode `CLASS_DESTRUCTURE` 加步骤字节, 编译模式调 `ArrayDestructuring.destructure`): OPEN (GetIterator, 只读一次 `next`), STEP (IteratorStepValue, 完成后不再调 `next`; `next` / `done` / `value` 出错时记录已标记完成, 不关闭), SKIP (空位, 不读 `value`), REST, VALUE (带默认值的元素读两次值, STEP 单独成一个表达式, 两处读 VALUE), FINISH (未完成时 IteratorClose, `return` 的结果须是对象). 嵌套模式各有记录, 由内向外.
- 中途出错的关闭: 步骤是表达式, try 只能放在语句上, 所以 IRFactory.transform 开头的钩子把可能含模式的语句 (表达式语句, 声明, return, throw, if, switch, with, while / do / for / for-in / for-of; for 头部的声明归循环) 作为 "所在语句" 变换, 模式登记到同一函数里最内层的所在语句; 变换后每个记录包一层 `try { 语句 } catch { CLOSE_QUIET; rethrow }`, 内层模式在内. CLOSE_QUIET 忽略 `return` 的错误 (原异常优先); 生成器里语句含 yield 时再加 `finally { CLOSE_RETURN }` (生成器在模式中的 yield 处被 `return()`). 记录完成后这些步骤什么也不做. for-in / for-of 头部每次迭代的解构是自己的所在语句 (`createForIn` 的钩子), 先于 fork P2 的循环关闭. 不在其函数的语句里的模式 (参数默认值里的解构赋值, 1.7 的推导) 仍按下标读.
- 不建 catch 作用域: 第一版用 `createTryCatchFinally` 的 catch, 它要求 activation (catch 作用域是 with 作用域), 整个函数的变量都进了作用域对象: 编译模式 `var [x, y] = a` 的循环从 11 ns 变成 146 ns. 改为 `BindingLowering.closing` 直接建 TRY 节点 (处理器只关闭并 RETHROW, 不绑定异常), 变量照旧在寄存器 / 栈里. fork P2 的 for-of 关闭原来同样用 `createTryCatchFinally`, 含 for-of 的函数整个进入 activation (同函数里无关的循环解释模式 2.2 倍, 编译模式 12 倍), 一并改用 `closing` (附录 C Q31, 已按推荐先行), `createTryCatchFinally` 恢复为上游的 private.
- 普通数组: `ArraySpread.iterationRealm` (从 P6.9 的条件里拆出: 无自身 `Symbol.iterator`, 原型是数组且其 `Symbol.iterator` 是内建 `values`, 该 realm 的内建 `next`) 成立时不建迭代器, 按数组迭代器的方式读 (每步对照当时的长度, `ScriptableObject.getProperty` 读元素, 空位也读, 同 P6.10 的 `nextValue`); FINISH 时 `%ArrayIteratorPrototype%` / `Object.prototype` 上有 `return` (`mayHaveReturn`) 才在已读到的位置建出数组迭代器 (`NativeArrayIterator.setIndex`) 再关闭. 不要求密集或无空洞 (与展开不同, 解构不复制).
- 守卫: 每次解构都查一遍 (约 5 次槽表查找) 时编译模式仍是 44 ns. `ArrayIterationGuard` 在某 realm 首次确认成立后, 把 `Array.prototype` / `%ArrayIteratorPrototype%` / `Object.prototype` 的槽表换成 `Watched` 子类 (槽的副本, 旧表的读者不受影响), 新建 / 重定义 / 删除 `Symbol.iterator` / `next` / `return` 或表扩容 (`promoteMap`) 时作废; 已有属性的普通写入原地改槽的值, 由 `holds` 比较两个槽的值, 另比较两个槽表与两个原型. 作废后不恢复, 该 realm 回到每次查找. 线程安全的槽表不换 (只认 `EmbeddedSlotMap` / `ImmutableSmallSlotMap`).
- 计时 (`bench-dstr-p612.txt`, `bench-dstr.js` 十种形式, 两次取中位数的中位, 修前是 P6.11 的下标读): 解释模式 varDecl 228 -> 287 ns (1.26 倍), let 1.18, const 1.25, 赋值 1.16, 交换 1.27, 四元素 1.16, rest 0.85, 默认值 1.38, for-of 头部 1.06; 编译模式 varDecl 11.2 -> 23.6 ns (2.1 倍), let 1.8, const 1.3, 赋值 1.8, 交换 1.5, 四元素 2.1, rest 0.65, 默认值 1.7, for-of 头部 1.06. 1.1 倍的要求只在 for-of 头部与 rest 达到: 修前的下标读不调用任何运行时方法 (编译模式两次 GETELEM 约 2 ns), 修后每个模式至少 OPEN + 每元素一步 + FINISH 的调用与一个记录对象; 解释模式多出的是 4 次指令分派 (加下标寄存器前缀) 与记录. 再往下需要编译模式内联快速路径 (按守卫分支到下标读) 或简单模式的合并指令, 登记附录 C Q28.
- 兼容性: 不可迭代的类数组对象 (`{0: 1, length: 1}`, 没有 `Symbol.iterator` 的宿主对象) 原来能按下标解构, 现在报 TypeError (规范行为, Chrome 同样); Java 数组与 `java.util.List` 可迭代, 不受影响. 宿主 API 返回的集合是否都可迭代留给 P7 核对, 登记附录 C Q29. 消息同展开与参数解构 ("Cannot find function Symbol(Symbol.iterator).").
- 新发现未修: 默认值在目标已有值时不生效 (`processDestructuringDefaults` 生成 `x = (x === undefined) ? (v === undefined ? d : v) : x`, 赋值模式与循环里重复执行的 var 声明可见; 数组与对象模式都有, 上游既有), 登记附录 C Q30; 赋值模式的成员表达式目标先取值后求引用 (上游顺序, P6.12 之后关闭时机可观察, 登记附录 C Q32).
- V2: 没有 IR 到 V2 的编译器 (附录 C Q11), 同 P6.6 不加 V2 指令类.

### 上游 PR 拆分边界 (P8)

- P6.8 的两个修复各是几行的独立 PR (同上 "P6.8"): 不依赖 fork 其他部分, `ClassBodyCodegen.toObject` 在 PR 里内联进 `Optimizer`, `savedHomeObjectLocal` 的保存 / 恢复原样.
- P6.9 一个 PR 候选, 快速路径是否值得上游由评审定 (附录 C 与 P8 附记): `ArraySpread` 加 `NewLiteralStorage.spreadArray` 的 1 行调用即可单独成立 (只加速 `[...a]`, 上游没有展开调用); PR 里把 `NativeArray.ITERATOR_DESCRIPTOR` 改为取 `values` 的描述符 (顺带修好 `NativeTypedArrayView` 的比较), 不加 `VALUES_DESCRIPTOR`, `NEXT_DESCRIPTOR` 照加. 编译模式单个展开实参的路径 (`SpreadCall.singleSpread` / `spreadArgs`, `SpreadCallCodegen` 的分支) 依赖 P6.6 的实参展开, 随 P6.6 的 PR 或排在它之后.
- P6.10 一个 PR 候选 (规范修正, 可与 P6.9 合为一个 PR 由评审定): 新文件 `IteratorClose` 加 7 行钩子 (NativeArrayIterator 1, ES6Iterator 2, IteratorLikeIterable 4), 不依赖 fork 其他部分; Q22 是单独一个提交, PR 里可与 Q21 的三处合在一起. 测试 `IteratorProtocolFixesTest` 里 Map / Set 装入的用例在上游走 `NativeMap` / `NativeSet` 自己的 `loadFromIterable` (fork P4 换成了 `BuiltinConstruction`), PR 里在上游版本上重跑核对; 参数解构与 for-of 的用例依赖 fork P2 的迭代器关闭, PR 里去掉或改为只核对上游已有的部分.
- P6.11 一个 PR 候选: 新文件 `SetLikeKeys` 加 8 行钩子 (NativeSet 6, ScriptRuntime 2), 不依赖 fork 其他部分; 测试里正常结束不关闭的断言依赖 P6.10, 随 P6.10 的 PR 或排在它之后.
- P6.12 一个 PR 候选 (规范修正, 见 ROADMAP P8 附记): 依赖 P6.10 (数组迭代器按 Get 读元素); 上游没有 `Token.CLASS` / `ClassOp`, PR 里改为自己的 IR 节点与 icode, `BindingLowering` 的所在语句与 `closing` 搬进 IRFactory 或一个新文件; 测试 `ArrayDestructuringTest` 里 for-of 头部关闭顺序的用例依赖 fork P2 的 for-of 关闭, PR 里去掉或随它的 PR. `ArrayIterationGuard` 换内建原型的槽表, 评审可能不接受, 可以拆成第二个提交 (不带它时只是慢, 语义相同).
- 要替换的 fork 依赖: 没有 `ClassRuntime` / `ClassOp` 依赖; `ArraySpread` 用到的包内成员 (`SlotMapOwner.getMap()`, `Slot.value`, `NativeArray.getDenseOnly()`, `TopLevel.getBuiltinPrototype(Builtins)` 的缓存) 都是上游已有, 同包可用; 测试 `ArraySpreadTest` 去掉 `super.m(...)` / `class` 形式 (上游没有 class 与展开调用) 后可用于 `[...a]`, 展开调用的部分随 P6.6 的 PR.

### 未结问题 (P6.8-P6.12)

- 附录 C Q21 (上游展开的三处规范偏差): 维护者选 B, 已由 P6.10 修正 (见上); Q22-Q25 已决 A (Q22 随 P6.10, Q23 / Q24 为 P6.11, Q25 为 P6.12); P6.11 新登记 Q26 (Set 集合方法的其余偏差) / Q27 (Reflect 的下标字符串键与 undefined); P6.12 新登记 Q28 (计时门槛只部分达到, 推荐接受现状), Q29 (不可迭代的类数组对象现在报 TypeError, P7 核对宿主集合), Q30 (默认值在目标已有值时不生效, 上游缺陷, 推荐 fork 修), Q31 (for-of 关闭不再要求 activation, 已按推荐先行), Q32 (数组赋值模式的成员目标先取值后求引用, 推荐 fork 修); 都不阻塞 P7.
- 未改的上游既有问题: `NativeTypedArrayView` 拿 null 的 `ITERATOR_DESCRIPTOR` 比较 (TypedArray 由数组构造的快速路径从不生效); fork 的 Array 子类实例不是 denseOnly (只影响速度).

### 验证 (P6.8-P6.12)

- P6.8: 提交 bd10a3967 (功能与 HOOKS.md), a55fc4569 (`CompiledModeFixesTest`), 0fe03a84a (勾选); 五模块全量 149255 例 27 失败, 对照 P6.6 / P6.7 新增 0; test262 无状态变化, properties 不重生成.
- P6.9: 提交 25a1ee39c (功能与 HOOKS.md), 1720a82a2 (`ArraySpreadTest` 13 例), bee17163c (勾选); test262 限定集 (`expressions/call` / `new` / `array` / `super` / `object` / `class`, `statements/class` / `for-of`, `arguments-object`, `built-ins/Array` / `ArrayIteratorPrototype` / `Function`, 三模式 44180 例) 失败 0, 没有 "marked as failing but it does not", 行为不变, properties 不重生成; 五模块全量 (JDK 21, `--rerun-tasks`, 18 分 24 秒, 同时跑 `spotlessCheck`): 149268 例 27 失败 (多出的 13 例是 `ArraySpreadTest`), 失败清单与 P6.8 的 `current-failures-p68.txt` 逐条相同 (只差对象哈希), 新增 0. 门禁 `spotlessCheck`, `git diff --check`, `generate_markdown.py --check` 通过. 证据 `build/upstream-sync/class-p6c-20261026/` (计时脚本与结果, 限定集 properties 与日志, 全量日志与失败清单).
- P6.10: 提交 b61131887 (三处修正与 HOOKS.md, `ArraySpreadTest` 一处断言), c01b234a5 (Q22, `IteratorClose`), 1f8f99066 (`IteratorProtocolFixesTest` 6 例), 勾选提交; 中间提交 b61131887 上跑过 classsyntax 全部与 `ArraySpreadTest` (只有尚未提交的 `IteratorProtocolFixesTest` 里依赖 Q22 的 3 例失败); test262 全部目录三模式 137526 例失败 0, 没有新通过, properties 不重生成; 五模块全量: (JDK 21, `--rerun-tasks`, 18 分 45 秒, 同时跑 `spotlessCheck`) 149274 例 27 失败 (多出的 6 例是 `IteratorProtocolFixesTest`), 失败清单与 P6.9 的 `current-failures.txt` 逐条相同 (只差对象哈希), 新增 0. 门禁 `spotlessCheck`, `git diff --check`, `generate_markdown.py --check` 通过. 证据 `t262-p610.log` / `t262-q22.log` 与两份意外通过清单, `bench-spread3.txt` 末段, `full-tests-p610.log`, `current-failures-p610.txt`.
- P6.11: 提交 4fa5ced86 (功能与 HOOKS.md), 0a67bfc4b (`SetMethodsAndFromEntriesTest` 6 例), 01659e89e (properties: 解锁 4, 新增失败 0), 勾选提交; 五模块全量 (17 分 47 秒) 149280 例 27 失败, 与 P6.10 逐条相同, 新增 0. 门禁通过. 证据 `regen-p611.log`, `props-before-p611.properties`, `full-tests-p611.log`, `current-failures-p611.txt`.
- P6.12: 提交 24e56725a (功能与 HOOKS.md), e94fedb6b (`ArrayDestructuringTest` 14 例), 71326b360 (properties: 解锁 258, 新增失败 0, 标签变化 0), 勾选提交; 五模块全量 (19 分 55 秒) 149294 例 27 失败, 与 P6.11 逐条相同, 新增 0. 门禁通过. 证据 `bench-dstr-p612.txt`, `regen-p612.log`, `props-before-p612.properties`, `pd612/`, `full-tests-p612.log`, `current-failures-p612.txt`.

## P6.13 Set 集合方法, Reflect 的键, 解构默认值, 数组赋值模式的成员目标 (2026-10-26, 附录 C Q26 / Q27 / Q30 / Q32 = A, 维护者 15:3x 拍板)

- Q28 的结论 (维护者选 A, 接受现状): P6.12 之后参数之外的数组解构在解释模式每个模式多 40-90 ns (宿主用解释模式), 编译模式 1.3-2.1 倍; 换来规范行为 (迭代器协议, 提前退出与出错时关闭) 与 test262 的 258 个解锁. 不做简单模式的合并指令, 也不做编译模式的内联快速路径.
- Q29 (维护者选 A): 保持规范行为, 不可迭代的类数组对象解构报 TypeError; 宿主 API 返回的集合是否可迭代由 P7 核对. Q31 (维护者选 A): 保持现状.
- Set (Q26): 新文件 `SetRecord` 按规范读 GetSetRecord: `size` 读后立即 ToNumber (NaN 报 TypeError), ToIntegerOrInfinity, 负数报 RangeError, 再读并检查 `has`, 再读并检查 `keys`; 7 个方法读三项的 3 行换成它, 上游其后的转换与检查保留 (总能通过, 少改上游行). `union` / `symmetricDifference` 的复制挪到 `IteratorLikeIterable` 建好之后 (GetIteratorFromMethod 已调 `keys()` 并读 `next`, 所以 `keys()` 里对 this 的修改可见, `next()` 里的不可见); `symmetricDifference` 按规范只用 keys: this 里有的值从结果删除 (-0 先换成 +0), 都没有的才加入; `isSupersetOf` 在 this 的元素数小于 `size` 时直接 false. `run262.py` 两模式跑 `built-ins/Set` 全部 383 个文件: 只有需要 `$262.createRealm` 的 `proto-from-ctor-realm.js` 在 shell 里跑不了 (套件里通过), 其余全过, 原登记的 14 个都通过.
- Reflect (Q27): 新文件 `ReflectKey`: `of` 是 ToPropertyKey (对象按字符串提示 ToPrimitive 一次, 可得 Symbol; 缺省为 "undefined"), `get` / `has` / `delete` 经 `toStringIdOrIndex` 分派到下标或名字 (上游只按名字查: `Reflect.get(['d'], '0')` 是 undefined, `Reflect.deleteProperty(o, '0')` 返回 true 却不删), `get` 与 `getOwnPropertyDescriptor` 读不到返回 `Undefined.instance` (上游的 `SCRIPTABLE_UNDEFINED` 的 `typeof` 是 "object"). 核对时另发现两处一并改: `defineProperty` 先 ToPropertyDescriptor 再转换键, 且键的异常被 `catch (EcmaError)` 变成 false (test262 `defineProperty/return-abrupt-from-property-key.js`), 改为 checkTarget 与键的转换挪到缺参检查之前 (只传两个实参时规范先转换键, 再在 ToPropertyDescriptor(undefined) 报 TypeError), 且放在 try 之外; `deleteProperty` 经上游 `ScriptableObject.deleteProperty` 删原型链上持有属性的对象 (`Reflect.deleteProperty(Object.create(p), 'a')` 删掉 `p.a`), 也只在属性存在时才调 Proxy 的 deleteProperty trap (trap 抛错传不出来, test262 `deleteProperty/return-abrupt-from-result.js`), 改为总调用目标自身的 [[Delete]] 再看它是否还有该属性. 第一次全量重生成发现 TypedArray 的 `internals/HasProperty` 4 个文件新增失败: TypedArray 是整数索引的奇异对象, 下标键的 [[HasProperty]] 只看下标是否有效, 不查原型链; 按下标分派后 `ScriptableObject.hasProperty(obj, int)` 沿原型链找到了 `TypedArray.prototype[42]` (上游按名字查, 碰巧是 false), 改为 TypedArray 目标的下标键只问它自己 (提交 51d754ac1), 第二次重生成核对. `set` 原来就按 `toStringIdOrIndex` 分派, 未改.
- 新发现未修 (登记 Q33): Proxy 的 set trap 照例以 receiver 转发 (`set(t, k, v, r) { return Reflect.set(t, k, v, r); }`) 时栈溢出, 普通对象与数组的 Proxy 都是; `Reflect.set` 在 receiver 不是 target 时走 `AbstractEcmaObjectOperations.ordinarySet`, 它对 receiver (Proxy) 调 [[Set]] 而不是规范的 [[DefineOwnProperty]], 又回到 set trap. 宿主现在的 jar (2026-10-24) 同样, 与 P6.13 无关. `ReflectKeyTest` 的 Proxy 用例因此只用 get / has trap.
- 解构默认值 (Q30): 外层 `x === undefined ? ... : x` 来自上游默认参数的提交 9ca146955 (设计注释写的是参数: `if (x == undefined) if ($1[0] == undefined) var x = 1; ...`); 参数绑定在每次调用时本来就是 undefined, 判断对参数没有作用, 所以全部路径都去掉, 不分参数与非参数 (嵌套模式的默认值本来就没有外层判断). `DefaultParametersTest` 与 test262 的函数参数 dstr 目录核对不变 (见验证).
- 数组赋值模式的成员目标 (Q32): `BindingLowering.member` (Parser 6 行钩子) 在赋值模式有记录时接管成员表达式目标 (`[o.x]`, `[o[k]]`, `[...o[k]]`, `[o.x = d]`): 变换目标得到 GETPROP / GETELEM, 对象与非常量键存进 let 临时绑定 (沿用 P6.7 的 `LogicalAssignment.Temps`, 改为包内可见), 绑定的初始化先于 STEP / REST, 然后写入; 带默认值时 STEP 单独成一项, 值是 `VALUE === undefined ? d : VALUE`. 求引用时抛错或在键里的 yield 处 `return()` 由 P6.12 的所在语句关闭迭代器. 上游对带默认值的成员目标 (`[o.x = 1] = it`) 直接报 SyntaxError (`processDestructuringDefaults` 只接受名字与嵌套模式), 现在可用. 私有名键是常量 (不建临时绑定), `super.x` / `super[k]` 的 `super` 不存临时绑定, 写入时求值 (键照常先求). 声明 (`var [o.x]`) 照旧报错, 参数与不在语句里的模式不经这里.
- test262: properties 用 JDK 17 重生成 (全部目录三模式, 15 分 16 秒), 失败登记 20029 -> 19968 个文件 (P6 的 `propdiff.py` 计数, 不含整目录排除), 解锁 61, 新增失败 0, 标签变化 0: Q26 14 (`built-ins/Set` 原登记的 14 个全部解锁, 该目录不再有登记), Q27 5 (`Reflect/defineProperty/return-abrupt-from-property-key.js`, `Reflect/deleteProperty/return-abrupt-from-result.js`, `Proxy/deleteProperty/targetdesc-is-configurable-target-is-not-extensible.js`, TypedArray `internals/HasProperty/abrupt-from-ordinary-has-parent-hasproperty.js` 两个), Q30 5 (`array-elem-init-evaluation.js` 与 `array-elem-init-simple-no-strict.js` 在 `expressions/assignment/dstr` 与 `statements/for-of/dstr` 各一, `obj-id-init-simple-no-strict.js`), Q32 37 (两个 dstr 目录各 18: `array-elem-iter-*-close*`, `array-elem-trlg-iter-*-close*`, `array-rest-iter-*-close*`, `array-rest-lref-err.js`, `array-elem-put-obj-literal-prop-ref-init*.js`; 加 `expressions/assignment/destructuring/iterator-destructuring-property-reference-target-evaluation-order.js`). 比预计的约 28 多, 因为带默认值的成员目标原来是 SyntaxError. 证据 `regen-p613.log`, `props-before-p613.properties`, `pd613/`.
- `built-ins/Reflect` 剩 8 个登记失败都不属于 P6.13 的键: `set/*` 6 个 (receiver 与不可写属性, 与 Q33 同源的 OrdinarySet), `deleteProperty/return-boolean.js` strict (严格模式删不可配置属性抛错), `ownKeys/return-on-corresponding-order-large-index.js` (大下标的键序).
- 全量第一次 (149313 例) 比 P6.12 多 4 个失败, 都在上游的 `es2025.SetMethodsTest`: 它断言参数缺 `size` 时的文案 "Set-like object must have a 'size' property", `SetRecord` 把缺失当 undefined 报成了 "size is not a number". 规范只要求 TypeError, 改为缺失时沿用上游的三条文案 (`size` / `has` / `keys`, e16b3d70e), `SetMethodsTest` 31 个全过, 再跑全量 (见验证).
- 计时 (`bench-dstr-p613.txt`, P6.12 的 `bench-dstr.js` 十种形式各两次, 对照 P6.12 的最终值 after3): 解释模式 varDecl 277 / 275 ns (P6.12 298 / 277), let / const / 赋值 / 交换 / 四元素在 -10% 到 +3% 之间, rest 395 / 406 (381 / 374), 手写下标读 217 / 220 (209 / 212, 机器整体慢约 3%); 编译模式各形式在两次之间的波动范围内 (varDecl 19.6 / 33.0, P6.12 27.7 / 19.4). 没有退化; `defaults` 解释模式 346 / 344 (330 / 325) 慢 5% 是 Q30 的正确行为: 上游的外层判断在循环第二次起目标已有值, 跳过了默认值的求值. for-of 头部两模式都比 P6.12 记录的快 (解释 1318 / 2364 ns, 编译 347 / 953 ns), P6.13 没有改这条路径, 原因未查, 以后以本次为准.
- 上游 PR (P8): 四项各是单独的 PR 候选: Q26 `SetRecord` 加 `NativeSet` 33 行, 测试依赖 P6.11 的 `SetLikeKeys` (随它或排在它之后); Q27 `ReflectKey` 加 `NativeReflect` 10 行, 不依赖 fork 其他部分, `ReflectKeyTest` 可原样用; Q30 是 `Parser` 2 行, `DestructuringDefaultsTest` 的参数用例在上游同样成立, 赋值模式与 for-of 头部的用例依赖 P6.12 的迭代器路径 (上游按下标读, 默认值的行为相同); Q32 依赖 P6.12 的迭代器记录 (`BindingLowering.member`, Parser 6 行, 临时绑定沿用 P6.7 的 `LogicalAssignment.Temps`), 随 P6.12 的 PR 或排在它之后, PR 里把 `member` 与 `Temps` 搬进 IRFactory 或新文件.
- 未结: Q26 / Q27 / Q30 / Q32 已决并实施, Q28 / Q29 / Q31 已决 (见上); 新登记 Q33 (Proxy 的 set trap 以 receiver 转发时栈溢出, 上游既有, 推荐 fork 修, 不阻塞 P7). 同在 Proxy 的既有问题 (`Reflect.deleteProperty` 对 Proxy 的结果取自 `has` trap, 严格模式删不可配置属性抛错) 记在 HOOKS.md 第 8 节, 归 Q33 一并处理.
- 验证: 提交 42b618140 / e16b3d70e (Q26), 9e9963ebb (`SetRecordTest` 5 例), 9fa2ef97d / 51d754ac1 (Q27), 960bc4369 (`ReflectKeyTest` 6 例), d8b1d2516 (Q30 / Q32), aac7681e0 (`DestructuringDefaultsTest` 3 例, `ArrayMemberTargetsTest` 5 例), d4d75a5b8 (properties: 解锁 61, 新增失败 0, 标签变化 0), 勾选提交; 五模块全量 (JDK 21, `--rerun-tasks`, 16 分 57 秒, 含 `spotlessCheck`) 149313 例 27 失败, 与 P6.12 的 `current-failures-p612.txt` 逐条相同, 新增 0. 门禁 `spotlessCheck`, `git diff --check`, `generate_markdown.py --check` 通过. 证据 `regen-p613.log`, `props-before-p613.properties`, `pd613/`, `full-tests-p613.log`, `current-failures-p613.txt`, `bench-dstr-p613.txt`.

## P6.14 Proxy 的 [[Set]] / [[Delete]] (2026-10-29, 附录 C Q33 = A, 维护者 13:48 拍板)

- 原状 (上游既有, 宿主 2026-10-24 的 jar 同样): Rhino 的 `put` / `delete` 没有返回值, [[Set]] / [[Delete]] 的结果丢了. `Reflect.set` 总返回 true; receiver 与目标相同时直接 `target.put` (不看原型链, 原型上的 setter 不被调用), 不同时走 `AbstractEcmaObjectOperations.ordinarySet`, 它在目标没有该属性时调原型的 `put(key, receiver, v)`, `ScriptableObject.put` 在自身也没有时转给 `receiver.put`, receiver 是 Proxy 就是它的 [[Set]], set trap 以 `Reflect.set(t, k, v, r)` 转发时无限递归 (StackOverflowError, 复现脚本 `rhino-p6b-q33-repro.js` 修前两模式都是, 证据 `q33-repro-before.txt` / `q33-repro-after.txt`); 非对象的 receiver 报错. 没有 set trap 的 Proxy 把 receiver 换成目标 (`start = target`), 目标的 getOwnPropertyDescriptor / defineProperty trap 不被调用, setter 的 this 是目标. 赋值 (`ScriptableObject.putProperty`) 沿原型链问 `has`, 遇到 Proxy 时问它的 has trap, 回答 false 就越过它在 heir 上新建属性, 不调它的 set trap. `Reflect.deleteProperty` 与 `delete p.k` 的结果取自其后 has trap 的回答; 严格模式代码里 `Reflect.deleteProperty` 删不可配置属性抛 TypeError (删除时按调用者的严格模式判断).
- [[Set]] (`OrdinarySet.set`, 规范 10.1.9 OrdinarySet / OrdinarySetWithOwnDescriptor 与 10.5.9): 沿原型链取自有描述符 (`ScriptableObject.getOwnPropertyDescriptor`), 遇到 Proxy 把 receiver 交给它的 [[Set]] 并返回其结果 (trap 结果为 true 时的不变量检查用 SameValue, 上游的 `Objects.equals` 把编译模式的 Double 1.0 与 Integer 1 当成不同). 访问器: 没有 setter 返回 false, 有则在持有它的对象上 put (槽以 receiver 为 setter 的 this, 与上游赋值经原型的路径相同), receiver 不是对象时直接调 setter. 数据属性: 不可写返回 false, receiver 不是对象返回 false; receiver 是 Proxy 时调它的 getOwnPropertyDescriptor (trap), 已有访问器或不可写返回 false, 已有可写的以 `{ value }` 调 defineProperty trap, 没有则以完整的数据描述符 (CreateDataProperty); 没有 defineProperty trap 的 Proxy 一层层交给目标, 目标上不可扩展 / 不可配置时返回 false, 否则以 put 写入 (稠密数组, `length`, Java 实现的槽照旧, 不经 `defineOwnProperty`: `NativeArray.defineOwnProperty` 会把稠密数组拆成槽). receiver 不是 Proxy 时先查它自身已有的访问器 / 不可写属性与可扩展性 (返回 false), 再按上游的方式 put (原型上持有该属性的对象 put 给 receiver, 或 receiver 自己 put), 这样 `Reflect.set(o, k, v)` 与 `o[k] = v` 写入的方式相同. TypedArray 的下标键按整数索引的奇异对象: receiver 是它自己时写元素并返回 true, 无效下标返回 true, 其余以元素为可写数据属性继续. 非 `ScriptableObject` 的 `Scriptable` 在链上时按 has / put 照旧.
- 赋值的原型链查找 (`OrdinarySet.base`, `putProperty` 三个重载的钩子): 与 `getBase` 相同, 只多一条遇到 Proxy 即停, 交给它的 put (`OrdinarySet.put`: [[Set]] 以原对象为 receiver; 严格模式下 false 报 TypeError, 附录 C Q35). `hasProperty` 仍用 `getBase` (`in` 的偏差另登记 Q34). 没有 Proxy 时多一次 `instanceof`, 计时见下.
- [[Delete]] (`OrdinaryDelete`, 10.5.10): Proxy 没有 trap 时交给目标, trap 结果为 false 返回 false, 为 true 时目标的不可配置属性或不可扩展目标报 TypeError; 其他对象只删自身属性, 不可配置的直接返回 false (不进上游的 `checkSlotRemoval`, 所以严格模式代码里的 `Reflect.deleteProperty` 不抛), 其余照 P6.13 调 [[Delete]] 后看是否还有. `delete` 运算符 (`ScriptRuntime.deleteObjectElem`) 只在目标是 Proxy 时交给它 (严格模式下 false 报 TypeError), 普通对象照旧 (严格模式删不可配置属性仍由 `checkSlotRemoval` 报错). P6.13 的 `ReflectKey.delete` 移到这里.
- 顺带: `NativeProxy.getOwnPropertyDescriptor` 在 trap 返回 undefined 且目标没有该属性时空指针 (上游用 `Undefined.isUndefined(targetDesc)` 判断一个 Java null), P6.14 之后每次经转发 Proxy 新建属性都会走到 (`set/trap-is-missing-receiver-multiple-calls*.js`), 改为 `targetDesc == null` (1 行钩子).
- 重生成 test262 时补的两处 (第一次重生成: 新增失败 1, 标签变化 2, 都由上面的赋值查找引出): (1) 以原始值为 base 的赋值 (`OrdinarySet.primitive`, `ScriptRuntime.setObjectProp` / `setObjectElem` / `setObjectIndex` 各 3 行钩子): 按 PutValue 的 `ToObject(base).[[Set]](k, v, base)`, 严格模式下 false 报上游的 `undefWriteError` (消息不变). 上游在严格模式下对一切原始值的 base 先报错 (ES5), 原型上的 setter / Proxy 不被调用; 非严格模式写在临时的包装对象上, setter 的 this 是包装对象. `language/types/reference/put-value-prop-base-primitive.js` 与 `-realm.js` 的非严格模式因赋值查找遇到 Proxy 即停而先通过 (标签 all 变 strict), 补上后两种模式都通过. 只用于字符串, 数字 (含 BigInt), 布尔, Symbol 且语言版本 ES6 起 (Symbol 的原始值 `SymbolKey` 不是 `Scriptable`; 严格模式下 false 时上游的 `undefWriteError` 无法把它转成字符串, 报的是 "The object is not a string", 改报已有的 `msg.no.assign.symbol.strict`; 第二次重生成时这两个用例的严格模式仍因 Symbol 失败, 由此补上); 属性名形式的自增自减 (`'s'.x++`, `propIncrDecr`) 不经这三个入口, 照旧. 同时修了 `OrdinarySet.set` 里 receiver 是原始值时直接调 setter 的一处: Java 实现的 setter (`LambdaAccessorSlot` 的 `LambdaFunction`, 如 `__proto__`) 把 this 强转为 `Scriptable`, `Reflect.set(Object.prototype, '__proto__', {}, 5)` 抛 ClassCastException (第二个功能提交引入, 未发布), 改为传包装对象. (2) `Array.prototype.copyWithin` 的通用循环 (`NativeArray` 2 行, 改动的上游行, `OrdinarySet.getIfPresent`): 先 HasProperty 再 Get, 只有不存在时删除目标下标. 上游用 Get 的结果判断, 把值为 undefined 的源下标也当作不存在 (删除目标), Proxy 的 has trap 只经赋值的 `getBase` 被问到 (问的是目标下标), P6.14 之后赋值不再问 has trap, `built-ins/Array/prototype/copyWithin/return-abrupt-from-has-start.js` 变为失败; 补上后它照常通过, has trap 问的是源下标 (规范).
- 全量测试时补的一处: 上游 `NativeProxyTest.setTrapWithProxyAsReceiverViaWith` 检查 `with (proxy) { p = 1 }` 的 trap 顺序 `has, has, set, getOwnPropertyDescriptor, defineProperty`, 第二个 has 是 SetMutableBinding 的 HasProperty (规范), 上游经 `WithScope.put` 到 `putProperty` 的原型链查找问到; P6.14 之后少了它 (全量 28 失败, 比 P6.13 多这 1 个). `WithScope.put(String)` (with 语句的 VarScope) 加 1 行钩子 (`OrdinarySet.binding`: `with` 对象是 Proxy 时先问它的 has trap), 其他对象不问 (结果不可观察, 不多一次查找). `with (heir)` 而 heir 的原型是 Proxy 时经 `base` 调 set trap, 但 SetMutableBinding 的 HasProperty 不再走到 Proxy 的 has trap (`binding` 只看 `with` 对象本身), 与 `with` 名字查找的其余偏差同属 Q34 (6).
- 行为变化 (宿主脚本可见, 都是规范行为): 严格代码里 Proxy 赋值 / delete 为 false 时报 TypeError (Q35); 没有 set trap 的 Proxy 上, setter 的 this 是 Proxy, 赋值会调 getOwnPropertyDescriptor / defineProperty trap; 原型链上的 Proxy 在赋值时调 set trap, 不再问 has trap; `Reflect.set` 返回实际结果, 会调原型上的 setter. 非严格代码里冻结目标经 Proxy 赋值仍静默. 严格代码里给原始值的属性赋值, 原型上有 setter 或 Proxy 时调用它 (不再先报错, setter 的 this 是原始值), 其余照旧报错; `copyWithin` 复制值为 undefined 的元素 (不再删除目标).
- 未改 (登记 Q34): 没有 get trap 时目标自身 getter 的 this, `in` 越过原型链上的 Proxy, `Object.setPrototypeOf` 的环检查调 getPrototypeOf trap, getOwnPropertyDescriptor trap 的访问器描述符与不变量, defineProperty / setPrototypeOf / ownKeys 的不变量与目标是 Proxy 的情形, `with (proxy)` 的名字查找. `super.x = v` 的 receiver 是 Proxy 时 (`putSuperProperty`) 仍按上游; 属性名形式的自增自减以原始值为 base 时仍按上游.
- 上游 PR (P8): 一个 PR: `OrdinarySet` / `OrdinaryDelete` 与 25 行钩子 (NativeProxy 4: 三个 `put` 方法体与 `getOwnPropertyDescriptor` 的 1 行; NativeReflect 3: `set`; ScriptableObject 3; ScriptRuntime 12: `deleteObjectElem` 3 与赋值三个入口各 3; NativeArray 2: `copyWithin`; WithScope 1) 加 `deleteProperty` 的 1 行; 依赖 P6.13 Q27 的 `ReflectKey.of` (随它的 PR 或排在它之后), 不依赖 class. `NativeProxy` 的 `TRAP_SET` 与 `AbstractEcmaObjectOperations.ordinarySet` 在 PR 里可以删除 (fork 里保留上游原样, 少改上游行). getOwnPropertyDescriptor 的空指针可以单独成一个 1 行的 PR.
- test262: properties 用 JDK 17 `-DupdateTest262properties=all` 重生成 (全部目录三模式). 第一次 (只有前两个功能提交, 23 分 36 秒) 新增失败 1 (`copyWithin/return-abrupt-from-has-start.js`), 标签变化 2 (`put-value-prop-base-primitive*.js` all -> strict), 由此补上原始值为 base 的赋值与 `copyWithin`; 第二次 (58 分 5 秒) 两个 put-value 用例的严格模式仍因 Symbol 失败, 补上 Symbol 后用 Proxy / Reflect / Symbol / `language/types` 的有限运行核对 (2628 例, 失败 0, 正是这两个 "marked as failing but it does not"); 第三次 (39 分 43 秒) 失败登记 19968 -> 19925 个文件 (P6 的 `propdiff.py` 计数), 解锁 43, 新增失败 0, 标签变化 0: `built-ins/Proxy` 23 (`set` 13, `deleteProperty` 7, `defineProperty` 2, `getOwnPropertyDescriptor` 1), `built-ins/Reflect` 7 (`set` 6, `deleteProperty` 1), TypedArray `internals/Set` 7 (下标键不是规范的数字串 / 不是数字 / 是 Symbol, 越界且 receiver 不是对象, 含 BigInt), `JSON/parse/reviver-*-define-prop-err.js` 2, `language/types/reference/put-value-prop-base-primitive*.js` 2, `Array/prototype/splice/property-traps-order-with-species.js`, `Object/getOwnPropertyDescriptors/proxy-undefined-descriptor.js`. `built-ins/Proxy` 剩 28 个 (Q34), `built-ins/Reflect` 剩 1 个 (`ownKeys/return-on-corresponding-order-large-index.js`, 与本条无关). 之后的 `with (proxy)` 钩子只影响 `with` 对象为 Proxy 的赋值, `language/statements/with` 与 `built-ins/Proxy/has` 里用到 Proxy 与 `with` 的 16 个文件两模式逐个核对, 结果与 properties 一致 (12 个登记 non-strict 失败, 4 个通过), 未再重生成. 证据 `regen-p614.log` (第三次), `props-before-p614.properties`, `pd614/`, `t262-limited-p614.*`.
- 计时 (`bench-put-p614.txt`, `bench-put.js` 八种赋值形式, 修前是 P6.13 的构建, 修后是最终构建, JDK 21; 机器负载高, 同一构建两轮之间相差可达 25%): 每个形式单独一个进程, 修前修后交替 7 轮取中位数: 解释模式 assignOwn 1.03, createNew 1.00, createDeep 1.04, shadowProto 1.00, ctorThis 0.94, index 1.01, symbol 0.99, baseline (不赋值) 1.00; 编译模式 assignOwn 1.14, createNew 0.92, createDeep 1.07, shadowProto 1.01, ctorThis 0.87, index 0.99, symbol 0.94, baseline 0.96. 编译模式 assignOwn / createDeep / baseline 再各跑 15 轮: assignOwn 34.1 -> 37.3 ns (中位 1.09, 最小值 1.09, 均值 1.07), createDeep 1.02, baseline 0.96. 同一 JVM 两个类加载器交替的 A/B (`BenchPutAB.java`, 41 轮) 噪声更大 (解释模式 symbol 0.71, 编译模式 symbol 1.28, 两模式 baseline 0.99-1.05), 编译模式 assignOwn 1.00, 只作参考. 10% 硬门槛 (附录 C Q8): 解释模式全部达到; 编译模式样本最多的一次全部达到, assignOwn 在 1.09-1.14 之间, 与噪声同量级. 理论开销是 `base` 每一层多一次 `instanceof NativeProxy` 与赋值入口一次 `instanceof ScriptableObject`.
- 验证: 8a3a4b14e (登记 P6.14 与 Q33 已决), 710ac1441 (功能与 HOOKS.md) / 5b8105478 (`ProxySetAndDeleteTest` 9 例), e64720227 (原始值为 base 的赋值, `copyWithin`) / 02ed9fe58 (2 例), 209d00a5d (Symbol) / 59d06c2ef, 84898d5ca (`with (proxy)`) / 65cc40667 (1 例), fa9e648ac (只含 properties), 本条勾选提交 (NOTES, 附录 C Q34 / Q35, P8 附记); `ProxySetAndDeleteTest` 12 例两模式通过, Q33 复现脚本修前两模式 StackOverflowError, 修后 `ok 30 / ok 1 / no receiver ok 2` (`q33-repro-before.txt` / `q33-repro-after.txt`, 最终构建复跑相同). 五模块全量 (JDK 21, `--rerun-tasks`, 含 `spotlessCheck`, 25 分 13 秒): 第一次 (`with` 钩子之前) 149324 例 28 失败, 比 P6.13 多出上游 `NativeProxyTest.setTrapWithProxyAsReceiverViaWith`, 补上后 149325 例 27 失败, 与 P6.13 的 `current-failures-p613.txt` 逐条相同, 新增 0; 其中 Test262SuiteTest 137526 例失败 0, 39 个 "marked as failing but it does not" 都是 Unicode 版本相关 (`language/identifiers/*-unicode-14/15*`, `RegExp/property-escapes`: properties 只能用 JDK 17 生成, 全量在 JDK 21 上跑). 钩子标记行 604 (class syntax 554, async 50), `HooksRegistryTest` 通过. 门禁 `spotlessCheck`, `git diff --check`, `generate_markdown.py --check` 通过. 证据 `full-tests-p614.log`, `current-failures-p614.txt`.

## P6.15 Proxy 其余的规范偏差 (2026-10-29, 附录 C Q34 = A, 维护者 19:06 拍板)

- 原状 (上游既有, 宿主 2026-10-24 的 jar 同样): P6.14 之后 test262 `built-ins/Proxy` 剩 28 个登记失败, 2 个是 unsupported (`preventExtensions/trap-is-undefined-target-is-proxy.js` 要 module, `revocable/tco-fn-realm.js` 要尾调用, 保持登记), 其余 26 个分六组 (ROADMAP P6.15). 新代码在四个新文件, 上游文件 43 行钩子 (含上游测试 3 行, HOOKS.md 第 3 节).
- (1) [[Get]] (`OrdinaryGet`, 10.5.8 第 7 步与 OrdinaryGet): 没有 get trap 时以原 receiver 调目标的 [[Get]], 目标沿自己的原型链查找, 目标的 getter 以 Proxy (或继承它的对象) 为 this; 上游把 start 换成目标. 原型链读取 (`getPropWalkingPrototypeChain` 三个重载, `getProperty` / `getSuperProperty` 经它) 在 Proxy 之后停下 (钩子行内的判断, 与 `OrdinaryGet.next` 相同, 写在行内的原因见计时): Proxy 的 get 已查过目标的整条链, 上游会再取 getPrototypeOf trap 的原型接着找 (trap 被调用, 结果也可能不同). 读不存在的属性不再调 getPrototypeOf trap.
- (2) HasProperty (`OrdinaryHas`, 10.5.7 与 OrdinaryHasProperty): `ScriptableObject.hasProperty` 三个重载 (`in`, `Reflect.has`, `with` 的绑定查找等) 沿原型链问自有属性, 遇到 Proxy 时交给它的 [[HasProperty]] (has trap 与不变量; 没有 trap 时目标的 HasProperty), 不再越过它. 进程里创建过 Proxy 之前 (`NativeProxy` 构造器调 `OrdinaryHas.created()` 置 volatile 标记 `proxies`), `hasProperty` 照上游 `getBase`: 链上不可能有 Proxy, 而每层判断 Proxy 的循环在编译模式下让沿原型链的 `in` 慢到 2 倍 (计时见下, Q39). 反序列化得到的 Proxy 不经构造器, 不置标记. `getBase` 不变 (其他上游查找仍把 `NativeProxy.has` 当作 "自有", HOOKS.md 第 8 节).
- (3) [[SetPrototypeOf]] (`OrdinarySetPrototype`, 10.5.2 与 OrdinarySetPrototypeOf): 普通对象的环检查经 `OrdinaryGet.next`, 遇到 Proxy 即停, 不调 getPrototypeOf trap (`Object.setPrototypeOf` 与 `__proto__` 的 setter 共用 `NativeObject.setPrototypeOf`, `Reflect.setPrototypeOf`). Proxy: trap 结果为 false 时 `Object.setPrototypeOf` 报 TypeError (`proxy refused to set the prototype`), `Reflect.setPrototypeOf` 返回 false; 为 true 且目标不可扩展时目标的原型必须与 V 相同, 否则 TypeError; 没有 trap 时目标的 [[SetPrototypeOf]] (含它的检查). 上游的 `Reflect.setPrototypeOf` 先取 Proxy 的原型与可扩展性 (调了 trap), 并丢掉 trap 结果.
- (4) [[GetOwnProperty]] (`OwnProperty.result`, 10.5.5 第 12-18 步): trap 返回的对象按 ToPropertyDescriptor 读六个字段 (HasProperty 再 Get, 顺序同规范; get / set 不可调用, 数据与访问器字段同时出现都报 TypeError), 补全后与目标的描述符比较 (IsCompatiblePropertyDescriptor, SameValue), 再查不可配置 / 不可写的不变量. 上游只读四个数据字段, 访问器变成 value 为 undefined 的数据属性, 不查不变量. HasOwnProperty (`AbstractEcmaObjectOperations.hasOwnProperty`: `Object.hasOwn`, `Object.prototype.hasOwnProperty` 等) 对 Proxy 改问 [[GetOwnProperty]] (上游问 has trap).
- (5) [[DefineOwnProperty]] (`OwnProperty.define`, 10.5.6): 沿没有 defineProperty trap 的 Proxy 走到底 (中间的 Proxy 有 trap 时调它的, 上游直接取最内层目标); trap 与不变量检查同上游, 比较改用 SameValue (上游 `Objects.equals` 把编译模式的 Double 1.0 与 Integer 1 当成不同). 普通对象以 TypeError 表示拒绝: `Object.defineProperty` 原样抛出 (消息不变, 上游 `NativeProxyTest.definePropertyFreezedWithoutHandler` 断言它), `Reflect.defineProperty` 为 false; Proxy 的 trap 结果为 false 时 `Object.defineProperty` 报 TypeError (`proxy refused to define property 'k'`), 不变量违反时两者都报 TypeError (上游的 `Reflect.defineProperty` 把它吞成 false). `Reflect.ownKeys` 对 Proxy 保留 ownKeys trap 的顺序 (上游把字符串排在 Symbol 之前). `Proxy.revocable` 的 revoke 改为不带 prototype 的 `LambdaFunction` (不是构造器). String 对象的下标 (10.4.3.2, ES6 起): `Object.defineProperty(new String('ab'), '0', d)` 与该字符的描述符 (不可写, 可枚举, 不可配置) 比较, 不兼容时按普通对象的消息报 TypeError, 兼容时不改动; 上游把它加成被 `get(int)` 遮住的隐藏属性 (静默成功), 是 `defineProperty/trap-is-missing-target-is-proxy.js` 的根因, 修 (5) 时发现.
- (6) `with (proxy)` (`OrdinaryHas.withValue` / `withHas`, 9.1.1.2.1 HasBinding 与 9.1.1.2.6 GetBindingValue): 名字查找 (`ScriptRuntime.nameOrFunction` 两个, `bind`, `WithScope.get`) 对 Proxy 先 HasProperty (has trap), 再读 `Symbol.unscopables`, 读值时再 HasProperty 后 Get; has 为真而值为 undefined 时取 undefined (上游落到外层作用域), 读值前绑定消失时严格模式 ReferenceError, 否则 undefined. 其他 `with` 对象照上游 (只 Get, 不看 @@unscopables, Q36). 上游 `NativeProxyTest.setTrapWithProxyAsReceiverViaWith` 的期望改为规范顺序 (它的注释写明了 "finally should be" 这个值; tests/src 3 行, AGENTS.md 第三节第 5 条). 核对时发现名字的复合赋值 (`x += 1`) 与 `typeof x` 先 `bind` 再按名字读值, 多一轮 has trap 与 @@unscopables (Q37); 读取, 调用, `x++` 同规范.
- 修复中补的: `NativeProxyTest.hasTargetNotExtensible` 断言 has trap 不变量的上游消息, `OrdinaryHas` 沿用原文. 第一版 `NativeObject.setPrototypeOf` 的钩子行超过 100 列, spotless 折成两行后第一行没有标记 (ErrorProne 的 StringConcatToTextBlock 随之崩溃), 改了 `OrdinarySetPrototype.object` 的参数类型, 钩子行不超过 100 列 (含标记).
- 行为变化 (宿主脚本可见, 都是规范行为): 没有 get trap 的 Proxy 上目标 getter 的 this 是 Proxy; `in` / `Reflect.has` 在原型链上遇到 Proxy 时以它为准; `Object.setPrototypeOf(proxy, v)` 在 trap 返回 false 时报 TypeError; `Object.getOwnPropertyDescriptor(proxy, k)` 返回访问器描述符, 非法或违反不变量的 trap 结果报 TypeError; `hasOwnProperty` / `Object.hasOwn` 对 Proxy 调 getOwnPropertyDescriptor trap 而不是 has trap; `Object.defineProperty(proxy, ...)` 在 trap 返回 false 时报 TypeError, `Reflect.defineProperty` 违反不变量时报 TypeError; `Reflect.ownKeys(proxy)` 的顺序; `new revoke()` 报 TypeError; 给 String 对象的下标定义不兼容的属性报 TypeError (上游静默); `with (proxy)` 调 has trap 并读 `Symbol.unscopables`. 普通对象 (链上没有 Proxy) 的读取, `in` 与 `with` 结果不变.
- 未改 (登记): `with` 对普通对象不看 `Symbol.unscopables` (Q36); `with (proxy)` 里复合赋值与 `typeof` 的二次解析 (Q37); `__proto__` 的读写 (`SpecialRef`) 对 Proxy 不经 get / set trap, 赋值的环检查调 getPrototypeOf trap (Q38); `Object.defineProperties` / `Object.create` 的第二个参数对 Proxy 仍丢掉 [[DefineOwnProperty]] 的 false; `propertyIsEnumerable` 与 `for-in` / `Object.keys` 对 Proxy 按上游; `getBase` 的其他使用者 (HOOKS.md 第 8 节); 编译模式 `with` 查找的计时 (Q40, 见计时).
- 上游 PR (P8): 一个 PR: 四个新文件与 40 行钩子 (NativeProxy 9, ScriptableObject 6, NativeObject 5, NativeReflect 7, NativeString 6, AbstractEcmaObjectOperations 3, ScriptRuntime 3, WithScope 1) 加上游测试 `NativeProxyTest` 3 行 (合计 43); `in` 的守卫 (`OrdinaryHas.proxies`, NativeProxy 构造器 1 行与 `hasProperty` 3 行) 是否随 PR 由评审定 (Q39); 新文件不依赖 P6.13 / P6.14 的代码, 但与 P6.14 的 PR 同改 `NativeProxy` / `WithScope` / `ScriptableObject`, 排在它之后; 不依赖 class, `ProxyInternalMethodsTest` 不用 class, 可原样用. `NativeProxy` 的 `TRAP_DEFINE_PROPERTY` / `TRAP_SET_PROTOTYPE_OF` 与 `AbstractEcmaObjectOperations.isCompatiblePropertyDescriptor` 在 PR 里可以删除 (fork 保留上游原样). String 下标 (NativeString 6 行加 `OwnProperty.stringIndex`) 与 `with (proxy)` (ScriptRuntime 3, WithScope 1, `OrdinaryHas.withValue` / `withHas`) 也可以各自单独成 PR.
- test262: 修后先用 `built-ins/Proxy` 的 28 个登记文件与 `language/statements/with` 里用到 Proxy 的 8 个逐个跑两模式 (`run262.py`), 再跑有限范围 (Array, JSON, Object, Proxy, Reflect, String, `language/expressions/in`, `language/statements/with`, `language/types`; 33738 例, 失败 0, 32 个 "marked as failing but it does not"); properties 用 JDK 17 `-DupdateTest262properties=all` 重生成 (全部目录三模式, 24 分 39 秒): 失败登记 19925 -> 19892 个文件 (`propdiff.py`), 解锁 33, 新增失败 0, 标签变化 0: `built-ins/Proxy` 26 (`has` 8, `setPrototypeOf` 6, `getOwnPropertyDescriptor` 5, `defineProperty` 3, `get` / `ownKeys` / `revocable` / `set` 各 1), `language/statements/with` 6 (`*-with-proxy-env.js` 5 个, `has-property-err.js`), `language/expressions/object/prop-def-id-eval-error-2.js` (`with (proxy)` 里 has trap 抛错). `built-ins/Proxy` 剩 2 个 unsupported, `built-ins/Reflect` 剩 1 个 (`ownKeys/return-on-corresponding-order-large-index.js`, 同 P6.14, 与本条无关). 之后的守卫 (8925c53ab) 只改创建 Proxy 之前的执行路径, 结果不变, 未再重生成 (全量的 Test262SuiteTest 覆盖). 证据 `regen-p615.log`, `props-before-p615.properties`, `pd615/`.
- 计时 (`bench-get.js` 18 种形式: 读全局变量的 getOwn / getProto / getDeep / getMiss / method / index, `in` 的 inOwn / inDeep / inMiss, `with` 块内的 withName / withOuter, 读局部变量的 getProtoL / getDeepL / getMissL / inOwnL / inDeepL / inMissL, baseline (只循环); 都是普通对象, 进程里没有 Proxy; 修前是 P6.14 的构建 564167be6, JDK 21, 每个形式单独一个进程, 修前修后交替, 9 或 21 轮取中位数之比; 机器被其他会话占满, 同一构建两轮之间相差可达 30%, 编译模式沿原型链的 `in` 是双峰 (inDeepL 12-13 或 21-24 ns, inDeep 25-28 或 34-40 ns)). 经过: (a) 第一次对照无效: 修前的类用 javac 不带 `-g` 编译, Gradle 带 `-g`, 不带时 javac 折叠 final 局部变量, 字节码不同 (`ScriptRuntime.class` 129917 / 157020 字节); 改用 `javac -g -nowarn -encoding UTF-8 --release 17`, 与 Gradle 的产物逐字节相同. (b) 第一版 `hasProperty` 直接走 `OrdinaryHas.has` (原型链上每层先判断 Proxy 再问自有属性), 编译模式沿原型链的 `in` 慢到 2.2 倍 (inDeepL 12.5 -> 28 ns, 读全局变量的 inDeep / inMiss 1.17-1.25); 判断放在 `has` 之后时 C2 能把它折叠掉, 但遇到 Proxy 会先调它的 `has` (多一次 has trap), 不能用; 改为守卫 (Q39, 8925c53ab): 进程里没有创建过 Proxy 时照上游 `getBase`; 守卫写在 `OrdinaryHas.has` 里仍偏慢 (中位 1.71, 双峰), 写在 `hasProperty` 入口后 21 轮 inDeepL 1.01, inDeep 1.09, inMiss 1.07. (c) 8925c53ab 的全表 (9 轮) 里读全局变量的 inDeep / inMiss 仍为 1.15 / 1.10, 读局部变量的 1.01-1.04; 21 轮三方交替隔离 (`bench-get-p615-steps.txt`): 守卫去掉 volatile 1.12 / 1.12 (无变化), 去掉 `hasProperty` 的守卫行 1.14 / 1.08, 换回修前的 ScriptableObject 0.97 / 1.00, 换回修前的 ScriptRuntime 1.13 / 1.10: 开销来自读取循环钩子经 `OrdinaryGet.next` 多出的一层调用 (循环里读全局变量 `deep` 的名字查找经过它), 与 `in` 本身无关; 把同一判断写在钩子行内 (4c06480fc) 后 1.00 / 1.01, 同轮 getDeep 1.02, getMiss 1.02. (d) 4c06480fc 的全表时负载降低 (数值约为之前的一半), 编译模式 withOuter (`with (env) { s += outer }`, 名字在 with 对象上找不到, 落到外层) 1.27, 21 轮 1.19; 隔离: 换回修前的 ScriptRuntime 或 WithScope (`with` 的钩子) 1.12 / 1.13, 两者同时换回 1.31, 换回修前的 ScriptableObject 1.30 (双峰), 8925c53ab 0.83 / 0.99, 4c06480fc 同轮 1.03 / 1.41: 读取循环加了判断后 43 字节, 超过 HotSpot 的 MaxInlineSize (35), `-XX:+PrintInlining` 显示不热的调用点 "callee is too large" (修前 32 字节, 调 `next` 的写法 30 字节, 处处内联); 循环里加任何判断都超过 35 字节 (最紧的写法 36). 又试了守卫放到 `getProperty` 入口 (读取循环恢复上游, 建过 Proxy 之后改走 `OrdinaryGet.get`): volatile 时 getDeep 1.15, inDeep 1.15, withOuter 1.19; 非 volatile 时 withOuter 0.99, 但 inDeep 1.18, inMiss 1.13, getDeep 1.09, 都不采用. 两种可行写法里, 行内判断的编译模式 `in` 与读取更好, 调 `next` 的 `with` 更好, 保留行内判断, 登记 Q40 (已按推荐先行). 最终构建 (4c06480fc) 全表 9 轮 (`bench-get-p615.txt`): 解释模式 getOwn 0.97, getProto 0.99, getDeep 1.01, getMiss 1.04, method 1.11 (21 轮 0.98), index 0.98, inOwn 0.96, inDeep 0.96, inMiss 1.02, withName 1.00, withOuter 1.05 (21 轮 1.06), getProtoL 1.00, getDeepL 1.01, getMissL 1.03, inOwnL 0.99, inDeepL 0.97, inMissL 1.01, baseline 1.00; 编译模式 getOwn 1.06, getProto 1.06, getDeep 1.00, getMiss 0.99, method 0.98, index 0.91, inOwn 1.46 (21 轮 1.04, 另一轮 0.84; 修后双峰 8-11 或 15-21 ns), inDeep 1.02, inMiss 1.03, withName 0.88, withOuter 1.27, getProtoL 0.96, getDeepL 1.05, getMissL 1.07, inOwnL 0.85, inDeepL 1.05, inMissL 1.00, baseline 0.94. 4c06480fc 在其余各轮 21 轮对照里: inDeep 0.97-1.10, inMiss 1.01-1.09, getDeep 1.01-1.02, getDeepL 1.07-1.12 (修前修后都是双峰 14 或 32 ns), withOuter 1.03-1.41. 创建过 Proxy 之后 (`bench-get-proxy.js`: 先建一个 Proxy, 再跑同样的普通对象形式, 守卫关闭; 8925c53ab 的构建, `in` 的路径与最终构建相同; `bench-get-p615-proxy.txt`, 9 轮): 编译模式沿原型链的 `in` 为修前的 1.15-2.12 倍 (inOwnL 1.20, inDeepL 2.12, inMissL 1.20, inDeep 1.27, inMiss 1.15), 解释模式 0.90-1.24, 是 Q39 记下的代价. 10% 硬门槛 (附录 C Q8, 进程里没有 Proxy): 解释模式全部达到 (宿主在 ART 上走解释模式); 编译模式读取与 `in` 达到, 个别形式 (inDeep, getDeepL, inOwn) 在单轮里到 1.10-1.46, 都是双峰造成, 同一形式样本更多的轮次在 1.10 以内或贴线, 与 P6.14 的 assignOwn 同类; 编译模式 withOuter 未达到 (1.03-1.41, 多数轮 1.2-1.3), 登记 Q40.
- 验证: 提交 a2e672029 (登记 P6.15 与 Q34 / Q35 已决), 227884f97 (功能与 HOOKS.md), e205746fd (`ProxyInternalMethodsTest` 7 例, 每例 `assertWithAllModes_ES6`), c021cf3da (只含 properties), 8925c53ab (`in` 的守卫, Q39), 4c06480fc (读取循环的判断写在钩子行内), 本条勾选提交 (NOTES, 附录 C Q36-Q40, P8 附记); `ProxyInternalMethodsTest` 7 例, `ProxySetAndDeleteTest` 12 例, `HooksRegistryTest` 通过. 五模块全量 (JDK 21, `--rerun-tasks`, 含 `spotlessCheck`): 8925c53ab 上 47 分 34 秒, 4c06480fc 上 23 分 43 秒, 两次都是 149332 例 (P6.14 的 149325 加 `ProxyInternalMethodsTest` 7) 27 失败 21 跳过, 与 P6.14 的 `current-failures-p614.txt` 逐条相同, 新增 0; 上游 `NativeProxyTest` 只有基线里的 `ctorMissingArgs` / `ctorWrongArgs`, `NativeReflectTest` 只有基线里的 `applyArgumentsListNotFunction`; Test262SuiteTest 137526 例失败 0, 39 个 "marked as failing but it does not" 同 P6.14 (Unicode 版本相关). 钩子标记行 647 (class syntax 597, async 50; `rhino/src/main/java` 637, `tests/src` 10), 本条新增 43. 门禁 `spotlessCheck`, `git diff --check`, `generate_markdown.py --check` 通过. 证据 `full-tests-p615.log` (4c06480fc 的那次), `current-failures-p615.txt`, `bench-get-p615.txt`, `bench-get-p615-steps.txt`, `bench-get-p615-proxy.txt`, `bench-get*.js`, `bench-get*-run*.py`.
