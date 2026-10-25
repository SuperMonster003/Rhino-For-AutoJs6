# Rhino ES6 class 与 async/await 路线图 (Rhino-For-AutoJs6)

更新日期: 2026-10-24

本文是 Rhino-For-AutoJs6 (AutoJs6 使用的 mozilla/rhino 分支) 实现 ES2015+ class 语法 (含字段, static block, 私有名), 内建类型与 Java 类的子类化, 以及 async/await (含 async 生成器与 for await) 的可执行状态表. 它以 2026-10-24 维护者经四轮选择题确认的 16 项决策 (第 2 节) 为依据, 记录现状证据 (第 1 节), 派生技术决策 (第 3 节), 红线 (第 4 节), 阶段任务 (第 5 节) 与基线数据 (附录).

使用方式:

1. 每个子会话从第 5 节选取未完成条目, 按阶段顺序推进; 条目完成后勾选 `[x]` 并追加证据 (提交 hash / 测试类名 / test262 通过数 / 设备型号与 API). 阶段完成即本地提交, 不推送 (fork 与宿主都只本地提交, 推送由维护者逐次指示).
2. 第 2 节固定决策不再重新讨论; 第 3 节派生决策可在标注的推翻点之前推翻, 之后视同固定; 附录 C 待决事项在进入对应阶段前由调度会话用选择题请维护者拍板, 结果回填到第 2 节或第 3 节.
3. 工程约定以仓库 `AGENTS.md` (上游同步规程与必须保留的定制) 与工作区 `../COMMON_AGENTS.md` 为准 (ASCII 标点, 提交身份, 共享工作区). 本文只记录 "改什么" 与证据.
4. 看板: `D:/idea-projects/.claude/boards/rhino-class.md` (当前批次, 子会话 id, 待决事项, 调度日志以看板为准).

---

## 1. 现状与证据

### 1.1 上游 (mozilla/rhino master 34abaca89, 2026-10-23; fork 已于 2026-10-24 同步至此, 提交 26f223854 + 24f8242cb)

- `class` 仍是保留字: `TokenStream` 中 `Id_class = Token.RESERVED`; Parser / AST / IRFactory / 三个后端没有任何 class 处理. test262 的 `language/statements/class` (4367 例) 与 `language/expressions/class` (4059 例) 在 `tests/testsrc/test262.properties` 中整目录排除 (前缀 `~`).
- issue #835 (2021-02 开, ES2015 里程碑) 无人认领, 无关联 PR. gbrail 2024-10: "a big hole", 只在 parse/compile 管线做变换不够, 需要新的运行时支持. andreabergia 2024-11 起先实现对象字面量方法中的 `super` (已合入).
- 已就位的前置能力: `super` 属性访问 (`ScriptRuntime.getSuperProp/setSuperProp/getSuperElem/...`) 与 home object (`BaseFunction.setHomeObject`, Interpreter 在字面量创建时设置; `NativeCall.getHomeObject`); `new.target` (Parser 3458 行起, `Token.NEW_TARGET`); `Constructable.construct(Context cx, Object nt, VarScope s, Object[] args)` 带 new.target 参数 (注释明说 "super constructor calls may pass in different values"); `Reflect.construct` 透传 newTarget (`NativeReflect` 145 行); 对象字面量生成器方法 (`ast/GeneratorMethodDefinition`); `let` / `const` 块级作用域 (2026-10-13 修复组); Interpreter V2 (`InterpreterV2.java` 619 行 + `interpreterv2/instruction` 94 个指令类, `setOptimizationLevel(-2)` 选择; `EvaluationMethod` 枚举目前只有 `Interpreter` 与 `Compiler` 两个值, 上游 Test262SuiteTest 没有 V2 模式, V2 仍抛 "Haven't implemented all the continuations stuff", 处于早期).
- 仍缺的运行时支持: `BaseFunction.construct` 中 `createObject(cx, scope)` 不看 `nt`, 子类实例的原型不会取自 `new.target.prototype`; 带 home object 的函数被判为不可构造 (`msg.not.ctor`); 没有 "派生构造器 this 未初始化" 与 "类构造器不带 new 调用抛 TypeError" 的概念; 方法需不可枚举; 没有私有名槽位; 没有 async 函数 (生成器 `ES6Generator` / `NativeGenerator` / `GeneratorState` 与 `NativePromise` / `Context.enqueueMicrotask` / `processMicrotasks` / `UnhandledRejectionTracker` 已有).
- `Native*` 文件 60 个 (`org.mozilla.javascript` 下 44 个, `typedarrays` 下 16 个): 18 个用 `LambdaConstructor` 风格, 42 个用 `IdScriptableObject` 或其他风格; 其中带 JS 构造器的 39 个 (非构造器的如 NativeMath / NativeJSON / NativeReflect / NativeGlobal / NativeConsole / 各 Iterator / NativeCall / NativeWith / NativeJavaPackage 不在子类化范围).

### 1.2 fork 的定制规模与上游活跃度

- 相对 merge-base d878324a6, fork 在 Parser (55 行), IRFactory (272), NodeTransformer (250), Interpreter (73), CodeGenerator (43), ScriptRuntime (228) 六个文件共 838 行增改; 全部定制文件 132 个 (大半是 changelog / 翻译 / 构建脚本).
- 上游一年 (2025-10-24 起) 对这些文件的提交: Parser 21, IRFactory 12, NodeTransformer 7, Interpreter 38, CodeGenerator 13, ScriptRuntime 51. 2026-10-24 同步 49 个上游提交只出 3 处文本冲突 (NativeObject.fromEntries, NativeObjectTest, Messages.properties). 结论: 钩子式定制的冲突可控, 真正要防的是上游改约定导致 fork 逻辑静默失效, 靳靠自带回归兜住.
- 文件规模: Parser 5866 行, IRFactory 2880, NodeTransformer 807, Interpreter 4584, CodeGenerator 2109, Icode 401, ScriptRuntime 6575, optimizer/Codegen 1201, optimizer/BodyCodegen 5160.

### 1.3 宿主 (AutoJs6) 侧现状

- 引擎: `AndroidContextFactory` / `RhinoJavaScriptEngine` 设 `languageVersion = Context.VERSION_ES6`, 解释模式. 2026-10-24 宿主 jar 已换为同步后的 Rhino (宿主提交 0e4f95bb2).
- Promise: `RhinoJavaScriptEngine.init` 把全局 `Promise` 定义为 `runtime.js_Promise` (READONLY | PERMANENT), 来源 `assets/modules/promise.js` (319 行, 改自 promise-polyfill). 定制点: `Promise.prototype.await()` (经 `continuation.await`), `Promise.prototype.wait()` (`runtime.loopers.newSettlementLatch()`), `finally`, `_immediateFn` 用 `setImmediate` / `setTimeout(fn, 0)` 做宏任务调度 (不是微任务). Augment 路线图 P4.2 已把 promise.js 列为 "值得 Kotlin 化但风险最高, 也可考虑在 fork 的 NativePromise 上适配协程" 的评估项, 本路线图 D14 合并该评估.
- 微任务: 宿主只有 `ScriptAsyncDispatcher.kt:85` 调过 `Context.processMicrotasks()`; 脚本主循环 (`core/looper/Loopers`, `Timer.kt`, `LoopBasedJavaScriptEngine`) 没有逐宏任务排空微任务的步骤.
- 协程: `rhino/continuation/Continuation.kt` 与 `runtime/api/modules/bootstrap/ContinuationPrototype.kt` (`await` / `delay`) 基于 Rhino continuation 在脚本顶层同步式等待, 是宿主特有写法, 文档与大量用户脚本在用.
- Java 子类化: Rhino `JavaAdapter` 在 Android 上经 `rhino/AndroidClassLoader.kt` + `ClassFileDexer.kt` (D8) 把生成的类字节码转 dex 后加载 (`AndroidContextFactory` 引用 JavaAdapter); `JavaAdapter.getAdapterClass` 按 `JavaAdapterSignature` 缓存. 宿主另有 `engine/RuntimeJavaSubclassGenerator.kt` (Node 桥接的运行时 Java 子类生成, `__autojs6_super_N` 超类方法命名), 只服务 Node 运行时, 不在 Rhino 路径上.
- TypeScript Engine 插件 (TypeScript 6.0.3, WebView 119+ 内编译) 的 Rhino profile 固定 `target: ES2018` (`compiler-worker.js`: `controlledTsConfigRevision3RhinoTargets = {"ES2018"}`), 不降级 class, 所以 `.ts` 项目在 Rhino 上同样不能写 class; Rhino 支持后自动透传, 插件无需改编译器, 只需 README 说明.
- 文档站 `AutoJs6-Documentation`, d.ts 仓库 `AutoJs6-TypeScript-Declarations` (本路线图不需要改 d.ts).

### 1.4 test262 规模 (fork `tests/test262` 子模块, 2026-10-24 统计)

| 范围 | 用例数 | 说明 |
| --- | --- | --- |
| class 核心 (ES2015 语法 + 后续非字段语法) | 2414 | 其中 `dstr` (方法参数解构) 1488, 根目录 315, `subclass` 106, `definition` 62, `subclass-builtins` 72, `gen-method*` 124, `accessor-name-*` 84, `method*` 80, `elements` (非字段部分) 52, `syntax` 13, `super` 8, `name-binding` 6 |
| class 公共字段与 static block | 673 | features 含 class-fields-public / class-static-fields-public / class-static-block |
| class 私有名 | 4016 | features 含 class-fields-private / class-methods-private / class-static-*-private / class-fields-private-in |
| class async 方法 | 1299 | 随 P9 |
| class 装饰器 | 24 | 不在范围 (decorators 提案) |
| async 函数 (statements 74 + expressions 93) | 167 | |
| async 箭头函数 | 60 | 现有 42 失败 18 通过 (负向语法用例) |
| await | 22 | |
| for await | 1234 | 大半是解构变体 |
| async 生成器 (statements 301 + expressions 623) | 924 | |
| Async* 内建原型 (AsyncFunction 18, AsyncGeneratorFunction 23, AsyncGeneratorPrototype 48, AsyncFromSyncIteratorPrototype 38, AsyncIteratorPrototype 13) | 140 | |
| 对象字面量 async 方法 | 145 | `language/expressions/object/method-definition` 中 async-* |

范围内合计: class 7103 (2414 + 673 + 4016) + async 3991 (1299 + 2692) = 11094 例. 排除规则见 T10.

---

## 2. 固定决策

以下决策由维护者于 2026-10-24 经四轮选择题确认, 后续阶段不再重新讨论.

