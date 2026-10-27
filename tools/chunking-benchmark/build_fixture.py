"""Build the frozen, synthetic Chinese learning corpus; no application data is read."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
documents, questions = [], []


def add_doc(doc_id, kind, text, cases, protected=()):
    doc = {"id": doc_id, "kind": kind, "title": f"合成学习资料 {doc_id}", "text": text,
           "protected": []}
    for block_type, block in protected:
        start = text.index(block)
        doc["protected"].append({"type": block_type, "start": start, "end": start + len(block)})
    documents.append(doc)
    for category, question, evidence in cases:
        spans = []
        for quote in evidence:
            assert text.count(quote) == 1, (doc_id, quote[:30])
            start = text.index(quote)
            spans.append({"start": start, "end": start + len(quote), "text": quote})
        questions.append({"id": f"q{len(questions) + 1:02}", "category": category,
                          "question": question, "doc_id": doc_id, "evidence": spans})


def long_notes(items, intro):
    """Natural paragraphs with a marked topic switch; evidence is never used as a title."""
    paragraphs = [intro]
    for topic, evidence, context in items:
        paragraphs.extend([context, evidence,
                           f"在练习中可以先复述{topic}的适用条件，再建立一个规模很小的反例。" + context,
                           context + f"复盘时需要把{topic}的原因、现象和处理方式分开记录。",
                           f"关于{topic}，课堂实验安排了正常输入、边界输入和异常输入三个阶段。先在隔离环境准备可重复的数据，再记录每次执行前后的状态和持续时间。改变一个变量后重新运行，比较现象是否随该变量一起变化。若同时更改多个配置，就难以判断哪个条件真正影响结果。实验报告应保留环境版本、输入规模、复现步骤与观察结论，避免将猜测当作已经验证的原因。排查过程中也应标出尚未解释的现象，下一轮验证优先处理这些不确定性。",
                           f"{topic}的复习作业要求同学用自己的语言描述触发条件，再为错误做法给出一个反例。答案中既需要说明通常情况，也需要说出该结论依赖哪些假设。对于不确定的系统行为，先查看版本对应的文档，再使用最小程序验证。团队讨论应区分语法层面的约束、运行时实现和部署环境造成的差异，避免凭单次观察推导普遍规律。最终把实验过程整理成可执行清单，在下次改动后复用同样的检查步骤，才能判断行为是否发生回归。"])
    return "\n\n".join(paragraphs)


java = [
    ("对象可达性", "垃圾回收判断对象是否存活时，从线程栈中的引用、静态字段和本地方法引用等 GC Roots 出发遍历。只要仍有一条引用链能够到达对象，它就不能按不可达对象回收。局部变量离开作用域不等于对象立刻被释放，具体回收时间由收集器决定。", "分析内存泄漏时，应区分对象数量增长与堆空间暂时扩张。用小对象实验观察引用关系，比仅凭某次内存曲线下结论更可靠。需要同时检查集合长期持有、监听器未注销和线程上下文未清理等原因。"),
    ("可见性与原子性", "volatile 写入与随后读取建立可见性和有序性约束，但 count++ 包含读取、计算、写回三个步骤，不能因此成为原子操作。多个线程共同增加计数时，应使用 AtomicInteger.incrementAndGet，或让读改写过程位于同一把锁的保护范围内。", "并发错误往往取决于调度时机。测试需要重复执行并用屏障让线程同时开始，不能把一次正确结果当成线程安全证明。锁、原子类与不可变对象分别解决不同层次的问题，选择前应明确共享状态与临界区。"),
    ("字符串构造", "循环中反复使用不可变 String 追加内容，会产生多个中间字符串及复制开销。单线程构建较长文本时，可以使用 StringBuilder，并在容量可估算时预先指定容量。跨线程共享同一个构造器仍需要同步，方法内的局部构造器通常无需额外加锁。", "性能分析先确认输入规模、调用频率和分配热点。少量固定字面量拼接可能被编译器优化，不能把循环场景的结论机械套用到所有表达式。需要把预热、数据生成和结果消费同被测操作分开。"),
    ("异常资源释放", "try-with-resources 在作用域结束时自动关闭实现 AutoCloseable 的资源，多个资源按声明顺序的反向关闭。若主体与关闭过程同时抛出异常，主体异常会保留为主异常，关闭异常作为 suppressed exception 附着其上，排查时也要查看被抑制异常。", "文件句柄、网络连接和数据库游标都需要明确的生命周期。把资源获取写在循环外可能延长占用，把关闭遗漏在异常路径则容易产生泄漏。日志应保留原始异常链，避免只输出一个没有上下文的错误字符串。"),
    ("哈希集合", "HashMap 的键在放入后不应修改参与 equals 和 hashCode 的字段。否则键的哈希值可能对应另一个桶，随后即使拿着同一个对象也可能查找失败。可以使用不可变键，或在修改前先删除旧映射、修改完成后重新插入。", "集合选型要同时考虑查找、顺序、重复元素和并发要求。散列表适合按键查找，树结构提供顺序关系，而数组结构擅长连续访问。对象相等契约是容器能够正确工作的基础，应把边界输入也纳入单元测试。"),
    ("线程池拒绝", "有界线程池中，核心线程忙碌时新任务先进入队列；队列已满才会尝试增加非核心线程；线程数已达到最大值仍无法接纳任务时才触发拒绝策略。CallerRunsPolicy 让提交线程执行任务，能够形成反馈压力，但也会占用请求线程的处理时间。", "线程池容量要结合服务时间、阻塞比例和下游承载能力。无限增大队列会把过载转成延迟与内存压力，盲目增加线程则会提高切换开销。监控除了活跃线程数，还需要队列长度、拒绝次数和任务等待时间。"),
]
java_questions = ["还有引用的对象为什么不会被垃圾回收？GC Roots 如何参与判断？", "把共享计数器声明为 volatile，能否保证自增正确？如何改写？", "在循环里组装很长的文本，用什么替代字符串反复相加？", "自动关闭资源时发生第二个异常，原始异常会丢失吗？", "HashMap 里放入键后修改键字段，为什么同一个对象也查不到？", "线程池队列满了以后一定直接拒绝吗？调用方执行策略有何代价？"]
add_doc("A", "file", long_notes(java, "以下是由课堂练习整理的连续摘录，原始文档没有稳定的标题层级，讨论内容会随案例切换。"), [("长文主题", q, [item[1]]) for item, q in zip(java, java_questions)])

db = [
    ("联合索引", "联合索引 (tenant_id, status, created_at) 通常按最左前缀组织。只有 created_at 的过滤条件不能直接等同于完整利用该索引；tenant_id 等值且 status 等值时，created_at 的范围条件更容易形成连续扫描区间。实际是否使用仍应查看执行计划和数据分布。", "调优查询时，索引名称本身不能解释性能。应该结合过滤比例、返回字段与回表次数，观察估算行数与真实行数的差异。分页方式、排序方向和参数分布都可能改变计划，小数据集上的快速查询并不能证明索引设计合理。"),
    ("事务隔离", "可重复读隔离级别下，普通一致性读取通常使用快照视图；SELECT FOR UPDATE 属于锁定读，需要访问可锁定的当前记录。不能因为两次普通查询结果一致，就推断事务内所有读取都看到相同版本。并发更新场景还需要分析锁范围、事务边界和重试策略。", "事务让一组修改以明确边界提交或回滚，但长事务会延长资源占用。讨论隔离现象时需要画出两个会话的执行顺序，明确每次查询是否为普通读取。把事务和数据库连接生命周期混为一谈容易引入隐藏的锁等待。"),
    ("深分页", "使用 LIMIT offset, size 做很深的分页时，数据库通常仍要扫描并丢弃前面的 offset 条记录。若按唯一递增的 id 排序，可以保存上一页最后的 id，使用 WHERE id > last_id ORDER BY id LIMIT size 继续读取。游标方式不适合直接随机跳到任意页。", "分页接口需要稳定排序，否则并发插入和删除可能导致重复或遗漏。唯一键可以为同值排序字段提供确定的次序。统计总数、展示页码和导出全量数据分别有不同的成本，服务端应按实际产品行为选择实现。"),
    ("缓存一致性", "更新数据库后删除缓存属于常见的缓存旁路写法，但删除失败和并发旧读回填仍可能造成短期不一致。可以结合有限过期时间、删除重试和版本控制降低影响。缓存并不是事务真相来源，强一致业务需要由数据库事务或经过验证的一致性协议保证。", "缓存有效性需要从数据所有权和容许陈旧时间出发讨论。热点数据、空结果和大对象会带来不同的风险。对缓存命中率的追求不能掩盖错误率和恢复能力，测试时应主动模拟超时、进程崩溃与消息重复。"),
    ("消息幂等", "至少一次投递意味着同一条业务消息可能被重复消费。消费者可以在同一数据库事务内写入唯一业务流水并完成状态更新，依靠唯一约束阻止重复处理。仅在内存集合里保存已处理编号，无法覆盖重启和多个消费者实例，不能构成可靠幂等保障。", "业务事件的编号要跨重试保持稳定，并明确去重记录的保留周期。消息确认、数据库提交与异常重试的先后关系会影响最终结果。观测系统应该同时统计积压、重试、死信和实际业务重复，而不能只看消费者有没有成功返回。"),
    ("读写分离", "数据库主从复制存在传播延迟，写入主库后立即从从库读取，可能暂时看不到刚才的修改。对读己之写的交互，可以在写后有限窗口路由到主库，或使用能够证明复制进度的会话标记。简单延迟固定毫秒数不能保证所有负载下都已同步。", "读写分离扩大读取能力，但也引入路由、延迟和故障切换问题。报表查询可容忍的陈旧程度往往不同于订单确认页。容量规划应评估主库保护、复制落后告警和回源压力，避免在延迟上升时让所有查询同时涌向主库。"),
]
db_questions = ["只有创建时间条件，能充分利用租户、状态、时间的联合索引吗？", "可重复读事务里，普通查询和 FOR UPDATE 看到的是同类版本吗？", "数据翻到很后面的页面越来越慢，怎样用游标改写，有什么限制？", "改数据库后删缓存就能实现强一致吗？失败时怎样降低影响？", "消息可能重复投递，怎样跨重启和多实例避免业务重复执行？", "刚保存的信息下一次读取却消失了，读写分离下应如何保证读到自己的修改？"]
add_doc("B", "file", long_notes(db, "本摘录把不同数据库实践按讲课顺序连续记录，没有为每个知识点添加小标题。"), [("长文主题", q, [item[1]]) for item, q in zip(db, db_questions)])

formula = r"""$$
\begin{aligned}
\operatorname{sim}(q,d) &= \frac{q^\top d}{\lVert q\rVert_2\lVert d\rVert_2}, \\
s_{\mathrm{fuse}}(d) &= \sum_{r\in\{\mathrm{dense},\mathrm{sparse}\}}\frac{w_r}{k+\operatorname{rank}_r(d)}, \\
\mathcal{L}_{i} &= -\log\frac{\exp(s(q_i,d_i^+)/\tau)}{\exp(s(q_i,d_i^+)/\tau)+\sum_j\exp(s(q_i,d_j^-)/\tau)}, \\
P(y=1\mid x) &= \frac{1}{1+\exp(-w^\top x-b)}, \\
\nabla_w\mathcal{L} &= \frac{1}{n}\sum_{i=1}^n(\sigma(w^\top x_i+b)-y_i)x_i+\lambda w, \\
w_{t+1} &= w_t-\eta_t\nabla_w\mathcal{L}(w_t), \\
\eta_t &= \frac{\eta_0}{1+\gamma t}, \\
\operatorname{Precision@K} &= \frac{|R_K\cap G|}{K}, \\
\operatorname{Recall@K} &= \frac{|R_K\cap G|}{|G|}, \\
\operatorname{RR}(q) &= \frac{1}{\min\{r:d_r\in G_q\}}, \\
\operatorname{MRR} &= \frac{1}{|Q|}\sum_{q\in Q}\operatorname{RR}(q).
\end{aligned}
$$"""
math_e1 = "对比损失中的温度越小，分数差异经过指数函数后越明显；它控制正负样本分布的尖锐程度。训练目标要求正样本得分相对负样本升高，但损失数值不能直接解释为答案正确概率。"
math_e2 = "梯度下降把当前参数沿损失梯度的反方向更新；学习率控制步长，过大可能振荡或发散，过小则收敛缓慢。正则项的梯度需要一并参与更新。"
math_e3 = "精确率用返回结果数作分母，召回率用全部相关证据数作分母。MRR 只关注第一条相关结果的位置，不能度量一次回答是否收齐多个必要条件；完整证据覆盖需要另外报告。"
math_text = "# 检索与优化练习\n\n## 基本量\n\n" + ("学习指标时先明确样本集合、分母与适用条件，不同指标之间不能直接互换。" * 15) + "\n\n" + formula + "\n\n" + math_e1 + "\n\n" + math_e2 + "\n\n## 评价\n\n" + math_e3
add_doc("C", "note", math_text, [("公式", "对比学习中温度参数有什么作用？请结合完整损失式说明。", [formula, math_e1]), ("公式", "梯度下降如何更新参数，步长过大过小各有什么影响？", [r"w_{t+1} &= w_t-\eta_t\nabla_w\mathcal{L}(w_t),", math_e2]), ("多证据", "精确率、召回率的分母分别是什么，为什么 MRR 不能说明证据齐全？", [math_e3])], [("math", formula)])

code = '''```java
public final class TransferService {
    private final AccountRepository repository;

    public TransferService(AccountRepository repository) {
        this.repository = repository;
    }

    // 调用方必须开启数据库事务，两个账户按编号顺序锁定以减少死锁。
    public void transfer(long sourceId, long targetId, long cents) {
        if (sourceId == targetId || cents <= 0) {
            throw new IllegalArgumentException("invalid transfer");
        }

        long first = Math.min(sourceId, targetId);
        long second = Math.max(sourceId, targetId);
        repository.lock(first);
        repository.lock(second);

        Account source = repository.get(sourceId);
        Account target = repository.get(targetId);
        if (source.balance() < cents) {
            throw new IllegalStateException("insufficient balance");
        }

        repository.debit(sourceId, cents);
        repository.credit(targetId, cents);
        repository.appendAudit(sourceId, targetId, cents);
    }
}
```'''
code_explain = "转账示例先校验两个账户不能相同且金额必须为正，再按账户编号顺序取得两个锁。余额校验失败要让整个事务回滚，扣款、入账和审计记录必须处于同一事务中。固定锁顺序可以减少反向转账产生的死锁，但仍应准备处理数据库报告的死锁并进行有限重试。"
shell = '''~~~~bash
# 这里的井号是脚本注释，不是文档小标题。
set -euo pipefail

release_dir="/srv/synthetic/releases/v42"
test -f "$release_dir/application.jar"

# 先通过本地探测确认新进程健康，再切换流量入口。
curl --fail --max-time 3 http://127.0.0.1:18081/health
ln -sfn "$release_dir" /srv/synthetic/current
~~~~'''
shell_explain = "发布脚本先检查产物存在，再验证新进程的健康端点，最后切换 current 软链接。仅切换软链接并不会让已运行的进程重新加载文件；实际发布还需要明确启动、流量切换和失败回退步骤。"
code_text = "# 程序练习\n\n## 转账\n\n" + ("阅读代码时需要同时关注前置条件、执行顺序、异常传播和资源所有权。" * 14) + "\n\n" + code + "\n\n" + code_explain + "\n\n## 发布脚本\n\n" + shell + "\n\n" + shell_explain
add_doc("D", "note", code_text, [("代码", "转账示例怎样确定加锁顺序，为什么两个方向同时转账更不容易死锁？", ["long first = Math.min(sourceId, targetId);\n        long second = Math.max(sourceId, targetId);\n        repository.lock(first);\n        repository.lock(second);", code_explain]), ("代码", "转账前哪些参数和余额要检查，扣款入账及审计要满足什么事务要求？", ["if (sourceId == targetId || cents <= 0) {\n            throw new IllegalArgumentException(\"invalid transfer\");\n        }", "if (source.balance() < cents) {\n            throw new IllegalStateException(\"insufficient balance\");\n        }", code_explain]), ("代码", "部署脚本是先探活还是先切换链接？改软链接会让旧进程自动重载吗？", [shell, shell_explain])], [("code", code), ("code", shell)])

table_rows = [
    ("200", "请求成功", "客户端按约定解析响应；写操作是否创建新资源仍应看业务语义。"),
    ("201", "资源已创建", "常用于创建成功，必要时在 Location 指明新资源的位置。"),
    ("204", "成功且无响应正文", "不能再把空正文当作 JSON 解析，否则会得到解析错误。"),
    ("301", "永久重定向", "缓存与历史客户端可能长期复用目标，迁移时应注意回退成本。"),
    ("304", "资源未修改", "配合条件请求使用客户端已有副本，而不是重新下载实体正文。"),
    ("400", "请求格式或参数错误", "优先校验参数与序列化格式，修正请求后再发起调用。"),
    ("401", "缺少有效身份认证", "检查令牌是否缺失、过期或签发方不匹配，不能仅提高权限。"),
    ("403", "身份已识别但访问被禁止", "检查角色与资源范围，重复刷新有效令牌未必能够解决。"),
    ("404", "目标资源未找到", "核对路径与资源编号，也要确认网关前缀和服务路由。"),
    ("409", "资源状态冲突", "适合乐观锁版本冲突，客户端应重新获取状态再决定是否重试。"),
    ("429", "请求过于频繁", "按 Retry-After 或指数退避重试，同时限制并发和总重试次数。"),
    ("500", "服务内部错误", "保留关联编号查询服务日志，避免把敏感堆栈直接返回客户端。"),
    ("502", "网关收到无效上游响应", "核对上游地址、连接与协议，不能直接当作客户端参数错误。"),
    ("503", "服务暂时不可用", "结合容量与维护状态判断，配合限流和有上限的重试。"),
    ("504", "网关等待上游超时", "沿调用链定位耗时，盲目增大全部超时可能放大资源占用。"),
]
table = "| 状态码 | 含义 | 处理建议 |\n| --- | --- | --- |\n" + "\n".join(f"| {a} | {b} | {c} |" for a, b, c in table_rows)
table_e = "HTTP 状态码反映协议层处理结果，业务返回体中的错误码可以提供更细的业务原因。客户端重试写请求前还必须确认幂等性，不能仅因收到服务器错误就无上限重发。"
table_text = "# 接口排查手册\n\n## 状态码对照\n\n" + ("排查调用失败先保留请求时间、链路编号、响应码和服务端关联日志。" * 17) + "\n\n" + table + "\n\n" + table_e
add_doc("E", "file", table_text, [("表格", "401 和 403 分别应该检查认证还是资源权限？", [f"| {a} | {b} | {c} |" for a, b, c in table_rows if a in ("401", "403")]), ("表格", "收到 204 后继续解析 JSON 为什么会报错？", [f"| {a} | {b} | {c} |" for a, b, c in table_rows if a == "204"]), ("表格", "接口被限流时应如何退避，重试写请求前还要确认什么？", [f"| {a} | {b} | {c} |" for a, b, c in table_rows if a == "429"] + [table_e]), ("表格", "网关无效上游响应和等待超时对应哪些状态码，排查方向是什么？", [f"| {a} | {b} | {c} |" for a, b, c in table_rows if a in ("502", "504")])], [("table", table)])

ops = [
    ("就绪与存活", "存活探针用于判断进程是否需要重启，就绪探针用于判断当前实例是否适合接收流量。数据库短时不可达可以使就绪检查失败，但不宜让所有实例同时因存活检查而循环重启。启动很慢的应用应设置启动探针或合理的初始等待。", "探针应该快速且有明确超时。把完整业务查询当作每秒探针会增加依赖压力，使轻微抖动变成更多失败。设计验证时要分别模拟进程卡死、下游短时故障和冷启动，观察调度平台对不同信号的处理。"),
    ("容器网络", "容器内的 localhost 指向该容器自身，不会自动指向宿主机。Windows Docker Desktop 上访问宿主机服务通常使用 host.docker.internal，容器之间则优先使用同一网络中的服务名。还需确认宿主服务监听的地址、防火墙规则与端口映射。", "网络排查需要分开检查名称解析、连接建立、TLS 协商与应用响应。端口能够连通只说明传输层可达，并不能证明接口路径和认证配置正确。日志中的连接拒绝、解析失败与读取超时应按不同分支处理。"),
    ("优雅停机", "优雅停机先停止接收新的请求，再在有限时间内等待正在执行的请求完成，最后关闭连接池与后台资源。若直接退出进程，长请求和未提交的任务可能被中断。终止等待时间需要与网关摘流、任务上限和平台强制结束时间协调。", "发布流程应考虑负载均衡器传播配置所需的时间。新增实例通过就绪检查后再导流，旧实例摘流后仍可能有存量连接。压力测试应在持续请求期间触发更新，核对失败率与请求重复，而不是只验证首页能否打开。"),
    ("日志脱敏", "访问日志不应记录完整密码、访问令牌或模型 API 密钥。排障可以记录请求编号、状态、耗时和经过允许的标识；令牌可用不可逆摘要或末尾少量字符辅助关联。脱敏逻辑必须覆盖异常路径，不能只处理正常请求。", "可观测性要帮助定位故障，也需要控制高基数字段和存储成本。用户输入可能携带敏感内容，结构化日志应采用字段白名单并设置保留期。共享排障材料时使用合成数据复现，比直接导出本机完整日志更稳妥。"),
    ("超时预算", "一次上游请求的总时间应包括连接、读取、重试和退避。若外层接口只允许两秒，内部每次调用却设置三秒并重试三次，超时预算就不一致。应沿调用链分配剩余预算，限定尝试次数，并对可重试错误和非幂等操作分别处理。", "短暂失败并不总能靠重试解决。多个服务层同时重试会让一次用户动作产生大量下游调用，在拥塞时进一步降低可用性。演练需要核对最大尝试总量，保证取消信号和剩余截止时间可以向下传播。"),
]
ops_questions = ["数据库临时故障该让存活探针失败并不断重启吗？", "在 Docker 容器里连 localhost 的宿主服务失败，应怎样配置地址？", "滚动发布时，优雅停机应该按什么顺序处理请求和资源？", "调用 AI 模型出错时，日志可以直接输出完整令牌吗？异常路径怎么办？", "外层接口只给两秒，内部三秒重试三次为什么不合理，预算怎样分配？"]
add_doc("F", "file", long_notes(ops, "这是运维实践课连续转写的文本，换行和标点已经恢复，但尚未添加章节标题。"), [("长文主题", q, [item[1]]) for item, q in zip(ops, ops_questions)])

cards = [
    ("G", "# 速查\n\n二分查找要求搜索空间有单调性，每轮排除不可能包含目标的一半区间。使用左闭右开区间时，右边界不包含在候选范围内，更新和终止条件必须与这个约定一致。空数组、首尾元素和不存在的目标都应测试。", "二分查找为什么需要单调性，左闭右开区间有哪些边界测试？"),
    ("H", "# 速查\n\nBFS 使用队列按距离层次扩展节点，在无权图上首次到达目标时可得到最少边数路径。DFS 主要沿一条路径深入，不能仅凭首次发现目标保证路径最短。若边权不同，应考虑 Dijkstra 等满足条件的最短路算法。", "无权图求最少边数应该用队列还是深度优先，带不同边权时还能直接用吗？"),
    ("I", "# 速查\n\nGit revert 生成一个新提交来反向应用指定提交的修改，适合已经共享的历史。reset 移动当前分支位置，配合 hard 会同时覆盖暂存区与工作区。多人共享分支上撤销错误提交，通常优先使用 revert 并保留历史记录。", "多人共享分支里撤销已推送的错误提交，用 revert 和 reset 有何区别？"),
    ("J", "# 速查\n\n测试替身应围绕外部边界建立，避免把内部实现每一步都写成断言。针对缓存测试，至少覆盖首次未命中、后续命中、过期刷新与底层加载失败。固定时钟或可控时间源通常比真实 sleep 更稳定，也更容易定位失败。", "验证缓存时要覆盖哪些状态，怎样避免等待真实过期造成测试不稳定？"),
]
for doc_id, text, question in cards:
    add_doc(doc_id, "quick_ref", text, [("短速查", question, [text.split("\n\n", 1)[1]])])

suite = {"version": "synthetic-chunking-v1-frozen", "description": "手工编写的合成中文软件学习资料；所有证据为原文字符跨度；标题不用于判定命中。", "documents": documents, "questions": questions}
out = ROOT / "fixture.json"
out.write_text(json.dumps(suite, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"documents": len(documents), "questions": len(questions), "characters": sum(len(d["text"]) for d in documents), "document_chars": {d["id"]: len(d["text"]) for d in documents}}, ensure_ascii=False))
