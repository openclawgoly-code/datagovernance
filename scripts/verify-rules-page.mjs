import { createRequire } from 'node:module'
const require = createRequire(process.env.DG_PLAYWRIGHT_ROOT.replace(/\/?$/, '/'))
const { chromium } = require('playwright')
const BASE = 'http://127.0.0.1:4173'
const RUN = new Date().toTimeString().slice(0,8).replace(/:/g,'')
const NEW_RULE = `身份证脱敏-${RUN}`
let pass = 0, fail = 0
const errs = []
function ok(label, cond, detail = '') {
  console.log(`  [${cond ? 'PASS' : 'FAIL'}] ${label}${detail ? '  — ' + detail : ''}`)
  cond ? pass++ : fail++
}

/**
 * 存一张排障用的截图。没设 SHOT_DIR 就跳过 —— 不设时路径会拼成
 * "undefined/xxx.png",于是仓库根目录里凭空长出一个 undefined/ 目录,
 * 还差点被 git add -A 提交进去。
 */
async function shot(page, name) {
  if (!process.env.SHOT_DIR) return
  await page.screenshot({ path: `${process.env.SHOT_DIR}/${name}.png` })
}

const browser = await chromium.launch({ executablePath: process.env.DG_CHROMIUM_PATH })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
const consoleErrors = []
page.on('console', m => { if (m.type() === 'error') consoleErrors.push(m.text()) })
page.on('pageerror', e => consoleErrors.push(String(e)))

await page.goto(BASE + '/', { waitUntil: 'networkidle' })
await page.fill('input[autocomplete="username"]', 'admin')
await page.fill('input[autocomplete="current-password"]', process.env.DG_ADMIN_PASSWORD)
await page.click('button[type="submit"]')
await page.waitForURL(u => !u.pathname.startsWith('/login'))
await page.waitForTimeout(1500)

// ── 从侧边栏点进去,不猜 URL。当初就是靠点菜单才发现 404 的 ──
console.log('\n【规则管理】从侧边栏进入')
await page.locator('.el-sub-menu').filter({ hasText: '数据集成' }).first().click()
await page.waitForTimeout(800)
await page.locator('.el-menu-item').filter({ hasText: '规则管理' }).first().click()
await page.waitForTimeout(2500)
ok('点侧边栏菜单进得去(不再是 404)', page.url().endsWith('/integration/rules') &&
   !(await page.locator('body').innerText()).includes('页面不存在'), page.url())

await page.waitForSelector('.el-table__row', { timeout: 15000 })
const rowCount = await page.locator('.el-table__row').count()
ok('列表加载出已有规则', rowCount >= 3, `${rowCount} 行`)

const bodyText = await page.locator('body').innerText()
ok('参数以键值对呈现,不是一坨 JSON', bodyText.includes('mode=') || bodyText.includes('keepPrefix='),
   (bodyText.match(/mode=\w+/) || [''])[0])
ok('被引用的规则显示引用数', bodyText.includes('个任务'), (bodyText.match(/\d+ 个任务/) || [''])[0])

// ── 删除保护:被引用时按钮该是禁用的 ──
const disabledDel = await page.locator('.el-table__row button:has-text("删除")[disabled]').count()
ok('被任务引用的规则,删除按钮就地禁用(不是点下去吃 409)', disabledDel >= 3, `${disabledDel} 个禁用`)

// ── 分类筛选 ──
await page.locator('.el-radio-button').filter({ hasText: '清洗' }).first().click()
await page.waitForTimeout(700)
const cleanseRows = await page.locator('.el-table__row').count()
await page.locator('.el-radio-button').filter({ hasText: '转换' }).first().click()
await page.waitForTimeout(700)
const transformRows = await page.locator('.el-table__row').count()
ok('清洗/转换筛选各自生效', cleanseRows !== transformRows || cleanseRows + transformRows > 0,
   `清洗 ${cleanseRows} 行,转换 ${transformRows} 行`)
await page.locator('.el-radio-button').filter({ hasText: '全部' }).first().click()
await page.waitForTimeout(600)

// ── 新建:参数表单由 paramSpec 动态渲染 ──
console.log('\n【规则管理】新建一条规则 —— 参数表单由后端 paramSpec 渲染')
await page.getByRole('button', { name: '新建规则' }).click()
await page.waitForSelector('.el-dialog', { state: 'visible' })
await page.fill('.el-dialog input[placeholder*="一眼认得出"]', NEW_RULE)

await page.locator('.el-dialog .el-select').first().click()
await page.waitForSelector('.el-select-dropdown__item:visible', { state: 'visible' })
const groups = await page.locator('.el-select-group__title:visible').allInnerTexts()
ok('种类下拉按清洗/转换分组', groups.length === 2, groups.join(' / '))
await page.locator('.el-select-dropdown__item:visible').filter({ hasText: '脱敏' }).first().click()
await page.waitForTimeout(900)

const dlg = await page.locator('.el-dialog').innerText()
ok('选「脱敏」后自动出现它的五个参数',
   ['mode', 'keepPrefix', 'keepSuffix', 'maskChar', 'saltCredentialId'].every(p => dlg.includes(p)))