| 编号 | 决策 | 含义 |
| --- | --- | --- |
| D1 | 一期做全 class 语法 | 声明与表达式, extends, constructor 与 super(), 实例 / 静态方法, getter / setter, 计算属性名, 生成器方法, super.x 访问, new.target 子类化, 公共实例 / 静态字段, static block, 私有字段 / 方法 / 访问器 / 静态私有成员, `#x in obj`. 装饰器不做. async 方法随 D7. |
| D2 | 内建构造器全部精确按 new.target 创建 | 60 个 Native* 中所有带 JS 构造器的类型 (盘点后登记精确数, 预计 35-39 个) 都实现 OrdinaryCreateFromConstructor 语义: 实例原型取自 new.target.prototype, species 与原型方法行为随之正确. 不用 "构造后 setPrototypeOf" 作为最终方案 (可作中间脚手架, 阶段结束前拆掉). |
| D3 | 一期支持继承 Java 类与接口 | `class X extends <Java 类或接口>` 自动走 JavaAdapter 生成子类, 方法覆盖映射到 Java 方法, `super.method()` 调 Java 父类实现, `super(args)` 调 Java 父类构造器. AutoJs6 特色能力, 实现为独立的 fork 钩子层 (D6). 细节见 T6 与附录 C Q3 / Q4. |
| D4 | 门控: 语言版本 >= ES6 且开关默认开 | `Context.VERSION_ES6` (200) 及以上启用 (宿主引擎即此版本), 更低版本保持 "class 是保留字" 的现有报错; 另加 `CompilerEnvirons` 开关 (默认开) 供回归隔离与将来上游实现后退场. async/await 同一门控. |
| D5 | 三个后端都支持 | Interpreter (-1), Interpreter V2 (-2), Codegen (0-9). V2 的现状限制见附录 C Q2. |
| D6 | 通用部分分批向上游提 PR, Java 继承留 fork | 核心语法经 fork 验收后按 "语法 + AST / 降糖 + 运行时 / 字段与私有名 / 内建精确化 / 测试" 拆分, 在干净的上游 master 克隆上移植为多个 PR, 引用 #835. Java 继承 (D3) 与宿主专属行为留在 fork. 上游合入后 fork 对应部分在同步时退场. |
| D7 | async/await 作为本路线图最后一期 | P9. 范围见 D13. 维护者 2026-10-24 明确要求与 class/extends 一起立项. |
| D8 | class 验收: 范围内 test262 100% | 7103 例全部通过, 依赖其他未实现特性的用例按 features 标签排除并登记 (T10); fork 全量测试与同步前基线对照无新增失败; fork 自有 ClassSyntaxTest / JavaClassInheritanceTest; 宿主 jar 更新后单测 + 三个 assemble + 设备 instrumentation (API 24 与 API 35 模拟器各一台 + 一台真机). |
| D9 | 9 张卡顺序执行 | P1-P9 每阶段一个子会话卡片, 同一 checkout 不并行 (fork 仓库不开额外 worktree), 每张卡自带单测与阶段证据, 本地提交后换卡. |
| D10 | 文档与周边 | 宿主 zh-Hans changelog (其他语言由维护者同步); fork 自己的 10 语 changelog; 文档站 AutoJs6-Documentation 新增 "class 与 async/await" 章节 (含 Java 类继承的 AutoJs6 特有写法与限制, continuation 与 async/await 的差异); TypeScript Engine 与 Ace Editor 插件 README 各补一句 Rhino 现已支持 class / async. d.ts 不改. |
| D11 | roadmap 位置 | 本文件 `docs/autojs6/class-syntax/ROADMAP.md`, 随提交入库; 上游 PR 不带 `docs/autojs6/`. |
| D12 | 调度会话 | 维护者从桌面端 dispatch 环境新建调度会话, 输入 `/dispatch rhino-class` 接管 (首轮把自己的 id 写进看板调度入口行并发第一张卡). 立项会话 local_b3d11b86 写完 roadmap 与看板后归档. |
| D13 | async 范围: 全部 | async 函数 / 方法 / 箭头函数, await, for await, async 生成器 (含 yield* 与请求队列), Symbol.asyncIterator, AsyncIterator / AsyncGenerator / AsyncFromSyncIterator 原型, AsyncFunction / AsyncGeneratorFunction 构造器. |
| D14 | Promise 统一到 NativePromise, 宿主 promise.js 退场 | async 函数返回 Rhino NativePromise, 用 Context 微任务队列; 宿主脚本循环每个宏任务后 processMicrotasks; promise.js 的定制行为逐条核对后在 NativePromise / 宿主侧补齐 (附录 C Q5), 然后全局 Promise 换回 NativePromise. Augment 路线图 P4.2 的 promise.js 评估并入本路线图 P9. |
| D15 | continuation 写法与 async/await 并存 | continuation 模块不变 (顶层同步等待仍是它的独有能力); 文档站写清 "顶层等待用 continuation, 函数内用 async/await"; 互操作用例: `continuation.await(asyncFn())` 与 async 函数内 await 宿主 API 返回的 Promise. |
| D16 | async 验收: 范围内 test262 100% | 3991 例, 排除规则同 T10; 另加宿主真机用例: 脚本循环中 await 计时器 / 宿主 API Promise 的时序, 脚本结束判定, 未捕获 rejection 的报错. |

---

## 3. 派生技术决策

| 编号 | 决策 | 依据与推翻点 |
| --- | --- | --- |
| T1 | 降糖在 IRFactory, 后端只认少量新原语 | Parser 产出 class 专用 AST; IRFactory 把 class 降糖为现有 IR (函数 + 对象字面量 + 作用域节点) 加不超过 8 个新原语 (设置 home object, 定义不可枚举方法, this 初始化检查 / 绑定, super() 构造, 按 new.target 创建实例, 类构造器调用检查, 私有名定义 / 读写 / 品牌检查, 字段初始化器调用). 每个后端为这些原语各写一处处理 (预计 Interpreter 150-250 行, Codegen 150-250 行, V2 按其指令类风格). 这是 Rhino 处理解构 / for-of / 模板字符串的惯用方式, 也是上游 PR 最容易被接受的形态. 推翻点: P2 结束前; 若发现某原语在 Codegen 无法表达, 允许该原语退化为 `ScriptRuntime` 静态调用. |
| T2 | 新代码新文件, 上游文件只留钩子 | 新文件: `ast/ClassNode.java`, `ast/ClassElement.java` (或拆 ClassMethod / ClassField / StaticBlock), `ast/PrivateName.java`, `ClassLowering.java` (IRFactory 的降糖辅助, 包私有), `ClassRuntime.java` (运行时辅助), `PrivateNameKey.java`, `autojs6/JavaClassInheritance.java` (D3 钩子层, 独立包), 各后端的新指令类 / 处理方法. 上游文件的改动点统一加注释标记 `// AutoJs6: class syntax` 或 `// AutoJs6: async`; 钩子清单 `docs/autojs6/class-syntax/HOOKS.md` 自 P1 建立, 每阶段同一提交内更新 (格式与核对方式见 `AGENTS.md` 第七节 7.1 / 7.2, 该节 2026-10-24 已写好: 同步时的保护清单, 只读预检, 上游实现后的退役判定与步骤). 推翻点: 无 (结构性约定). |
| T3 | 词法: `class` / `extends` / `static` / `await` / `async` 的识别 | ES6 门控开启时 `class` 从 RESERVED 变为 `Token.CLASS`; `extends` / `static` / `get` / `set` / `async` / `of` 按上下文关键字处理 (不新增全局保留字, 避免破坏把它们用作标识符的旧脚本); `await` 只在 async 函数体内是关键字 (与 ES 规范一致, 脚本顶层 `await` 仍是标识符, 见 D15); `#name` 新增私有名 token. 推翻点: P1 结束前. |
| T4 | class 构造器与方法的函数对象 | 构造器是普通 `InterpretedFunction` / 编译函数, 带 `isClassConstructor` 标志 (call 时抛 `msg.class.ctor.call`), 派生类带 `isDerived` 标志 (this 以未初始化状态开始, `super()` 后绑定; 重复 super() 抛 ReferenceError; 访问未初始化 this 抛 ReferenceError); 方法沿用对象字面量方法的机制 (home object, 不可构造), 但以不可枚举方式定义到原型 / 构造器; getter / setter 用现有访问器槽位; 匿名类表达式按 ES 规则推断 `name`. `Function.prototype.toString` 对类构造器返回类定义的源码片段 (从 `class` 到类体结束的 `}`, 与 ES 规范一致; 附录 C Q1 已决: A), 由 P2.4 实现. 推翻点: P2 结束前. |
| T5 | 私有名的存储 | 每个 class 定义求值时为每个私有名创建唯一的 `PrivateNameKey` (不可枚举, 不进属性表的键空间), 存放在 `ScriptableObject` 的独立私有槽表中 (与 `SlotMapOwner` 并列的小表, 旧 Android 兼容按 AGENTS.md 属性表定制同样处理); 反射 API (Object.keys / getOwnPropertyNames / getOwnPropertySymbols / Proxy 陷阱 / JSON.stringify / for-in) 一律看不见; 品牌检查失败抛 TypeError (消息 10 语言). 私有方法与访问器放在类的私有品牌上, 不在实例上复制函数对象. 推翻点: P3 开始前. |
| T6 | Java 类继承经 JavaAdapter 的 fork 钩子 | 降糖时 `extends` 目标在运行时求值: 若是 `NativeJavaClass` (类或接口), 转入 `autojs6/JavaClassInheritance` 钩子: 用 `JavaAdapter.createAdapterCode` 生成 (基类 + 接口 + 覆盖方法集) 的适配器类 (签名缓存沿用 `JavaAdapterSignature`, Android 上经宿主 AndroidClassLoader 转 dex), `super(args)` 映射到 Java 父类构造器 (按 Rhino 现有的 Java 重载选择), `super.m()` 映射到 JavaAdapter 生成的 `super$m` 入口, 实例字段 (P3) 放在 JS self 对象上, 静态成员放在 JS 构造器上. `new X()` 的返回值形态与多接口写法见附录 C Q3 / Q4, 在 P5 开始前拍板. 不进上游 PR. 推翻点: P5 开始前. |
| T7 | async 建立在生成器机制之上 | async 函数体按生成器方式编译 (复用 `GeneratorState` / 生成器 Icode 与 Codegen 的生成器状态机), `await` 降为带 "awaiter" 的 yield; 驱动器 (`AsyncFunctionDriver`) 在 Promise 落定的微任务里恢复执行; 返回 NativePromise. async 生成器在此之上加请求队列与 AsyncGenerator 原型; for await 用 AsyncFromSyncIterator 包装同步迭代器; 未捕获 rejection 经 `UnhandledRejectionTracker` 交给宿主 (附录 C Q6). 推翻点: P9 开始前. |
| T8 | V2 顺带补到能跑 class 基线 | 附录 C Q2 已决 (2026-10-24, 维护者选 C): 本路线图顺带把 Interpreter V2 补到能跑 class 基线, P2 范围据此扩大. 做法: P2 先盘点 class 降糖后用到的现有 IR 在 V2 上的缺口 (生成器, super 属性访问, new.target, 解构, 块级作用域等) 并补齐, 再按 V2 指令类风格实现新原语; P3 / P9 的新原语同样在 V2 实现. 验收用 fork 自加的 V2 测试模式 (系统属性开启, 不改上游默认矩阵) 跑 class 范围的 test262 与自带测试类, 通过数与仍缺的能力逐阶段登记. V2 中与 class / async 无关的周边能力 (如 continuation) 仍不在本路线图修. 推翻点: 无 (已拍板). |
| T9 | 内建精确化的统一入口 | 在 `BaseFunction` / `LambdaConstructor` / `IdScriptableObject` 的构造路径提供统一的 `ScriptRuntime.createFromConstructor(cx, nt, scope, defaultProto)` (OrdinaryCreateFromConstructor), 各 Native* 构造器改为用它拿原型; 两种风格 (LambdaConstructor 18 个, IdScriptableObject 42 个) 各做一个样板后批量套用; `Symbol.species` 相关 (Array / Promise / RegExp / TypedArray / ArrayBuffer / Map / Set) 核对现有实现是否读取 `this.constructor[Symbol.species]`. 推翻点: P4 开始前. |
| T10 | test262 的排除规则 | 范围内用例按目录解锁; 仅当用例 `features` 含本路线图范围外且 Rhino 未实现的特性 (decorators, explicit-resource-management, iterator-helpers, Array.fromAsync, import-defer, source-phase-imports, 以及 Rhino 整体未支持的模块语法 flags: [module]) 时排除, 逐条写入 `test262.properties` 的注释或本文附录 E. 不允许因 "难" 排除. `test262.properties` 用 `-DupdateTest262properties` 由工具重生成, 不手工编辑 (同步规程据此加一步: 先取上游版本再重生成). 推翻点: 无. |
| T11 | 错误消息 | 新增 msg key 一律同时补 9 个翻译 bundle (与 2026-10-24 同步的做法一致, MessageTranslationsTest 会校验), 文案用半角标点. 推翻点: 无. |
| T12 | 宿主侧 Promise 退场的方式 | 宿主先把 promise.js 的定制点逐条列表 (附录 C Q5 的备选项), 在 `RhinoJavaScriptEngine` 上以 Kotlin 给 NativePromise.prototype 挂宿主扩展 (`await` / `wait`), Loopers 在每个宏任务回调后与空转判定前调用 `processMicrotasks`, 然后把全局 `Promise` 换回 NativePromise 并删除 promise.js; 宿主自有 Promise 用例 (augment 的 promise 相关测试, `tools/augment/device_promise_check.py`) 作为回归. 推翻点: P9 宿主部分开始前. |

