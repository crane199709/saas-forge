import { DOMWrapper } from '@vue/test-utils'

/** el-table 的行上放不了 data-testid，按行内锚点元素取所在行。 */
export function tableRow(wrapper: { get(selector: string): { element: Element } }, testId: string) {
  return new DOMWrapper(wrapper.get(`[data-testid="${testId}"]`).element.closest('tr')!)
}
