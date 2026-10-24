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