---

## 4. 红线 (所有阶段必须遵守)

- 不推送任何仓库; fork 与宿主都只本地提交. 不切分支, 不 stash / reset. fork 仓库不开额外 worktree.
- 保留 `AGENTS.md` 第二节列出的全部定制 (XML/UI, 本地化, Context/VMBridge, Android 反射容错, SlotMapOwner synchronized, ByteBuffer 经 java.nio.Buffer, 宿主接口, 打包). 新代码同样遵守 Android 兼容约束: minSdk 24, 不用 API 26+ 的 JDK 类 (java.util.Base64 之类靠宿主脱糖的例外要写明), ByteBuffer 状态操作经 `java.nio.Buffer`.
- 新增或改动的上游文件行必须带 `// AutoJs6:` 标记; 新功能尽量放新文件 (T2).
- fork 全量测试 (rhino / rhino-xml / rhino-engine / rhino-tools / tests) 在每阶段结束时跑一次, 用 `.python/junit_failures.py` 与上一阶段 (首次与 2026-10-24 同步基线, 见附录 A) 对照, 不允许新增失败; `spotlessCheck`, `git diff --check`, `.python/generate_markdown.py --check` 通过.
- 宿主工作区 `D:/idea-projects/AutoJs6` 多会话共用: 只 `git add` 自己的文件, 共享文件 (changelog JSON 与生成的 md) 只暂存自己的 hunk; 宿主构建与设备占用先在 `C:/Users/wu2du/AppData/Roaming/Claude/side-session-notes/local_8ab23cdc-d795-4973-9379-2554a76c2967/locks.md` 登记并错开; 不碰 `assets/modules` 以外会话在改的文件; 守护进程按 PID 结束, 不用 `gradlew --stop`; 手机上需人工确认的对话框由维护者操作.
- 宿主 changelog 只改 `.changelog/lang_zh-Hans.json` 当前开发版本, 其他语言由维护者同步; fork changelog 10 种语言都写, 用生成器重生成 md.
- 全部文案 (changelog, 注释, 错误消息, 文档) 用半角标点; 过程说明与汇报用简体中文.
- 不为了省额度在会话中途切换模型; 子会话用 claude-opus-5-5, effort xhigh.

---

## 5. 阶段任务

每阶段开始前读本节与附录 C 中标注该阶段的待决事项; 结束时追加证据, 本地提交 (fork 提交说明用英文, 格式参照 2026-10-24 的两个同步提交), 并把结论追加到看板 "子会话结果".

### P1 语法与 AST (fork)

- [x] P1.1 词法: `Token.CLASS` 等新 token 与 ES6 门控 (T3), 私有名 `#x` 扫描, 上下文关键字 `extends` / `static` / `async` 的处理; `CompilerEnvirons` 开关 (D4) 与 `Context` 侧的默认值.
- [x] P1.2 AST 新节点 (T2): ClassNode (名字, 超类表达式, 元素列表, 是声明还是表达式, 源码位置), ClassElement 各类 (方法 / getter / setter / 字段 / static block, static 标志, 计算键, 私有名), PrivateName; `toSource` 与 `visit` 实现; `ast` 包的 Node 类型注册.
- [x] P1.3 Parser: class 声明 (statement 位置, 含 `export` 之外的全部位置约束) 与 class 表达式 (primaryExpr), class 体解析复用对象字面量方法解析 (生成器方法, getter / setter, 计算键), 字段与 static block 解析, 私有名解析与作用域登记, `super()` 调用 (仅派生构造器) 与 `super.x` (方法, 字段初始化器, static block), `new.target`, class 体隐式严格模式, 匿名类名推断的 AST 标记.
- [x] P1.4 早期错误: 重复 constructor, constructor 为 getter / setter / 生成器 / 字段名, 未声明的 `#x`, 重复私有名 (getter + setter 配对除外), `super()` 出现在非派生构造器或非构造器中, 字段名 `constructor`, 静态字段名 `prototype`, `#constructor`, `delete this.#x`, 类名 `await` / `yield` 的上下文限制等, 消息 10 语言 (T11).
- [x] P1.5 async 语法先行解析 (方法, 函数, 箭头, await, for await, async 生成器), IRFactory 在 P9 之前对 async 节点抛 "尚不支持" 的清晰错误 (消息 10 语言), 保证 P9 前的失败是可读的而不是语法错误.
- [x] P1.6 测试: fork 新增 `ClassSyntaxParserTest` (AST 形状, toSource 往返, 早期错误) 与 `AsyncSyntaxParserTest`; test262 class 两目录中 `negative: SyntaxError` (parse 阶段) 的用例全部通过 (登记数量); 全量测试与基线对照 (附录 A) 无新增失败.
- [x] P1.7 建立 `docs/autojs6/class-syntax/HOOKS.md` (按 `AGENTS.md` 第七节 7.1 的内容: 钩子行清单, 新文件清单, 开关名, 自带测试类与模块, 依赖的上游机制, 编号链唯一性测试), 并写 `TokenUniquenessTest` 一类的编号链测试; 之后每阶段的提交都同步更新 HOOKS.md.
- 证据: 提交 hash, 新 token / AST 文件清单, 通过的负向用例数, HOOKS.md 条目数.
- 完成 (2026-10-24, 子会话 rhino-p1): 提交 b5eafcb3f (功能, 测试, 消息, HOOKS.md, NOTES.md) + 本条勾选提交.
  - 新 token: `Token.CLASS` / `PRIVATE_NAME` / `CLASS_ELEMENT` / `AWAIT` (链尾, `LAST_TOKEN = AWAIT + 1`); 开关 `Context.FEATURE_CLASS_SYNTAX` (23) / `FEATURE_ASYNC_SYNTAX` (24), `CompilerEnvirons.set/isClassSyntaxEnabled`, `set/isAsyncSyntaxEnabled`, 默认开, 需语言版本 >= 200.
  - 新文件: `ClassParser`, `AsyncParser`, `ClassLowering` (P1 只报 `msg.class.not.supported`), `AsyncLowering` (P1 只报 `msg.async.not.supported`), `ast/ClassNode`, `ast/ClassElement`, `ast/PrivateName`, `ast/AwaitExpression`, `ast/ForAwaitOfLoop`; 消息 27 个 (class 21, async 6), 10 语言.
  - test262 class 两目录 (只解析, `ClassSyntaxTest262ParseTest`): parse 阶段反例 1262 / 1262 拒绝; 正例 7101 / 7127 可解析, 其余 26 例是上游生成器 `yield` 解析缺口 (附录 C Q10); 跳过 module 13 例与 decorators 24 例 (T10). `test262.properties` 未改 (附录 C Q9).
  - HOOKS.md: 钩子行 127 (class syntax 77, async 50, 9 个上游文件, 表内 43 行登记), 新文件 9 个, 自带测试类 6 个; `HooksRegistryTest` 对照通过.
  - 自带测试: rhino 模块 ClassSyntaxParserTest 61, AsyncSyntaxParserTest 20, TokenUniquenessTest 4, HooksRegistryTest 2, MessageTranslationsTest 36 (含新 key) 全部通过; tests 模块 ClassSyntaxTest262ParseTest 通过.
  - 全量 (JDK 21, 五个模块): rhino 4094 (0 失败), rhino-xml 6, rhino-engine 43, rhino-tools 21 (1 失败, 同基线), tests 121076 (54 失败); 失败集与附录 A 基线 55 项完全相同, 无新增. 另有 9 个登记为失败的 test262 用例现在通过 (await 作标识符 / 标签, yield 换行 *, `for (async of ...)`), P6.1 重生成时去掉.
  - fork changelog 不在 P1 写: P1 没有用户可见的可执行功能, 按 P7.3 / P9.7 写 10 语言 changelog.
  - 交接要点与留给 P2 / P3 / P9 的早期错误清单见 `NOTES.md`.

### P2 ES2015 核心: 降糖, 运行时原语, 三个后端 (fork)

