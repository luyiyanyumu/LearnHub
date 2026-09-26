-- 检索评测集（2026-09）
-- 写法原则：问题用**真实口语**，不照抄标题 —— 照抄标题测的是"字符串相等"，
-- 真实难点是"用户这么说、资料里那么写"。所以下面刻意混了三类：
--   ① 词面能直接命中（标题里有同样的词）
--   ② 换一种说法（词面对不上、只能靠语义）—— 这一组最能暴露检索的真实水平
--   ③ 跨来源（同一主题散在笔记/速查卡/资料里，看能不能召回正确的那个）
INSERT IGNORE INTO rag_eval (question, expect_refs, expect_words, note) VALUES
-- ① 词面型（基线：这些不该失败，失败说明检索坏了）
('Maven 坐标三要素是什么', 'note:1', 'groupId|artifactId|version', '词面型：标题直接含关键词'),
('Spring Boot 的启动流程讲一下', 'note:2', 'SpringApplication|自动装配|Tomcat', '词面型'),
('== 和 equals 有什么区别', 'quick_ref:4', '地址|内容|常量池', '词面型：两个符号都在标题里'),
('Docker 怎么看所有容器', 'quick_ref:2', 'docker ps', '词面型：查看所有容器'),
('@GetMapping 和 @PostMapping 分别对应什么操作', 'quick_ref:3', 'GET|POST|幂等', '词面型'),
('String 和 StringBuilder 有什么区别', 'quick_ref:1', '不可变|可变|线程安全', '词面型'),
('Git 怎么配置用户名和邮箱', 'quick_ref:7', 'git config|user.name', '词面型'),
('AI Agent 有哪几个组成部分', 'note:44', '模型|工具|编排', '词面型'),

-- ② 换一种说法（语义鸿沟：词面大概率 0 命中）
('我想把暂存区的东西退回来，该敲什么', 'quick_ref:7', 'git reset|restore', '语义型：口语"退回来" vs 资料写 git reset'),
('代码提交了一半想反悔，怎么撤销', 'quick_ref:7', 'reset|revert|checkout', '语义型：没有"提交"以外的共同词'),
('怎么把一个正在跑的容器里打开命令行', 'quick_ref:2', 'docker exec|-it|bash', '语义型：口语描述 vs docker exec'),
('两个字符串内容一样为什么比较结果是 false', 'quick_ref:4', '常量池|地址|equals', '语义型：完全绕开 == 这个符号'),
('大量字符串拼接用哪个类性能更好', 'quick_ref:1', 'StringBuilder|拼接慢|性能', '语义型：问效果不问类名'),
('接口路径上的注解怎么区分查和增', 'quick_ref:3', 'GetMapping|PostMapping', '语义型：用"查/增"代替 HTTP 动词'),
('启动一个 Java 应用时框架背后做了什么', 'note:2', 'SpringApplication|自动装配|启动', '语义型：不问"启动流程"这个词'),
('让不同进程之间传传感器数据用什么协议', 'note:3', 'MQTT|发布|订阅', '语义型：问场景不问协议名'),
('怎么让 AI 自己决定调用哪个工具', 'note:44', 'Function Calling|工具|Agent', '语义型：问机制不问术语'),

-- ③ 跨来源 / 资料型（同一主题多来源，要看能不能召回资料）
('Spring 的核心容器里都有哪些模块', 'file:2|note:5', 'Core Container|Bean|上下文', '资料型：答案在 PDF 的架构小节'),
('Spring Boot 的自动配置是怎么生效的', 'file:2|note:2', '自动配置|spring.factories|条件', '资料型：PDF + 笔记都有'),
('JVM 和 JRE 和 JDK 是什么关系', 'note:5', 'Java 虚拟机|运行环境|开发工具包', '跨来源：Java 笔记里的三级关系'),
('Java 的垃圾回收是怎么工作的', 'note:5', 'GC|回收|堆', '跨来源'),
('Maven 是怎么管理依赖的', 'file:2|note:1', '依赖|pom|仓库', '跨来源：PDF + 笔记'),
('Docker 容器和镜像有什么区别', 'quick_ref:2|note:5', '镜像|容器|docker', '跨来源'),
('Java 里==比较字符串为什么有时候对有时候错', 'quick_ref:4|note:5', '常量池|equals|地址', '跨来源：速查卡 + 笔记都讲了'),
('Spring Boot 里怎么处理 GET 和 POST 请求', 'quick_ref:3|file:2', 'GetMapping|PostMapping|映射', '跨来源：速查卡 + PDF'),
('帮我看看 dsh 有哪些命令', 'quick_ref:8', 'dsh|web|profile', '词面型：缩写命中'),
('物联网设备上报数据一般怎么做', 'note:3', 'MQTT|主题|QoS', '语义型：场景化提问');
