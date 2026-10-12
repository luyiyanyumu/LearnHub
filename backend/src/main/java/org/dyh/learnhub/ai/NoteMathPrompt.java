package org.dyh.learnhub.ai;

/** Shared writing rules for the chat and the independent note-generation calls. */
final class NoteMathPrompt {
    private NoteMathPrompt() { }

    static final String GENERATE = """
            【数学表达与代码的选择】
            能准确用数学公式表达的知识，一律优先用 LaTeX 公式：变量与上下标、概率分布、相似度与评分、损失函数、状态/参数更新、复杂度、定义、约束和推导。
            行内公式使用 $…$；独立公式的起止 $$ 各自独占一行。多行推导在同一数学块中使用 aligned 或 cases，保留上下标、分式、矩阵和括号结构。
            Markdown 正文中的 LaTeX 命令使用单个反斜杠，不额外做 Markdown 转义；工具参数是 JSON 时仅按 JSON 规则转义一次，解析后的正文仍应是正常 LaTeX。
            数学表达不要放在行内反引号、缩进代码块或任何围栏代码块（包括 text、latex、python）里；也不要用伪代码、ASCII/Unicode 拼接、图片或程序函数代替本可直接排版的公式。
            例如双编码器评分写成 $s(q,d)=\\langle E_q(q),E_d(d)\\rangle$，不要写成代码 dot(E_q(q), E_d(d))；公式后用正文说明变量含义与适用条件。
            只有真实程序实现、命令、SQL、配置、日志，或用户明确要求演示源码时，才使用代码格式；真实代码保持原有语言与内容，不强行改成公式。
            沿用来源和原文中的定义、数值与条件，不补造缺乏依据的公式。输出前检查：数学表达已在公式分隔符内，代码块仅承载真实代码，LaTeX 分隔符和环境成对闭合。
            """;

    static final String MERGE = GENERATE + """
            此规则只用于新增或确需改写的数学内容；与本次融入无关的原文（包括已有公式和真实代码）逐字保留，不扩展修改范围。
            """;

    static final String FORMAT = GENERATE + """
            本次只整理现有内容的格式，不新增知识。只将语义明确的数学表达改为公式，保持原数值、符号含义和条件；无法确定的表达保留原文，不猜测公式。已有正确 LaTeX 和真实代码逐字保留。
            """;

    static final String POLISH = """
            【数学与代码保留规则】
            本次仅润色文字，已有 LaTeX 公式、公式分隔符、真实代码、命令、SQL、配置和日志逐字保留；不将数学公式改成代码或伪代码，不转换原代码，不补造或新增公式。
            """;
}