- [x] P2.1 `ClassLowering` (T1): class 声明 / 表达式降糖为 "类作用域 + 构造器函数 + 原型方法定义 + 静态方法定义 + 类名内绑定 (const 语义, TDZ)"; 默认构造器 (基类 `constructor() {}`, 派生类 `constructor(...args) { super(...args) }`); 计算键按源码顺序求值一次; `extends` 表达式求值与校验 (null, 非构造器, prototype 不是对象或 null 时的 TypeError).
- [x] P2.2 运行时原语 (T1 / T4): 设置 home object; 不可枚举方法 / 访问器定义; 类构造器调用检查 (`msg.class.ctor.call`); 派生构造器的 this 未初始化状态, `super()` 构造 (以当前 new.target 调父构造器, 结果绑定为 this, 重复调用抛 ReferenceError), 构造器返回值规则 (对象 / undefined / 其他 的 TypeError); `BaseFunction.construct` 与 `createObject` 改为按 new.target 原型创建 (T9 的统一入口, 本阶段先覆盖脚本函数与 Object / Function, 其余在 P4).
- [x] P2.3 三个后端 (D5): Interpreter 新 Icode 与处理; Codegen (optimizer/BodyCodegen) 对应生成; V2 新指令类 (T8). `new.target` 在构造器 / 箭头函数 / eval 中的传递核对. V2 按附录 C Q2 = C: 先盘点并补齐 class 降糖依赖的现有能力 (生成器, super 属性访问, new.target 等), 使 V2 能跑 class 基线, 再加新原语; 缺口清单与补齐结果登记在本条证据.
- [x] P2.4 名称与长度: `name` (含匿名类名推断, 静态 `name` 方法覆盖时的规则), `length`, `prototype` 不可写, `constructor` 回指; `typeof` 为 function; `Function.prototype.toString` 按附录 C Q1 的拍板实现.
- [x] P2.5 测试: fork 新增 `ClassSyntaxTest` (每条语义一组用例, 解释 / 编译 / V2 三模式); test262 核心 2414 例在解释与编译模式 100% (T10 排除登记), V2 按 T8; 全量测试与基线对照无新增失败; `benchmarks` 模块加 class 实例化与方法调用的微基准, 与 function + prototype 写法对照 (附录 A 登记). 硬门槛 (附录 C Q8 = B): class 方法调用慢于 function + prototype 写法超过 10% 即本阶段不通过.
- 证据: 提交 hash, test262 两目录核心部分的通过数 / 排除清单, 基准数值.
- 完成 (2026-10-25, 子会话 rhino-p2): 提交 b3e9405cd (功能, 测试, 消息, 基准, HOOKS.md, NOTES.md) + 本条勾选提交.
  - 降糖与原语 (P2.1 / P2.2): `ClassLowering` 把 class 降为 `Token.CLASS` 节点, `ClassOp` 区分 9 种原语 (0-8: DEFINE, SUPER_CALL, THIS, TDZ, CTOR_CHECK, ITERATOR_REST, REQUIRE_OBJECT, ITERATOR_CLOSE, PARAM_SCOPE), 运行时全部在 `ClassRuntime`; 与 class 无关但 test262 class 目录依赖的绑定修复在 `BindingLowering` (参数作用域与参数 TDZ, 参数数组解构的迭代器协议, for-of 的 IteratorClose). T9: 脚本函数本来就按 new.target 的 prototype 创建, Object 加一行钩子, Function 本来就按 new.target 创建; 其余内建留给 P4.
  - 三个后端 (P2.3): 解释器 `ClassInterpreter` (icode `CLASS_DEFINE`..`CLASS_PARAM_SCOPE` 共 9 个, 接在上游链尾), 编译器 `ClassBodyCodegen`, V2 指令类 12 个 (`interpreterv2/instruction/Class*`; 附录 C Q11 已按 A 先行: 只有指令类与单测 `ClassInstructionsTest`, V2 端到端 0 例). new.target 核对: 构造器, 箭头函数 (含箭头函数里的 `super()`), `Reflect.construct` 传入的 new.target 都正确; eval 代码里的 new.target 与 `super()` 不可用 (上游现状, 附录 C Q12 已按 A 先行).
  - 生成器 yield (附录 C Q10 = A): Parser 钩子修好 ES6 生成器里未加括号的 yield (逗号后, 实参, 条件分支, 对象展开, 计算键, `yield a, yield b;`, `yield*` 后换行), `KNOWN_PARSE_GAPS` 已清空; 放开解析后暴露的上游编译模式缺陷 (yield 点把栈上的 int / `LookupResult` 存进 `Object[]` 后类型丢失, VerifyError) 由新文件 `classfile/StackSpills` 修复; NodeTransformer 把对象展开项变换两次的上游缺陷一并修复.
  - 名称与长度 (P2.4): 匿名类按绑定名推断, 静态 `name` 方法覆盖推断名, `length`, `prototype` 不可写, `constructor` 回指, `typeof` 为 function, class 代码里的函数访问 `caller` / `arguments` 抛 TypeError (其他函数保持 Rhino 原行为), `Function.prototype.toString` 返回类定义源码 (附录 C Q1 = A).
  - test262 class 两目录核心 (`ClassSyntaxTest262CoreTest`, fork 自有执行测试, 附录 C Q9): 解释与编译模式各运行 2428 例, 各通过 2419; 其余 9 例登记在 `KNOWN_FAILURES` 与附录 E (4 例 P4 内建子类, 5 例 P9 async 箭头函数, 两种模式相同), 除此之外 100%. 运行数按 `LATER_PHASES` (P3 / P9 特性) 与 `UNSUPPORTED` (T10) 选出, 比第 1 节按目录估计的 2414 多 14 例.
  - test262 class 两目录只解析 (`ClassSyntaxTest262ParseTest`): 反例 1262 / 1262 拒绝, 正例 7127 / 7127 可解析 (P1 时 7101, Q10 的 26 例缺口已修).
  - 自带测试 (rhino 模块): ClassSyntaxTest 22, ClassSyntaxParserTest 76, ClassInstructionsTest 14, ClassChainUniquenessTest 2, HooksRegistryTest 2, TokenUniquenessTest 4, AsyncSyntaxParserTest 20, MessageTranslationsTest 36 (含新 key) 全部通过 (解释 / 编译两模式).
  - 微基准 (附录 A): class 方法调用 解释 -1.5%, 编译 +1.4% (硬门槛 10%, 附录 C Q8 = B, 通过); super 方法调用比 `Base.prototype.m.call(this)` 写法快 19% / 25%; 实例化 +3.9% / +2.0%.
  - HOOKS.md: 钩子行 330 (class syntax 280, async 50), P2 新增 203 行; P2 新文件 22 个 (main 18, 测试 4) 与基准 2 个; 消息 P2 新增 11 个, 10 语言; `HooksRegistryTest` 与 `ClassChainUniquenessTest` 通过.
  - 全量 (JDK 21, 五个模块): rhino 4147 (0 失败, 4 跳过), rhino-xml 6, rhino-engine 43, rhino-tools 21 (1 失败, 同基线), tests 121078 (54 失败, 16 跳过); 失败集与附录 A 基线 55 项完全相同, 无新增 (`build/upstream-sync/class-p2-20261025/`). 第一轮全量发现的回归 (对象方法 / eval 里的 `super()`, for-of 迭代表达式的作用域, JS 1.7 推导式里的 for-of, 普通函数的 `caller` / `arguments`) 已在提交前修复. `Test262SuiteTest` 另有 927 个登记为失败的文件现在通过 (P1 时 47 个之外), P6.1 重生成时去掉, 目录统计与普通生成器清单见 NOTES.md.
  - 已知限制见 `HOOKS.md` 第 8 节 "P2"; 结构, 给 P3 的接口与新通过的 test262 用例见 `NOTES.md` 的 P2 一节.
  - fork changelog 不在 P2 写 (按 P1 的决定, P7.3 / P9.7 写 10 语言 changelog).

### P3 字段, static block, 私有名 (fork)

- [x] P3.1 公共字段: 实例字段初始化器 (基类在构造器开头, 派生类在 `super()` 返回后立即执行; 初始化器是独立的函数作用域, this / super / new.target / arguments 规则), 静态字段 (类定义求值时, this 为构造器), 计算字段键的求值顺序, `[[Define]]` 语义 (不触发 setter).
- [x] P3.2 static block: 求值时机与顺序 (与静态字段交织), 作用域 (var / await / return 的限制), this 为构造器.
- [x] P3.3 私有名 (T5): 私有字段 / 方法 / 访问器 / 静态私有成员, `#x in obj`, 嵌套类的私有名作用域, 重复安装同一私有字段抛 TypeError, 品牌检查 TypeError, 反射不可见 (Object.keys / getOwnPropertyNames / getOwnPropertySymbols / Proxy 所有陷阱 / JSON.stringify / for-in / Object.assign / 展开), `Object.freeze` 后仍可写私有字段, Java 互操作 (NativeJavaObject 作为 this 时的品牌检查) 的行为登记.
- [x] P3.4 三个后端的新原语处理 (字段初始化器调用, 私有名定义 / 读 / 写 / in); V2 同 P2.3 (附录 C Q2 = C), 字段与私有名依赖的现有能力在 V2 上缺失时一并补齐.
- [x] P3.5 测试: `ClassFieldsTest`, `PrivateNamesTest`; test262 字段 673 + 私有名 4016 在解释与编译模式 100% (T10 排除登记); 全量回归无新增失败.
- 证据: 提交 hash, 通过数 / 排除清单.
- 完成 (2026-10-25, 子会话 rhino-p3): 提交 c89818fc8 (功能, 测试, 消息, HOOKS.md, NOTES.md, 附录 C / E) + 本条勾选提交.
  - 字段与 static block (P3.1 / P3.2): 每个字段初始化器与 static block 降为一个方法 (home object 为 prototype 或 F, 所以 `super.x` 可用, this / new.target / arguments 规则随函数作用域), 键与方法一起按源码顺序求值一次, 记进构造器的 `JSFunction.classData` (`ClassRuntime.ClassData`); 基类在构造器体开头 (INIT_FIELDS), 派生类在 `super()` 绑定 this 之后执行 InitializeInstanceElements; 静态字段与 static block 在类名绑定初始化之后按源码顺序以 F 为 this 执行 (STATIC_INIT); [[Define]] 语义; 匿名函数 / 类按字段名命名; static block 里 `arguments` / `return` / `await` 是早期错误.
  - 私有名 (P3.3, T5 可行, 不登记推翻): 每个私有名在类作用域有隐藏绑定 `#名字@序号`, 每次类求值新建 `PrivateNameKey`; `o.#x` 降为以该绑定为键的元素访问, `ScriptRuntime` 的元素读 / 写 / 调用 / `in` 在入口转 `ClassRuntime`. 私有元素表是 `ScriptableObject` 的独立字段 (新文件 `PrivateElements`: 写时复制的两个数组, 读不加锁, 添加在对象监视器下, 不用 VarHandle, 与 `SlotMapOwner.ThreadedAccess` 同样的 Android 兼容做法); 其他 `Scriptable` 用弱引用标识表, Java 对象包装按被包装的 Java 对象记 (NativeJavaObject 作为 this 时的行为登记在 HOOKS.md 第 8 节). 私有方法 / 访问器的函数在键上, 对象只有类的品牌. 反射 (Object.keys / getOwnPropertyNames / getOwnPropertySymbols / Reflect.ownKeys), Proxy 陷阱, JSON.stringify, for-in, Object.assign, 展开都看不到私有元素; freeze / preventExtensions 之后仍可安装与写入. 品牌检查与重复安装抛执行代码所在 realm 的 TypeError (test262 realm 用例要求). 直接 eval 看得到外层私有名 (附录 C Q13 已按 A 先行).
  - 后端 (P3.4): 新 ClassOp 3 个 (9-11: PRIVATE_NAME, INIT_FIELDS, STATIC_INIT) 与元素标志 FIELD / PRIVATE / NAMED / NO_VALUE / BLOCK; 解释器 icode `CLASS_PRIVATE_NAME` / `CLASS_INIT_FIELDS` / `CLASS_STATIC_INIT` 接在 fork 链尾 (`MIN_ICODE = CLASS_STATIC_INIT`), 编译器 `ClassBodyCodegen` 对应分支; V2 按 Q11 = A 新增指令类 `ClassPrivateName` / `ClassInitFields` / `ClassStaticInit` 并让 `ClassElement` 支持无初始化器的字段, 单测在 `ClassInstructionsTest` (18 例), V2 端到端 0 例.
  - 顺带修复 (test262 class 目录依赖): 对象解构赋值里的成员表达式目标先求值再读属性, 不在任何非箭头函数里的箭头函数用 `new.target` 报 SyntaxError, `o?.#m()` 在 o 为 null 时结束可选链.
  - test262 class 两目录 (`ClassSyntaxTest262CoreTest`, P3 起只跳过 async 与 T10): 解释与编译模式各运行 5700 例, 各通过 5677, 两种模式相同. 字段 612 例运行通过 606, 私有名 2660 例运行通过 2652, 核心 2428 / 2419 同 P2; 未通过的 23 例都是 `KNOWN_FAILURES` (附录 E: P4 4 例, P9 19 例, 其中 P3 新登记的 14 例是没有 async 标签的私有 async 方法 / 字段计算键里的 async 箭头函数 / static block 里的 async 函数). 第 1.4 节的字段 673 例 = 运行 612 + async 标志 56 (P9) + module 5 (T10); 私有名 4016 例 = 运行 2660 + async 1354 (P9) + `nonextensible-applies-to-private` 2 (T10 排除, 附录 C Q14 已按 A 先行, 附录 E). 只解析 (`ClassSyntaxTest262ParseTest`): 反例 1262 / 1262, 正例 7127 / 7127.
  - 自带测试 (rhino 模块): ClassFieldsTest 12, PrivateNamesTest 15 (新), ClassSyntaxTest 22, ClassSyntaxParserTest 76, ClassInstructionsTest 18, ClassChainUniquenessTest 2, HooksRegistryTest 2, TokenUniquenessTest 4, AsyncSyntaxParserTest 20, MessageTranslationsTest 36 全部通过 (解释 / 编译两模式).
  - HOOKS.md: 钩子行 368 (class syntax 318, async 50), P3 新增 40 行; P3 新文件 7 个 (main 5: `PrivateNameKey`, `PrivateElements`, 3 个 V2 指令类; 测试 2); 消息删除 `msg.class.not.supported`, 新增 8 个 (私有元素的读 / 写 / 重复安装 / 方法只读 / 缺 getter / 缺 setter, 字段定义失败), 10 语言; `HooksRegistryTest` 与 `ClassChainUniquenessTest` 通过.
  - 全量 (JDK 21, 五个模块): rhino 4178 (0 失败, 4 跳过), rhino-xml 6, rhino-engine 43, rhino-tools 21 (1 失败, 同基线), tests 121078 (54 失败, 16 跳过); 失败集与基线 55 项完全相同, 无新增 (`build/upstream-sync/class-p3-20261025/`). `Test262SuiteTest` 另有 26 个登记为失败的文件现在通过 (共 1000 个, P6.1 重生成时去掉), 清单见 NOTES.md.
  - 门禁: `spotlessCheck`, `git diff --check`, `.python/generate_markdown.py --check` 通过. 已知限制见 `HOOKS.md` 第 8 节 "P3"; 结构与给 P4 / P5 / P9 的接口见 `NOTES.md` 的 P3 一节. fork changelog 不在 P3 写 (P7.3 / P9.7).

