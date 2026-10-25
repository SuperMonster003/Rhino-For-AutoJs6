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