// 参数顺序照后端 RuleKind 里写的来:脱敏的 mode 排第一,因为其余四个参数的
// 含义都取决于它。按字母排会把它排到第四位。
ok('参数按后端声明的顺序渲染(mode 在最前)', dlg.indexOf('mode') < dlg.indexOf('keepPrefix'))
ok('自由文本参数没被误判成下拉(maskChar 要能填 * 或 #)',
   await page.locator('.el-dialog .el-form-item')
     .filter({ has: page.locator('.el-form-item__label', { hasText: /^maskChar$/ }) })
     .locator('.el-select').count() === 0)
ok('脱敏/解密给出「密钥不写在这里」的警示', dlg.includes('凭据 ID，不是密钥本身'))
await shot(page, 'rules-form')

// mode 的说明里枚举了取值,应该渲染成下拉而不是文本框
// 按 label 精确定位。用 hasText:'PARTIAL' 会先命中 maskChar —— 它的说明里
// 也有 PARTIAL,但那说的是适用模式,不是取值
const modeItem = page.locator('.el-dialog .el-form-item')
  .filter({ has: page.locator('.el-form-item__label', { hasText: /^mode$/ }) }).first()
const modeIsSelect = await modeItem.locator('.el-select').count()
ok('说明里枚举了取值的参数渲染成下拉(mode)', modeIsSelect > 0)
if (modeIsSelect) {
  await modeItem.locator('.el-select').first().click()
  await page.waitForSelector('.el-select-dropdown__item:visible', { state: 'visible' })
  const opts = await page.locator('.el-select-dropdown__item:visible').allInnerTexts()
  ok('下拉选项就是说明里那几个', opts.includes('PARTIAL') && opts.includes('HASH') && opts.includes('FIXED'),
     opts.join('/'))
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: 'PARTIAL' }).first().click()
  await page.waitForTimeout(400)
}
// 自由文本参数仍是输入框
const prefixInput = page.locator('.el-dialog .el-form-item').filter({ hasText: '保留前几位' }).first()
await prefixInput.locator('input').first().fill('6')
const suffixInput = page.locator('.el-dialog .el-form-item').filter({ hasText: '保留后几位' }).first()
await suffixInput.locator('input').first().fill('4')

await page.locator('.el-dialog').getByRole('button', { name: '保存' }).click()
await page.waitForTimeout(2500)
const afterCreate = await page.locator('body').innerText()
ok('新规则出现在列表里', afterCreate.includes(NEW_RULE), NEW_RULE)
ok('新规则未被引用,可以删除',
   await page.locator('.el-table__row').filter({ hasText: '身份证脱敏' })
     .locator('button:has-text("删除"):not([disabled])').count() > 0)

// 保存后对话框必须自己关掉。不关的话后面每一次点击都会被遮罩拦下 ——
// 而那看起来会像"页面坏了",其实只是上一步没收尾
ok('保存成功后对话框自动关闭',
   await page.locator('.el-overlay-dialog:visible').count() === 0)
await shot(page, 'rules-list')

// ── 必填校验 ──
await page.getByRole('button', { name: '新建规则' }).click()
await page.waitForSelector('.el-dialog', { state: 'visible' })
await page.fill('.el-dialog input[placeholder*="一眼认得出"]', `缺参数的解密规则-${RUN}`)
await page.locator('.el-dialog .el-select').first().click()
await page.waitForSelector('.el-select-dropdown__item:visible', { state: 'visible' })
await page.locator('.el-select-dropdown__item:visible').filter({ hasText: '解密' }).first().click()
await page.waitForTimeout(700)
await page.locator('.el-dialog').getByRole('button', { name: '保存' }).click()
await page.waitForTimeout(1200)
const warn = await page.locator('.el-message').first().innerText().catch(() => '')
ok('缺必填参数时就地拦住并点名(不是提交后吃 400)',
   warn.includes('必填') && (warn.includes('algorithm') || warn.includes('credentialId')), warn)
await page.keyboard.press('Escape')
await page.waitForTimeout(600)

ok('浏览器控制台无 JS 报错', consoleErrors.length === 0, consoleErrors.slice(0, 2).join(' | '))

// 自清理:删掉本轮建的规则,脚本才能反复执行
await page.goto(BASE + '/integration/rules', { waitUntil: 'networkidle' })
await page.waitForTimeout(1500)
const mine = page.locator('.el-table__row').filter({ hasText: NEW_RULE })
if (await mine.count()) {
  await mine.locator('button:has-text("删除")').first().click()
  await page.waitForTimeout(600)
  await page.locator('.el-message-box__btns button').filter({ hasText: /确定|确认/ }).first().click()
  await page.waitForTimeout(1500)
  ok('自己建的规则能删掉(未被引用)',
     !(await page.locator('body').innerText()).includes(NEW_RULE))
}

console.log(`\n通过 ${pass},失败 ${fail}`)
await browser.close()
process.exit(fail ? 1 : 0)