### P4 内建构造器按 new.target 精确创建 (fork, D2)

- [x] P4.1 盘点: 60 个 Native* 文件中带 JS 构造器的清单 (预计 35-39 个), 标注风格 (LambdaConstructor / IdScriptableObject / 其他), 现有 `construct` / `js_construct` 的创建方式, 是否已读 species; 写入附录 D.
  - 完成 (2026-10-25, 子会话 rhino-p4, 盘点提交见 P4 证据): 60 个 Native* 中带 JS 构造器的 37 个 (核心 21 + NativeJavaClass, typedarrays 15), 附录 D 表 36 行 (含 Native* 以外的构造路径, 12 个具体 TypedArray 与 6 个原生错误各合一行). 风格: 上游已改为 ClassDescriptor (JSFunction), 只剩 NativeSymbol 是 LambdaConstructor, 没有 IdScriptableObject 风格的内建构造器; 上游 `ScriptRuntime.findPrototype` 已让 20 个构造器按 new.target 取原型. 状态: 已对只核对 5 行, 待核对 (走统一入口加用例) 6 行, 待修 20 行, P4.3 拆脚手架 1 行, P5 / P9 / 范围外 (E4X) / 不可构造各 1 行 (逐行见附录 D). species: Array / Map / Set / Promise / RegExp / 各 TypedArray 有, ArrayBuffer 缺. test262 基线: 两模式各 33 例失败 (附录 D 表后).
- [ ] P4.2 统一入口 (T9) `createFromConstructor` 与两个样板 (一个 LambdaConstructor 风格如 NativeMap, 一个 IdScriptableObject 风格如 NativeError), 带测试后批量套用到清单全部构造器; 特殊项: Array 子类的 exotic length, Error 家族的 stack / name / message 与 `Error.captureStackTrace` 式宿主定制, Function / GeneratorFunction 子类, Promise 子类 (then / species), RegExp (species, lastIndex), Date, Boolean / Number / String 包装, Symbol / BigInt (不可 new, 子类化抛 TypeError 的位置), Map / Set / WeakMap / WeakSet (构造器调用被覆盖的 `set` / `add`), ArrayBuffer / DataView / 各 TypedArray (species, 子类的 `of` / `from`), WeakRef / FinalizationRegistry, Proxy (无 prototype, extends Proxy 的 TypeError), NativeObject, NativeJavaClass (转 P5 钩子), NativeContinuation / NativeGenerator 等不可直接构造者的行为核对.
- [ ] P4.3 拆掉 P2 遗留的 "构造后 setPrototypeOf" 脚手架 (若有).
- [ ] P4.4 测试: `BuiltinSubclassingTest` (每个构造器至少一条子类化用例, 覆盖 instanceof / 原型方法 / species / 自定义成员); test262 `subclass-builtins` 72 例与 `built-ins/*` 中涉及 newTarget 的用例 (用 `grep -l newTarget` 盘点并登记) 100%; 全量回归无新增失败.
- 证据: 提交 hash, 清单与逐项状态 (附录 D), 通过数.

### P5 继承 Java 类与接口 (fork 钩子层, D3 / T6)

- [ ] P5.0 进入前拍板附录 C Q3 (多接口写法) 与 Q4 (`new X()` 返回值形态); 结果回填 T6.
- [ ] P5.1 `autojs6/JavaClassInheritance`: 降糖后的 extends 目标为 NativeJavaClass 时的分派; 适配器类生成 (基类 + 接口 + 被覆盖方法集, 签名缓存), `super(args)` 到 Java 构造器, 覆盖方法的参数 / 返回值转换 (沿用 Rhino Java 互操作的转换规则), `super.m()` 到 `super$m`, 抽象方法未实现时的错误 (调用时 TypeError 还是定义时, 登记), 字段 / 静态成员 / 私有名在 JS self 上的行为, `instanceof` (对 Java 类与对 X 两者都成立), `toString` / `equals` / `hashCode` 覆盖, 线程回调 (Java 回调进入 JS 方法时的 Context 关联与宿主线程规则).
- [ ] P5.2 Android: 生成的类字节码经宿主 `AndroidClassLoader` / `ClassFileDexer` 加载的路径核对 (fork 内只到 `createAdapterCode` 与类加载器调用, 宿主已有 D8 转换), 缓存 (同一类定义多次求值不重复生成), 打包 APK (inrt, 混淆) 下的可用性作为 P7 用例.
- [ ] P5.3 错误消息 (10 语言): extends Java 类但调用 `super()` 前访问 this, final 类 / final 方法, 多接口写法错误, 构造器参数无匹配重载.
- [ ] P5.4 测试: `JavaClassInheritanceTest` (JVM: 继承 java.lang.Thread / java.util.AbstractList / 自定义抽象类 / 接口 Runnable + Comparable 等, super 调用, 字段, 静态成员, instanceof, 回调线程); 全量回归无新增失败. 真机部分在 P7.
- 证据: 提交 hash, 用例清单, Q3 / Q4 的拍板记录.

### P6 test262 解锁与全量回归 (fork)

- [ ] P6.1 以 `-DupdateTest262properties` 重生成 `tests/testsrc/test262.properties`, 解锁 class 两目录与 P4 涉及的 built-ins 用例; 排除项按 T10 逐条登记到附录 E.
- [ ] P6.2 三模式矩阵: 解释与编译模式按上游 Test262SuiteTest 现有做法; V2 用 fork 自加的测试模式 (系统属性开启, 不改上游默认矩阵) 跑 T8 范围, 结果登记.
- [ ] P6.3 全量测试 (五个模块) 与 2026-10-24 基线 (附录 A) 对照: 新增失败为 0; 既有 54 项基线差异不变 (或减少并登记原因).
- [ ] P6.4 `spotlessCheck`, `git diff --check`, `.python/generate_markdown.py --check`; 微基准复测 (P2.5 的对照) 并登记, 硬门槛 10% 同 P2.5 (附录 C Q8 = B).
- [ ] P6.5 同步演练: 用 `git merge --no-commit` 对最新 `upstream/master` 做一次干跑 (不提交, 只记录冲突文件与行数, 然后 `git merge --abort`), 验证 T2 的钩子式结构, 结果登记到附录 A 的 "冲突面" 一行. 注意: 这是本路线图中唯一允许的 merge 操作, 且必须 abort.
- 证据: 提交 hash, test262.properties 的 diff 统计, 基线对照输出, 干跑冲突统计.

### P7 宿主集成与真机 (宿主 + fork jar)

- [ ] P7.1 按 `AGENTS.md` 第四节构建完整 jar (JDK 25 shadowJar, slim 排除项不变), 记录 sha256 与条目差异; 宿主 `libs/org-mozilla-rhino-2_0_0-SNAPSHOT.jar` 换入 (隔离克隆先验: `:app:testAppDebugUnitTest :app:assembleAppDebug :app:assembleInrtDebug :app:assembleAppDebugAndroidTest`, 守护进程带 `-Daj6.session=<名>`, 用完按 PID 结束).
- [ ] P7.2 宿主设备用例: `RhinoClassSyntaxDeviceTest` (class 核心, 字段与私有名, 内建子类化 Error / Array / Map, Java 类继承: `extends java.lang.Thread`, `extends android.os.Handler` 或 `implements Runnable` 的回调, ui 模式下继承 View 类的一个用例, 打包 APK 路径由 inrt 构建产物在同一台设备上验证), `tools/rhino/device_rhino_check.py` 的 DEFAULT_CLASSES 加入新类; API 24 与 API 35 模拟器 (临时 AVD, 用后删除, 配方见 2026-10-24 同步记录) 各一台 + 一台真机 (设备按 `COMMON_AGENTS.md` 可用测试设备, locks.md 登记; 装回原包核对 md5).
- [ ] P7.3 宿主 changelog zh-Hans (feature: class 语法; feature: 继承 Java 类; improvement / dependency: Rhino 版本行); fork 10 语 changelog 与生成 md; 宿主本地提交 (只含 jar, 新用例, 脚本, changelog).
- [ ] P7.4 文档站 AutoJs6-Documentation: 新增 "class" 章节 (语法支持范围, 内建子类化, Java 类继承的写法与限制, 与 JavaAdapter 的关系, 版本要求); TypeScript Engine 与 Ace Editor 插件 README 各一句 (按各仓库惯例本地提交, 推送按插件推送策略由子会话判断是否需要维护者指示).
- 证据: jar sha256, 宿主提交 hash, 设备结果 (型号 / API / 通过数), 文档提交 hash.

### P8 上游 PR 与同步规程 (fork)

- [ ] P8.1 定制清单核对: `docs/autojs6/class-syntax/HOOKS.md` (P1.7 建立, 各阶段更新) 与源码逐条对账 (`rg -n "AutoJs6: (class syntax|async)" rhino/src/main/java` 的数量与文件一致, 新文件清单完整, 唯一性测试齐全); `AGENTS.md` (本地忽略文件, 不入库) 第七节已于 2026-10-24 写好保护与退役规程, 本阶段只按实际落地情况修订其中的测试类名, 模块名与命令, 并把第五节验收项与 HOOKS.md 对齐.
- [ ] P8.2 在 `E:/.cache` 导出干净的 `upstream/master` 克隆, 以 patch 方式移植通用部分 (不含 `autojs6/` 包与 docs/autojs6), 拆成 "语法 + AST", "降糖 + 运行时 + 后端", "字段与私有名", "内建精确化", "test262 解锁" 五个提交, 在该克隆上跑上游全量测试与 spotless; 开 draft PR 到 mozilla/rhino 引用 #835 (PR 文案由维护者确认后由维护者推送 / 开 PR, 子会话只准备好分支与文案); 评审反馈作为待决事项登记到看板.
- [ ] P8.3 同步规程演练: 按 `AGENTS.md` 全流程对最新 upstream/master 做一次真实同步 (若有新提交), 验证 P6.5 的结论与新规程步骤.
- 附记 (附录 C Q10 = A): P2 修好的生成器 `yield` 解析缺口 (未加括号的 `yield` 出现在逗号表达式 / 条件表达式分支 / 对象展开 / 计算属性名里) 不依赖 class, 作为通用修复单独提上游 PR.
- 证据: AGENTS.md 提交 hash, 克隆路径与五个提交 hash, PR 文案位置.

### P9 async/await (fork + 宿主, D7 / D13 / D14 / D15 / D16)

