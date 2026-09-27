# paper-finder —— 文献检索技能（给人看的）

`SKILL.md` 是送给模型的提示词，本文件是**配套参考**：设计理由、可用的真实数据源与调用方式、
标识符校验规则、常见错误清单。它不会被送进模型，所以这里可以写得啰嗦一点。

## 为什么这个技能的第一条是"禁止编造"

检索类任务是幻觉的高发区，而且**错得非常像对的**：

- 标题能编得通顺（"某某: 基于 X 的 Y 方法"），作者能编成领域内的真实人名，年份落在合理区间；
- DOI / arXiv 号的**格式**也能编对（arXiv 是 `2401.01234`，DOI 是 `10.1145/…`），只有点开才知道不存在；
- 一旦这些条目进了综述、周报、开题材料，追溯成本极高——读者要么找不到原文，要么找到的是另一篇。

所以这个技能不靠"请你尽量准确"这种软要求，而是把它做成**可核对的流程约束**：
每条结果的每个字段都必须能指认到本次抓取过的页面，并给出"证据"（核对了哪些页面、对上了哪些字段）。
宁可少列、明确写"未找到"，也不允许出现一条没有出处的条目。

判断标准很简单，交付前可以拿它反问自己：
**"这条记录里的每个字符，我在哪个页面上见过？"**

## 数据源与调用方式（按可用性排序）

以下地址都可以直接用抓取工具打开（返回 JSON 或 HTML），**返回值就是元数据的唯一依据**。

### 定元数据（A 级）

| 来源 | 用途 | 地址模板 |
| --- | --- | --- |
| DOI 解析 | 拿到 DOI 对应的官方落地页 | `https://doi.org/<doi>` |
| Crossref | 按 DOI 取全量书目字段 | `https://api.crossref.org/works/<doi>` |
| Crossref 检索 | 按标题/作者找候选 | `https://api.crossref.org/works?query.bibliographic=<关键词>&rows=5` |
| arXiv abs | 预印本标题/作者/摘要/版本 | `https://arxiv.org/abs/<id>` |
| arXiv API | 按标题/作者检索 | `https://export.arxiv.org/api/query?search_query=ti:%22<标题>%22&max_results=5` |
| DBLP | 计算机领域发表记录（venue/年份/页码） | `https://dblp.org/search/publ/api?q=<关键词>&format=json` |
| ACL Anthology | NLP 会议论文的正式版与 PDF | `https://aclanthology.org/volumes/2024.acl-long/`（卷）或 `https://aclanthology.org/2024.acl-long.1/`（单篇） |
| OpenReview | ICLR/NeurIPS 等投稿与评审 | `https://api2.openreview.net/notes?forum=<id>` |
| PubMed / PMC | 生物医学论文与免费全文 | `https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi?db=pubmed&term=<关键词>` |
| 会议/期刊官网 | 最终 venue 与 proceedings | 例如会议 proceedings 页面、期刊卷期目录页 |

### 补充与开放获取（B 级）

| 来源 | 用途 | 地址模板 |
| --- | --- | --- |
| OpenAlex | 综合元数据 + OA 状态 | `https://api.openalex.org/works/doi:<doi>` 或 `https://api.openalex.org/works?search=<关键词>` |
| Semantic Scholar | 元数据 + 开放获取 PDF 字段 | `https://api.semanticscholar.org/graph/v1/paper/DOI:<doi>?fields=title,authors,year,venue,externalIds,openAccessPdf` |
| Unpaywall | 由 DOI 查合法免费全文 | `https://api.unpaywall.org/v2/<doi>?email=<邮箱>` |
| DOAJ | 开放获取期刊论文 | `https://doaj.org/api/search/articles/<关键词>` |

注意：这些接口有的限流、有的需要邮箱参数，抓取失败时**如实说明失败**，不要用记忆补。

### 只能用来"发现"（C 级，不得定元数据）

搜索结果页、聚合站、博客、百科、二手书单、社交媒体、模型自己生成的摘要。
它们可以告诉你"可能存在这样一篇论文"，然后必须回到 A/B 级来源核实。

## 标识符校验

输出前逐个校验，校验不过就写「未核实」：

- **arXiv**：`^\d{4}\.\d{4,5}(v\d+)?$`（新式）或 `^[a-z-]+(\.[A-Z]{2})?/\d{7}$`（2022 年前的旧式）。
  打开 `https://arxiv.org/abs/<id>` 能出对应标题才算核实。
- **DOI**：`^10\.\d{4,9}/\S+$`。打开 `https://doi.org/<doi>` 能落到对应论文页、且页面上写的是同一个标题才算核实。
- **常见伪造痕迹**：DOI 前缀写成 `10.1000`、`10.1234`；arXiv 号用未来的月份；把会议年份当 arXiv 号的年份；
  把 `10.48550/arXiv.<id>`（arXiv 给预印本注册的 DOI）当成出版商 DOI。

## 版本差异（写错等于给错论文）

