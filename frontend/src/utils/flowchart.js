/** One editable synthetic template for rich-text, Markdown and legacy preview insertion. */
export const DEFAULT_FLOWCHART_SOURCE = [
  'flowchart TD',
  '  A([开始]) --> B[处理步骤]',
  '  B --> C{是否完成？}',
  '  C -->|是| D([结束])',
  '  C -->|否| B',
].join('\n')

export const DEFAULT_FLOWCHART_MARKDOWN = '```mermaid\n' + DEFAULT_FLOWCHART_SOURCE + '\n```'
