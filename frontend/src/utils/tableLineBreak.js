import { TextSelection } from '@tiptap/pm/state'
import { cellAround } from '@tiptap/pm/tables'

/** Insert a line break without changing the table or another selected cell. */
export function insertTableLineBreak(state, dispatch) {
  const { selection, schema } = state
  if (!(selection instanceof TextSelection) || !schema.nodes.hardBreak) return false
  const start = cellAround(selection.$from)
  const end = cellAround(selection.$to)
  if (!start || !end || start.pos !== end.pos) return false
  if (!selection.$from.parent.inlineContent || selection.$from.parent.type.spec.code
    || !selection.$to.parent.inlineContent || selection.$to.parent.type.spec.code) return false
  if (dispatch) {
    const marks = state.storedMarks || selection.$from.marks()
    dispatch(state.tr.replaceSelectionWith(schema.nodes.hardBreak.create(), false)
      .ensureMarks(marks).scrollIntoView())
  }
  return true
}