同一项工作常有多个版本，**分开列**并注差异，不要合成一条：

1. **预印本**（arXiv / bioRxiv）：标题作者可能与正式版不同，需注明"预印本"与 v 号；
2. **会议版**：正式发表处，页码/venue 以 proceedings 为准；
3. **期刊扩展版**：常改标题、加实验，引用时以期刊版为准；
4. **workshop / findings / demo** 与主会不同，venue 要写准；
5. **撤稿与勘误**：若页面显示 retracted / erratum，必须在条目里写明（这类信息只在页面上看，不要凭记忆判断）。

## 下载入口的优先级与法律边界

1. 出版商或会议的开放获取 PDF（页面上有明确 Download / PDF 链接）；
2. arXiv / bioRxiv / PubMed Central；
3. 作者主页、实验室页面、机构知识库；
4. Unpaywall / OpenAlex 报出的合法 OA 版本。

**不得提供**：付费墙绕过、盗版镜像（Sci-Hub、libgen 一类）、共享账号、需要破解或抓取付费接口的地址。
付费墙论文就写「需订阅或购买，未找到合法免费全文」——这比给一个来路不明的 PDF 更负责。

## 常见错误清单（检索类任务的实际踩坑项）

- 把综述（survey/review）当成原始方法论文，或反过来；
- 作者列表只用搜索结果摘要里的"前三位 + et al."，导致作者顺序或人数错；
- 年份取 arXiv 上传年，却按会议年份写；
- 把 `researchgate` / `semanticscholar` 的页面当成"官方页"（它们是二次来源，官方页应是 DOI 落地页或会议官网）；
- 把需要登录/JS 的下载页当成 PDF 直链；
- 同一篇论文的预印本与正式版各列一条却不说明关系，让读者以为是两篇；
- 用户给的论文名本身记错了，顺着错误前提编出一篇"匹配"的论文；
- 用"应该是这一篇"这种措辞把不确定写成确定。

## 与本应用（learn-hub）的配合

### 检索与核对

- 检索工具：`web_search`（找候选）、`web_fetch`（抓页面核对）；
- 本机核对：`search_knowledge` / `get_file` 可以确认这篇论文是否已经上传到资料库；
  如果已上传，直接给出资料名，让用户点开就能读全文——**这比再给一个外链更快**。

### 直接把论文存进资料库（已验证可用）

**应用内智能体**（悬浮对话）已经有 `add_file_from_url` / `list_files` 两个工具，用户说"帮我放进资料库"
时直接调用即可（写操作会挂成待确认卡片）；learn-hub 的 MCP server 另有同一套资料库工具，
DeepSeek Harness 侧也能用：

| 工具 | 用途 |
| --- | --- |
| `list_files` | 看资料库里已有什么（文件名 / 类型 / 大小 / **抽取状态与字数**），先查重再入库 |
| `upload_file_from_url`（MCP）/ `add_file_from_url`（应用内智能体） | 给一个**全文直链**，下载并入库 |
| `upload_file`（MCP） | 把**本机已下载**的文件按路径入库（终端里 `curl` 下来的 PDF 用这个） |
| `get_file_text`（MCP）/ `get_file`（应用内智能体） | 读入库后抽出来的正文（前者默认 2000 字可翻页，后者 8000 字并支持 `query` 全文定位） |
| `search_all` / `search_knowledge` | 统一检索：入库的资料正文会出现在这里 |

后端的等价 REST（没有 MCP 工具时用）：`POST /api/files/upload`（multipart，字段名 `file`，可选 `categoryId`）。

**两条必须记住的实测约束：**

1. **必须是全文直链，不能是落地页。** 给 `https://arxiv.org/abs/1706.03762` 会被拒收（返回 `text/html`）；
   要给 `https://arxiv.org/pdf/1706.03762`。工具会先检查 Content-Type 与开头字节，把"网页"挡在库外——
   因为把落地页当全文存进去，会得到一条**有记录、抽不出正文、检索里查不到**的资料。
2. **入库后一定要看 `textStatus`。** 只有 `ok` 才算"能被检索"；
   `empty`（扫描件）/`unsupported`（压缩包等）/`skipped`（太大）都要如实告诉用户，
   并说明这份资料只能按文件名与手写「说明」参与检索。

另外：arXiv 的 PDF 直链没有 `.pdf` 后缀（`/pdf/1706.03762`），入库时工具会按 Content-Type
补成 `1706.03762.pdf`——这不是"改文件名"，而是后端靠扩展名决定怎么抽正文（`03762` 会被判 unsupported）。

## 技能怎么被加载

- 目录约定：`skills/<id>/SKILL.md`，正文即提示词，frontmatter 是元数据；本文件不会被送进模型。
- 后端 `SkillService` 启动时扫描 `skills/`，设置面板「技能」里会自动列出（保存即生效，不必重启）。
- `applies_to` 只是展示用的用途标签；这个技能没有绑定工具栏按钮，由支持技能加载的智能体按需取用。
