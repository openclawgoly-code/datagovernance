import { ElMessageBox, type ElMessageBoxOptions } from 'element-plus'

/**
 * 二次确认,返回布尔而不是抛异常。
 *
 * ElMessageBox.confirm 在用户点「取消」时 <b>reject</b>。直接 await 它,
 * 一次取消就变成一条未处理的 Promise rejection —— 控制台里一片红,
 * 而用户只是改了主意。逐个 try/catch 也行,但那会在每个删除按钮旁边
 * 复制五行样板,并且总有一处会漏掉(本项目就漏了五处,是 UI 验证脚本
 * 的"控制台无报错"断言把它们揪出来的)。
 *
 * @returns true 表示用户确认,false 表示取消或关闭
 */
export function confirmAction(
  message: string,
  title: string,
  options?: ElMessageBoxOptions,
): Promise<boolean> {
  return ElMessageBox.confirm(message, title, { type: 'warning', ...options })
    .then(() => true)
    .catch(() => false)
}

/** 危险操作的确认:确认按钮标红,措辞用「删除」而不是默认的「确定」。 */
export function confirmDanger(
  message: string,
  title: string,
  confirmButtonText = '删除',
): Promise<boolean> {
  return confirmAction(message, title, {
    confirmButtonText,
    confirmButtonClass: 'el-button--danger',
  })
}
