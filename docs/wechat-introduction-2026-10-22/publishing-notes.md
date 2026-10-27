# LearnHub 微信公众号发布资料

主标题：别让资料停在收藏夹：认识一下 LearnHub

备选标题：资料越来越多，知识怎样连起来？试试 LearnHub 学习工作台

摘要（可填写到公众号摘要栏）：

> 从收藏到理解，再到自己的笔记。LearnHub 把资料、检索、知识关联和 AI 协作放在同一个学习工作台里，让每一次阅读都能继续积累。

## 文件说明

- `article.md`：可编辑正文，图片使用同目录 `images` 中的相对路径。
- `article.html`：带图排版预览，图片已内嵌，可离线打开；采用白底与青绿配色，适配手机阅读。
- `images/01-cover.png`：公众号宽幅封面，1922 × 818，约 2.35:1。
- `images/02-note-editor.jpg` 至 `images/06-agent.jpg`：五张正文配图，按正文顺序命名，保留项目原始截图。

## 放入微信公众号

1. 将主标题和摘要分别填入公众号后台；上传 `images/01-cover.png` 作为封面，在后台确认裁切。
2. 打开 `article.html`，从标题下的导语开始复制正文到图文编辑器；主标题已单独填写时，不重复粘贴正文中的标题。
3. 在对应图注前上传五张正文截图。网页中的内嵌图片仅用于预览，公众号需要以其后台实际上传结果为准。封面如已选择“显示在正文”，可去掉正文内重复的封面。
4. 使用手机预览确认图片、段落和代码块显示。宽屏截图可供读者点开查看，图注已单独说明看点。
5. 公众号正文外链的展示与点击方式取决于账号能力，可将项目仓库链接另行添加至“阅读原文”。仓库地址为 https://github.com/luyiyanyumu/LearnHub 。

文稿与素材已准备，本次未登录或发布到公众号。

## 内容核对与图片来源

内容基于本项目当前工作区的 `README.md`、`docs/reader.md`、`docs/note-edit-tool.md`、`docs/code-agentic-search.md` 与 `docs/knowledge-maintenance.md`，未加入效果数据、用户规模或收益承诺。

正文配图依次复制自 `docs-shots/readme-note-editor.jpg`、`readme-fusion-search.jpg`、`readme-community-galaxy.jpg`、`readme-wiki.jpg` 和 `readme-agent.jpg`。这组截图使用独立合成演示数据，未使用本机知识库、数据库、配置或密钥。

封面使用内置 image_gen 生成，属于概念插画。最终提示词如下：

```text
Use case: ads-marketing
Asset type: WeChat Official Account article cover for the LearnHub learning workspace, a wide editorial banner.
Primary request: Create one exceptionally polished, restrained cover illustration showing scattered document sheets and note cards becoming connected into a luminous knowledge constellation and learning workbench. This is a conceptual illustration, not a product UI screenshot.
Scene/backdrop: warm light ivory paper-like background, generous negative space, delicate teal connecting arcs and a few fine star nodes.
Subject: abstract white and pale mint document cards with minimal teal line marks, gathered into a coherent constellation; no readable UI or fake app controls.
Style/medium: contemporary editorial graphic design, elegant semi-flat dimensional paper collage, precise fine geometry, subtle soft shadows and tasteful luminous teal accents, professional technology publication aesthetic.
Composition/framing: approximately 2.35:1 wide landscape, target 1880 x 800 pixels. Keep all important title and illustration inside the central safe area with comfortable margins. Place a large clear title toward the center-left, the connected card constellation toward the center-right, balanced and uncluttered. The title and Chinese subtitle must read clearly at mobile thumbnail size.
Lighting/mood: calm, focused, intelligent, hopeful; soft diffuse light.
Color palette: deep teal typography, turquoise and mint accents, warm off-white; avoid saturated multicolor or a dark background.
Text (verbatim): "LearnHub"
Text (verbatim, Chinese subtitle directly underneath): "让资料，连成知识"
Typography: crisp premium modern sans serif; render these exact two lines only, with correct spelling and punctuation. Strong hierarchy, title larger than subtitle.
Constraints: no people, robots, mascots, QR codes, watermarks, logos other than the word LearnHub, browser chrome, fake product screenshots, paragraphs or extra text. Do not crowd the edges. No surrounding mockup frame.
```