- [ ] P9.0 进入前拍板附录 C Q5 (promise.js 保留项), Q6 (未捕获 rejection), Q7 (脚本结束判定); 结果回填 T7 / T12.
- [ ] P9.1 fork: async 函数 / 方法 / 箭头函数与 await 的降糖与驱动器 (T7), 返回 NativePromise, 微任务排队, `await` 的 thenable 解析 (对宿主 Promise 与 Java Future 的包装行为登记), 栈与错误位置 (await 之后的异常栈), 三个后端; V2 同 P2.3 (附录 C Q2 = C), async 降糖依赖的生成器状态机在 V2 上缺失时一并补齐.
- [ ] P9.2 fork: for await (同步迭代器经 AsyncFromSyncIterator, 异步迭代器协议, 解构变体), Symbol.asyncIterator, AsyncIteratorPrototype.
- [ ] P9.3 fork: async 生成器 (请求队列, yield / yield* 对异步迭代器的委托, return / throw, AsyncGeneratorFunction / AsyncGeneratorPrototype), class 与对象字面量中的 async 方法 / async 生成器方法, 以及 P1.5 留下的 "尚不支持" 错误的移除.
- [ ] P9.4 fork 测试: `AsyncFunctionTest`, `AsyncGeneratorTest`, `ForAwaitTest`; test262 async 范围 3991 例 100% (T10 排除登记); 全量回归无新增失败; 微基准 (await 一个已落定 Promise 的往返开销) 登记, class 方法调用对照复测仍须满足 10% 硬门槛 (附录 C Q8 = B).
- [ ] P9.5 宿主 (T12): promise.js 定制点清单与对应实现 (NativePromise.prototype 的宿主扩展 `await` / `wait`, Q5 结论), Loopers 逐宏任务 `processMicrotasks` 与空转判定, 全局 Promise 换回 NativePromise 并删除 promise.js, 未捕获 rejection 的宿主处理 (Q6), 脚本结束判定 (Q7); 宿主 promise 相关单测与 `tools/augment/device_promise_check.py` 回归; Augment 路线图 P4.2 条目回写为 "由 rhino-class P9 完成".
- [ ] P9.6 宿主设备用例 `RhinoAsyncDeviceTest`: await 计时器 / 宿主 API Promise 的时序, 与 continuation.await 的互操作 (D15), 线程模块中的 async 函数, 脚本结束与退出码, 未捕获 rejection 的控制台输出; 三台设备同 P7.2.
- [ ] P9.7 jar 重建与宿主换入 (同 P7.1), 宿主 changelog zh-Hans (feature: async/await; improvement: Promise 统一; 行为变化提示), fork 10 语 changelog, 文档站 "async/await 与 continuation" 章节, 插件 README 补 async.
- [ ] P9.8 上游 PR 补一批 (async/await 通用部分), 方式同 P8.2.
- 证据: 提交 hash, 通过数, 设备结果, 文档与 PR 文案位置.

---

## 附录 A 基线数据 (2026-10-24)

- fork 全量测试 (JDK 21, 24f8242cb): rhino 3971 (0 失败, 4 跳过), rhino-xml 6, rhino-engine 43, rhino-tools 21 (1 失败: JLineConsoleTest.barePrintlnWritesOnlyLineSeparator, Windows 上纯上游同样失败), tests 121075 (54 失败, 16 跳过; 54 项全部是既有 fork 差异: 28 test262, 19 __proto__/__parent__, 7 Proxy / Reflect / FunctionName / optional chaining / LambdaProperty). 对照脚本 `.python/junit_failures.py <results-root> [<other-root>]`.
- 2026-10-24 同步记录 (jar 前后 sha256, 测试日志, 失败对照, 临时 AVD 配方): `build/upstream-sync/const-sync-20261024/` (git 忽略目录).
- test262 范围统计见 1.4; `test262.properties` 中 class 两目录当前为 `~` 整目录排除 (4367 + 4059 全失败), async 相关目录亦为整目录排除 (async-arrow-function 42/60 失败).
- 冲突面 (P6.5 干跑后填写): 冲突文件数 / 行数 / 钩子点数.
- 微基准 (P2.5 填写; P6.4 / P9.4 复测): `benchmarks` 模块 `ClassBenchmark` + `testsrc/benchmarks/micro/class-benchmarks.js`, JMH avgt, 每次调用 1000 次操作, 单位 us/op, class 对 function + prototype 写法 (2026-10-25, JDK 21, 本机):
  - 方法调用: 解释 301.5 ± 25.0 对 306.0 ± 11.1 (-1.5%), 编译 28.60 ± 2.32 对 28.21 ± 1.08 (+1.4%); 10% 硬门槛通过 (附录 C Q8 = B).
  - super 方法调用 (`super.sum()` 对 `PointF.prototype.sum.call(this)`): 解释 490.5 ± 53.2 对 607.4 ± 99.7 (-19%), 编译 55.45 ± 3.40 对 74.08 ± 6.65 (-25%).
  - 实例化: 解释 280.8 ± 26.8 对 270.2 ± 21.4 (+3.9%), 编译 80.40 ± 0.84 对 78.83 ± 6.13 (+2.0%).
  - 派生类实例化 (`super(x, y)` 对 `PointF.call(this, x, y)`): 解释 531.7 ± 47.3 对 549.8 ± 36.2 (-3.3%), 编译 153.3 ± 17.6 对 158.0 ± 6.2 (-3.0%).
  - await 往返: P9.4 填写. 原始输出 `build/upstream-sync/class-p2-20261025/bench-class.txt` (git 忽略目录).

## 附录 B 设备矩阵

- JVM: JDK 21 (测试), JDK 25 (shadowJar, 见 AGENTS.md 第四节); 解释 / 编译 / V2 三模式.
- Android 模拟器: 临时 AVD API 24 (google_apis x86) 与 API 35 (google_apis x86_64), 高端口需 `adb connect 127.0.0.1:<端口+1>`, 用后关闭并删除 AVD 目录与 ini.
- 真机: 按 `COMMON_AGENTS.md` 可用测试设备, 用前在 locks.md 登记, 测后装回原包并核对 md5; 需人工确认的对话框由维护者操作.

## 附录 C 待决事项

格式: 编号 问题 | 选项 (推荐项标 "(推荐)") | 影响条目 | 拍板时机. 由调度会话用选择题请维护者拍板, 不自行采用默认值.

- Q1 已决 (2026-10-24, 维护者选: A 返回类定义源码片段; 已回填 T4 / P2.4) class 的 `Function.prototype.toString` 返回什么 | A (推荐) 返回类定义源码片段 (Rhino 保留源码时) 与 ES 规范一致; B 返回 `class X { [native code] }` 形式; C 沿用现有函数风格字符串 | P2.4 | P2 开始前
- Q2 已决 (2026-10-24, 维护者选: C 本路线图顺带把 V2 补到能跑 class 基线, P2 范围扩大; 已回填 T8 / P2.3 / P3.4 / P9.1) 若 P2 时 Interpreter V2 跑不通现有生成器 / super 基线怎么办 | A (推荐) V2 原语照实现, 验收只跑 V2 当时能跑的子集并登记, V2 整体就绪由上游负责; B 暂不实现 V2 原语, 留到上游 V2 可用后补 (上游 PR 会被要求补齐); C 本路线图顺带把 V2 补到能跑 class 基线 (范围失控) | P2.3, P3.4, P9.1 | P2 开始前
- Q3 继承 Java 时的多接口写法 | A (推荐) `extends` 单一 Java 类或接口; 需要同时实现多个接口时用表达式 `extends JavaAdapter(Base, I1, I2)` (JavaAdapter 构造器作为超类工厂返回可被 extends 的适配器基类), 不新增语法; B 只支持单一 extends, 多接口仍用传统 `new JavaAdapter(...)`; C 非标准语法 `class X extends Base implements I1, I2` (上游化与编辑器 / TS 都不认, 不推荐) | P5.1 | P5 开始前
- Q4 `new X()` (X 继承 Java 类) 的返回值形态 | A (推荐) 返回适配器 Java 对象的包装, 其 JS 原型指向 X.prototype (JS 方法 / 字段 / instanceof X 都可用, 传给 Java API 即是该 Java 对象); B 返回 JS self 对象, Java 对象经 `x.javaObject` 之类属性取得 (传给 Java API 需显式取); C 与 JavaAdapter 现状一致返回 NativeJavaObject, JS 侧只能通过 Java 方法访问 | P5.1, P7.2 | P5 开始前
- Q5 promise.js 退场时保留哪些宿主扩展 | A (推荐) 在 NativePromise.prototype 上保留 `await()` (经 continuation) 与 `wait()` (settlement latch) 两个宿主扩展方法, `_immediateFn` 的宏任务调度改为规范微任务 (回调顺序与现在不同, changelog 写明); B 全部不保留, 文档引导改用 continuation.await(p) / async; C 连宏任务调度语义也保留 (偏离规范, 与 await 时序打架, 不推荐) | P9.5 | P9 开始前
- Q6 未捕获的 Promise rejection 怎么处理 | A (推荐) 控制台 error 级输出一次 (含栈), 脚本继续 (浏览器行为); B 终止脚本并按未捕获异常处理 (Node 15+ 默认); C 只在开发者选项开启时输出 | P9.5, P9.6 | P9 开始前
- Q7 脚本结束判定与挂起的 await | A (推荐) 没有待执行宏任务 / 定时器 / 监听时脚本结束, 未落定的 Promise 不保活 (Node 语义), 控制台不提示; B 同 A 但在有未落定 async 函数时输出一条提示; C 等待所有 async 函数落定 (可能永不结束, 不推荐) | P9.5, P9.6 | P9 开始前
- Q8 已决 (2026-10-24, 维护者选: B 硬门槛 10%, class 方法调用慢于 function + prototype 写法超过 10% 即阶段不通过; 已回填 P2.5 / P6.4 / P9.4) 性能门槛 | A (推荐) 不设硬门槛, 登记基准; 方法调用慢于 function + prototype 写法超过 20% 时登记为待决再议; B 设硬门槛 10%; C 不测 | P2.5, P6.4, P9.4 | P2 开始前
- Q9 已决 (2026-10-24, 维护者选: A, P1 不改 `test262.properties`, P2 起核心部分由 fork 自有执行测试验证; P1 子会话登记) P1 阶段是否重生成 `test262.properties` | A (推荐, 已先行) P1 不改 `test262.properties`, class 两目录仍为 `~` 整目录排除; P1.6 的 "parse 阶段反例全部通过" 由 tests 模块的 `ClassSyntaxTest262ParseTest` 只解析地验证 (反例 1262 / 1262 拒绝, 正例 7101 / 7127 可解析, 跳过 module 与 decorators 37 例), P6.1 按原计划重生成解锁; B 现在就用 `-DupdateTest262properties` 重生成解锁 class 两目录 (反例经编译期报错计为通过, 约 7100 个正例以 "暂不支持" 失败逐条写进 properties, P2-P6 每阶段都要重生成, properties 改动量大) | P1.6, P6.1 | 改选返工: B 约 1 小时 (重生成一次加全量对照); A 无 | P2 开始前
- Q10 已决 (2026-10-24, 维护者选: A, fork 在 P2 顺带修 Parser 的 `yield` 解析, P8 作为通用修复提上游 PR; P1 子会话登记) test262 class 两目录有 26 个正例因上游生成器解析的缺口不能解析 (未加括号的 `yield` 出现在逗号表达式 / 条件表达式分支 / 对象展开 / 计算属性名里; 同样写法在普通生成器里也失败, 上游 properties 里对应的生成器用例同样登记为失败), 清单在 `ClassSyntaxTest262ParseTest.KNOWN_PARSE_GAPS` | A (推荐) fork 在 P6 (或 P2 顺带) 修 Parser 的 `yield` 解析, 钩子带 `// AutoJs6: class syntax` 并登记 HOOKS.md, 普通生成器的同类用例一并转为通过, P8 作为通用修复提上游 PR; B 登记为附录 E 例外 (与 T10 "不允许因难排除" 冲突, 需维护者明确豁免); C 先给上游提 PR, 等合入后随同步获得 | P6.1 (class 范围 100%), P8 | 改选返工: A 约 30-80 行 Parser 钩子加测试; B 无; C 依赖上游节奏, 可能拖到 P6 之后 | P6 开始前
- Q11 已决 (2026-10-25, 维护者选: A 保持现状, 已实施; P2 子会话 rhino-p2 2026-10-24 登记, P3 按同样做法只写指令类) 上游 Interpreter V2 目前只有指令类与调用帧, 没有 IR 到 V2 指令的编译器 (`compileScript` 抛 `UnsupportedOperationException`, 优化级别里没有 V2 入口), Q2 = C 的 "把 V2 补到能跑 class 基线" 不写编译器就做不到 | A (推荐, 已先行) P2 只按 V2 风格为 9 个新原语 (`ClassOp` 0-8) 写 12 个指令类 (`interpreterv2/instruction/Class*.java`) 并单测 (栈变化, 调试串), V2 端到端运行与 `Test262SuiteTest` 的 V2 模式 等上游编译器合入后接上 (P3.4 / P9.1 同样只写指令类), P2 登记 V2 可运行 0 例; B fork 自写 IR 到 V2 的编译器 (估计 3000-5000 行, 与上游后续合入的编译器冲突, P8 上游化困难); C P2 不写 V2 指令类, 全部留到上游编译器可用后 | P2.3, P2.5, P3.4, P9.1 | 改选返工: B 约数天并需重做 V2 证据; C 删除 12 个指令类与对应单测 | P3 开始前
- Q12 已决 (2026-10-25, 维护者选: A 保持现状, 已实施; P2 子会话 rhino-p2 2026-10-25 登记; 类元素里的直接 eval 另见 Q13) eval 代码里的 `new.target` 与 `super()` 不可用 (P2.3 核对 "new.target 在构造器 / 箭头函数 / eval 中的传递" 时发现): Parser 只在函数内接受 `new.target`, eval 代码按脚本解析报 "Invalid use of new"; 派生构造器里的直接 eval `super()` 报 SyntaxError (规范允许, eval 代码不知道所在的函数); 普通函数的 `new.target` 同样, 上游 test262 `eval-code/direct/new.target-fn.js` 等在 properties 里登记为失败; class 两目录的 test262 核心不涉及 | A (推荐, 已先行) 记为已知限制 (HOOKS.md 第 8 节), P2 不实现, P6.1 重生成时这些用例保持失败登记, 列为 P8 上游候选; B 在 P6 补上 (Parser 对直接 eval 按调用处的函数放行 `new.target` 与 `super()`, 两个后端把调用者的 new.target 与派生构造器的 this 绑定传给 eval 脚本, 约 150-250 行钩子加测试) | P2.3, P6.1 | 改选返工: B 约半天; A 无 | P6 开始前
- Q13 已决 (2026-10-25, 维护者选: A 保持现状, 已实施; P3 子会话 rhino-p3 2026-10-25 登记) test262 字段 / 私有名用例要求类元素里的直接 eval 知道所在的元素: `direct-eval-err-contains-newtarget` (字段初始化器里 eval 用 new.target 合法, 值为 undefined), `*-err-contains-arguments` (字段初始化器与 static block 里 eval 引用 arguments 报 SyntaxError), `private-*-visible-to-direct-eval*` (eval 代码引用外层私有名); 按 Q12 = A 的现状 (eval 代码不知道所在的函数) 这些用例失败 | A (推荐, 已先行) 有限支持: 直接 eval 时沿作用域链收集外层私有名的隐藏绑定, 并从调用者的 `JSDescriptor.classElement` 得知它是方法 / 字段初始化器 / static block, 解析 eval 代码时据此放行私有名与顶层 `new.target` (降为 undefined, 这三种函数从不被构造), 字段初始化器与 static block 里拒绝 `arguments`; 普通函数与构造器里的 eval 仍按 Q12 = A (约 60 行: `ClassRuntime.evalContext`, `ClassParser` 两处, 4 行上游钩子) | B 不实现, 这些用例登记为附录 E 例外 (与 T10 "不允许因难排除" 冲突, 需维护者明确豁免) | P3.3, P3.5, P6.1 | 改选返工: A 改 B 删约 60 行与对应单测, 附录 E 登记相关用例; B 改 A 重做上述实现 | P6 开始前
- Q14 已决 (2026-10-25, 维护者选: A 保持现状, 已实施; P3 子会话 rhino-p3 2026-10-25 登记) class 两目录有 2 例带 stage 3 提案特性 `nonextensible-applies-to-private` (`statements/class/elements/private-class-field-on-nonextensible-objects.js`, `statements/class/subclass/private-class-field-on-nonextensible-return-override.js`; 全库另有 staging 与 import-defer 各 1 例): 提案让 PrivateFieldAdd / PrivateMethodOrAccessorAdd 在不可扩展对象上抛 TypeError, 与现行规范 (ES2025) 和 P3.3 的验收 "freeze / preventExtensions 之后仍可安装与写入私有元素" 相反 | A (推荐, 已先行) 按现行规范实现, 该特性按 T10 排除 (路线图范围外的提案, 与 decorators 同类; `ClassSyntaxTest262CoreTest.UNSUPPORTED`, 附录 E), 提案进入正式规范后另立条目改语义 | B 现在实现提案语义 (`PrivateElements.add` 前检查 isExtensible, 约 10 行, `PrivateNamesTest` 的 freeze 用例改为期望 TypeError) | P3.3, P3.5, P6.1 | 改选返工: A 改 B 约 1 小时; B 改 A 同样 | P6 开始前

## 附录 D 内建构造器清单 (P4.1 填写)

盘点 (P4.1, 2026-10-25, fork 80f957ac9): 第 1.1 节的 "18 个 LambdaConstructor 风格, 42 个 IdScriptableObject 风格" 已过时. 上游在 2026 年把内建迁到 `ClassDescriptor` 描述符: 构造器统一是 `JSFunction`, 实现签名 `(cx, f, nt, s, thisObj, args)`, 只剩 `NativeSymbol` 用 `LambdaConstructor`, 已没有 IdScriptableObject 风格的内建构造器. 上游已有近似统一入口 `ScriptRuntime.findPrototype(f, nt, type)` / `setBuiltinProtoAndParent(obj, f, nt, s, type)` (`nt` 是 JSFunction 时取 `prototype`, 其他可构造对象 Get `prototype`, 不是对象时退到 `nt` 声明作用域所在 realm 的同名内建原型), 20 个构造器已经用它按 new.target 取原型. 60 个 Native* 文件中带 JS 构造器的 37 个 (核心 21 + NativeJavaClass, typedarrays 15), 其余 23 个不可构造 (末行). 另有 Native* 以外的构造路径 (Function / GeneratorFunction, 6 个 NativeError, RegExp, JavaImporter, 脚本函数与 class, LambdaConstructor / FunctionObject 宿主构造器等) 一并登记. 基线: 新增的 `BuiltinSubclassingTest262Test` (P4.4) 在盘点时运行 class 两目录的 `subclass-builtins` 72 例与 `subclass/builtin-objects` 70 例, 以及 `built-ins` 里提到 newTarget 的 219 个文件, 两种模式各 33 例失败 (见表后的 test262 基线).

T9 落地方式 (未推翻): 统一入口放新文件 `BuiltinConstruction` (GetPrototypeFromConstructor 与 GetFunctionRealm: 绑定函数取目标的 realm, Proxy 取 target 的 realm, 已撤销的 Proxy 抛 TypeError), `ScriptRuntime.findPrototype` 两个重载改为一行钩子转调它, 已经调用 `setBuiltinProtoAndParent(obj, f, nt, ...)` 的 20 个构造器因此一并套用; 另在 `ScriptRuntime` 加 `createFromConstructor(cx, nt, scope, defaultProto)` 钩子 (OrdinaryCreateFromConstructor, 默认原型以 `TopLevel.Builtins` 给出, 跨 realm 时按 newTarget 的 realm 取同名内建原型), 供脚本函数 / class 的基类构造器使用. 普通 `new X()` (new.target 就是 X) 的快速路径: 直接取 X 自己的 `prototype` 字段, 不走属性查找, 不算 realm.

| 构造器 | 风格 | 现状 (创建方式 / species) | P4 状态 |
| --- | --- | --- | --- |
| Object (`NativeObject`) | ClassDescriptor | `setBuiltinProtoAndParent(res, f, nt, ...)`; P2 钩子: new.target 不是 Object 时忽略实参 | 待核对: 经 findPrototype 走统一入口 |
| Function (`BaseFunction.jsConstructor`) | ClassDescriptor (BaseFunction) | 编译后 `setBuiltinProtoAndParent(res, f, nt, ..., Function)`, 顺序同规范 (先解析后取原型) | 待核对: 统一入口 |
| GeneratorFunction (`BaseFunction`) | ClassDescriptor (BaseFunction) | 同 Function, 类型 GeneratorFunction; 新函数的 `prototype` 对象带了 `constructor` (上游缺陷, 普通生成器声明同样), test262 `subclass/builtin-objects/GeneratorFunction/instance-prototype.js` 因此失败 (附录 E 原登记为 P4) | 待修: 生成器函数的 `prototype` 不带 `constructor` |
| AsyncFunction / AsyncGeneratorFunction | - | 未实现 (P9) | P9; `built-ins/AsyncFunction/instance-construct-throws.js` 没有 async 标签, 登记附录 E (P9) |
| Array (`NativeArray`) | ClassDescriptor | 先建数组后 `setBuiltinProtoAndParent` (规范先取原型); species: `ArraySpeciesCreate` 读 `constructor[Symbol.species]` (`ArrayLikeAbstractOperations`); 子类 exotic length, `from` / `of` / `map` / `concat` / `slice` / `filter` 探针正确 | 待修: 先取原型 (顺序) |
| Error (`NativeError`) | ClassDescriptor | `make()` 先 `setBuiltinProtoAndParent` 后处理 message / cause (同规范); 子类实例的 stack, `Error.captureStackTrace`, message 自有属性探针正确 | 待核对: 统一入口, stack / name / message 用例 |
| EvalError, RangeError, ReferenceError, SyntaxError, TypeError, URIError (描述符在 `NativeGlobal`, 实现 `NativeError.js_constructor`) | ClassDescriptor | 共用 `js_constructor`, 类型一律传 Error: new.target 的 `prototype` 不是对象时退到 Error.prototype 而不是对应原生错误的原型 (test262 6 例 `NativeErrors/*/proto-from-ctor-realm.js`) | 待修: 按函数取对应类型 |
| InternalError, JavaException (Rhino) | ClassDescriptor | 同上 | 待修: 同上 |
| AggregateError (`NativeError.makeAggregate`) | ClassDescriptor | 先取原型, 同规范 | 待核对: 统一入口 |
| Boolean / Number / String | ClassDescriptor | 先转换值后取原型 (同规范) | 待核对: 统一入口, 包装对象子类用例 |
| Symbol (`NativeSymbol`) | LambdaConstructor (仅调用) | `new` 抛 TypeError; `class X extends Symbol` 可定义, `super()` 抛 TypeError (同规范) | 已对, 只核对 |
| BigInt (`NativeBigInt`) | ClassDescriptor | `new` 抛 TypeError, `super()` 同样 (同规范) | 已对, 只核对 |
| Date (`NativeDate`) | ClassDescriptor | 先算时间值后取原型 (同规范) | 待核对: 统一入口 |
| RegExp (`regexp/NativeRegExp`, 不在 60 个之内) | ClassDescriptor | `compile` 后 `setBuiltinProtoAndParent`; species: `Symbol.split` / `matchAll` 用 `speciesConstructor`; lastIndex 正确. 当函数调用时只认 `NativeRegExp`, 没有 IsRegExp (`Symbol.match`) 与 `pattern.constructor === RegExp` 的判断, 类 RegExp 对象不取 source / flags (test262 9 例, 其中 5 例提到 newTarget); `test` 不调用子类覆盖的 `exec` | 待修: 构造器的 IsRegExp 规则, `test` 走 RegExpExec (内建 exec 时保留快速路径) |
| Map / Set (`NativeMap` / `NativeSet`) | ClassDescriptor | 先从可迭代对象装入, 后取原型; 装入用 realm 全局 `Map.prototype.set` / `Set.prototype.add`, 不调用子类覆盖的 `set` / `add`; species 有 | 待修: 先取原型, adder 从新对象上 Get |
| WeakMap / WeakSet | ClassDescriptor | 同 Map / Set; 另有规范没有的 `Symbol.species` (上游现状, 不影响子类化, 不改) | 待修: 同 Map / Set |
| WeakRef (`NativeWeakRef`) | ClassDescriptor | 不看 new.target, 原型固定为 `f.prototype` (test262 3 例 + subclass-WeakRef 2 例, 附录 E) | 待修: 统一入口 |
| FinalizationRegistry (`NativeFinalizationRegistry`) | ClassDescriptor | 同 WeakRef (test262 3 例) | 待修: 统一入口 |
| Promise (`NativePromise`) | ClassDescriptor | 执行器运行之后才取原型 (规范在执行器之前); species: `then` / `finally` 用 `speciesConstructor`, 子类 `then` / `resolve` / `all` 探针正确 | 待修: 执行器之前取原型 |
| Proxy (`NativeProxy`) | ClassDescriptor | 经 findPrototype 读 new.target 的 `prototype` (规范 ProxyCreate 不读, 多一次可观察的 Get); 没有 `prototype`, `class X extends Proxy` 抛 TypeError (同规范) | 待修: 不读 new.target |
| ArrayBuffer (`typedarrays/NativeArrayBuffer`) | ClassDescriptor | 先分配数据块后取原型 (规范先取原型, test262 `data-allocation-after-object-creation.js`); 没有 `Symbol.species` (test262 4 例 `ArrayBuffer/Symbol.species`), 所以子类的 `slice` 返回 ArrayBuffer (`subclass/builtin-objects/ArrayBuffer/regular-subclassing.js`, 附录 E) | 待修: 先取原型, 补 species |
| DataView (`NativeDataView`) | ClassDescriptor | 校验后取原型, 但取原型之后不再检查缓冲区 (规范要求重查 detached 与长度, test262 3 例) | 待修: 取原型后重查 |
| %TypedArray% (`NativeTypedArrayView`) | ClassDescriptor | 一律抛 TypeError (抽象类, 同规范) | 已对, 只核对 |
| Int8Array, Uint8Array, Uint8ClampedArray, Int16Array, Uint16Array, Int32Array, Uint32Array, Float16Array, Float32Array, Float64Array, BigInt64Array, BigUint64Array (12 个, `NativeTypedArrayView.js_constructor`) | ClassDescriptor | 先按实参建好数组后取原型 (对象实参时规范先取原型; 非对象实参先 ToIndex 后取原型, 同现状); 布尔 / Symbol 等非对象实参抛 `Error: invalid argument` 而不是 ToIndex (test262 `throw-type-error-before-custom-proto-access.js`); species 在每个具体构造器上, 子类 `from` / `of` / `map` / `subarray` / `slice` / `filter` 探针正确 | 待修: 非对象实参走 ToIndex |
| Iterator (JS 1.7 `NativeIterator`, Rhino) | ClassDescriptor | 不看 new.target (`createNativeIterator` 用类原型); ES2025 的 `Iterator` 构造器 Rhino 未实现 (iterator-helpers, T10) | 待修: 统一入口 |
| Script (`NativeScript`, Rhino) | ClassDescriptor | 按类名取原型, 不看 new.target | 待修: 统一入口 |
| CallSite (`NativeCallSite`, Rhino) | ClassDescriptor | 原型固定为 `f.prototype` | 待修: 统一入口 |
| JavaImporter (`ImporterTopLevel`, Rhino) | ClassDescriptor | 原型固定为 `f.prototype` | 待修: 统一入口 |
| Continuation (`NativeContinuation`, Rhino) | ClassDescriptor | `new` 抛 "Direct call is not supported" (不可直接构造) | 已对, 只核对 |
| JavaAdapter, NativeJavaClass, NativeJavaConstructor | 其他 (Java 反射) | `class X extends <Java 类>` 现在靠 `ClassRuntime.superCall` 的 P2 脚手架 (非 JSFunction 父构造器构造后改原型) 得到 X.prototype | P5 (T6 钩子层); P4.3 拆脚手架时只给 NativeJavaClass 留这一处, 由 P5 的 JavaClassInheritance 接手 |
| 脚本函数与 class 基类构造器 (`JSFunction.construct` -> `BaseFunction.createObject(cx, scope, nt)`) | JSFunction | new.target 是 JSFunction 时取其 `prototype`, 其他 (绑定函数, Proxy) 退到自身原型: 撤销的 Proxy 不抛 TypeError, 绑定函数与 Proxy 的 realm 不对 (test262 5 例: `Function/internals/Construct/base-ctor-revoked-proxy*.js`, `Function/prototype/bind/*realm*.js`, `Proxy/construct/trap-is-undefined-proto-from-newtarget-realm.js`) | 待修: `createFromConstructor` |
| 派生 class 构造器 (`ClassRuntime.constructDerived` / `superCall`) | JSFunction | 以当前 new.target 构造父类; P2 脚手架: 父构造器不是 JSFunction 时构造后按 new.target 改原型 | P4.3 拆除 (NativeJavaClass 除外, 见上) |
| BoundFunction / NativeProxyFunction 的 [[Construct]] | 其他 | 透传 new.target (绑定函数作 new.target 时换成目标, 同规范) | 已对, 只核对 |
| LambdaConstructor / FunctionObject (宿主用 `LambdaConstructor` 定义构造器, 如 ScriptModuleMounter / RhinoUtils / MimeModule) | 其他 | 不看 new.target, 实例原型固定为类原型; 现在靠 P2 脚手架让 class 子类正确, `Reflect.construct` 不对 | 待修: 创建时按 new.target 取原型 (Java 侧构造器不知道 new.target, 原型在它返回新对象时设置) |
| E4X: XML, XMLList, Namespace, QName (`rhino-xml`) | ClassDescriptor | 不看 new.target | 范围外 (不在 Native* 清单, 不属于 ECMAScript, 无 test262), 维持现状 |
| 不可构造的 23 个 Native* | - | NativeArrayIterator, NativeCall, NativeCollectionIterator, NativeConsole, NativeFunction (脚本函数基类), NativeGenerator (JS 1.7 生成器, 无构造器), NativeGlobal (只登记 NativeError 构造器), NativeJSON, NativeJavaArray, NativeJavaList, NativeJavaMap, NativeJavaMethod, NativeJavaObject, NativeJavaPackage, NativeJavaTopPackage, NativeMath, NativeReflect, NativeStringIterator, NativeWith, NativeArrayBufferView, NativeBigIntArrayView, NativeTypedArrayIterator; NativeJavaConstructor 归入上面的 Java 行 | 不在范围 |

test262 基线 (盘点时, 解释 / 编译相同): `subclass-builtins` 72 例运行 70 (2 例 SharedArrayBuffer 按 T10 跳过), 通过 68; `subclass/builtin-objects` 70 例运行 70, 通过 68; `built-ins` 里提到 newTarget 的 219 个文件运行 171 (跳过 SharedArrayBuffer 20, explicit-resource-management 13, Temporal 9, async-iteration 4, async-functions 1, iterator-helpers 1), 通过 142. 失败按类: 跨 realm 回退 9 (NativeErrors 6, 绑定函数作 new.target 3; `Iterator/proto-from-ctor-realm.js` 带 iterator-helpers 按 T10 跳过, 不计), 脚本函数基类构造器 3 (撤销的 Proxy 2, Proxy 无陷阱 1), WeakRef / FinalizationRegistry 6 + 2, RegExp 5, DataView 3, ArrayBuffer 2, TypedArray 1, GeneratorFunction 1, AsyncFunction 1 (P9).

## 附录 E test262 排除清单 (T10, P6.1 / P9.4 填写)

格式: 用例路径 | 排除依据 (features 标签) | 登记阶段.

`test262.properties` 的排除项: (空, P6.1 重生成时填写).

`ClassSyntaxTest262CoreTest.UNSUPPORTED` 里 P3 新增的 T10 排除 (该测试执行 class 两目录, 附录 C Q9):

- features 含 `nonextensible-applies-to-private` (stage 3 提案, 附录 C Q14) 的 2 例: `language/statements/class/elements/private-class-field-on-nonextensible-objects.js`, `language/statements/class/subclass/private-class-field-on-nonextensible-return-override.js` | P3

`ClassSyntaxTest262CoreTest.KNOWN_FAILURES` (P2 起 fork 自有的核心执行测试, 附录 C Q9; 用例缺少后续阶段的 features 标签, 由该阶段修好后删除, 测试在它们通过时报错提醒):

- `language/expressions/class/subclass-builtins/subclass-WeakRef.js` | features: [WeakRef]; WeakRef 还不按 new.target 创建 | P4
- `language/statements/class/subclass-builtins/subclass-WeakRef.js` | features: [WeakRef]; 同上 | P4
- `language/statements/class/subclass/builtin-objects/ArrayBuffer/regular-subclassing.js` | 无 features 标签; ArrayBuffer 还不按 new.target 创建 | P4
- `language/statements/class/subclass/builtin-objects/GeneratorFunction/instance-prototype.js` | 无 features 标签; GeneratorFunction 还不按 new.target 创建 | P4
- `language/expressions/class/cpn-class-expr-accessors-computed-property-name-from-async-arrow-function-expression.js` | features: [computed-property-names]; 用到 async 箭头函数但没有 async-functions 标签 | P9
- `language/expressions/class/cpn-class-expr-computed-property-name-from-async-arrow-function-expression.js` | 同上 | P9
- `language/expressions/class/heritage-async-arrow-function.js` | features: [class]; 同上 | P9
- `language/statements/class/cpn-class-decl-accessors-computed-property-name-from-async-arrow-function-expression.js` | features: [computed-property-names]; 同上 | P9
- `language/statements/class/cpn-class-decl-computed-property-name-from-async-arrow-function-expression.js` | 同上 | P9
- `language/expressions/class/cpn-class-expr-fields-computed-property-name-from-async-arrow-function-expression.js` | features: [computed-property-names, class-fields-public, class-static-fields-public]; 用到 async 箭头函数但没有 async-functions 标签 (P3 起执行) | P9
- `language/expressions/class/cpn-class-expr-fields-methods-computed-property-name-from-async-arrow-function-expression.js` | 同上 | P9
- `language/statements/class/cpn-class-decl-fields-computed-property-name-from-async-arrow-function-expression.js` | 同上 | P9
- `language/statements/class/cpn-class-decl-fields-methods-computed-property-name-from-async-arrow-function-expression.js` | 同上 | P9
- `language/{expressions,statements}/class/elements/private-async-generator-method-name.js`, `private-async-method-name.js`, `private-static-async-generator-method-name.js`, `private-static-async-method-name.js` (共 8 例) | features: [class-methods-private, class]; 私有 async 方法 / async 生成器方法, 没有 async-functions / async-iteration 标签 | P9
- `language/statements/class/static-init-arguments-functions.js`, `static-init-arguments-methods.js` | features: [class-static-block]; static block 里定义 async 函数 / 方法 | P9

## 附录 F 工具用法

- 全量测试与对照: `./gradlew test` (五个模块), 然后 `py -3.12 -I .python/junit_failures.py <本次结果根目录> <基线结果根目录>` (结果根目录是包含 `*/build/test-results/test/*.xml` 的项目目录; 基线用 `git worktree` 以外的方式取: 导出同步基线源码到 `E:/.cache/<名>` 后跑一次).
- test262 单跑: `./gradlew :tests:test --tests org.mozilla.javascript.tests.Test262SuiteTest` ; 重生成 properties: 加 `-DupdateTest262properties=true`; 限定目录 / 调试: `-Dtest262properties=<文件>`, `-DrunTest262Debug=...` (见 `tests/build.gradle` 104-117 行).
- 格式与文档: `./gradlew spotlessApply`, `git diff --check`, `py -3.12 .python/generate_markdown.py` (写) / `--check` (只读).
- 完整 jar 与宿主换入: `AGENTS.md` 第四节; 宿主设备脚本 `tools/rhino/device_rhino_check.py -s <serial> [--install] [--class <类>]`.
